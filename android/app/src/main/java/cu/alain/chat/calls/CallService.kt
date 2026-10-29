package cu.alain.chat.calls

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
import cu.alain.chat.notify.Notifier

/**
 * S1/D107 (§4.2 auditoría de llamadas): servicio foreground de la LLAMADA.
 *
 * Sin esto, en cuanto la app pasa a background o se APAGA LA PANTALLA,
 * Android puede congelar el proceso (caché de procesos / Doze ligero): el
 * audio se corta y la llamada muere a los segundos aunque WebRTC estuviera
 * conectado. El FGS con tipo `microphone|camera` es la única vía que Android
 * documenta para mantener la captura de micrófono/cámara viva en background
 * — es lo que hace cualquier app de llamadas seria.
 *
 * - Arranca al INICIAR una llamada saliente y al ACEPTAR una entrante
 *   (siempre desde la UI: los permisos RECORD_AUDIO/CAMERA ya están
 *   concedidos, requisito de Android 14 para los tipos mic/cam).
 * - Se para al terminar la llamada (CallDispatcher.finish) — nunca queda
 *   pegado: la notificación desaparece con la llamada.
 * - Notificación discreta (IMPORTANCE_LOW, sin sonido): solo cumple el
 *   contrato del FGS.
 */
class CallService : Service() {

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Si la llamada ya no existe (carrera con finish): fuera.
        if (CallDispatcher.state.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY // la revive quien la necesita, no el sistema
    }

    private fun startInForeground() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, Notifier.CALLS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.call_notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        // El tipo se declara según los permisos CONCEDIDOS (Android 14 exige
        // el runtime permission antes de startForeground con ese tipo).
        val mic = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val cam = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (cam) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        ServiceCompat.startForeground(this, CALL_SVC_NOTIF_ID, n, type)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CALL_SVC_NOTIF_ID = 1002

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, CallService::class.java),
                )
            } catch (_: Exception) {
                // arranque en background restringido (no debería: las llamadas
                // nacen de la UI) — la llamada sigue sin FGS como hasta ahora
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CallService::class.java))
        }
    }
}

/**
 * S1/D107: acción «Rechazar» de la notificación de llamada entrante —
 * colgar sin desbloquear (la notificación full-screen lleva esta acción).
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val app = ctx.applicationContext as? ChatApp ?: return
        Notifier.cancelIncomingCall(ctx)
        CallDispatcher.decline(app)
    }

    companion object {
        const val ACTION_DECLINE = "cu.alain.chat.action.DECLINE_CALL"
    }
}
