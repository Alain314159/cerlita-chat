package cu.alain.chat.ui.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.max

/**
 * Utilidades de adjuntos (v0.7.0, D76): procesado de imágenes, mime/ext y
 * tamaños legibles. Todo en el teléfono: lo que sale de aquí ya son bytes
 * listos para cifrar.
 */
object Attach {

    const val IMG_MAX_SIDE = 1600
    const val IMG_JPEG_QUALITY = 85

    /** Decodifica una imagen del picker, reescala a ≤1600px, endereza el EXIF
     *  y re-codifica en JPEG (sin EXIF: fuera la geolocalización). */
    fun processImage(ctx: Context, uri: Uri): Triple<ByteArray, Int, Int>? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMG_MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            // enderezar según EXIF (la foto de la cámara suele venir girada)
            val rotation = exifRotation(ctx, uri)
            val rotated = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            } else bmp
            // recorte a ≤1600 por lado (el inSampleSize solo divide por potencias de 2)
            val scaled = if (max(rotated.width, rotated.height) > IMG_MAX_SIDE) {
                val scale = IMG_MAX_SIDE.toFloat() / max(rotated.width, rotated.height)
                Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
            } else rotated
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, IMG_JPEG_QUALITY, out)
            Triple(out.toByteArray(), scaled.width, scaled.height)
        } catch (_: Exception) {
            null
        }
    }

    private fun exifRotation(ctx: Context, uri: Uri): Int = try {
        val exif = androidx.exifinterface.media.ExifInterface(ctx.contentResolver.openInputStream(uri)!!)
        when (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
            3 -> 180; 6 -> 90; 8 -> 270; else -> 0
        }
    } catch (_: Exception) {
        0
    }

    /** Mime razonable por extensión (para el icono del chip de archivo). */
    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase().take(8)

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "%.1f MB".format(bytes / 1024f / 1024f)
    }

    fun formatDuration(ms: Long): String {
        val total = (ms / 1000).toInt()
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    /** Waveform → bytes 0..255 comprimidos (para el sobre E2EE). */
    fun waveformToB64(amps: List<Int>): String {
        val maxAmp = amps.maxOrNull()?.coerceAtLeast(1) ?: 1
        val bytes = ByteArray(amps.size)
        amps.forEachIndexed { i, a ->
            // escala logarítmica: la voz no usa todo el rango lineal
            val norm = if (a <= 0) 0f else log10(a.coerceAtLeast(1).toFloat() / maxAmp + 1f) / log10(2f)
            bytes[i] = (norm * 255f).toInt().coerceIn(0, 255).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun b64ToWaveform(b64: String): List<Int> = try {
        Base64.decode(b64, Base64.NO_WRAP).map { it.toInt() and 0xFF }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Grabador de notas de voz: AAC en M4A (universal en Android 24+), mono
 * 44,1 kHz a ~48 kbps (≈360 KB por minuto). La UI muestrea [amplitude]
 * cada ~100 ms para dibujar la waveform.
 */
class VoiceRecorder(private val ctx: Context) {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    val durationMs: Long get() = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt

    fun start(): Boolean {
        if (recorder != null) return false
        return try {
            val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
            val f = File(dir, "note-${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(48_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setMaxDuration(5 * 60 * 1000)
            r.setOutputFile(FileOutputStream(f).fd) // fd: robusto ante rutas raras
            r.prepare()
            r.start()
            recorder = r
            outFile = f
            startedAt = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            outFile?.delete()
            outFile = null
            startedAt = 0
            false
        }
    }

    /** Amplitud instantánea 0..32767 (para la waveform en vivo). */
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: Exception) {
        0
    }

    /** Termina y devuelve (archivo m4a, duración ms). */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val dur = durationMs
        return try {
            r.stop()
            Pair(outFile!!, dur)
        } catch (_: Exception) {
            null // grabación demasiado corta (<1 s) o error
        } finally {
            try { r.release() } catch (_: Exception) {}
            recorder = null
            startedAt = 0
        }
    }

    fun cancel() {
        val r = recorder ?: return
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        recorder = null
        startedAt = 0
        outFile?.delete()
        outFile = null
    }
}
