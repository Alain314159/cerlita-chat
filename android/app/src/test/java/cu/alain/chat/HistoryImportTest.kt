package cu.alain.chat

import cu.alain.chat.core.C
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.export.HistoryImport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Tests JVM del import del export cifrado (D47/D58).
 *
 * El blob se construye aquí con el MISMO formato que HistoryExport (magic,
 * salt, nonce, ct, AAD) — es el contrato de archivo, no una copia del código:
 * si alguien cambia el formato sin romper esto, el import dejará de leer
 * respaldos viejos y este test lo delatará… y al revés.
 */
class HistoryImportTest {

    private val MAGIC = "E2EEEXP2".toByteArray(Charsets.US_ASCII)
    private val AAD_NEW = "cerlita-chat-export-v2".toByteArray(Charsets.UTF_8)
    private val AAD_LEGACY = "alain-chat-export-v2".toByteArray(Charsets.UTF_8) // exports v0.2.0
    private val PASS = "frase-secreta-del-export".toCharArray()

    /** Derivación pura JVM (argon2kt es JNI: D38). El FORMATO es lo que se prueba. */
    private val FAKE_DERIVE: (CharArray, ByteArray) -> ByteArray =
        { p, s -> Crypto.hkdf(String(p).toByteArray(Charsets.UTF_8), s, "test-export-key") }

    private val roomA = "11111111-2222-3333-4444-555555555555"
    private val roomB = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val keyA = ByteArray(32) { (it + 1).toByte() }
    private val saltA = ByteArray(16) { (it + 7).toByte() }

    /** Espejo del formato de HistoryExport.export (el contrato del archivo). */
    private fun buildBlob(aad: ByteArray, pass: CharArray = PASS): ByteArray {
        val rooms = JSONArray()
            .put(
                JSONObject()
                    .put("id", roomA).put("name", "Sala prueba").put("owner", true)
                    .put("sas", "123456")
                    .put("k", Base64.getEncoder().encodeToString(keyA))
                    .put("s", Base64.getEncoder().encodeToString(saltA)),
            )
            .put(
                JSONObject()
                    .put("id", roomB).put("name", "Sala sin clave").put("owner", false)
                    .put("sas", "").put("k", "").put("s", ""),
            )
        val msgs = JSONArray()
            .put(
                JSONArray()
                    .put("m-uno").put(roomA).put("alain").put(1)
                    // > 512 B: fuerza el camino gzip del framing (D35)
                    .put("hola".padEnd(700, 'x')).put(1_700_000_000_000).put(C.ST_SENT),
            )
            .put(
                JSONArray()
                    .put("m-dos").put(roomA).put("cerlita").put(0)
                    .put("qué tal").put(1_700_000_000_001).put(C.ST_SENDING), // debe → FAILED
            )
            .put(
                JSONArray()
                    .put("m-tres").put(roomB).put("cerlita").put(0)
                    .put("sin clave igual se guarda el texto").put(1_700_000_000_002).put(C.ST_RECEIVED),
            )
        val json = JSONObject()
            .put("v", 2)
            .put("exported_at", 1_728_000_000_000)
            .put("me", "alain")
            .put("rooms", rooms)
            .put("messages", msgs)
            .toString()

        val wire = Crypto.frame(json)
        val salt = Crypto.newSalt16()
        val key = FAKE_DERIVE(pass, salt)
        val (nonce, ct) = Crypto.seal(key, wire, aad)
        Crypto.zero(key)
        return MAGIC + salt + nonce + ct
    }

    @Test
    fun `roundtrip completo con gzip y claves`() {
        val p = HistoryImport.parse(buildBlob(AAD_NEW), PASS, FAKE_DERIVE)

        assertEquals("alain", p.me)
        assertEquals(1_728_000_000_000, p.exportedAt)
        assertEquals(2, p.rooms.size)
        assertEquals(3, p.messages.size)

        val ra = p.rooms[0]
        assertEquals(roomA, ra.roomId)
        assertEquals("Sala prueba", ra.name)
        assertTrue(ra.owner)
        assertEquals("123456", ra.sas)
        assertEquals(keyA.toList(), Base64.getDecoder().decode(ra.keyB64).toList())
        assertEquals(saltA.toList(), Base64.getDecoder().decode(ra.saltB64).toList())

        // sala exportada sin clave (2º teléfono): keyB64 null, no string vacío
        val rb = p.rooms[1]
        assertEquals(roomB, rb.roomId)
        assertNull(rb.keyB64)
        assertNull(rb.saltB64)

        val m1 = p.messages[0]
        assertEquals("m-uno", m1.msgId)
        assertEquals(700, m1.body.length) // gzip ida y vuelta sin pérdida
        assertTrue(m1.mine)
        assertEquals(C.ST_SENT, m1.state)

        // sending → failed: el outbox no viaja en el export
        assertEquals(C.ST_FAILED, p.messages[1].state)
        assertEquals("qué tal", p.messages[1].body)

        assertEquals(C.ST_RECEIVED, p.messages[2].state)
    }

    @Test
    fun `aad legacy de v0_2_0 tambien se acepta`() {
        val p = HistoryImport.parse(buildBlob(AAD_LEGACY), PASS, FAKE_DERIVE)
        assertEquals("alain", p.me)
        assertEquals(2, p.rooms.size)
    }

    @Test
    fun `frase incorrecta rechazada`() {
        val blob = buildBlob(AAD_NEW)
        try {
            HistoryImport.parse(blob, "otra-frase-totalmente-distinta".toCharArray(), FAKE_DERIVE)
            throw AssertionError("debía fallar con BadPassphraseException")
        } catch (_: HistoryImport.BadPassphraseException) {
            // esperado
        }
    }

    @Test
    fun `archivos que no son export rechazados`() {
        // basura: falla en el magic (antes de derivar)
        try {
            HistoryImport.parse(ByteArray(64) { (it * 3).toByte() }, PASS, FAKE_DERIVE)
            throw AssertionError("debía fallar con FormatException (magic)")
        } catch (_: HistoryImport.FormatException) {
        }

        // truncado: falla en la longitud mínima
        val blob = buildBlob(AAD_NEW)
        try {
            HistoryImport.parse(blob.copyOf(30), PASS, FAKE_DERIVE)
            throw AssertionError("debía fallar con FormatException (corto)")
        } catch (_: HistoryImport.FormatException) {
        }

        // magic bueno, ciphertext cortado: tag imposible → BadPassphrase
        // (mismo mensaje que frase errónea: para el usuario es indistinguible
        // y así no filtramos información sobre el contenido)
        try {
            HistoryImport.parse(blob.copyOf(blob.size - 5), PASS, FAKE_DERIVE)
            throw AssertionError("debía fallar (ct truncado)")
        } catch (_: HistoryImport.BadPassphraseException) {
        } catch (_: HistoryImport.FormatException) {
        }
    }
}
