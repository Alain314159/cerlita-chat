package cu.alain.chat.ui.attachments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guardar archivos recibidos (D80): un TOQUE, sin diálogos del sistema.
 *
 *  - API 29+ (scoped storage): MediaStore — Descargas/Cerlita y
 *    Imágenes/Cerlita. Sin ningún permiso.
 *  - API <29: devuelve false y la UI cae al selector SAF (CreateDocument),
 *    que tampoco necesita permisos pero sí que el usuario elija dónde.
 */
object AttachSaver {

    /** Nombre de archivo saneado (sin caracteres ilegales del sistema). */
    private fun cleanName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "archivo" }

    /** API 29+: guarda un archivo en Descargas/Cerlita. false = usar SAF. */
    @SuppressLint("InlinedApi")
    fun saveToDownloads(ctx: Context, name: String, mime: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, cleanName(name))
                put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Cerlita")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** API 29+: guarda una imagen en Imágenes/Cerlita (galería). false = SAF. */
    @SuppressLint("InlinedApi")
    fun saveImageToGallery(ctx: Context, bmp: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val name = "cerlita_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cerlita")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }
}
