package cu.alain.chat.notify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.random.Random

/**
 * Cliente WebSocket del "timbre" Realtime (D63), escrito a mano sobre OkHttp.
 *
 * POR QUÉ a mano y sin supabase-kt (D65): supabase-kt arrastra Ktor +
 * kotlinx-serialization (~2 MB contra un APK de 2,8 MB) y realtime-kt exige
 * API 26+ (la app es minSdk 24). El protocolo Phoenix necesario para un
 * broadcast de solo-escucha son ~150 líneas.
 *
 * Comportamiento:
 *  - Conecta a wss://<host>/realtime/v1/websocket?apikey=<anon>&vsn=1.0.0
 *    (el host sale de la URL base configurable en Ajustes).
 *  - Se suscribe a room-<roomId> por cada sala local; al cambiar la lista
 *    hace diff (join nuevos / leave quitados) SIN cerrar la conexión.
 *  - Heartbeat phoenix cada 30 s (el servidor corta a los mudos ~75 s);
 *    OkHttp hace ping WS de transporte (25 s) para detectar sockets muertos.
 *  - Reconexión con backoff exponencial 5 s → 60 s + jitter ±25 %.
 *  - Tras CADA reconexión avisa [onConnected]: el servicio re-sincroniza y
 *    así nada de lo timbrado durante el corte se pierde (el /sync es la
 *    fuente de verdad, el timbre solo despierta).
 *
 * Push que NO transporta contenido (D63): del mensaje solo viaja el roomId.
 */
class RealtimeDoorbell(
    baseUrl: () -> String,
    anonKey: () -> String,
    private val scope: CoroutineScope,
    private val onDoorbell: (roomId: String?) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onStateChange: ((Boolean) -> Unit)? = null,
    /** Otros broadcasts (dm nuevo, señalización de llamadas): payload crudo JSON. */
    private val onBroadcast: (event: String, payload: String) -> Unit = { _, _ -> },
    /** uid con sesión: se suscribe además al topic personal user-<uid> (D74). */
    private val userId: () -> String? = { null },
) {
    /** URL del WS derivada de la URL base (https://x/functions/v1 → wss://x/...).
     *  D115: el esquema se deriva del de la base (http→ws) — permite apuntar el
     *  timbre al backend simulado EN el dispositivo en la prueba instrumentada;
     *  en producción (https) queda exactamente igual que siempre. */
    private val wsUrl: String = run {
        val base = baseUrl().trimEnd('/')
        val host = base.removePrefix("https://").removePrefix("http://").substringBefore('/')
        val scheme = if (base.startsWith("http://")) "ws" else "wss"
        "$scheme://$host/realtime/v1/websocket?apikey=${anonKey()}&vsn=${Phoenix.VSN}"
    }

    private var ws: WebSocket? = null
    private var topics: Set<String> = emptySet()
    private var ref = 0
    private var hbJob: Job? = null
    private var reconnectJob: Job? = null
    private var backoffMs = 5_000L
    @Volatile private var started = false
    @Volatile private var connected = false

    /** ¿Conectado y con heartbeat al día? (para Ajustes). */
    val isConnected: Boolean get() = connected

    fun start() {
        if (started) return
        started = true
        connect()
    }

    fun stop() {
        started = false
        hbJob?.cancel()
        try {
            ws?.close(1000, "stop")
        } catch (_: Exception) {
        }
        ws = null
        setConnected(false)
    }

    /** stop + libera los hilos del cliente OkHttp (al destruir el servicio). */
    fun shutdown() {
        stop()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /**
     * Q2/S1 (D105): «despierta ya». Cancela la reconexión pendiente y conecta
     * en el acto. Lo llama NetWatch al VOLVER la red (onAvailable) — sin esto,
     * tras un apagón el timbre podía seguir ciego hasta 60 s (backoff alto).
     * Inofensivo si ya está conectado o parado.
     */
    fun kick() {
        if (!started || connected) return
        reconnectJob?.cancel()
        connect()
    }

    /** Nuevos topics de sala (diff: no reabre la conexión). */
    fun setTopics(roomIds: Set<String>) {
        val wanted = roomIds.map { it.lowercase() }.toSet()
        val add = wanted - topics
        val remove = topics - wanted
        topics = wanted
        if (!connected) return
        add.forEach { send(Phoenix.join("room-$it", nextRef())) }
        remove.forEach { send(Phoenix.leave("room-$it", nextRef())) }
    }

    // ────────────────────────── conexión ──────────────────────────

    private fun connect() {
        if (!started) return
        val req = try {
            Request.Builder().url(wsUrl).build()
        } catch (_: Exception) {
            scheduleReconnect(); return
        }
        ws = client.newWebSocket(req, listener)
    }

    /** Cliente propio: ping WS cada 25 s detecta sockets muertos (sin pong → onFailure). */
    private val client = OkHttpClient.Builder()
        .pingInterval(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = 5_000L
            setConnected(true)
            topics.forEach { send(Phoenix.join("room-$it", nextRef())) }
            userId()?.let { send(Phoenix.join("user-$it", nextRef())) } // D74: dm + llamadas
            startHeartbeat()
            onConnected() // reconexión = re-sync (nada perdido)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val f = Phoenix.parse(text)) {
                is Phoenix.Frame.Doorbell -> onDoorbell(f.roomId)
                is Phoenix.Frame.Broadcast -> onBroadcast(f.event, f.payload)
                is Phoenix.Frame.Reply -> if (!f.ok && f.topic.startsWith("realtime:")) {
                    // join rechazado (raro): reintentarlo en 5 s
                    val topic = f.topic.removePrefix("realtime:")
                    scope.launch { delay(5_000); if (connected) send(Phoenix.join(topic, nextRef())) }
                }
                null -> {}
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Q2/S1 (D105): «sin red» ≠ «servidor caído». Un fallo de DNS/ruta
            // (UnknownHostException/ConnectException) falla RÁPIDO y barato:
            // el backoff se REINICIA a 5 s para que al volver la red conecte
            // en el primer intento — antes subía a 60 s y había hasta 1 min
            // de ceguera tras el apagón. Los fallos de servidor/protocolo
            // mantienen la escalada 5→60 s de siempre.
            if (t is java.net.UnknownHostException || t is java.net.ConnectException) {
                backoffMs = 5_000L
            }
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            scheduleReconnect()
    }

    private fun startHeartbeat() {
        hbJob?.cancel()
        hbJob = scope.launch {
            while (isActive && connected) {
                delay(30_000)
                send(Phoenix.heartbeat(nextRef()))
            }
        }
    }

    private fun scheduleReconnect() {
        hbJob?.cancel()
        setConnected(false)
        if (!started) return
        val wait = backoffMs + Random.nextLong(0, backoffMs / 4) // jitter ≤25 %
        backoffMs = min(backoffMs * 2, 60_000L)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    private fun send(text: String) {
        try {
            ws?.send(text)
        } catch (_: Exception) {
        }
    }

    private fun nextRef(): String = (++ref).toString()

    private fun setConnected(v: Boolean) {
        if (connected != v) {
            connected = v
            onStateChange?.invoke(v)
        }
    }
}
