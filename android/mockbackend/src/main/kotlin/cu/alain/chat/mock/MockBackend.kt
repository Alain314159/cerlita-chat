package cu.alain.chat.mock

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * D115: backend simulado que corre EN EL MISMO DISPOSITIVO (127.0.0.1) que la
 * app bajo prueba — la primera prueba instrumentada del proyecto (Fase V del
 * informe 1.0: «cero verificación en dispositivo real»).
 *
 * Implementa EXACTAMENTE el contrato que la app espera de las Edge Functions:
 *  - POST /auth (a=reg|in|out|room_new|room_join|rooms|pk|…) con las formas
 *    de Api.kt (códigos 200/201 y campos uid/u/tok/exp).
 *  - GET  /sync → 204 vacío o {"n":seq,"m":[[msgId,roomId,sender,seq,nonce,ct,uname,ts],…]}
 *  - POST /outbox → {"a":[ids…]} (eco de lo recibido = el ack).
 *  - GET  /turn, /heartbeat.
 *  - WS   /realtime/v1/websocket — habla Phoenix v1.0.0: responde phx_reply ok
 *    a joins/heartbeats, y expone pushDoorbell() para INYECTAR el broadcast
 *    «new-msg» que despierta la app por el canal real del timbre.
 *  - Cualquier otra ruta (ntfy poll/publish, broadcast REST): 200 vacío.
 *
 * Vive en sharedTest: MockBackendTest lo valida en JVM (sin emulador, en cada
 * `test` de CI) y FlujoCriticoTest lo usa en el dispositivo — mismo código,
 * dos mundos. Cero dependencias de Android.
 */
class MockBackend {

    private val counter = AtomicLong(100)
    private val pendingRows = CopyOnWriteArrayList<JSONArray>()
    private val knownRooms = LinkedHashSet<String>()
    private val openSockets = CopyOnWriteArrayList<WebSocket>()

    /** (uid, uname, token) de la última sesión aceptada — null tras a=out. */
    @Volatile
    var currentUser: Triple<String, String, String>? = null
        private set

    /** Acks de /outbox: (roomId, msgId). */
    val acked = CopyOnWriteArrayList<Pair<String, String>>()

    val server = MockWebServer()

    fun start() {
        server.dispatcher = disp
        // Bind explícito a 127.0.0.1: el default es «localhost» y la network
        // security policy de la app diferencia AMBOS nombres de dominio.
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    fun stop() {
        try {
            // cerrar sockets abiertos ANTES del shutdown: el MockWebServer
            // clásico se niega a morir con un WS vivo (AssertionError)
            openSockets.forEach { try { it.close(1000, "stop") } catch (_: Exception) {} }
            openSockets.clear()
            Thread.sleep(200)
            server.shutdown()
        } catch (_: Throwable) {
            // AssertionError incluido: en un backend de prueba, morir en
            // silencio tras el último test es lo correcto
        }
    }

    /** URL base para prefs.baseUrl (http://127.0.0.1:PUERTO). */
    val url: String get() = server.url("/").toString().trimEnd('/')

    /** Seq nueva para una fila entrante (monótona, > que todo lo ackeado). */
    fun nextSeq(): Long = counter.incrementAndGet()

    /** Encola una fila (cifrada por quien llama) para el próximo /sync. */
    fun deliverIncoming(row: JSONArray) {
        pendingRows.add(row)
    }

    /**
     * Inyecta el broadcast «new-msg» por el último WebSocket abierto — el mismo
     * frame que produce Supabase Realtime cuando el par envía. Devuelve false
     * si aún no hay socket vivo (reintentar).
     */
    fun pushDoorbell(roomId: String): Boolean {
        val ws = openSockets.lastOrNull() ?: return false
        val frame = JSONObject()
            .put("topic", "realtime:room-$roomId")
            .put("event", "broadcast")
            .put(
                "payload",
                JSONObject()
                    .put("event", "new-msg")
                    .put("payload", JSONObject().put("roomId", roomId))
                    .put("type", "broadcast"),
            )
            .put("ref", JSONObject.NULL)
        return try {
            ws.send(frame.toString())
        } catch (_: Exception) {
            false
        }
    }

    private fun ok(json: String, code: Int = 200): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(json)

    private val disp = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val full = request.path ?: "/"
            val path = full.substringBefore('?')
            val query = full.substringAfter('?', "")
            val body = request.body.readUtf8()
            return when {
                path.startsWith("/realtime/v1/websocket") ->
                    MockResponse().withWebSocketUpgrade(wsListener)

                path == "/heartbeat" -> ok("""{"ok":true}""")

                path == "/turn" -> ok("""{"s":[]}""")

                path == "/sync" -> when {
                    // histórico por sala (B1): vacío — nada previo al join
                    query.contains("room=") -> MockResponse().setResponseCode(204)
                    pendingRows.isEmpty() -> MockResponse().setResponseCode(204)
                    else -> {
                        val rows = JSONArray()
                        var maxSeq = counter.get()
                        pendingRows.forEach {
                            rows.put(it)
                            val s = it.optLong(3)
                            if (s > maxSeq) maxSeq = s
                        }
                        pendingRows.clear()
                        // n = cursor nuevo: ≥ seq de TODAS las filas entregadas
                        // (las filas pueden venir con seq externa) y ≥ lo ackeado
                        if (maxSeq > counter.get()) counter.set(maxSeq)
                        ok(JSONObject().put("n", maxSeq).put("m", rows).toString())
                    }
                }

                path == "/outbox" -> {
                    val j = JSONObject(body)
                    val room = j.getString("room")
                    val m = j.getJSONArray("m")
                    val a = JSONArray()
                    for (i in 0 until m.length()) {
                        val id = m.getJSONArray(i).getString(0)
                        nextSeq() // seq del servidor para ese mensaje
                        a.put(id)
                        acked.add(room to id)
                    }
                    ok(JSONObject().put("a", a).toString())
                }

                path == "/auth" -> auth(request.method ?: "POST", body)

                // ntfy (poll/publish), broadcast REST de señalización y resto
                else -> MockResponse().setResponseCode(200)
            }
        }
    }

    private fun auth(method: String, body: String): MockResponse {
        // GET /auth = whoami (D93)
        if (method == "GET") {
            val u = currentUser ?: return MockResponse().setResponseCode(401)
            return ok(
                JSONObject()
                    .put("uid", u.first).put("u", u.second)
                    .put("exp", EXP).toString(),
            )
        }
        val j = try {
            JSONObject(body)
        } catch (_: Exception) {
            JSONObject()
        }
        return when (j.optString("a")) {
            "reg" -> {
                currentUser = Triple("u-test0001", j.optString("u", "tester"), "tok-1")
                sessionBody(201)
            }
            "in" -> {
                if (currentUser == null) currentUser = Triple("u-test0001", j.optString("u", "tester"), "tok-2")
                sessionBody(200)
            }
            "out" -> {
                currentUser = null
                ok("{}")
            }
            "room_new" -> {
                synchronized(knownRooms) { knownRooms.add(j.getString("r")) }
                ok("{}", 201)
            }
            "room_join" -> {
                synchronized(knownRooms) { knownRooms.add(j.getString("r")) }
                ok("{}", 200)
            }
            "rooms" -> {
                val arr = JSONArray()
                synchronized(knownRooms) { knownRooms.forEach { arr.put(JSONArray().put(it).put(1)) } }
                ok(JSONObject().put("rooms", arr).toString())
            }
            else -> ok("{}") // pk, search, dm_new, dm_ack, room_leave…
        }
    }

    private fun sessionBody(code: Int): MockResponse {
        val u = currentUser!!
        return ok(
            JSONObject()
                .put("uid", u.first).put("u", u.second).put("tok", u.third)
                .put("exp", EXP).toString(),
            code,
        )
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            openSockets.add(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            openSockets.remove(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            openSockets.remove(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            when (o.optString("event")) {
                "phx_join", "heartbeat" -> {
                    val reply = JSONObject()
                        .put("event", "phx_reply")
                        .put("payload", JSONObject().put("status", "ok").put("response", JSONObject()))
                        .put("ref", if (o.isNull("ref")) JSONObject.NULL else o.opt("ref") ?: JSONObject.NULL)
                        .put("topic", o.optString("topic").ifEmpty { "phoenix" })
                    webSocket.send(reply.toString())
                }
            }
        }
    }

    companion object {
        /** Misma forma ISO que devuelve Postgres (D93). */
        const val EXP = "2027-01-01T00:00:00Z"
    }
}
