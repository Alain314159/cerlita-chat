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
 * D119 (Fase I — identidad multi-dispositivo): además del contrato viejo,
 *  - reg/in/pk registran la clave del teléfono y devuelven kid (conjunto de
 *    claves: userKeys, sin reemplazo);
 *  - search devuelve el fakePeer configurable CON sus claves activas
 *    [[kid, pubB64], …] (elemento 3);
 *  - dm_new registra la sala como kind=1 y cuenta los wraps multi-dispositivo;
 *  - rooms devuelve [roomId, owner, kind, pending];
 *  - dm_stuck / dm_wraps / dm_ack quedan grabados para que el test afirme;
 *  - deliverDm() permite al TEST fabricar wraps «del par» y servirlos por
 *    /sync (campo dm), como haría el servidor real al re-emparejar.
 *
 * Vive en sharedTest: MockBackendTest lo valida en JVM (sin emulador, en cada
 * `test` de CI) y las instrumentadas lo usan en el dispositivo — mismo código,
 * dos mundos. Cero dependencias de Android.
 */
class MockBackend {

    private val counter = AtomicLong(100)
    private val pendingRows = CopyOnWriteArrayList<JSONArray>()
    private val openSockets = CopyOnWriteArrayList<WebSocket>()

    /** (uid, uname, token) de la última sesión aceptada — null tras a=out. */
    @Volatile
    var currentUser: Triple<String, String, String>? = null
        private set

    /** Acks de /outbox: (roomId, msgId). */
    val acked = CopyOnWriteArrayList<Pair<String, String>>()

    // ── D119: identidad multi-dispositivo ──

    /** Claves registradas por el usuario simulado: (kid, pubB64). */
    val userKeys = CopyOnWriteArrayList<Pair<String, String>>()

    /** El «par» que devuelve search: (uid, uname, claves activas (kid, pubB64)). */
    @Volatile
    var fakePeer: Triple<String, String, List<Pair<String, String>>>? = null

    /** Salas de chat directo conocidas (rooms las lista con kind=1). */
    val dmRooms = CopyOnWriteArrayList<String>()

    /** Wraps pendientes de /sync (campo dm) — los fabrica el test como el par. */
    private val pendingDms = CopyOnWriteArrayList<JSONArray>()

    /** dm_stuck grabados: roomId. */
    val stuckRooms = CopyOnWriteArrayList<String>()

    /** dm_wraps grabados: (roomId, nº de wraps). */
    val wrapUploads = CopyOnWriteArrayList<Pair<String, Int>>()

    /** dm_ack grabados: roomId. */
    val ackedDms = CopyOnWriteArrayList<String>()

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
     * D119: encola un wrap de chat directo PARA el usuario simulado, como
     * haría el servidor real al servir los re-emparejamientos del par.
     * fromPubB64 = pública del par CON la que se fabricó el wrap (el test
     * la controla porque controla el keypair del par).
     */
    fun deliverDm(roomId: String, fromUid: String, fromUname: String, fromPubB64: String, wrappedB64: String) {
        pendingDms.add(
            JSONArray()
                .put(roomId).put(fromUid).put(fromUname)
                .put(fromPubB64).put(wrappedB64),
        )
    }

    /** Registra (o refresca) la clave del teléfono → kid asignado (D119). */
    private fun recordKey(pubB64: String?): String? {
        if (pubB64.isNullOrEmpty()) return null
        userKeys.firstOrNull { it.second == pubB64 }?.let { return it.first }
        val kid = "k-${userKeys.size + 1}"
        userKeys.add(kid to pubB64)
        return kid
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
                    pendingRows.isEmpty() && pendingDms.isEmpty() -> MockResponse().setResponseCode(204)
                    else -> {
                        val rows = JSONArray()
                        var maxSeq = counter.get()
                        pendingRows.forEach {
                            rows.put(it)
                            val s = it.optLong(3)
                            if (s > maxSeq) maxSeq = s
                        }
                        pendingRows.clear()
                        // D119: wraps de chats directos pendientes (forma de DmPickup)
                        val dms = JSONArray()
                        pendingDms.forEach { dms.put(it) }
                        pendingDms.clear()
                        // n = cursor nuevo: ≥ seq de TODAS las filas entregadas
                        // (las filas pueden venir con seq externa) y ≥ lo ackeado
                        if (maxSeq > counter.get()) counter.set(maxSeq)
                        val j = JSONObject().put("n", maxSeq).put("m", rows)
                        if (dms.length() > 0) j.put("dm", dms)
                        ok(j.toString())
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
                recordKey(j.optString("pk")) // D119
                sessionBody(201)
            }
            "in" -> {
                if (currentUser == null) currentUser = Triple("u-test0001", j.optString("u", "tester"), "tok-2")
                recordKey(j.optString("pk")) // D119
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
                synchronized(knownRooms) {
                    knownRooms.forEach { r ->
                        // D119: [roomId, owner, kind, pending] — pending = wraps sin consumir
                        val kind = if (dmRooms.contains(r)) 1 else 0
                        val pending = pendingDms.any { it.optString(0) == r }
                        arr.put(JSONArray().put(r).put(1).put(kind).put(if (pending) 1 else 0))
                    }
                }
                ok(JSONObject().put("rooms", arr).toString())
            }
            // D119: search devuelve el par simulado con SUS claves activas
            "search" -> {
                val peer = fakePeer
                    ?: return ok(JSONObject().put("r", JSONArray()).toString())
                val keys = JSONArray()
                peer.third.forEach { (kid, pub) -> keys.put(JSONArray().put(kid).put(pub)) }
                val latest = peer.third.lastOrNull()?.second
                ok(
                    JSONObject().put(
                        "r",
                        JSONArray().put(
                            JSONArray().put(peer.first).put(peer.second).put(latest ?: JSONObject.NULL).put(keys),
                        ),
                    ).toString(),
                )
            }
            // D119: registra la clave del teléfono en el conjunto → kid
            "pk" -> {
                val kid = recordKey(j.optString("pk"))
                if (kid == null) ok("""{"e":"bad_pk"}""", 400)
                else ok(JSONObject().put("ok", true).put("kid", kid).toString())
            }
            // D119: crea el chat directo — registra sala kind=1 y cuenta wraps
            "dm_new" -> {
                val r = j.optString("r")
                synchronized(knownRooms) { knownRooms.add(r) }
                dmRooms.add(r)
                val ws = j.optJSONArray("ws")
                wrapUploads.add(r to (ws?.length() ?: 0))
                ok(JSONObject().put("ok", true).put("room", r).put("exists", 0).toString(), 201)
            }
            "dm_ack" -> {
                ackedDms.add(j.optString("r"))
                ok("{}")
            }
            // D119: el teléfono no puede abrir → grabar (el test afiere)
            "dm_stuck" -> {
                stuckRooms.add(j.optString("r"))
                ok("{}")
            }
            // D119: re-emparejamiento — grabar los wraps subidos
            "dm_wraps" -> {
                val ws = j.optJSONArray("ws")
                wrapUploads.add(j.optString("r") to (ws?.length() ?: 0))
                ok("{}")
            }
            else -> ok("{}")
        }
    }

    private val knownRooms = LinkedHashSet<String>()

    private fun sessionBody(code: Int): MockResponse {
        val u = currentUser!!
        val kid = userKeys.lastOrNull()?.first
        return ok(
            JSONObject()
                .put("uid", u.first).put("u", u.second).put("tok", u.third)
                .put("exp", EXP)
                .put("kid", kid ?: JSONObject.NULL) // D119
                .toString(),
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
