package cu.alain.chat

import cu.alain.chat.crypto.Crypto
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Tests JVM del modelo criptográfico rooms (D41-D43, D54).
 * Sin Android: Tink subtle y argon2kt son 100% Java.
 */
class CryptoRoomsTest {

    // ---------- base32 (RFC4648) ----------

    @Test
    fun `base32 vectores RFC4648`() {
        assertEquals("", Crypto.base32Encode("".toByteArray()))
        assertEquals("MY", Crypto.base32Encode("f".toByteArray()))
        assertEquals("MZXQ", Crypto.base32Encode("fo".toByteArray()))
        assertEquals("MZXW6", Crypto.base32Encode("foo".toByteArray()))
        assertEquals("MZXW6YQ", Crypto.base32Encode("foob".toByteArray()))
        assertEquals("MZXW6YTBOI", Crypto.base32Encode("foobar".toByteArray()))
    }

    @Test
    fun `base32 roundtrip longitudes varias`() {
        for (n in intArrayOf(1, 4, 5, 15, 16, 32, 50, 54, 100)) {
            val b = ByteArray(n) { (it * 7 + 3).toByte() }
            assertEquals(b.toList(), Crypto.base32Decode(Crypto.base32Encode(b))?.toList())
        }
    }

    @Test
    fun `base32 rechaza caracteres invalidos`() {
        assertNull(Crypto.base32Decode("MZXW6YTBO0")) // 0 no está en el alfabeto
        assertNull(Crypto.base32Decode("MZXW6YTBO8")) // 8 tampoco
    }

    // ---------- código de invitación (D42) ----------

    @Test
    fun `invite roundtrip devuelve key y salt originales`() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val key = ByteArray(32) { (it * 3 + 7).toByte() }
        val code = Crypto.encodeInvite(salt, key)
        val decoded = Crypto.decodeInvite(code)
        assertNotNull(decoded)
        assertEquals(key.toList(), decoded!!.first.toList())
        assertEquals(salt.toList(), decoded!!.second.toList())
    }

    @Test
    fun `invite tolera minusculas y guiones`() {
        val salt = Crypto.newSalt16()
        val key = Crypto.newRoomKey()
        val code = Crypto.encodeInvite(salt, key)
        val mangled = code.lowercase().replace("-", "") // pegado a mano sin guiones
        val decoded = Crypto.decodeInvite(mangled)
        assertNotNull(decoded)
        assertEquals(key.toList(), decoded!!.first.toList())
    }

    @Test
    fun `invite con un caracter cambiado falla por CRC`() {
        val code = Crypto.encodeInvite(Crypto.newSalt16(), Crypto.newRoomKey())
        // cambia un carácter de datos por otro válido del alfabeto
        val i = code.indexOfFirst { it != '-' }
        val swapped = if (code[i] == 'A') code.replaceRange(i, i + 1, "B") else code.replaceRange(i, i + 1, "A")
        assertNull(Crypto.decodeInvite(swapped))
    }

    @Test
    fun `invite truncado es invalido`() {
        val code = Crypto.encodeInvite(Crypto.newSalt16(), Crypto.newRoomKey())
        assertNull(Crypto.decodeInvite(code.dropLast(5)))
    }

    @Test
    fun `invite tiene longitud esperada`() {
        // 54 B → 87 chars base32 → 17 guiones = 104
        val code = Crypto.encodeInvite(Crypto.newSalt16(), Crypto.newRoomKey())
        assertEquals(104, code.length)
        assertEquals(87, code.count { it != '-' })
    }

    // ---------- roomId y SAS (D41, D43) ----------

    @Test
    fun `roomId es UUID determinista`() {
        val salt = ByteArray(16) { 1 }
        val key = ByteArray(32) { 2 }
        val id1 = Crypto.roomId(key, salt)
        val id2 = Crypto.roomId(key, salt)
        assertEquals(id1, id2)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id1))
    }

    @Test
    fun `roomId distinto con salt distinto`() {
        val key = ByteArray(32) { 2 }
        assertNotEquals(Crypto.roomId(key, ByteArray(16) { 1 }), Crypto.roomId(key, ByteArray(16) { 9 }))
    }

    @Test
    fun `sas es 6 digitos y vector conocido`() {
        val salt = ByteArray(16) { 5 }
        val key = ByteArray(32) { 6 }
        val s1 = Crypto.sas6(key, salt)
        val s2 = Crypto.sas6(key, salt)
        assertEquals(s1, s2)
        assertTrue(Regex("^\\d{6}$").matches(s1))
        // KAT: HKDF-SHA256(key=[6]*32, salt=[5]*16, info="sas")[0..4] BE mod 10^6
        assertEquals("539954", s1)
    }

    @Test
    fun `roomId vector conocido`() {
        // KAT calculado con implementación Python independiente (RFC 5869 verificado):
        // HKDF-SHA256(key=[2]*32, salt=[1]*16, info="room-id")[0..16] como UUID
        assertEquals(
            "280f9ed9-06d1-3807-f23c-38b5ab6b8b65",
            Crypto.roomId(ByteArray(32) { 2 }, ByteArray(16) { 1 }),
        )
    }

    // ---------- claves de mensaje y AAD ----------

    @Test
    fun `convKey depende del roomId`() {
        val key = Crypto.newRoomKey()
        assertNotEquals(
            Crypto.convKey(key, "11111111-1111-1111-1111-111111111111").toList(),
            Crypto.convKey(key, "22222222-2222-2222-2222-222222222222").toList(),
        )
    }

    @Test
    fun `aad formato pipe con roomId`() {
        assertEquals(
            "v1|m-1|s-1|r-1",
            String(Crypto.aad("m-1", "s-1", "r-1")),
        )
    }

    @Test
    fun `seal open roundtrip y aad incorrecto falla`() {
        val convKey = Crypto.convKey(Crypto.newRoomKey(), "11111111-1111-1111-1111-111111111111")
        val mk = Crypto.msgKey(convKey, "msg-1")
        val aad = Crypto.aad("msg-1", "sender-1", "room-1")
        val (nonce, ct) = Crypto.seal(mk, "hola desde La Habana".toByteArray(), aad)
        assertEquals("hola desde La Habana", String(Crypto.open(mk, nonce, ct, aad)!!))
        assertNull(Crypto.open(mk, nonce, ct, Crypto.aad("msg-1", "sender-OTRO", "room-1")))
        assertNull(Crypto.open(mk, nonce, ct, Crypto.aad("msg-OTRO", "sender-1", "room-1")))
    }

    // ---------- framing ----------

    @Test
    fun `frame unframe texto corto y largo comprimido`() {
        val corto = "hola"
        assertEquals(corto, Crypto.unframe(Crypto.frame(corto)))
        val largo = "mensaje largo ".repeat(500) // >512 B → flag gzip
        val framed = Crypto.frame(largo)
        assertEquals(1.toByte(), framed[0])
        assertEquals(largo, Crypto.unframe(framed))
    }

    @Test
    fun `uuid7 formato y orden`() {
        val a = Crypto.uuid7()
        Thread.sleep(2) // distinto milisegundo → ordenable por timestamp
        val b = Crypto.uuid7()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(a))
        assertTrue("UUIDv7 con ms posterior debe ordenarse después: $a >= $b", a < b)
    }

    @Test
    fun `encodeInvite rechaza tamanos incorrectos`() {
        try {
            Crypto.encodeInvite(ByteArray(15), ByteArray(32))
            org.junit.Assert.fail("salt de 15 B debe lanzar IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
        try {
            Crypto.encodeInvite(ByteArray(16), ByteArray(31))
            org.junit.Assert.fail("key de 31 B debe lanzar IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }
}
