package cu.alain.chat

import android.app.Application
import android.content.Context
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.engine.AppState
import cu.alain.chat.engine.ChatEngine
import cu.alain.chat.net.Api
import cu.alain.chat.notify.Notifier
import cu.alain.chat.vault.SessionStore
import java.util.UUID

/**
 * Application: service locator manual (app pequeña, sin framework DI).
 * Vida del proceso ↔ vida de la app; la rootKey NO sobrevive aquí
 * porque nunca se persiste — tras muerte del proceso la app pide la frase.
 */
class ChatApp : Application() {

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
            savedPeer = { prefs.peerUname },
            onNewMessages = { /* el polling foreground refresca la UI por Room Flow */ },
        )

        Notifier.ensureChannel(this)
        cu.alain.chat.work.WorkSync.ensurePeriodic(this)
        engine.refreshState()
    }
}

/** Configuración no sensible en SharedPreferences (sin secretos, regla del skill). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", cu.alain.chat.core.C.DEFAULT_BASE)!!
        set(v) = sp.edit().putString("base_url", v).apply()

    var peerUname: String?
        get() = sp.getString("peer", null)
        set(v) = sp.edit().putString("peer", v).apply()

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
}
