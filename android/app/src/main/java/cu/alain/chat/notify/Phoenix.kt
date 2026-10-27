package cu.alain.chat.notify

import org.json.JSONObject

/**
 * Encuadre del protocolo Phoenix v1.0.0 que habla Supabase Realtime (D63).
 * Puro y sin red: testeable en JVM y compartido por el cliente y los tests.
 *
 * Wire (validado en vivo contra producción, hallazgos 10-11):
 *  join      → {"topic":"realtime:room-<id>","event":"phx_join","payload":{"config":{...}},"ref":"N"}
 *  reply ok  → {"event":"phx_reply","payload":{"status":"ok",...},"ref":"N","topic":"..."}
 *  broadcast → {"event":"broadcast","payload":{"event":"new-msg","payload":{"roomId":...},"type":"broadcast"},"topic":"realtime:room-<id>","ref":null}
 *  heartbeat → {"topic":"phoenix","event":"heartbeat","payload":{},"ref":"N"}
 *
 * El canal "room-<roomId>" NO es privado: el nombre ES la capability (roomId =
 * HKDF(roomKey), 128 bits). El payload del timbre lleva SOLO el roomId.
 */
object Phoenix {

    const val VSN = "1.0.0"
    const val EVENT_DOORBELL = "new-msg"

    /** Topic phoenix completo de una sala (el canal se llama room-<roomId>). */
    fun topicOf(roomId: String) = "realtime:room-$roomId"

    /** Topic personal: chats directos nuevos + señalización de llamadas (v0.7.0). */
    fun userTopic(uid: String) = "realtime:user-$uid"

    /** El wire siempre lleva el prefijo realtime: (tolerante si ya viene). */
    private fun fullTopic(channel: String) =
        if (channel.startsWith("realtime:")) channel else "realtime:$channel"

    private fun frame(topic: String, event: String, payload: JSONObject, ref: String) =
        JSONObject()
            .put("topic", topic)
            .put("event", event)
            .put("payload", payload)
            .put("ref", ref)
            .toString()

    fun join(channel: String, ref: String): String = frame(
        fullTopic(channel), "phx_join",
        JSONObject()
            .put(
                "config",
                JSONObject()
                    .put("broadcast", JSONObject().put("self", false))
                    .put("presence", JSONObject().put("key", "")),
            ),
        ref,
    )

    fun leave(channel: String, ref: String): String =
        frame(fullTopic(channel), "phx_leave", JSONObject(), ref)

    fun heartbeat(ref: String): String =
        frame("phoenix", "heartbeat", JSONObject(), ref)

    /** Frame entrante ya interpretado. */
    sealed class Frame {
        /** Respuesta a un join/heartbeat nuestro. */
        data class Reply(val ref: String, val topic: String, val ok: Boolean) : Frame()

        /** Timbre: mensaje nuevo en una sala. roomId es lo ÚNICO que viaja. */
        data class Doorbell(val roomId: String?, val event: String) : Frame()

        /** Cualquier otro broadcast (dm nuevo, señalización de llamadas…):
         *  payload crudo como string — quien decide qué hacer es el receptor. */
        data class Broadcast(val event: String, val payload: String) : Frame()
    }

    /** Interpreta un frame del servidor; null si no nos interesa. */
    fun parse(text: String): Frame? {
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        return when (o.optString("event")) {
            "phx_reply" -> {
                val ref = o.optString("ref")
                if (ref.isNullOrEmpty()) null
                else Frame.Reply(ref, o.optString("topic"), o.optJSONObject("payload")?.optString("status") == "ok")
            }
            "broadcast" -> {
                val p = o.optJSONObject("payload") ?: return null
                val event = p.optString("event")
                when {
                    event == EVENT_DOORBELL -> Frame.Doorbell(
                        p.optJSONObject("payload")?.optString("roomId")?.takeIf { it.isNotEmpty() },
                        event,
                    )
                    event.isNotEmpty() -> Frame.Broadcast(event, p.optJSONObject("payload")?.toString() ?: "{}")
                    else -> null
                }
            }
            else -> null
        }
    }
}
