package cu.alain.chat.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Fotos de perfil (D61): cuadradas, pequeñas y con presupuesto garantizado.
 *
 * El anuncio viaja cifrado en un mensaje normal → el ciphertext del servidor
 * admite 8192 B. Con margen para nombre+avatar: la foto se degrada hasta que
 * quepa (JPEG cuadrado 128→96→64 px). En disco se guarda la versión elegida
 * (no la original gigante): los avatares se ven a ≤48 dp en la UI.
 */
object Photos {

    private const val TARGET_BYTES = 5_000        // presupuesto JPEG antes de b64 (+33%)
    private val SIZES = intArrayOf(128, 96, 64)

    /** Lee la Uri elegida (respeta el EXIF de rotación), recorta al centro y
     *  devuelve JPEG ≤ ~5 KB. null si la Uri no es una imagen legible. */
    fun loadSquareJpeg(ctx: Context, uri: Uri, maxSrcPx: Int = 1024): ByteArray? {
        // copiar a temp: el EXIF (framework) solo lee archivos, no streams
        val tmp = File.createTempFile("pick", ".jpg", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { input.copyTo(it) }
            } ?: return null

            val rotation = try {
                when (ExifInterface(tmp.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) {
                0f
            }

            val bmp = decodeDownsampled(tmp, maxSrcPx) ?: return null
            val upright = if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                r
            } else bmp
            val square = centerCrop(upright)
            if (square !== upright) upright.recycle()
            try {
                for (px in SIZES) {
                    val scaled = if (square.width == px) square
                    else Bitmap.createScaledBitmap(square, px, px, true)
                    val bytes = jpeg(scaled, 70)
                    if (scaled !== square) scaled.recycle()
                    if (bytes != null && bytes.size <= TARGET_BYTES) return bytes
                }
                // incluso a 64 px no cabe (foto con muchísimo ruido): última llamada
                return jpeg(Bitmap.createScaledBitmap(square, 64, 64, true), 55)
            } finally {
                square.recycle()
            }
        } finally {
            tmp.delete()
        }
    }

    /** Guarda los bytes de la foto de un uid. bytes=null la borra. */
    fun saveProfilePhoto(avatarsDir: File, uid: String, bytes: ByteArray?) {
        val f = photoFile(avatarsDir, uid)
        if (bytes == null) { f.delete(); return }
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(bytes) }
    }

    fun bytesToB64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun b64ToBytes(b64: String): ByteArray? = try {
        Base64.decode(b64, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    fun photoFile(avatarsDir: File, uid: String): File = File(avatarsDir, "$uid.jpg")

    // ---------- internos ----------

    private fun decodeDownsampled(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun centerCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (src.width == side && src.height == side) return src
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createBitmap(src, x, y, side, side)
    }

    private fun jpeg(bmp: Bitmap, quality: Int): ByteArray? = try {
        val out = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (ok) out.toByteArray() else null
    } catch (_: Exception) {
        null
    }
}
