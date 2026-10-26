package cu.alain.chat.vault

import android.content.Context
import cu.alain.chat.crypto.KeyVault
import org.json.JSONObject
import java.io.File

/** Sesión activa (tras login/registro). El token viaja cifrado por Keystore en ss.bin. */
data class Session(
    val token: String,
    val uid: String,
    val uname: String,
    val did: String,
)

/** Material público de la frase E2EE guardado en kx.bin: salt de Argon2 + verificador. */
data class KxMaterial(val saltB64: String, val checkB64: String)

/**
 * Almacén local. Nada de esto es un secreto criptográfico salvo el token,
 * que está envuelto por el Keystore (KeyVault). La rootKey JAMÁS se persiste.
 */
class SessionStore(ctx: Context) {

    private val dir: File = ctx.filesDir

    private fun f(name: String) = File(dir, name)

    // ---------- sesión ----------

    val session: Session?
        get() = readSealed("ss.bin", "session-v1")?.let {
            try {
                val j = JSONObject(String(it, Charsets.UTF_8))
                Session(
                    token = j.getString("tok"),
                    uid = j.getString("uid"),
                    uname = j.getString("uname"),
                    did = j.getString("did"),
                )
            } catch (_: Exception) {
                null
            }
        }

    fun saveSession(s: Session) {
        val j = JSONObject()
            .put("tok", s.token)
            .put("uid", s.uid)
            .put("uname", s.uname)
            .put("did", s.did)
        writeSealed("ss.bin", "session-v1", j.toString().toByteArray(Charsets.UTF_8))
    }

    fun clearSession() { f("ss.bin").delete() }

    // ---------- material E2EE ----------

    val kx: KxMaterial?
        get() {
            val f = f("kx.bin")
            if (!f.exists()) return null
            return try {
                val j = JSONObject(f.readText(Charsets.UTF_8))
                KxMaterial(j.getString("salt"), j.getString("check"))
            } catch (_: Exception) {
                null
            }
        }

    fun saveKx(salt: ByteArray, check: ByteArray) {
        val j = JSONObject()
            .put("salt", b64(salt))
            .put("check", b64(check))
        f("kx.bin").writeText(j.toString(), Charsets.UTF_8)
    }

    fun clearKx() { f("kx.bin").delete() }

    // ---------- helpers ----------

    private fun readSealed(name: String, aad: String): ByteArray? {
        val f = f(name)
        if (!f.exists()) return null
        return KeyVault.unseal(f.readBytes(), aad)
    }

    private fun writeSealed(name: String, aad: String, plain: ByteArray) {
        f(name).writeBytes(KeyVault.seal(plain, aad))
    }

    private fun b64(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
}
