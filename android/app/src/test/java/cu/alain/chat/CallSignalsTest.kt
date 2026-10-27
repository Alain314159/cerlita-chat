package cu.alain.chat

import cu.alain.chat.calls.CallSignals
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
