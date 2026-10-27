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
    /** URL del WS derivada de la URL base (https://x/functions/v1 → wss://x/...). */
    private val wsUrl: String = run {
        val base = baseUrl().trimEnd('/')
        val host = base.removePrefix("https://").removePrefix("http://").substringBefore('/')
        "wss://$host/realtime/v1/websocket?apikey=${anonKey()}&vsn=${Phoenix.VSN}"
    }

    private var ws: WebSocket? = null
    private var topics: Set<String> = emptySet()
    private var ref = 0
    private var hbJob: Job? = null
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

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            scheduleReconnect()

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
        scope.launch {
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
