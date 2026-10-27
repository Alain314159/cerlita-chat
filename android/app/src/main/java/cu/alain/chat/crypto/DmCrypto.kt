package cu.alain.chat.crypto

import android.util.Base64
import com.google.crypto.tink.subtle.X25519
import java.nio.charset.StandardCharsets

/**
 * Envoltura de roomKeys para CHATS DIRECTOS (v0.7.0, D74) — sin código de
 * invitación y SIN que el servidor vea la clave:
 *
 *   1. Cada teléfono tiene un par X25519 (privada sellada por Keystore en
 *      [cu.alain.chat.vault.SessionStore.ensureDeviceKeyPair]; la pública
 *      se sube al servidor).
 *   2. El CREADOR del chat directo genera roomKey/roomSalt (como una sala
 *      normal) y ENVUELVE el par para el otro:
 *        shared = X25519(miPrivada, públicaDelPar)
 *        wrapKey = HKDF-SHA256(shared, salt=roomId, info="dm-wrap-v1")
 *        sealed  = ChaCha20-Poly1305(wrapKey, "V1"‖roomSalt‖roomKey,
 *                                    aad="dm-v1|roomId|uidDelReceptor")
 *   3. El servidor guarda el blob opaco (dm_keys); el receptor lo recoge
 *      por /sync junto con la pública del creador y lo desenvelve:
 *        shared = X25519(miPrivada, públicaDelCreador)   [ECDH simétrico]
 *   4. Verificación: roomId = HKDF(roomKey, roomSalt) debe COINCIDIR con el
 *      roomId que dijo el servidor — un wrap corrupto/manipulado se descarta.
 *
 * El servidor jamás ve shared, wrapKey ni roomKey. Si la keypair del
 * receptor cambia (teléfono nuevo), el wrap muere y la recuperación es el
 * código de invitación (que el par puede compartir desde Ajustes de sala).
 */
object DmCrypto {

    private val MAGIC = byteArrayOf(0x56, 0x31) // "V1"
    private const val PAYLOAD_LEN = 2 + 16 + 32   // magic + salt + key

    /** Par de claves del teléfono (la privada jamás sale del vault sellado). */
    data class KeyPair(val priv: ByteArray, val pub: ByteArray) {
        fun privB64(): String = Base64.encodeToString(priv, Base64.NO_WRAP)
        fun pubB64(): String = Base64.encodeToString(pub, Base64.NO_WRAP)

        companion object {
            fun fromB64(privB64: String, pubB64: String): KeyPair = KeyPair(
                Base64.decode(privB64, Base64.NO_WRAP),
                Base64.decode(pubB64, Base64.NO_WRAP),
            )
        }
    }

    fun newKeyPair(): KeyPair {
        val priv = X25519.generatePrivateKey()
        return KeyPair(priv, X25519.publicFromPrivate(priv))
    }

    /** Envuelve roomKey+roomSalt para el par (del lado del CREADOR). */
    fun wrap(
        myPriv: ByteArray,
        peerPub: ByteArray,
        roomId: String,
        forUserUid: String,
        roomKey: ByteArray,
        roomSalt: ByteArray,
    ): ByteArray {
        require(roomKey.size == 32 && roomSalt.size == 16) { "tamaños de clave/salt inválidos" }
        val wrapKey = wrapKey(myPriv, peerPub, roomId)
        val aad = aad(roomId, forUserUid)
        val (nonce, ct) = Crypto.seal(wrapKey, MAGIC + roomSalt + roomKey, aad)
        return nonce + ct
    }

    /**
     * Desenvelve (del lado del RECEPTOR). null si la clave privada no
     * corresponde (keypair rotada), el blob está corrupto o el roomId
     * derivado no coincide con el anunciado.
     */
    fun unwrap(
        myPriv: ByteArray,
        fromPub: ByteArray,
        roomId: String,
        myUid: String,
        wrapped: ByteArray,
    ): Pair<ByteArray, ByteArray>? { // (roomKey, roomSalt)
        if (wrapped.size < 12 + PAYLOAD_LEN + 16) return null
        return try {
            val nonce = wrapped.copyOfRange(0, 12)
            val ct = wrapped.copyOfRange(12, wrapped.size)
            val wrapKey = wrapKey(myPriv, fromPub, roomId)
            val plain = Crypto.open(wrapKey, nonce, ct, aad(roomId, myUid)) ?: return null
            if (plain.size != PAYLOAD_LEN || plain[0] != MAGIC[0] || plain[1] != MAGIC[1]) return null
            val roomSalt = plain.copyOfRange(2, 18)
            val roomKey = plain.copyOfRange(18, 50)
            // verificación de integridad extremo a extremo: el roomId que
            // deriva de (roomKey, roomSalt) DEBE ser el anunciado por el servidor
            if (!Crypto.roomId(roomKey, roomSalt).equals(roomId, ignoreCase = true)) return null
            roomKey to roomSalt
        } catch (_: Exception) {
            null
        }
    }

    private fun wrapKey(myPriv: ByteArray, peerPub: ByteArray, roomId: String): ByteArray {
        val shared = X25519.computeSharedSecret(myPriv, peerPub)
        return Crypto.hkdf(shared, roomId.lowercase().toByteArray(StandardCharsets.UTF_8), "dm-wrap-v1")
    }

    private fun aad(roomId: String, forUserUid: String): ByteArray =
        "dm-v1|${roomId.lowercase()}|${forUserUid.lowercase()}".toByteArray(StandardCharsets.UTF_8)
}
