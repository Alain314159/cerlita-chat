package cu.alain.chat

import cu.alain.chat.calls.CallSignals
import cu.alain.chat.crypto.Crypto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Señalización de llamadas (v0.7.0, D75): encode/parse puro, sin Android.
 * El wire viaja por broadcast REST → timbre del par → CallDispatcher.
 */
class CallSignalsTest {

    private val sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n"

    @Test
    fun `offer roundtrip`() {
        val j = CallSignals.encodeOffer("cid-1", "room-1", "uid-a", "Alain", true, sdp)
        assertEquals("sig", CallSignals.EV)
        assertEquals("room-room-1", CallSignals.topicOf("ROOM-1"))
        val sig = CallSignals.parse(j.toString(), "uid-b")
        assertTrue(sig is CallSignals.Signal.Offer)
        sig as CallSignals.Signal.Offer
        assertEquals("cid-1", sig.callId)
        assertEquals("room-1", sig.roomId)
        assertEquals("uid-a", sig.from)
        assertEquals("Alain", sig.fromName)
        assertTrue(sig.video)
        assertEquals("offer", sig.sdpType)
        assertEquals(sdp, sig.sdp)
    }

    @Test
    fun `answer, ice y bye roundtrip`() {
        val ans = CallSignals.parse(CallSignals.encodeAnswer("c2", "uid-a", sdp).toString(), "uid-b")
        assertTrue(ans is CallSignals.Signal.Answer)
        assertEquals(sdp, (ans as CallSignals.Signal.Answer).sdp)

        val ice = CallSignals.parse(
            CallSignals.encodeIce("c2", "uid-a", "0", 1, "candidate:1 udp").toString(), "uid-b")
        assertTrue(ice is CallSignals.Signal.Ice)
        ice as CallSignals.Signal.Ice
        assertEquals("0", ice.sdpMid)
        assertEquals(1, ice.lineIndex)
        assertEquals("candidate:1 udp", ice.candidate)

        val bye = CallSignals.parse(CallSignals.encodeBye("c2", "uid-a", "declined").toString(), "uid-b")
        assertEquals("declined", (bye as CallSignals.Signal.Bye).reason)
    }

    @Test
    fun `el eco propio se ignora`() {
        val j = CallSignals.encodeOffer("c3", "r", "uid-a", "A", false, sdp)
        assertNull(CallSignals.parse(j.toString(), "uid-a")) // yo mismo
    }

    @Test
    fun `basura e incompletos devuelven null sin explotar`() {
        assertNull(CallSignals.parse("", "uid-b"))
        assertNull(CallSignals.parse("no json", "uid-b"))
        assertNull(CallSignals.parse(JSONObject().put("t", "offer").toString(), "uid-b")) // sin c ni f
        assertNull(CallSignals.parse(JSONObject().put("t", "?").put("c", "x").put("f", "y").toString(), "uid-b"))
        // offer sin sdp
        assertNull(CallSignals.parse(
            JSONObject().put("t", "offer").put("c", "x").put("f", "y").put("r", "r").toString(), "uid-b"))
    }

    // ───────── B6 (v0.8.0): sobre cifrado de la señalización ─────────

    /** Deriva la clave igual que Crypto.sigKey (madre roomKey → convKey → sigKey). */
    private fun sigKey(roomKey: ByteArray, roomId: String, callId: String): ByteArray =
        Crypto.sigKey(roomKey, roomId, callId)

    @Test
    fun `sobre cifrado roundtrip completo`() {
        val roomKey = cu.alain.chat.crypto.Crypto.newRoomKey()
        val k = sigKey(roomKey, "room-9", "call-9")
        // 1) offer cifrado → fuera solo se ve t/c/r/n/d
        val outer = CallSignals.wrapEncrypted(k, "call-9", "room-9",
            CallSignals.encodeOffer("call-9", "room-9", "uid-a", "Alain", true, sdp))
        assertTrue(CallSignals.isEncrypted(outer))
        assertEquals("enc", outer.optString("t"))
        assertEquals("room-9", outer.optString("r"))
        // el nombre y el SDP JAMÁS aparecen en claro fuera del sobre
        val wire = outer.toString()
        assertFalse(wire.contains("Alain"))
        assertFalse(wire.contains("127.0.0.1"))
        // 2) abrir → parse → offer intacto
        val inner = CallSignals.unwrapEncrypted(k, "call-9", "room-9", outer)
        assertNotNull(inner)
        val sig = CallSignals.parse(inner!!, "uid-b")
        assertTrue(sig is CallSignals.Signal.Offer)
        sig as CallSignals.Signal.Offer
        assertEquals("Alain", sig.fromName)
        assertEquals(sdp, sig.sdp)
        assertTrue(sig.video)
    }

    @Test
    fun `ice y bye tambien viajan cifrados`() {
        val roomKey = cu.alain.chat.crypto.Crypto.newRoomKey()
        val k = sigKey(roomKey, "r", "c5")
        val iceOut = CallSignals.wrapEncrypted(k, "c5", "r",
            CallSignals.encodeIce("c5", "uid-a", "0", 1, "candidate:1 udp"))
        val iceIn = CallSignals.parse(CallSignals.unwrapEncrypted(k, "c5", "r", iceOut)!!, "uid-b")
        assertEquals(1, (iceIn as CallSignals.Signal.Ice).lineIndex)

        val byeOut = CallSignals.wrapEncrypted(k, "c5", "r",
            CallSignals.encodeBye("c5", "uid-a", "declined"))
        val byeIn = CallSignals.parse(CallSignals.unwrapEncrypted(k, "c5", "r", byeOut)!!, "uid-b")
        assertEquals("declined", (byeIn as CallSignals.Signal.Bye).reason)
    }

    @Test
    fun `clave de otra sala o llamada no abre el sobre`() {
        val roomKey = cu.alain.chat.crypto.Crypto.newRoomKey()
        val k = sigKey(roomKey, "r", "c6")
        val outer = CallSignals.wrapEncrypted(k, "c6", "r",
            CallSignals.encodeBye("c6", "uid-a", "hangup"))
        // misma clave pero callId distinto → AAD distinto → null
        assertNull(CallSignals.unwrapEncrypted(k, "OTRA", "r", outer))
        // roomId distinto → null
        assertNull(CallSignals.unwrapEncrypted(k, "c6", "OTRA", outer))
        // clave de otra sala → null
        val otra = sigKey(cu.alain.chat.crypto.Crypto.newRoomKey(), "r", "c6")
        assertNull(CallSignals.unwrapEncrypted(otra, "c6", "r", outer))
        // b64 corrupto → null sin excepción
        assertNull(CallSignals.unwrapEncrypted(k, "c6", "r",
            JSONObject().put("t", "enc").put("n", "!!!no-b64!!!").put("d", "xx")))
    }

    @Test
    fun `parse legacy en claro sigue funcionando (compat con v0_7)`() {
        // un v0.7.x emite sin sobre; un v0.8.0 lo sigue parseando
        val j = CallSignals.encodeAnswer("c7", "uid-a", sdp)
        assertFalse(CallSignals.isEncrypted(j))
        val sig = CallSignals.parse(j.toString(), "uid-b")
        assertTrue(sig is CallSignals.Signal.Answer)
    }
}
