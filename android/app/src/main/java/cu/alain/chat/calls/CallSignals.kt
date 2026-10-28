package cu.alain.chat.calls

import cu.alain.chat.crypto.Crypto
import org.json.JSONObject

/**
 * Señalización de llamadas (v0.7.0, D75) — parse PURO (testeable en JVM).
 *
 * Viaja por broadcast REST al topic de la sala (hallazgos 10-11) y entra por
 * el timbre del par como evento "sig". Desde v0.8.0 (B6 auditoría) el payload
 * va CIFRADO con la clave de señalización de la sala (Crypto.sigKey — madre
 * convKey, salt callId): el servidor solo ve roomId (que ya sabe por el topic)
 * y un callId aleatorio. El SDP (con las IPs), los candidatos ICE y el NOMBRE
 * real del llamante dejaron de viajar en claro — la promesa E2EE de D61
 * también se cumple en las llamadas. El MEDIO siempre fue P2P cifrado
 * DTLS-SRTP; ahora la señalización va a la par.
 *
 * Envelope v0.8.0:  {"t":"enc","c":callId,"r":roomId,"n":nonce_b64,"d":ct_b64}
 *   dentro (cifrado, AAD="sig-v1|callId|roomId"): el JSON de siempre —
 *   {"t":"offer","c":callId,"r":roomId,"f":uid,"fn":nombre,"v":0|1,"s":{...}}
 *   {"t":"ans","c":callId,"f":uid,"s":{...}} · {"t":"ice",...} · {"t":"bye",...}
 *
 * Compatibilidad: los tipos legacy en claro ("offer"/"ans"/"ice"/"bye")
 * siguen PARSEÁNDOSE (un v0.7.x que llame a un v0.8.0 todavía suena); los
 * v0.8.0 solo EMITEN cifrado. Actualizar ambos teléfonos para llamada
 * completa E2EE.
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

    // ───────── B6: sobre cifrado (la clave la deriva el dispatcher del vault) ─────────

    // Base64 RFC 4648 sin wraps — propio y puro Kotlin: testeable en JVM y
    // compatible con minSdk 24 (java.util.Base64 exige API 26 y android.util
    // no corre en tests JVM puros).
    private const val B64A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private fun b64e(b: ByteArray): String {
        val sb = StringBuilder((b.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < b.size) {
            val n = (b[i].toInt() and 0xFF shl 16) or (b[i + 1].toInt() and 0xFF shl 8) or (b[i + 2].toInt() and 0xFF)
            sb.append(B64A[n ushr 18 and 63]).append(B64A[n ushr 12 and 63])
                .append(B64A[n ushr 6 and 63]).append(B64A[n and 63])
            i += 3
        }
        when (b.size - i) {
            1 -> { val n = b[i].toInt() and 0xFF shl 16
                sb.append(B64A[n ushr 18 and 63]).append(B64A[n ushr 12 and 63]).append("==") }
            2 -> { val n = (b[i].toInt() and 0xFF shl 16) or (b[i + 1].toInt() and 0xFF shl 8)
                sb.append(B64A[n ushr 18 and 63]).append(B64A[n ushr 12 and 63])
                    .append(B64A[n ushr 6 and 63]).append('=') }
        }
        return sb.toString()
    }

    private fun b64d(s: String): ByteArray? {
        val tbl = IntArray(128) { -1 }.also { t -> B64A.forEachIndexed { i, c -> t[c.code] = i } }
        val acc = ArrayList<Byte>((s.length * 3) / 4 + 3)
        var buf = 0; var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = if (c.code < 128) tbl[c.code] else -1
            if (v < 0) return null
            buf = (buf shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; acc.add((buf ushr bits).toByte()) }
        }
        return acc.toByteArray()
    }

    /** Empaqueta un payload de señalización en el sobre cifrado v0.8.0. */
    fun wrapEncrypted(sigKey: ByteArray, callId: String, roomId: String, inner: JSONObject): JSONObject {
        val aad = sigAad(callId, roomId)
        val (nonce, ct) = Crypto.seal(sigKey, inner.toString().toByteArray(Charsets.UTF_8), aad)
        return JSONObject()
            .put("t", "enc").put("c", callId).put("r", roomId.lowercase())
            .put("n", b64e(nonce))
            .put("d", b64e(ct))
    }

    /**
     * Abre un sobre "enc". Devuelve el payload interior como String, o null si
     * la clave no sirve / el AAD no coincide (sala o llamada equivocada).
     */
    fun unwrapEncrypted(sigKey: ByteArray, callId: String, roomId: String, outer: JSONObject): String? {
        val nonce = b64d(outer.optString("n", "")) ?: return null
        val ct = b64d(outer.optString("d", "")) ?: return null
        val plain = Crypto.open(sigKey, nonce, ct, sigAad(callId, roomId)) ?: return null
        return String(plain, Charsets.UTF_8)
    }

    private fun sigAad(callId: String, roomId: String): ByteArray =
        "sig-v1|${callId.lowercase()}|${roomId.lowercase()}".toByteArray(Charsets.UTF_8)

    /** ¿Es un sobre cifrado v0.8.0? (el dispatcher lo abre antes de parsear). */
    fun isEncrypted(j: JSONObject): Boolean = j.optString("t", "") == "enc"

    // ───────── payloads (idénticos a v0.7.x — ahora viajan DENTRO del sobre) ─────────

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
        JSONObject()
            .put("t", "bye").put("c", callId).put("f", fromUid).put("x", reason)

    /** Parse del payload interior (o legacy en claro); null si no va a nosotros. */
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
