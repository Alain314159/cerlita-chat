package cu.alain.chat.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cu.alain.chat.ChatApp
import cu.alain.chat.MainActivity
import cu.alain.chat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio foreground del timbre (D63/D64): mantiene el WebSocket Realtime
 * abierto con la app cerrada. Tipo `remoteMessaging` porque:
 *
 *  - NO tiene el límite de 6 h de `dataSync` (Android 15+ lo mata) y a
 *    diferencia de `dataSync`, `remoteMessaging` SÍ puede arrancar desde
 *    BOOT_COMPLETED (está en FGS_BOOT_COMPLETED_ALLOWLIST) → el timbre
 *    revive solo tras reiniciar el teléfono.
 *  - El permiso FOREGROUND_SERVICE_REMOTE_MESSAGING es de nivel "normal".
 *
 * El push JAMÁS transporta contenido: al timbrar hace syncOnce() y /sync es
 * la fuente de verdad (D63). Las notificaciones las construye el Notifier
 * desde el callback del engine — este servicio solo despierta.
 *
 * Anti-asesinato (OEM): FGS + START_STICKY + reinicio por BootReceiver +
 * exención de optimización de batería pedida en la app (Xiaomi además
 * necesita "Autoinicio" ON y batería "Sin restricciones" — ajustes manuales).
 */
class DoorbellService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var doorbell: RealtimeDoorbell? = null

    /** Estado para Ajustes (¿está vivo el timbre?). */
    companion object {
        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var connected: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        // Contrato de startForegroundService: SIEMPRE foreground primero
        // (aunque luego descubramos que no hay sesión y hagamos stopSelf).
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ChatApp
        if (app.store.session == null) {
            stopSelf() // sin sesión no hay nada que escuchar
            return START_NOT_STICKY
        }
        if (doorbell == null) setUp(app)
        return START_STICKY // si el OEM lo mata, el sistema lo reintenta
    }

    private fun setUp(app: ChatApp) {
        doorbell = RealtimeDoorbell(
            baseUrl = { app.prefs.baseUrl },
            anonKey = { cu.alain.chat.core.C.ANON_KEY },
            scope = scope,
            onDoorbell = { _roomId -> // el roomId no hace falta: /sync trae todo lo nuevo
                scope.launch { app.engine.syncOnce() }
            },
            onConnected = { // (re)conexión = alcance de reloj: sincroniza lo perdido
                scope.launch { app.engine.syncOnce() }
            },
            onStateChange = { connected = it },
        ).also { it.start() }

        // Suscribirse a las salas locales; el Flow re-emite al crear/unirse.
        scope.launch {
            app.db.roomDao().observeAll().collect { rooms ->
                doorbell?.setTopics(rooms.map { it.roomId }.toSet())
            }
        }
    }

    // ────────────────────────── notificación foreground ──────────────────────────

    private fun startInForeground() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Notifier.SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN) // sin icono en la barra (API <26)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, Doorbell.SVC_NOTIF_ID, n, type)
    }

    override fun onDestroy() {
        running = false
        connected = false
        doorbell?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Arranque/parada del servicio desde la UI (idempotente). */
object Doorbell {
    const val SVC_NOTIF_ID = 1000
    const val EXTRA_ROOM = "room"

    fun ensureStarted(ctx: Context) {
        val app = ctx.applicationContext as ChatApp
        if (app.store.session == null) return
        try {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, DoorbellService::class.java),
            )
        } catch (_: Exception) {
            // arranque en background restringido (Android 12+): lo reintenta
            // el próximo onStart de la Activity o el BootReceiver
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, DoorbellService::class.java))
    }
}

/**
 * Revive el timbre tras reiniciar el teléfono. remoteMessaging está en la
 * allowlist de FGS_BOOT_COMPLETED (Android 14/15): puede arrancar desde el
 * broadcast sin violar las restricciones de arranque en background.
 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = ctx.applicationContext as? ChatApp ?: return
        if (app.store.session != null) Doorbell.ensureStarted(ctx)
    }
}
