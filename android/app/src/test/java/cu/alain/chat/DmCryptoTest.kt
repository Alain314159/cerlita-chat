package cu.alain.chat

import cu.alain.chat.crypto.Crypto
import cu.alain.chat.crypto.DmCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cripto de CHATS DIRECTOS (D74): X25519 de Tink + HKDF + ChaCha20-Poly1305.
 *
 * El contrato: el creador envuelve (roomKey, roomSalt) para el par; el par
 * desenvelve con su privada + la pública del creador. El servidor solo ve
 * un blob opaco. Si algo cambia (keypair, roomId, uid) el unwrap devuelve
 * null — nunca claves basura.
 */
class DmCryptoTest {

    private fun uuidOf(b: Byte) = Crypto.roomId(
        ByteArray(32) { b }, ByteArray(16) { b },
    )

    @Test
    fun `wrap y unwrap roundtrip completo`() {
        val creator = DmCrypto.newKeyPair()
        val peer = DmCrypto.newKeyPair()
        val roomKey = Crypto.newRoomKey()
        val roomSalt = Crypto.newSalt16()
        val roomId = Crypto.roomId(roomKey, roomSalt)

        val wrapped = DmCrypto.wrap(
            creator.priv, peer.pub, roomId, "uid-peer", roomKey, roomSalt,
        )
        // el servidor ve un blob opaco de tamaño fijo (12 nonce + 50 payload + 16 tag)
        assertEquals(12 + 50 + 16, wrapped.size)

        val out = DmCrypto.unwrap(peer.priv, creator.pub, roomId, "uid-peer", wrapped)
        assertTrue(out != null)
        assertArrayEquals(roomKey, out!!.first)
        assertArrayEquals(roomSalt, out.second)
    }

    @Test
    fun `ECDH simetrico - cualquiera puede envolver`() {
        // A envuelve para B; B también podría envolver para A con el mismo secreto
        val a = DmCrypto.newKeyPair()
        val b = DmCrypto.newKeyPair()
        val roomKey = Crypto.newRoomKey()
        val roomSalt = Crypto.newSalt16()
        val roomId = Crypto.roomId(roomKey, roomSalt)

        val w1 = DmCrypto.wrap(a.priv, b.pub, roomId, "uid-b", roomKey, roomSalt)
        val w2 = DmCrypto.wrap(b.priv, a.pub, roomId, "uid-b", roomKey, roomSalt)
        // mismo secreto compartido → misma wrapKey → ambos desenvelvan lo del otro
        assertArrayEquals(
            DmCrypto.unwrap(b.priv, a.pub, roomId, "uid-b", w1)!!.first,
            DmCrypto.unwrap(b.priv, a.pub, roomId, "uid-b", w2)!!.first,
        )
    }

    @Test
    fun `keypair equivocada no abre`() {
        val creator = DmCrypto.newKeyPair()
        val peer = DmCrypto.newKeyPair()
        val otra = DmCrypto.newKeyPair() // el teléfono NUEVO del par
        val roomKey = Crypto.newRoomKey()
        val roomSalt = Crypto.newSalt16()
        val roomId = Crypto.roomId(roomKey, roomSalt)

        val wrapped = DmCrypto.wrap(creator.priv, peer.pub, roomId, "uid-peer", roomKey, roomSalt)
        assertNull(DmCrypto.unwrap(otra.priv, creator.pub, roomId, "uid-peer", wrapped))
    }

    @Test
    fun `aad liga el wrap a roomId y receptor`() {
        val creator = DmCrypto.newKeyPair()
        val peer = DmCrypto.newKeyPair()
        val roomKey = Crypto.newRoomKey()
        val roomSalt = Crypto.newSalt16()
        val roomId = Crypto.roomId(roomKey, roomSalt)

        val wrapped = DmCrypto.wrap(creator.priv, peer.pub, roomId, "uid-peer", roomKey, roomSalt)
        // mismo wrap, otro roomId declarado → lo rechaza (integridad E2EE)
        assertNull(DmCrypto.unwrap(peer.priv, creator.pub, uuidOf(7), "uid-peer", wrapped))
        // mismo wrap, otro receptor → lo rechaza
        assertNull(DmCrypto.unwrap(peer.priv, creator.pub, roomId, "uid-otro", wrapped))
    }

    @Test
    fun `roomId derivado distinto al anunciado se descarta`() {
        // un servidor malicioso que sirva el wrap de una sala con el roomId
        // de OTRA: la verificación de roomId lo mata
        val creator = DmCrypto.newKeyPair()
        val peer = DmCrypto.newKeyPair()
        val roomKey = Crypto.newRoomKey()
        val roomSalt = Crypto.newSalt16()
        val realRoom = Crypto.roomId(roomKey, roomSalt)

        val wrapped = DmCrypto.wrap(creator.priv, peer.pub, realRoom, "uid-peer", roomKey, roomSalt)
        // el servidor declara otro roomId → unwrap devuelve null (verificación)
        assertNull(DmCrypto.unwrap(peer.priv, creator.pub, uuidOf(3), "uid-peer", wrapped))
    }

    @Test
    fun `blob corrupto no abre ni lanza`() {
        val creator = DmCrypto.newKeyPair()
        val peer = DmCrypto.newKeyPair()
        val roomKey = Crypto.newRoomKey()
        val roomSalt = Crypto.newSalt16()
        val roomId = Crypto.roomId(roomKey, roomSalt)
        val wrapped = DmCrypto.wrap(creator.priv, peer.pub, roomId, "uid-peer", roomKey, roomSalt)

        val corrupto = wrapped.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(DmCrypto.unwrap(peer.priv, creator.pub, roomId, "uid-peer", corrupto))
        assertNull(DmCrypto.unwrap(peer.priv, creator.pub, roomId, "uid-peer", wrapped.copyOf(20)))
        assertNull(DmCrypto.unwrap(peer.priv, creator.pub, roomId, "uid-peer", ByteArray(0)))
    }

    @Test
    fun `publica derivada de la privada`() {
        val kp = DmCrypto.newKeyPair()
        assertArrayEquals(
            kp.pub,
            com.google.crypto.tink.subtle.X25519.publicFromPrivate(kp.priv),
        )
    }
}
