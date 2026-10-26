package cu.alain.chat.vault

import android.content.Context
import android.util.Base64
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

/** roomKey + roomSalt de una sala, leídos del código de invitación. */
data class RoomKeyMaterial(val roomId: String, val key: ByteArray, val salt: ByteArray, val sas: String)

/**
 * Almacén local. El token de sesión y las roomKeys van CIFRADOS en reposo por
 * la clave AES-256-GCM respaldada por hardware del AndroidKeyStore (P3).
 * El blob no se puede descifrar fuera de este teléfono (ni con el archivo
 * copiado): la clave nunca sale del Keystore.
 *
 * El resto (nombres de sala, mensajes) no son secretos criptográficos: los
 * protege el cifrado de disco de Android + el bloqueo de pantalla.
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

    // ---------- vault de roomKeys (D54) ----------

    /** Todas las claves: roomId → (key b64, salt b64). Descifra rooms.bin una vez. */
    fun roomKeys(): Map<String, Pair<String, String>> {
        val plain = readSealed("rooms.bin", "rooms-v1") ?: return emptyMap()
        return try {
            val j = JSONObject(String(plain, Charsets.UTF_8))
            val out = mutableMapOf<String, Pair<String, String>>()
            val rooms = j.getJSONObject("rooms")
            val it = rooms.keys()
            while (it.hasNext()) {
                val id = it.next() as String
                val r = rooms.getJSONObject(id)
                out[id] = r.getString("k") to r.getString("s")
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun hasRoomKey(roomId: String): Boolean = roomKeys().containsKey(roomId.lowercase())

    /** Guarda (o sustituye) la clave de una sala. Reescribe el blob sellado completo. */
    fun putRoomKey(roomId: String, key: ByteArray, salt: ByteArray) {
        val id = roomId.lowercase()
        val cur = roomKeys().toMutableMap()
        cur[id] = b64(key) to b64(salt)
        val rooms = JSONObject()
        cur.forEach { (rid, ks) -> rooms.put(rid, JSONObject().put("k", ks.first).put("s", ks.second)) }
        val j = JSONObject().put("v", 1).put("rooms", rooms)
        writeSealed("rooms.bin", "rooms-v1", j.toString().toByteArray(Charsets.UTF_8))
    }

    fun removeRoomKey(roomId: String) {
        val cur = roomKeys().toMutableMap()
        if (cur.remove(roomId.lowercase()) != null) {
            val rooms = JSONObject()
            cur.forEach { (rid, ks) -> rooms.put(rid, JSONObject().put("k", ks.first).put("s", ks.second)) }
            val j = JSONObject().put("v", 1).put("rooms", rooms)
            writeSealed("rooms.bin", "rooms-v1", j.toString().toByteArray(Charsets.UTF_8))
        }
    }

    fun clearRoomKeys() { f("rooms.bin").delete() }

    // ---------- helpers ----------

    private fun readSealed(name: String, aad: String): ByteArray? {
        val f = f(name)
        if (!f.exists()) return null
        return KeyVault.unseal(f.readBytes(), aad)
    }

    private fun writeSealed(name: String, aad: String, plain: ByteArray) {
        f(name).writeBytes(KeyVault.seal(plain, aad))
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
}
