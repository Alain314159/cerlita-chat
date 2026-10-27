package cu.alain.chat.calls

import org.json.JSONObject

/**
 * Señalización de llamadas (v0.7.0, D75) — parse PURO (testeable en JVM).
 *
 * Viaja por broadcast REST al topic de la sala (hallazgos 10-11) y entra por
 * el timbre del par como evento "sig". El payload JAMÁS transporta secretos:
 * solo metadatos de conexión (SDP/ICE). El MEDIO (audio/vídeo) viaja cifrado
 * DTLS-SRTP directamente entre teléfonos (peer-to-peer).
 *
 *   {"t":"offer","c":callId,"r":roomId,"f":uid,"fn":nombre,"v":0|1,"s":{"t":"offer","s":sdp}}
 *   {"t":"ans",  "c":callId,"s":{"t":"answer","s":sdp}}
 *   {"t":"ice",  "c":callId,"f":uid,"m":sdpMid,"l":lineIdx,"a":candidate}
 *   {"t":"bye",  "c":callId,"x":"hangup|declined|busy|missed|cancelled"}
 */
object CallSignals {

    const val EV = "sig"
    const val TOPIC_PREFIX = "room-"

    sealed class Signal {
        abstract val callId: String
        abstract val from: String

        data class Offer(
            override val callId: String, override val from: String,
            val roomId: String, val fromName: String, val video: Boolean,
            val sdpType: String, val sdp: String,
        ) : Signal()

        data class Answer(
            override val callId: String, override val from: String,
            val sdpType: String, val sdp: String,
        ) : Signal()

        data class Ice(
            override val callId: String, override val from: String,
            val sdpMid: String, val lineIndex: Int, val candidate: String,
        ) : Signal()

        data class Bye(override val callId: String, override val from: String, val reason: String) : Signal()
    }

    fun topicOf(roomId: String) = TOPIC_PREFIX + roomId.lowercase()

    fun encodeOffer(callId: String, roomId: String, fromUid: String, fromName: String, video: Boolean, sdp: String): JSONObject =
        JSONObject()
            .put("t", "offer").put("c", callId).put("r", roomId.lowercase())
            .put("f", fromUid).put("fn", fromName).put("v", if (video) 1 else 0)
            .put("s", JSONObject().put("t", "offer").put("s", sdp))

    fun encodeAnswer(callId: String, fromUid: String, sdp: String): JSONObject =
        JSONObject()
            .put("t", "ans").put("c", callId).put("f", fromUid)
            .put("s", JSONObject().put("t", "answer").put("s", sdp))

    fun encodeIce(callId: String, fromUid: String, mid: String?, line: Int, candidate: String): JSONObject =
        JSONObject()
            .put("t", "ice").put("c", callId).put("f", fromUid)
            .put("m", mid ?: "").put("l", line).put("a", candidate)

    fun encodeBye(callId: String, fromUid: String, reason: String): JSONObject =
        JSONObject().put("t", "bye").put("c", callId).put("f", fromUid).put("x", reason)

    /** Parse del payload crudo que llegó por el timbre; null si no va a nosotros. */
    fun parse(payload: String, myUid: String): Signal? {
        return try {
            val j = JSONObject(payload)
            val callId = j.optString("c", "")
            val from = j.optString("f", "")
            if (callId.isEmpty() || from.isEmpty()) return null
            if (from.equals(myUid, ignoreCase = true)) return null // eco propio
            when (j.optString("t", "")) {
                "offer" -> {
                    val s = j.optJSONObject("s") ?: return null
                    Signal.Offer(
                        callId, from,
                        roomId = j.optString("r", ""),
                        fromName = j.optString("fn", "—"),
                        video = j.optInt("v", 0) == 1,
                        sdpType = s.optString("t", "offer"),
                        sdp = s.optString("s", ""),
                    )
                }
                "ans" -> {
                    val s = j.optJSONObject("s") ?: return null
                    Signal.Answer(callId, from, s.optString("t", "answer"), s.optString("s", ""))
                }
                "ice" -> Signal.Ice(
                    callId, from,
                    j.optString("m", ""), j.optInt("l", 0), j.optString("a", ""),
                )
                "bye" -> Signal.Bye(callId, from, j.optString("x", "hangup"))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
