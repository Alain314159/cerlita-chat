package cu.alain.chat.profile

import org.json.JSONObject

/**
 * Mensajes de control de la v0.6.0 — mismo truco de [ProfileMsg] (D61): el
 * plaintext lleva un prefijo con \u0000 que el compositor de texto jamás
 * produce, así el receptor los distingue de un mensaje normal SIN que el
 * servidor conozca el tipo (viaja cifrado como cualquier mensaje).
 *
 *   RoomInfo  "\u0000S" + JSON{"n": nombre, "p": fotoB64?, "t": tsMs}   (D69)
 *   Read      "\u0000L" + JSON{"u": seqMáximoLeído, "t": tsMs}         (D68)
 *
 * RoomInfo: nombre y foto COMPARTIDOS de la sala (latest-ts-wins). El que
 * entra sin poner nombre hereda el de la sala por el historial.
 *
 * Read: recibo de lectura — "he visto todo lo de esta sala con seq ≤ u".
 * El emisor marca sus mensajes con seq ≤ u como ✓✓ azul.
 */
object RoomInfoMsg {
    const val PREFIX = "\u0000S"

    const val MAX_NAME = 40
    const val MAX_PHOTO_B64 = 6600   // mismo presupuesto que las fotos de perfil

    fun encode(name: String, photoB64: String?, ts: Long): String {
        val j = JSONObject()
            .put("n", name.take(MAX_NAME))
            .put("t", ts)
        if (!photoB64.isNullOrBlank()) j.put("p", photoB64)
        return PREFIX + j.toString()
    }

    fun isRoomInfo(plain: String): Boolean = plain.startsWith(PREFIX)

    /** null si no es un anuncio válido (se muestra como texto). */
    fun parse(plain: String): Parsed? {
        if (!isRoomInfo(plain)) return null
        return try {
            val j = JSONObject(plain.substring(PREFIX.length))
            val name = j.optString("n", "").trim()
            if (name.isEmpty() || name.length > MAX_NAME) return null
            Parsed(
                name = name,
                photoB64 = j.optString("p", "").takeIf { it.length in 1..MAX_PHOTO_B64 },
                ts = j.optLong("t", 1L),
            )
        } catch (_: Exception) {
            null
        }
    }

    data class Parsed(
        val name: String,
        val photoB64: String?,   // null = sin foto (la quita si había)
        val ts: Long,
    )
}

object ReadReceipt {
    const val PREFIX = "\u0000L"

    fun encode(upToSeq: Long, ts: Long): String =
        PREFIX + JSONObject().put("u", upToSeq).put("t", ts).toString()

    fun isRead(plain: String): Boolean = plain.startsWith(PREFIX)

    /** null si no es un recibo válido. */
    fun parse(plain: String): Parsed? {
        if (!isRead(plain)) return null
        return try {
            val j = JSONObject(plain.substring(PREFIX.length))
            if (!j.has("u")) return null
            Parsed(upToSeq = j.getLong("u"), ts = j.optLong("t", 1L))
        } catch (_: Exception) {
            null
        }
    }

    data class Parsed(val upToSeq: Long, val ts: Long)
}
