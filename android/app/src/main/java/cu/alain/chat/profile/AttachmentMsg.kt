package cu.alain.chat.profile

import org.json.JSONObject

/**
 * Mensajes con ADJUNTOS (v0.7.0, D76) — imágenes, notas de voz y archivos.
 *
 * Mismo truco de [ProfileMsg] (D61): el plaintext lleva un prefijo \u0000 que
 * el compositor de texto jamás produce. El mensaje viaja cifrado como
 * cualquier otro (kind=0, burbuja VISIBLE); el JSON detrás del prefijo
 * describe el adjunto:
 *
 *   plaintext = "\u0000A" + JSON{
 *     t: "img"|"aud"|"doc",     tipo de burbuja
 *     n: nombre visible (máx 80),
 *     m: mime real,
 *     s: tamaño legible en bytes (aprox, del original),
 *     p: path en Storage "<room>/<uuid>.<ext>",
 *     k: fileKey b64 (32 B, aleatoria POR ARCHIVO),
 *     d: duración ms (solo aud),
 *     w: waveform b64 (bytes 0..255, solo aud),
 *     h: alto px, i: ancho px (solo img, para el layout)
 *   }
 *
 * El ARCHIVO viaja cifrado aparte (ChaCha20-Poly1305 con la fileKey, nonce
 * al principio del blob) directo a Storage; el servidor ve bytes opacos.
 * Sin el mensaje (que va E2EE con la roomKey) el archivo es irrecuperable.
 */
object AttachmentMsg {
    const val PREFIX = "\u0000A"

    const val TYPE_IMAGE = "img"
    const val TYPE_AUDIO = "aud"
    const val TYPE_DOC = "doc"

    const val MAX_NAME = 80
    const val MAX_MIME = 60
    const val MAX_PATH = 100
    const val MAX_WAVEFORM_B64 = 2400 // ~1800 muestras ≈ 3 min a 10 Hz

    fun encode(
        type: String,
        name: String,
        mime: String,
        size: Long,
        path: String,
        keyB64: String,
        durMs: Long = 0,
        height: Int = 0,
        width: Int = 0,
        waveformB64: String? = null,
    ): String {
        val j = JSONObject()
            .put("t", type)
            .put("n", name.take(MAX_NAME))
            .put("m", mime.take(MAX_MIME))
            .put("s", size)
            .put("p", path)
            .put("k", keyB64)
        if (durMs > 0) j.put("d", durMs)
        if (height > 0) j.put("h", height)
        if (width > 0) j.put("i", width)
        if (!waveformB64.isNullOrBlank() && waveformB64.length <= MAX_WAVEFORM_B64) {
            j.put("w", waveformB64)
        }
        return PREFIX + j.toString()
    }

    fun isAttachment(plain: String): Boolean = plain.startsWith(PREFIX)

    /** null si no es un adjunto válido (se muestra como texto). */
    fun parse(plain: String): Parsed? {
        if (!isAttachment(plain)) return null
        return try {
            val j = JSONObject(plain.substring(PREFIX.length))
            val type = j.optString("t", "")
            if (type !in listOf(TYPE_IMAGE, TYPE_AUDIO, TYPE_DOC)) return null
            val name = j.optString("n", "").trim()
            val path = j.optString("p", "")
            val key = j.optString("k", "")
            if (name.isEmpty() || path.isEmpty() || key.isEmpty()) return null
            Parsed(
                type = type,
                name = name.take(MAX_NAME),
                mime = j.optString("m", "application/octet-stream").take(MAX_MIME),
                size = j.optLong("s", 0),
                path = path.take(MAX_PATH),
                keyB64 = key,
                durMs = j.optLong("d", 0),
                height = j.optInt("h", 0),
                width = j.optInt("i", 0),
                waveformB64 = j.optString("w", "").takeIf { it.isNotEmpty() && it.length <= MAX_WAVEFORM_B64 },
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Texto de vista previa para notificaciones y lista de chats. */
    fun preview(plain: String): String? {
        val p = parse(plain) ?: return null
        return when (p.type) {
            TYPE_AUDIO -> "Nota de voz"
            TYPE_IMAGE -> "Foto"
            else -> "Archivo: ${p.name}"
        }
    }

    data class Parsed(
        val type: String,
        val name: String,
        val mime: String,
        val size: Long,
        val path: String,
        val keyB64: String,
        val durMs: Long,
        val height: Int,
        val width: Int,
        val waveformB64: String?,
    )
}
