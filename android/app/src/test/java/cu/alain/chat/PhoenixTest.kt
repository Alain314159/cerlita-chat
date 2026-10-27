package cu.alain.chat

import cu.alain.chat.notify.Phoenix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del encuadre Phoenix v1.0.0 (D63). Los frames de entrada salen de
 * capturas REALES del servidor en producción (scripts/test_realtime_protocol.py
 * y e2e_doorbell.py): lo que se valida aquí es lo que la app recibirá.
 */
class PhoenixTest {

    private val rid = "70f09cd7-219b-0e1b-b948-e8213a6db638"

    // ─────────────────── frames que enviamos ───────────────────

    @Test
    fun `join con config de broadcast`() {
        val o = JSONObject(Phoenix.join("room-$rid", "3"))
        assertEquals("realtime:room-$rid", o.getString("topic"))
        assertEquals("phx_join", o.getString("event"))
        assertEquals("3", o.getString("ref"))
        val cfg = o.getJSONObject("payload").getJSONObject("config")
        assertFalse(cfg.getJSONObject("broadcast").getBoolean("self"))
        assertTrue(cfg.getJSONObject("presence").getString("key").isEmpty())
    }

    @Test
    fun `heartbeat al topic phoenix`() {
        val o = JSONObject(Phoenix.heartbeat("9"))
        assertEquals("phoenix", o.getString("topic"))
        assertEquals("heartbeat", o.getString("event"))
        assertEquals("9", o.getString("ref"))
        assertEquals(0, o.getJSONObject("payload").length())
    }

    @Test
    fun `leave de una sala`() {
        val o = JSONObject(Phoenix.leave("room-$rid", "12"))
        assertEquals("realtime:room-$rid", o.getString("topic"))
        assertEquals("phx_leave", o.getString("event"))
    }

    @Test
    fun `join del topic personal (dm y llamadas)`() {
        val o = JSONObject(Phoenix.join("user-uid-123", "5"))
        assertEquals("realtime:user-uid-123", o.getString("topic"))
        assertEquals("phx_join", o.getString("event"))
    }

    // ─────────────────── frames que recibimos (capturas reales) ───────────────────

    @Test
    fun `reply ok del join`() {
        val raw = """{"ref": "1", "event": "phx_reply",
            "payload": {"status": "ok", "response": {"postgres_changes": []}},
            "topic": "realtime:room-$rid"}"""
        val f = Phoenix.parse(raw) as Phoenix.Frame.Reply
        assertEquals("1", f.ref)
        assertTrue(f.ok)
        assertEquals("realtime:room-$rid", f.topic)
    }

    @Test
    fun `reply ok del heartbeat`() {
        val raw = """{"ref": "2", "event": "phx_reply",
            "payload": {"status": "ok", "response": {}}, "topic": "phoenix"}"""
        val f = Phoenix.parse(raw) as Phoenix.Frame.Reply
        assertTrue(f.ok)
    }

    @Test
    fun `timbre de mensaje nuevo llega con roomId y sin contenido`() {
        // frame real capturado del E2E contra producción (e2e_doorbell.py)
        val raw = """{"ref": null, "event": "broadcast",
            "payload": {"event": "new-msg", "payload": {"roomId": "$rid"}, "type": "broadcast"},
            "topic": "realtime:room-$rid"}"""
        val f = Phoenix.parse(raw) as Phoenix.Frame.Doorbell
        assertEquals(rid, f.roomId)
        assertEquals("new-msg", f.event)
    }

    @Test
    fun `timbre con roomId ausente no explota`() {
        val raw = """{"ref": null, "event": "broadcast",
            "payload": {"event": "new-msg", "payload": {}, "type": "broadcast"},
            "topic": "realtime:room-x"}"""
        val f = Phoenix.parse(raw) as Phoenix.Frame.Doorbell
        assertNull(f.roomId)
    }

    @Test
    fun `eventos ajenos se ignoran`() {
        assertNull(Phoenix.parse("""{"event":"presence_diff","payload":{},"topic":"realtime:t"}"""))
        assertNull(Phoenix.parse("""{"event":"phx_error","payload":{},"topic":"phoenix"}"""))
        // un broadcast de OTRO evento (no new-msg) NO es timbre: llega como
        // Broadcast genérico (dm / señalización de llamadas, v0.7.0) con su payload
        val f = Phoenix.parse("""{"event":"broadcast","payload":{"event":"dm","payload":{"roomId":"$rid"},"type":"broadcast"},"topic":"realtime:user-yo"}""")
        assertTrue(f is Phoenix.Frame.Broadcast)
        f as Phoenix.Frame.Broadcast
        assertEquals("dm", f.event)
        assertEquals("""{"roomId":"$rid"}""", f.payload)
        // payload ausente → "{}" (nunca null: el receptor siempre parsea JSON)
        val g = Phoenix.parse("""{"event":"broadcast","payload":{"event":"sig"},"topic":"realtime:t"}""")
        assertTrue(g is Phoenix.Frame.Broadcast)
        assertEquals("{}", (g as Phoenix.Frame.Broadcast).payload)
    }

    @Test
    fun `basura devuelve null`() {
        assertNull(Phoenix.parse(""))
        assertNull(Phoenix.parse("no json"))
        assertNull(Phoenix.parse("""{"event":"phx_reply"}""")) // sin ref
    }
}
