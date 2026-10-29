package cu.alain.chat

import cu.alain.chat.mock.MockBackend
import cu.alain.chat.notify.Phoenix
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * D115: contrato del backend simulado, verificado EN JVM (corre en cada `test`
 * de CI, sin emulador). Si una forma cambia en Api.kt o en el protocolo del
 * timbre, este test lo destapa ANTES de que la instrumentada falle de forma
 * críptica en el dispositivo. Usa el PROPIO parser Phoenix de la app para
 * validar los frames del WebSocket simulado.
 */
class MockBackendTest {

    companion object {
        private val backend = MockBackend()
        private val http = OkHttpClient()
        private val JSON = "application/json".toMediaType()

        @BeforeClass @JvmStatic fun arrancar() = backend.start()

        @AfterClass @JvmStatic fun parar() = backend.stop()

        private fun post(path: String, json: String): Response =
            http.newCall(
                Request.Builder()
                    .url(backend.url + path)
                    .post(json.toRequestBody(JSON))
                    .build(),
            ).execute()

        private fun get(path: String): Response =
            http.newCall(Request.Builder().url(backend.url + path).get().build()).execute()
    }

    @Test fun `reg devuelve 201 con uid u tok y exp — la forma de Api register`() {
        val body = JSONObject().put("a", "reg").put("u", "tester").put("p", "clave12345").put("d", "dev").put("ic", "X")
        post("/auth", body.toString()).use { r ->
            assertEquals(201, r.code)
            val j = JSONObject(r.body!!.string())
            assertEquals("u-test0001", j.getString("uid"))
            assertEquals("tester", j.getString("u"))
            assertTrue(j.getString("tok").isNotEmpty())
            // D93: exp ISO-8601 parseable por parseIsoMs
            assertTrue(cu.alain.chat.net.parseIsoMs(j.getString("exp")) != null)
        }
    }

    @Test fun `login devuelve 200 con la misma sesión`() {
        post("/auth", JSONObject().put("a", "in").put("u", "tester").put("p", "clave12345").put("d", "dev").toString())
            .use { r ->
                assertEquals(200, r.code)
                val j = JSONObject(r.body!!.string())
                assertEquals("u-test0001", j.getString("uid"))
            }
    }

    @Test fun `whoami sin sesión da 401 y con sesión 200`() {
        post("/auth", JSONObject().put("a", "out").toString()).use { assertEquals(200, it.code) }
        get("/auth").use { assertEquals(401, it.code) }
        post("/auth", JSONObject().put("a", "in").put("u", "tester").put("p", "x").put("d", "d").toString()).use {
            assertEquals(200, it.code)
        }
        get("/auth").use { r ->
            assertEquals(200, r.code)
            assertEquals("u-test0001", JSONObject(r.body!!.string()).getString("uid"))
        }
    }

    @Test fun `room_new registra y rooms la lista`() {
        post("/auth", JSONObject().put("a", "room_new").put("r", "r-test-1").toString()).use {
            assertEquals(201, it.code)
        }
        post("/auth", JSONObject().put("a", "rooms").toString()).use { r ->
            assertEquals(200, r.code)
            val rooms = JSONObject(r.body!!.string()).getJSONArray("rooms")
            assertEquals("r-test-1", rooms.getJSONArray(0).getString(0))
            assertEquals(1, rooms.getJSONArray(0).getInt(1))
        }
    }

    @Test fun `outbox devuelve el ack con los mismos ids`() {
        post("/auth", JSONObject().put("a", "in").put("u", "tester").put("p", "x").put("d", "d").toString()).close()
        val m = JSONArray()
            .put(JSONArray().put("msg-1").put("ct").put("nonce").put(123))
            .put(JSONArray().put("msg-2").put("ct").put("nonce").put(124))
        post("/outbox", JSONObject().put("room", "r-test-1").put("m", m).toString()).use { r ->
            assertEquals(200, r.code)
            val a = JSONObject(r.body!!.string()).getJSONArray("a")
            assertEquals(2, a.length())
            assertEquals("msg-1", a.getString(0))
            assertEquals("msg-2", a.getString(1))
        }
        assertTrue(backend.acked.contains("r-test-1" to "msg-2"))
    }

    @Test fun `sync vacio da 204 y con fila entrega la forma exacta de 8 campos`() {
        get("/sync?s=0&l=200").use { assertEquals(204, it.code) }

        val row = JSONArray().put("m-x").put("r-test-1").put("u-par").put(backend.nextSeq())
            .put("bg5vbmNl").put("Y3Q").put("pareja").put(1770000000000L)
        val rowSeq = row.getLong(3)
        backend.deliverIncoming(row)
        get("/sync?s=0&l=200").use { r ->
            assertEquals(200, r.code)
            val j = JSONObject(r.body!!.string())
            assertTrue("n ≥ seq de la fila", j.getLong("n") >= rowSeq)
            val m = j.getJSONArray("m")
            assertEquals(1, m.length())
            assertEquals(8, m.getJSONArray(0).length()) // forma de SyncRow
            assertEquals("m-x", m.getJSONArray(0).getString(0))
            assertEquals("r-test-1", m.getJSONArray(0).getString(1))
            assertEquals(1770000000000L, m.getJSONArray(0).getLong(7))
        }
        // entregada una vez: el próximo sync vuelve a 204
        get("/sync?s=0&l=200").use { assertEquals(204, it.code) }
    }

    @Test fun `el WS habla Phoenix - join recibe reply ok y new-msg lo parsea el cliente real`() {
        val opened = CountDownLatch(1)
        val gotReply = CountDownLatch(1)
        val gotDoorbell = CountDownLatch(1)
        val frames = mutableListOf<String>()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                opened.countDown()
                webSocket.send(Phoenix.join("room-prueba", "1"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(frames) { frames.add(text) }
                when (Phoenix.parse(text)) {
                    is Phoenix.Frame.Reply -> gotReply.countDown()
                    is Phoenix.Frame.Doorbell -> gotDoorbell.countDown()
                    else -> {}
                }
            }
        }

        val wsUrl = backend.url.replaceFirst("http", "ws") + "/realtime/v1/websocket?apikey=test&vsn=1.0.0"
        val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), wsListener)

        assertTrue("WS abierto", opened.await(10, TimeUnit.SECONDS))
        assertTrue("phx_reply ok al join (lo exige RealtimeDoorbell)", gotReply.await(10, TimeUnit.SECONDS))

        // el inyector necesita un socket abierto: ya lo hay (el del test)
        assertTrue("push entregado", backend.pushDoorbell("prueba"))
        assertTrue(
            "el frame new-msg lo interpreta el PROPIO parser de la app",
            gotDoorbell.await(10, TimeUnit.SECONDS),
        )
        synchronized(frames) {
            assertTrue(frames.isNotEmpty())
        }

        ws.close(1000, "fin")
        http.dispatcher.executorService.shutdown()
    }
}
