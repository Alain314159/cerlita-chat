package cu.alain.chat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Envoltura Keystore (decisión P3): una clave AES-256-GCM respaldada por
 * hardware en AndroidKeyStore protege en reposo la sesión y otros blobs.
 * La clave NUNCA sale del Keystore; el blob va como iv(12)||ct.
 *
 * La rootKey de E2EE vive solo en RAM mientras la app está desbloqueada;
 * lo que se guarda en disco es únicamente {salt de Argon2 + check} (D36):
 * la frase SIEMPRE se re-deriva, no hay copia de la clave en ninguna parte.
 */
object KeyVault {

    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "chat_master_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun seal(plain: ByteArray, aad: String): ByteArray = sealFn(plain, aad)

    fun unseal(blob: ByteArray, aad: String): ByteArray? = unsealFn(blob, aad)

    // ── solo tests (patrón H5 de v0.3.0) ──
    // El provider AndroidKeyStore NO existe en Robolectric (primer test que
    // lo toca, D104): se inyecta una implementación transparente en @BeforeClass
    // y se RESTAURA en @AfterClass guardando la original. La producción jamás
    // escribe estas vars; el cifrado real es sealReal/unsealReal.
    @Volatile var sealFn: (ByteArray, String) -> ByteArray = ::sealReal
        internal set
    @Volatile var unsealFn: (ByteArray, String) -> ByteArray? = ::unsealReal
        internal set

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private fun sealReal(plain: ByteArray, aad: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return c.iv + c.doFinal(plain)
    }

    private fun unsealReal(blob: ByteArray, aad: String): ByteArray? = try {
        require(blob.size > IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    } catch (_: Exception) {
        null // tag inválido, AAD distinto o clave destruida al reinstalar
    }
}
