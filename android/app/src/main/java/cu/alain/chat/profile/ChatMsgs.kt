package cu.alain.chat.profile

import org.json.JSONObject

/**
 * Mensajes de control del CHAT (S2/D110): responder/citar, editar, borrar y
 * reaccionar — el mismo patrón E2EE de siempre (D61/D68/D69): viajan CIFRADOS
 * como mensajes kind≠0 invisibles, con prefijo \u0000 que un texto normal
 * nunca puede producir (el compositor no acepta NUL).
 *
 * Prefijos en uso: P perfil · S sala · L leído · A adjunto (preview) —
 * estos añaden: Q citar · E editar · D borrar · R reaccionar.
 *
 * Todos tolerantes al parseo (null = se ignora, jamás rompe el sync) y con
 * límites duros de tamaño (el ct del servidor admite 8192 B).
 */
object ChatMsgs {

    /** ── Responder/citar: el cuerpo del mensaje con la referencia ──
     *  plaintext = "\u0000Q" + JSON{"i": msgIdCitado, "t": texto} */
    object Reply {
        const val PREFIX = "\u0000Q"

        fun encode(replyTo: String, text: String): String =
            PREFIX + JSONObject().put("i", replyTo.take(64)).put("t", text).toString()

        fun isReply(plain: String): Boolean = plain.startsWith(PREFIX)

        fun parse(plain: String): Pair<String, String>? = try {
            if (!isReply(plain)) return null
            val j = JSONObject(plain.substring(PREFIX.length))
            val id = j.optString("i", "")
            val t = j.optString("t", "")
            if (id.isEmpty() || t.isEmpty()) null else id to t
        } catch (_: Exception) {
            null
        }
    }

    /** ── Editar: {i: msgId, t: texto nuevo} — solo el AUTOR edita ── */
    object Edit {
        const val PREFIX = "\u0000E"
        const val MAX_TEXT = 7500

        fun encode(msgId: String, text: String): String =
            PREFIX + JSONObject().put("i", msgId.take(64)).put("t", text.take(MAX_TEXT)).toString()

        fun isEdit(plain: String): Boolean = plain.startsWith(PREFIX)

        fun parse(plain: String): Pair<String, String>? = try {
            if (!isEdit(plain)) return null
            val j = JSONObject(plain.substring(PREFIX.length))
            val id = j.optString("i", "")
            val t = j.optString("t", "")
            if (id.isEmpty() || t.isEmpty()) null else id to t
        } catch (_: Exception) {
            null
        }
    }

    /** ── Borrar (para ambos): {i: msgId} — tumba el mensaje ── */
    object Delete {
        const val PREFIX = "\u0000D"

        fun encode(msgId: String): String =
            PREFIX + JSONObject().put("i", msgId.take(64)).toString()

        fun isDelete(plain: String): Boolean = plain.startsWith(PREFIX)

        fun parse(plain: String): String? = try {
            if (!isDelete(plain)) return null
            val id = JSONObject(plain.substring(PREFIX.length)).optString("i", "")
            id.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** ── Reaccionar: {i: msgId, e: emoji, x: 1 añade · 0 quita} ──
     *  Una reacción por usuario y mensaje (como WhatsApp): la nueva pisa. */
    object Reaction {
        const val PREFIX = "\u0000R"
        const val MAX_EMOJI = 16

        fun encode(msgId: String, emoji: String, on: Boolean): String =
            PREFIX + JSONObject()
                .put("i", msgId.take(64))
                .put("e", emoji.take(MAX_EMOJI))
                .put("x", if (on) 1 else 0)
                .toString()

        fun isReaction(plain: String): Boolean = plain.startsWith(PREFIX)

        data class Parsed(val msgId: String, val emoji: String, val on: Boolean)

        fun parse(plain: String): Parsed? = try {
            if (!isReaction(plain)) return null
            val j = JSONObject(plain.substring(PREFIX.length))
            val id = j.optString("i", "")
            val e = j.optString("e", "")
            if (id.isEmpty() || e.isEmpty()) null
            else Parsed(id, e, j.optInt("x", 1) == 1)
        } catch (_: Exception) {
            null
        }
    }
}
