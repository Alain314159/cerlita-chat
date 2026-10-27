package cu.alain.chat

import cu.alain.chat.calls.IceQueue
import org.junit.Test
import org.webrtc.IceCandidate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D81: el bug que mataba las llamadas — los candidatos ICE que llegan antes
 * que el SDP remoto se perdían en silencio. La IceQueue los acumula EN ORDEN
 * hasta que el SDP se aplica, y a partir de ahí los pasa directos.
 */
class IceQueueTest {

    @Test
    fun `ice antes del SDP se acumula en orden y se drena tras aplicarlo`() {
        val q = IceQueue()
        val c1 = IceCandidate("0", 0, "candidate-1")
        val c2 = IceCandidate("0", 1, "candidate-2")
        assertFalse(q.add(c1), "antes del SDP no se aplica: se encola")
        assertFalse(q.add(c2), "antes del SDP no se aplica: se encola")
        assertEquals(2, q.size)

        q.remoteApplied() // el SDP remoto quedó fijado
        assertEquals(listOf(c1, c2), q.drain(), "drena en orden de llegada")
        assertTrue(q.drain().isEmpty(), "drenar dos veces no repite")
    }

    @Test
    fun `ice despues del SDP se aplica directo sin encolar`() {
        val q = IceQueue()
        q.remoteApplied()
        val c = IceCandidate("audio", 0, "candidate-9")
        assertTrue(q.add(c), "con el SDP aplicado va directo")
        assertEquals(0, q.size, "nada quedó en la cola")
        assertTrue(q.drain().isEmpty())
    }

    @Test
    fun `cola nueva arranca sin SDP aplicado`() {
        val q = IceQueue()
        assertFalse(q.remoteSet)
        assertFalse(q.add(IceCandidate(null, 0, "x")))
    }
}
