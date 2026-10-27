package cu.alain.chat

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.engine.AppState
import cu.alain.chat.engine.ChatEngine
import cu.alain.chat.engine.NewMsg
import cu.alain.chat.net.Api
import cu.alain.chat.notify.Doorbell
import cu.alain.chat.notify.DoorbellService
import cu.alain.chat.notify.Notifier
import cu.alain.chat.notify.RealtimeDoorbell
import cu.alain.chat.vault.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    /** Tema activo (D73): Ajustes lo cambia y toda la app recompone al vuelo. */
    val themeFlow = MutableStateFlow(cu.alain.chat.ui.theme.Themes.CERLITA)

    /**
     * Nombres personales (D78): cómo me aparece el otro a MÍ. uid (minúsculas)
     * → apodo. Local y privado: la otra persona no se entera (viaja solo en
     * este teléfono, nunca por la red). Ajustes/chats/llamadas/notifs lo leen.
     */
    val nicknames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Guarda (o borra con null/"") el apodo de un contacto y refresca el flow. */
    fun setNickname(uid: String, name: String?) {
        prefs.setNickname(uid, name)
        nicknames.value = prefs.allNicknames()
    }

    /** Scope de aplicación para lanzar el notificador desde el callback del engine. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Timbre (D72): SIN notificación persistente por defecto ──
    // Con la app ABIERTA el WebSocket Realtime vive DENTRO del proceso (sin
    // servicio, sin notificación): los mensajes llegan al instante. Con la
    // app CERRADA no hay servicio por defecto — el WorkManager periódico
    // (~15 min) sincroniza y notifica. Quien quiera instantáneo con la app
    // cerrada activa «Avisos instantáneos» en Ajustes: solo entonces corre
    // el FGS remoteMessaging con su notificación permanente.
    private var appDoorbell: RealtimeDoorbell? = null
    private var topicsJob: Job? = null

    /** ¿Conectado el timbre vivo (in-app o FGS)? Para Ajustes. */
    val doorbellConnected: Boolean
        get() = if (DoorbellService.running) DoorbellService.connected
        else appDoorbell?.isConnected ?: false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        themeFlow.value = prefs.theme
        api = Api { prefs.baseUrl }
        db = AppDatabase.build(this)
        store = SessionStore(this)

        engine = ChatEngine(
            db = db,
            store = store,
            api = api,
            avatarsDir = java.io.File(filesDir, "avatars"), // fotos de perfil (D61)
            onNewMessages = { msgs: List<NewMsg> -> announceIncoming(msgs) },
            onSessionChanged = { syncDoorbell() }, // D72: login/logout enciende/apaga el timbre
        )

        nicknames.value = prefs.allNicknames() // D78: apodos locales

        Notifier.ensureServiceChannel(this)
        cu.alain.chat.work.WorkSync.ensurePeriodic(this)
        engine.refreshState()
        engine.onProcessStart() // sesión viva tras reinicio: claves a RAM + salas del servidor

        // topics siempre al día para el timbre que esté vivo (setTopics sobre
        // un timbre parado solo actualiza el set: inofensivo)
        topicsJob = appScope.launch {
            db.roomDao().observeAll().collect { rooms ->
                appDoorbell?.setTopics(rooms.map { it.roomId }.toSet())
            }
        }

        // Watchdog (D71): cada 30 s, con la app ABIERTA y sesión viva, el timbre
        // in-app debe estar vivo (algunos OEMs son creativos). syncDoorbell es
        // idempotente: si todo está bien, no hace nada.
        appScope.launch {
            while (true) {
                delay(30_000)
                if (inForeground && store.session != null) syncDoorbell()
            }
        }
    }

    /**
     * ÚNICO punto de decisión del timbre (D72). Llaman: onStart/onStop de la
     * Activity, login/logout (via onSessionChanged), el toggle de Ajustes y
     * el watchdog. Idempotente.
     *
     *  - instantBg ON  → FGS remoteMessaging (notificación permanente) siempre.
     *  - instantBg OFF → FGS parado; timbre in-app SOLO con la app abierta.
     */
    fun syncDoorbell() {
        val hasSession = store.session != null
        if (prefs.instantBg && hasSession) {
            Doorbell.ensureStarted(this)
        } else if (DoorbellService.running) {
            Doorbell.stop(this) // modo cambiado a OFF o logout: fuera el FGS
        }
        if (inForeground && hasSession && !DoorbellService.running) {
            startAppDoorbell()
        } else {
            stopAppDoorbell()
        }
    }

    private fun startAppDoorbell() {
        if (appDoorbell != null) return
        val d = RealtimeDoorbell(
            baseUrl = { prefs.baseUrl },
            anonKey = { cu.alain.chat.core.C.ANON_KEY },
            scope = appScope,
            onDoorbell = { _ -> appScope.launch { engine.syncOnce() } },
            onConnected = { appScope.launch { engine.syncOnce() } },
            onBroadcast = { event, payload -> onRealtimeEvent(event, payload) },
            userId = { store.session?.uid?.lowercase() },
        )
        appDoorbell = d
        d.start()
        // el collector de topics ya corre: emítelle el set actual
        appScope.launch {
            val rooms = db.roomDao().all()
            d.setTopics(rooms.map { it.roomId }.toSet())
        }
    }

    private fun stopAppDoorbell() {
        // stop (no shutdown): una sola instancia y un solo OkHttpClient por
        // proceso — los ciclos abrir/cerrar no filtran hilos
        appDoorbell?.stop()
        appDoorbell = null
    }

    /**
     * Broadcasts del timbre que NO son "mensaje nuevo" (v0.7.0):
     *  - "dm"  → chat directo nuevo para mí: /sync recoge el wrap (D74).
     *  - "sig" → señalización de llamadas (lo consume el CallDispatcher, D75).
     * Lo comparten el timbre in-app y el FGS — un solo punto de decisión.
     */
    fun onRealtimeEvent(event: String, payload: String) {
        when (event) {
            "dm" -> appScope.launch { engine.syncOnce() }
            "sig" -> cu.alain.chat.calls.CallDispatcher.onSignal(this, payload) // llamadas (D75)
        }
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

    /** D72: avisos instantáneos con la app CERRADA (FGS con notificación
     *  permanente). Default FALSE: sin notificación persistente — con la app
     *  abierta SIEMPRE es tiempo real vía el timbre in-app. */
    var instantBg: Boolean
        get() = sp.getBoolean("instant_bg", false)
        set(v) = sp.edit().putBoolean("instant_bg", v).apply()

    /** Tema de la app (D73): system | light | dark | cerlita. Default cerlita —
     *  es la identidad de la pareja (el mockup). Se cambia en Ajustes. */
    var theme: String
        get() = sp.getString("theme", cu.alain.chat.ui.theme.Themes.CERLITA)!!
        set(v) = sp.edit().putString("theme", v).apply()

    // ── Apodos locales (D78): uid.minúsculas → nombre personal ──

    fun nicknameOf(uid: String): String? = sp.getString("nick_${uid.lowercase()}", null)

    fun setNickname(uid: String, name: String?) {
        val k = "nick_${uid.lowercase()}"
        val v = name?.trim()?.take(cu.alain.chat.profile.RoomInfoMsg.MAX_NAME)
        if (v.isNullOrEmpty()) sp.edit().remove(k).apply()
        else sp.edit().putString(k, v).apply()
    }

    fun allNicknames(): Map<String, String> =
        sp.all.entries
            .filter { it.key.startsWith("nick_") && it.value is String }
            .associate { it.key.removePrefix("nick_") to it.value as String }
}
