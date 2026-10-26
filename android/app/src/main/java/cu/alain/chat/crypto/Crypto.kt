package cu.alain.chat.crypto

import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import cu.alain.chat.core.C
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de extremo a extremo (D3, D12, D14, D33, D34, D35 — docs/DECISIONES.md).
 *
 * rootKey = Argon2id(frase E2EE, salt 16B, t=3, m=64MiB, p=1)          [solo en el cliente]
 * convKey = HKDF-SHA256(rootKey, salt="lo|hi" (usernames ordenados), info="conv-v1")
 * msgKey  = HKDF-SHA256(convKey, salt=msg_id, info="msg-v1")
 * AEAD    = ChaCha20-Poly1305 con AAD = "v1|msgId|senderUid|lo|hi"
 *
 * El servidor NUNCA ve la frase, rootKey, convKey ni msgKey: solo nonce + ciphertext.
 * Ambos extremos derivan la misma convKey con solo conocerse por username — sin lookup.
 */
object Crypto {

    private val rnd = SecureRandom()

    // ---------- UUIDv7 (D16, RFC 9562) ----------
    // 48 bits de timestamp unix ms + versión 7 + 74 bits aleatorios (var 10).
    // Kotlin 2.4 solo trae UUIDv4; esto son ~20 líneas propias.
    fun uuid7(): String {
        val ts = System.currentTimeMillis()
        val b = ByteArray(16)
        for (i in 0..5) b[i] = ((ts ushr (8 * (5 - i))) and 0xFF).toByte()
        for (i in 6..15) b[i] = rnd.nextInt(256).toByte()
        b[6] = ((b[6].toInt() and 0x0F) or 0x70).toByte() // versión 7
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte() // variante 10
        val h = buildString(36) {
            for (i in b.indices) {
                append("0123456789abcdef"[(b[i].toInt() ushr 4) and 0xF])
                append("0123456789abcdef"[b[i].toInt() and 0xF])
                if (i == 3 || i == 5 || i == 7 || i == 9) append('-')
            }
        }
        return h
    }

    // ---------- Derivación de claves ----------

    /** Argon2id de la frase E2EE. Tarda ~1-3 s en móvil (64 MiB): solo al configurar/desbloquear. */
    fun deriveRootKey(passphrase: CharArray, salt16: ByteArray): ByteArray {
        val pw = String(passphrase).toByteArray(StandardCharsets.UTF_8)
        return try {
            Argon2Kt().hash(
                mode = Argon2Mode.ARGON2_ID,
                password = pw,
                salt = salt16,
                tCostInIterations = C.ARGON_T,
                mCostInKibibyte = C.ARGON_M_KIB,
                parallelism = C.ARGON_P,
                hashLengthInBytes = C.KEY_LEN,
                version = Argon2Version.V13,
            ).rawHashAsByteArray()
        } finally {
            pw.fill(0)
        }
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int = C.KEY_LEN): ByteArray =
        Hkdf.computeHkdf(
            "HMACSHA256",
            ikm,
            salt,
            info.toByteArray(StandardCharsets.UTF_8),
            len,
        )

    /** Clave de conversación: el salt es el par ordenado de usernames (D33). */
    fun convKey(rootKey: ByteArray, unameA: String, unameB: String): ByteArray {
        val (lo, hi) = sortPair(unameA, unameB)
        return hkdf(rootKey, "$lo|$hi".toByteArray(StandardCharsets.UTF_8), "conv-v1")
    }

    fun msgKey(convKey: ByteArray, msgId: String): ByteArray =
        hkdf(convKey, msgId.lowercase().toByteArray(StandardCharsets.UTF_8), "msg-v1")

    /** AAD (D12/D34): liga el ciphertext a mensaje, emisor y conversación. */
    fun aad(msgId: String, senderUid: String, unameA: String, unameB: String): ByteArray {
        val (lo, hi) = sortPair(unameA, unameB)
        return "v1|$msgId|${senderUid.lowercase()}|$lo|$hi".toByteArray(StandardCharsets.UTF_8)
    }

    private fun sortPair(a: String, b: String): Pair<String, String> =
        if (a <= b) a to b else b to a

    // ---------- AEAD: ChaCha20-Poly1305 (Tink) ----------
    // Tink devuelve nonce(12)||ct||tag; el protocolo guarda nonce y ct por separado.

    fun seal(msgKey: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val full = ChaCha20Poly1305(msgKey).encrypt(plaintext, aad)
        val nonce = full.copyOfRange(0, C.NONCE_LEN)
        val ct = full.copyOfRange(C.NONCE_LEN, full.size)
        return nonce to ct
    }

    fun open(msgKey: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray? =
        try {
            ChaCha20Poly1305(msgKey).decrypt(nonce + ct, aad)
        } catch (_: Exception) {
            null // tag inválido o AAD distinto → mensaje no verificable
        }

    // ---------- Framing del plaintext (D10/D35): [flag 1B][payload] ----------
    // flag=0 crudo UTF-8 · flag=1 gzip (solo si el texto supera 512 B).
    // La compresión SIEMPRE antes del AEAD, jamás después.

    fun frame(text: String): ByteArray {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        return if (raw.size > C.GZIP_THRESHOLD) {
            byteArrayOf(1) + gzip(raw)
        } else {
            byteArrayOf(0) + raw
        }
    }

    fun unframe(wire: ByteArray): String? {
        if (wire.isEmpty()) return null
        return try {
            val payload = wire.copyOfRange(1, wire.size)
            val raw = when (wire[0].toInt()) {
                1 -> gunzip(payload)
                0 -> payload
                else -> return null
            }
            String(raw, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val def = Deflater(Deflater.BEST_SPEED, true) // gzip header
        def.setInput(data)
        def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) {
            val n = def.deflate(buf)
            out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val inf = Inflater(true)
        inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    // ---------- Verificación local de la frase (D36) ----------
    // check = HMAC-SHA256(HKDF(rootKey, info="e2ee-check"), constante).
    // Permite validar la frase SIN servidor y SIN depender del Keystore.

    fun checkValue(rootKey: ByteArray): ByteArray {
        val k = hkdf(rootKey, ByteArray(0), "e2ee-check")
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(k, "HmacSHA256"))
        }
        return mac.doFinal("alain-chat-check-v1".toByteArray(StandardCharsets.UTF_8))
    }

    fun constEq(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun newSalt16(): ByteArray = ByteArray(16).also(rnd::nextBytes)

    fun zero(b: ByteArray?) { b?.fill(0) }
}
