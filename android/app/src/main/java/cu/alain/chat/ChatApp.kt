package cu.alain.chat

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.engine.AppState
import cu.alain.chat.engine.ChatEngine
import cu.alain.chat.engine.NewMsg
import cu.alain.chat.net.Api
import cu.alain.chat.notify.Notifier
import cu.alain.chat.vault.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Application: service locator manual (app pequeña, sin framework DI).
 * Vida del proceso ↔ vida de la app; las roomKeys en RAM se recargan del
 * vault sellado (Keystore) al arrancar — no hay frase que teclear.
 *
 * WorkManager on-demand (D62): ChatApp es Configuration.Provider → se inicializa
 * en el primer getInstance() sin depender del InitializationProvider (más
 * robusto ante OEMs agresivos y el arranque queda igual en todos los móviles).
 */
class ChatApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var store: SessionStore
        private set
    lateinit var engine: ChatEngine
        private set

    /** true entre onStart/onStop de la actividad: silencia notificaciones en foreground. */
    @Volatile
    var inForeground: Boolean = false

    /** Sala abierta en la pantalla de chat (suprime SU notificación en foreground). */
    @Volatile
    var currentRoomId: String? = null

    /** Sala que pide abrir una notificación (la consume AppRoot). */
    val pendingOpenRoom = MutableStateFlow<String?>(null)

    /** Scope de aplicación para lanzar el notificador desde el callback del engine. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api { prefs.baseUrl }
        db = AppDatabase.build(this)
        store = SessionStore(this)

        engine = ChatEngine(
            db = db,
            store = store,
            api = api,
            avatarsDir = java.io.File(filesDir, "avatars"), // fotos de perfil (D61)
            onNewMessages = { msgs: List<NewMsg> -> announceIncoming(msgs) },
        )

        Notifier.ensureServiceChannel(this)
        cu.alain.chat.work.WorkSync.ensurePeriodic(this)
        engine.refreshState()
        engine.onProcessStart() // sesión viva tras reinicio: claves a RAM + salas del servidor
    }

    /**
     * ÚNICA política de aviso (D63): la comparten timbre Realtime, polling
     * de primer plano, worker de 15 min y drenaje de pendientes. Decide el
     * Notifier: ajenos + recientes + no en la sala abierta en foreground.
     */
    private fun announceIncoming(msgs: List<NewMsg>) {
        val s = store.session ?: return
        val fg = inForeground
        val cur = currentRoomId
        appScope.launch {
            Notifier.notifyNewMessages(
                ctx = this@ChatApp,
                db = db,
                myUid = s.uid,
                inForeground = fg,
                suppressRoom = cur,
                msgs = msgs,
            )
        }
    }
}

/** Configuración no sensible en SharedPreferences (sin secretos, regla del skill). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", cu.alain.chat.core.C.DEFAULT_BASE)!!
        set(v) = sp.edit().putString("base_url", v).apply()

    /** ID de dispositivo estable por instalación (lo pide el backend en auth). */
    val deviceId: String
        get() {
            var d = sp.getString("did", null)
            if (d == null) {
                d = UUID.randomUUID().toString()
                sp.edit().putString("did", d).apply()
            }
            return d
        }

    var askedNotifPermission: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(v) = sp.edit().putBoolean("asked_notif", v).apply()

    /** Ya se pidió (una vez) quitar la optimización de batería (D67). */
    var askedBattery: Boolean
        get() = sp.getBoolean("asked_battery", false)
        set(v) = sp.edit().putBoolean("asked_battery", v).apply()
}
