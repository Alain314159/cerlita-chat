package cu.alain.chat.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export CIFRADO del historial (decisión P4 — mitigación del riesgo de pérdida).
 *
 * Formato de archivo (.e2ee):
 *   [0..8)   MAGIC "E2EEEXP1"
 *   [8..24)  salt Argon2id (16 B) — generado nuevo en cada export
 *   [24..36) nonce ChaCha20 (12 B)
 *   [36..)   ciphertext+tag
 *
 * Clave = HKDF(Argon2id(frase de export, salt), info="export-v1").
 * El plaintext interno usa el mismo framing de mensajes: [flag][json|gzip(json)].
 * La frase de export la elige el usuario al exportar: puede ser la misma E2EE
 * u otra distinta. Importar estará disponible en una versión futura (formato versionado).
 */
object HistoryExport {

    private val MAGIC = "E2EEEXP1".toByteArray(Charsets.US_ASCII)

    suspend fun export(ctx: Context, db: AppDatabase, myUname: String, passphrase: CharArray): Uri? =
        withContext(Dispatchers.IO) {
            val msgs = db.msgDao().all()
            if (msgs.isEmpty()) return@withContext null

            val arr = JSONArray()
            msgs.forEach { m ->
                arr.put(
                    JSONArray()
                        .put(m.msgId).put(m.peerUname).put(m.senderUname)
                        .put(if (m.mine) 1 else 0).put(m.body).put(m.ts).put(m.state)
                )
            }
            val json = JSONObject()
                .put("v", 1)
                .put("exported_at", System.currentTimeMillis())
                .put("me", myUname)
                .put("messages", arr)
                .toString()

            val wire = Crypto.frame(json)
            val salt = Crypto.newSalt16()
            val root = Crypto.deriveRootKey(passphrase, salt)
            val key = Crypto.hkdf(root, salt, "export-v1")
            Crypto.zero(root)
            val (nonce, ct) = Crypto.seal(key, wire, "alain-chat-export-v1".toByteArray(Charsets.UTF_8))
            Crypto.zero(key)

            val blob = MAGIC + salt + nonce + ct
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "chat-e2ee-$stamp.e2ee"

            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
                resolver.openOutputStream(uri)?.use { it.write(blob) }
                    ?: return@withContext null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                // API 24-28: carpeta privada + compartir (sin permiso de escritura pública)
                val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
                val f = File(dir, name)
                f.writeBytes(blob)
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            }
        }

    /** Intent de compartir para que el usuario mueva el archivo por donde quiera. */
    fun shareIntent(ctx: Context, uri: Uri): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Compartir export cifrado",
        )
}
