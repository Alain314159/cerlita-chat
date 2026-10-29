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
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de extremo a extremo — modelo ROOMS (D41-D43, D54 — docs/DECISIONES.md).
 *
 * 1. El CREADOR genera: roomKey = CSPRNG(32 B), roomSalt = CSPRNG(16 B).
 * 2. roomId = UUID( HKDF-SHA256(roomKey, salt=roomSalt, info="room-id")[0..16] )
 *    → el servidor SOLO conoce roomId (capability de membresía, 2^128).
 * 3. inviteCode = base32("V1" ‖ roomSalt ‖ roomKey ‖ CRC32) [D42]
 *    → El código ES el secreto: la roomKey viaja EN CLARO dentro, sin cifrado
 *      adicional. NUNCA al servidor; se comparte por canal controlado.
 * 4. SAS = 6 dígitos de HKDF(roomKey, salt=roomSalt, info="sas") [D43]
 *    → Se compara SOLO entre los dos clientes (voz / en persona).
 * 5. convKey = HKDF-SHA256(roomKey, salt=roomId, info="conv-v1")
 * 6. msgKey  = HKDF-SHA256(convKey, salt=msg_id, info="msg-v1")
 *    AAD = "v1|msgId|senderId|roomId" · AEAD = ChaCha20-Poly1305 (Tink).
 *
 * El servidor NUNCA ve roomKey, convKey ni msgKey: solo nonce + ciphertext.
 * El export (D47) usa Argon2id con contraseña INDEPENDIENTE.
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
        return hexUuid(b)
    }

    private fun hexUuid(b: ByteArray): String = buildString(36) {
        for (i in b.indices) {
            append("0123456789abcdef"[(b[i].toInt() ushr 4) and 0xF])
            append("0123456789abcdef"[b[i].toInt() and 0xF])
            if (i == 3 || i == 5 || i == 7 || i == 9) append('-')
        }
    }

    // ---------- material de sala ----------

    fun newRoomKey(): ByteArray = ByteArray(32).also(rnd::nextBytes)

    fun newSalt16(): ByteArray = ByteArray(16).also(rnd::nextBytes)

    /** roomId (D41): HKDF(roomKey, salt=roomSalt, info="room-id")[0..16] como UUID. */
    fun roomId(roomKey: ByteArray, roomSalt: ByteArray): String =
        hexUuid(hkdf(roomKey, roomSalt, "room-id", 16))

    /** SAS de 6 dígitos (D43): primeros 4 B de HKDF(...info="sas") como UInt BE mod 10^6. */
    fun sas6(roomKey: ByteArray, roomSalt: ByteArray): String {
        val d = hkdf(roomKey, roomSalt, "sas", 4)
        // Long sin signo: con Int con signo, byte alto >= 0x80 daría resto NEGATIVO
        val v = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return "%06d".format(v % 1_000_000L)
    }

    // ---------- código de invitación (D42) ----------
    // payload = "V1"(2B ASCII) ‖ roomSalt(16B) ‖ roomKey(32B) ‖ CRC32(payload)(4B BE)
    // código  = base32 RFC4648 sin padding, MAYÚSCULAS, grupos de 5 con "-".
    // 54 B → 87 caracteres: largo a propósito de teclear; el camino natural es
    // copiar/compartir (WhatsApp, en persona). El CRC detecta errores de tecleo.

    private const val B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val B32_REV = IntArray(128) { -1 }.also {
        for (i in B32_ALPHABET.indices) it[B32_ALPHABET[i].code] = i
    }
    private val INVITE_MAGIC = byteArrayOf(0x56, 0x31) // "V1"
    private const val INVITE_LEN = 2 + 16 + 32 + 4 // 54

    fun encodeInvite(roomSalt: ByteArray, roomKey: ByteArray): String {
        require(roomSalt.size == 16 && roomKey.size == 32)
        val body = INVITE_MAGIC + roomSalt + roomKey
        val crc = CRC32().apply { update(body) }
        val payload = body + byteArrayOf(
            ((crc.value ushr 24) and 0xFF).toByte(),
            ((crc.value ushr 16) and 0xFF).toByte(),
            ((crc.value ushr 8) and 0xFF).toByte(),
            (crc.value and 0xFF).toByte(),
        )
        val b32 = base32Encode(payload)
        return buildString(b32.length + b32.length / 5) {
            for (i in b32.indices) {
                append(b32[i])
                if ((i + 1) % 5 == 0 && i + 1 < b32.length) append('-')
            }
        }
    }

    /** null si el código está corrupto (CRC), trunco o con magic incorrecto. */
    fun decodeInvite(code: String): Pair<ByteArray, ByteArray>? {
        val clean = buildString(code.length) {
            for (ch in code.uppercase()) if (ch.code < 128 && B32_REV[ch.code] >= 0) append(ch)
        }
        val payload = base32Decode(clean) ?: return null
        if (payload.size != INVITE_LEN) return null
        if (payload[0] != INVITE_MAGIC[0] || payload[1] != INVITE_MAGIC[1]) return null
        val body = payload.copyOfRange(0, INVITE_LEN - 4)
        val crc = CRC32().apply { update(body) }
        val expect = byteArrayOf(
            ((crc.value ushr 24) and 0xFF).toByte(),
            ((crc.value ushr 16) and 0xFF).toByte(),
            ((crc.value ushr 8) and 0xFF).toByte(),
            (crc.value and 0xFF).toByte(),
        )
        val got = payload.copyOfRange(INVITE_LEN - 4, INVITE_LEN)
        if (!MessageDigest.isEqual(expect, got)) return null
        val salt = payload.copyOfRange(2, 18)
        val key = payload.copyOfRange(18, 50)
        return key to salt
    }

    // ---------- base32 (RFC4648, sin padding) ----------

    fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buf = 0        // acumulador de bits
        var bits = 0
        for (byte in data) {
            buf = (buf shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32_ALPHABET[(buf ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32_ALPHABET[(buf shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    fun base32Decode(s: String): ByteArray? {
        val out = ByteArrayOutputStream(s.length * 5 / 8 + 1)
        var buf = 0
        var bits = 0
        for (ch in s) {
            if (ch.code >= 128) return null
            val v = B32_REV[ch.code]
            if (v < 0) return null
            buf = (buf shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buf ushr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    // ---------- derivación de claves ----------

    /** Argon2id SOLO para la contraseña del export (D47) — independiente de todo. */
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

    /** Clave de conversación: la madre es roomKey y el salt es el roomId (D41). */
    fun convKey(roomKey: ByteArray, roomId: String): ByteArray =
        hkdf(roomKey, roomId.lowercase().toByteArray(StandardCharsets.UTF_8), "conv-v1")

    fun msgKey(convKey: ByteArray, msgId: String): ByteArray =
        hkdf(convKey, msgId.lowercase().toByteArray(StandardCharsets.UTF_8), "msg-v1")

    /**
     * Clave de la SEÑALIZACIÓN de llamadas (B6 auditoría): misma madre que los
     * mensajes (convKey de la sala) pero salt = callId, así cada llamada tiene
     * clave propia y el SDP/ICE/nombre viaja cifrado por el broadcast — el
     * servidor deja de ver IPs y nombres (promesa E2EE de D61 también aquí).
     */
    fun sigKey(roomKey: ByteArray, roomId: String, callId: String): ByteArray =
        hkdf(convKey(roomKey, roomId), callId.lowercase().toByteArray(StandardCharsets.UTF_8), "sig-v1")

    /**
     * Tema ntfy de una conversación (D104): mismo patrón que convKey/sigKey —
     * HKDF(roomKey, salt=roomId, "ntfy-topic-v1") a 16 bytes → base32 minúsculas
     * con prefijo "c": 27 chars ≈ 128 bits de entropía, no enumerable.
     *
     * El servidor NUNCA lo conoce: se deriva en el MÓVIL — el emisor lo usa para
     * publicar el ping DESPUÉS del ack del outbox; el receptor para suscribirse
     * al poll. 1 tema por conversación (sala o DM, misma madre roomKey): todos
     * los miembros escuchan el mismo y cada quien descarta su eco por msgId.
     * Rota solo al rotar la roomKey (D46).
     */
    fun ntfyTopic(roomKey: ByteArray, roomId: String): String {
        val k = hkdf(roomKey, roomId.lowercase().toByteArray(StandardCharsets.UTF_8), "ntfy-topic-v1", 16)
        return "c" + base32Encode(k).lowercase()
    }

    /** AAD (D41): liga el ciphertext a mensaje, emisor y sala. */
    fun aad(msgId: String, senderId: String, roomId: String): ByteArray =
        "v1|$msgId|${senderId.lowercase()}|${roomId.lowercase()}"
            .toByteArray(StandardCharsets.UTF_8)

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

    fun constEq(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun zero(b: ByteArray?) { b?.fill(0) }
}
