package cu.alain.chat

import cu.alain.chat.profile.ReadReceipt
import cu.alain.chat.profile.RoomInfoMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mensajes de control de la v0.6.0 (D68/D69): RoomInfo (nombre/foto de sala)
 * y ReadReceipt (recibo de lectura). Mismo contrato que ProfileMsg (D61):
 * prefijo con \u0000 imposible de teclear, JSON detrás, parse tolerante.
 */
class RoomInfoTest {

    // ── RoomInfoMsg ──

    @Test
    fun `roominfo roundtrip completo con foto`() {
        val photo = "aGk=".repeat(64) // ~192 B b64, dentro del presupuesto
        val s = RoomInfoMsg.encode("Nuestra sala", photo, 1234567890L)
        assertTrue(RoomInfoMsg.isRoomInfo(s))
        val p = RoomInfoMsg.parse(s)
        assertNotNull(p)
        assertEquals("Nuestra sala", p!!.name)
        assertEquals(photo, p.photoB64)
        assertEquals(1234567890L, p.ts)
    }

    @Test
    fun `roominfo sin foto queda photoB64 null`() {
        val p = RoomInfoMsg.parse(RoomInfoMsg.encode("Sala", null, 5L))
        assertNotNull(p)
        assertNull(p!!.photoB64)
        assertEquals(5L, p.ts)
    }

    @Test
    fun `roominfo nombre largo se recorta a MAX_NAME`() {
        val p = RoomInfoMsg.parse(RoomInfoMsg.encode("x".repeat(100), null, 1L))
        assertNotNull(p)
        assertEquals(RoomInfoMsg.MAX_NAME, p!!.name.length)
    }

    @Test
    fun `roominfo con prefijo pero JSON roto → null (se muestra como texto)`() {
        assertNull(RoomInfoMsg.parse(RoomInfoMsg.PREFIX + "esto no es json{"))
    }

    @Test
    fun `roominfo sin nombre → null`() {
        assertNull(RoomInfoMsg.parse(RoomInfoMsg.encode("   ", null, 1L)))
    }

    @Test
    fun `foto gigante se ignora (presupuesto MAX_PHOTO_B64)`() {
        val s = RoomInfoMsg.encode("Sala", "A".repeat(RoomInfoMsg.MAX_PHOTO_B64 + 1), 1L)
        val p = RoomInfoMsg.parse(s)
        assertNotNull(p)
        assertNull(p!!.photoB64) // fuera de presupuesto → como si no viniera
    }

    // ── ReadReceipt ──

    @Test
    fun `readreceipt roundtrip`() {
        val s = ReadReceipt.encode(987654321L, 111L)
        assertTrue(ReadReceipt.isRead(s))
        val p = ReadReceipt.parse(s)
        assertNotNull(p)
        assertEquals(987654321L, p!!.upToSeq)
        assertEquals(111L, p.ts)
    }

    @Test
    fun `readreceipt sin u → null`() {
        assertNull(ReadReceipt.parse(ReadReceipt.PREFIX + """{"t":1}"""))
    }

    // ── convivencia de prefijos (D61/D68/D69) ──

    @Test
    fun `prefijos distintos - un mensaje nunca se confunde con otro tipo`() {
        val profile = cu.alain.chat.profile.ProfileMsg.PREFIX
        val roomInfo = RoomInfoMsg.encode("S", null, 1L)
        val read = ReadReceipt.encode(1L, 1L)
        val plain = "hola cariño"
        assertFalse(RoomInfoMsg.isRoomInfo(profile))
        assertFalse(ReadReceipt.isRead(profile))
        assertFalse(RoomInfoMsg.isRoomInfo(read))
        assertFalse(ReadReceipt.isRead(roomInfo))
        assertFalse(RoomInfoMsg.isRoomInfo(plain))
        assertFalse(ReadReceipt.isRead(plain))
    }
}
