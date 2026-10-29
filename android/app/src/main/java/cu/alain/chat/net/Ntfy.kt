package cu.alain.chat.net

import cu.alain.chat.core.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Canal de notificaciones ntfy (D104) — el DESPERTADOR redundante del timbre,
 * con el sobre cifrado dentro del ping.
 *
 * Por qué otro canal si ya hay timbre Realtime (D63): porque ESTE no pasa por
 * Supabase — el tema se deriva de la roomKey en el móvil (el servidor NUNCA lo
 * conoce), así que el ping sobrevive a caídas/pausas de Supabase, lleva el
 * mensaje ya cifrado (el receptor lo ve SIN round-trip: en ETECSA, con RTT de
 * segundos, es la diferencia entre «al instante» y «cuando la red deje de
 * quejarse») y su caché de 12 h hace de buffer anti-desconexión.
 *
 * Publicación (emisor, tras el ack del outbox — JAMÁS antes: el receptor
 * despertaría y no encontraría el mensaje):
 *   POST {base}/<topic>   X-Firebase: no (D9: cero Firebase)  body = ping JSON
 *
 * Suscripción (receptor):
 *   GET {base}/<t1>,<t2>/json?poll=1&since=<unix-30>
 * SIEMPRE con since (regla dura ntfy.sh: sin cursor relee 12 h de caché y el
 * egress diario se agota → 429). El solape de 30 s lo deshace el dedup por
 * msgId (la PK local) — nunca hay lógica de deduplicación que mantener.
 *
 * El ping (JSON del body; llega tal cual en el campo "message" del NDJSON):
 *   {"v":1,"r":<roomId>}                                    ← despertador puro
 *   {"v":1,"r":<roomId>,"m":[[msgId,uid,seq,nonce,ct,uname,ts]]}
 * Filas en el MISMO orden posicional que /sync (sin roomId: va en "r").
 * seq=0 = desconocido (el ack del outbox no la trae; la llena el /sync).
 * Degradación: >[C.NTFY_PING_MAX] chars → puro (ntfy.sh convierte los bodies
 * >4 KB en ATTACHMENT con URL pública — nunca provocarlo; hallazgo e2e H25).
 *
 * Seguridad (doc v2 §9.3 — ntfy es transporte sin autenticar): publicar en un
 * tema es gratis para quien conozca el nombre (128 bits, no enumerable), pero
 * LEER solo puede quien tenga la roomKey: el AEAD tira lo inyectado y el msgId
 * (PK local) tira los replays. Se descarta en silencio, sin loguear nada.
 */
class Ntfy(private val baseUrl: () -> String) {

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Evento "message" del NDJSON (los "open"/"keepalive" no interesan). */
    data class Event(val time: Long, val topic: String, val message: String)

    /** Ping parseado: roomId + filas listas para ingest (vacías = puro). */
    data class Ping(val roomId: String, val rows: List<SyncRow>)

    /**
     * Publica el ping del lote recién ackeado. Best-effort total: si ntfy.sh
     * no está alcanzable (ETECSA lo bloquea, red caída) el ping se pierde y el
     * receptor lo cubre por el timbre Realtime o el /sync de 15 min — igual
     * que el broadcast del servidor (D63). Nunca lanza: no puede romper el
     * drenaje del outbox.
     */
    suspend fun publish(topic: String, body: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${baseUrl().trimEnd('/')}/$topic")
                .header("X-Firebase", "no") // D9/auditoría §4.3: ntfy.sh reenviaría a FCM por defecto
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody(OCTET))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Un poll multi-tema con cursor. Devuelve los eventos message, o null si
     * falló la red (el poller lo reintenta en su ciclo; no es un error del
     * canal). `sinceUnix` ya viene con el solape aplicado.
     */
    suspend fun poll(topics: List<String>, sinceUnix: Int): List<Event>? = withContext(Dispatchers.IO) {
        if (topics.isEmpty()) return@withContext emptyList()
        try {
            val url = "${baseUrl().trimEnd('/')}/${topics.joinToString(",")}/json?poll=1&since=${maxOf(0, sinceUnix)}"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val body = r.body?.string() ?: ""
                val out = mutableListOf<Event>()
                for (line in body.lineSequence()) {
                    if (line.isBlank()) continue
                    val j = try { JSONObject(line) } catch (_: Exception) { continue }
                    if (j.optString("event") != "message") continue
                    out.add(Event(j.optLong("time"), j.optString("topic"), j.optString("message")))
                }
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()

        /** Fila del ping: lo mínimo que el receptor necesita para ingest+AAD. */
        data class PingRow(val msgId: String, val nonceB64: String, val ctB64: String, val ts: Long)

        /**
         * Ping de un lote ackeado: hasta las [C.NTFY_PING_ROWS] últimas filas
         * por ts; si no caben en [C.NTFY_PING_MAX] chars, prueba con solo la
         * última; si ni esa cabe, despertador puro — el /sync que el propio
         * ping provoca trae el resto del lote igual.
         */
        fun buildPing(roomId: String, senderUid: String, senderUname: String, rows: List<PingRow>): String {
            val sorted = rows.sortedBy { it.ts }
            val candidates = listOf(
                sorted.takeLast(C.NTFY_PING_ROWS),
                sorted.takeLast(1),
            )
            for (cand in candidates) {
                if (cand.isEmpty()) break
                val j = pingJson(roomId, senderUid, senderUname, cand)
                if (j.length <= C.NTFY_PING_MAX) return j
            }
            return pingJson(roomId, senderUid, senderUname, emptyList())
        }

        private fun pingJson(roomId: String, uid: String, uname: String, rows: List<PingRow>): String {
            val j = JSONObject().put("v", 1).put("r", roomId)
            if (rows.isNotEmpty()) {
                val m = JSONArray()
                rows.forEach { r ->
                    m.put(JSONArray()
                        .put(r.msgId).put(uid).put(0L) // seq 0 = desconocida hasta el /sync
                        .put(r.nonceB64).put(r.ctB64).put(uname).put(r.ts))
                }
                j.put("m", m)
            }
            return j.toString()
        }

        /**
         * Ping → (roomId, filas). null = no es un ping (attachment, basura,
         * inyección malformada): se descarta EN SILENCIO, sin log — el
         * atacante no aprende nada del presupuesto gastado. Las validaciones
         * son las del e2e_ntfy.py (espejo Python, 32/32): m ≤ 8 filas,
         * 7 campos posicionales, longitudes acotadas. seq llega SIEMPRE 0:
         * la pone el /sync posterior.
         */
        fun parsePing(message: String): Ping? {
            val j = try { JSONObject(message) } catch (_: Exception) { return null }
            if (j.optInt("v", -1) != 1) return null
            val rid = j.optString("r")
            if (rid.isEmpty() || rid.length > 64) return null
            val m = j.optJSONArray("m") ?: return Ping(rid, emptyList())
            if (m.length() == 0 || m.length() > C.NTFY_PING_ROWS) return Ping(rid, emptyList())
            val rows = mutableListOf<SyncRow>()
            for (i in 0 until m.length()) {
                val f = m.optJSONArray(i) ?: return Ping(rid, emptyList())
                if (f.length() != 7) return Ping(rid, emptyList())
                val mid = f.optString(0)
                val uid = f.optString(1)
                val nonce = f.optString(3)
                val ct = f.optString(4)
                val uname = f.optString(5)
                if (mid.length > 64 || uid.length > 64 || uname.length > 64) return Ping(rid, emptyList())
                if (nonce.length > 32 || ct.length > 12000) return Ping(rid, emptyList())
                val seq = f.optLong(2, -1)
                val ts = f.optLong(6, -1)
                if (seq < 0 || ts < 0) return Ping(rid, emptyList())
                rows.add(
                    SyncRow(
                        msgId = mid, roomId = rid, senderId = uid, seq = seq,
                        nonceB64 = nonce, ctB64 = ct, senderUname = uname, ts = ts,
                    )
                )
            }
            return Ping(rid, rows)
        }
    }
}
