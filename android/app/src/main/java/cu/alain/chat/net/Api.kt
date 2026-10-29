package cu.alain.chat.net

import cu.alain.chat.core.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Respuesta exitosa de /sync (204 → rows vacías). rd = seq entregado por otros por sala (D68). */
data class SyncResp(
    val nextSeq: Long,
    val rows: List<SyncRow>,
    val rd: List<Pair<String, Long>> = emptyList(),
    val dm: List<DmPickup> = emptyList(),
)

/** Fila de /sync: [msg_id, room_id, sender_id, seq, nonce_b64, ct_b64, sender_uname, ts_ms]. */
data class SyncRow(
    val msgId: String,
    val roomId: String,
    val senderId: String,
    val seq: Long,
    val nonceB64: String,
    val ctB64: String,
    val senderUname: String,
    val ts: Long,
)

/** Chat directo pendiente de recoger (D74): roomKey ENVUELTA + pública del creador. */
data class DmPickup(
    val roomId: String,
    val fromUid: String,
    val fromUname: String,
    val fromPubB64: String?,
    val wrappedB64: String,
)

/** Servidor ICE de /turn (B6/R3 auditoría): url + credenciales opcionales. */
data class IceServerCfg(
    val url: String,
    val username: String? = null,
    val password: String? = null,
)

/** Usuario encontrado por búsqueda (D74). */
data class FoundUser(val uid: String, val uname: String, val pubB64: String?)

/** Respuesta de dm_new: el chat de la pareja (recién creado o el que ya existía). */
data class DmResp(val roomId: String, val exists: Boolean, val wrappedForMeB64: String?)

/** Error del API con código HTTP + código simbólico del servidor. */
class ApiException(val http: Int, val code: String) : Exception("$http/$code") {
    /** Mensaje en español para la UI. */
    val userMsg: String
        get() = when (code) {
            "bad_invite" -> "Código de invitación incorrecto"
            "username_taken" -> "Ese nombre de usuario ya existe"
            "bad_username" -> "Nombre inválido: 3-24 letras, números o _"
            "bad_password" -> "La contraseña debe tener al menos 8 caracteres"
            "bad_credentials" -> "Usuario o contraseña incorrectos"
            "locked" -> "Demasiados intentos fallidos; espera un poco"
            "user_cap" -> "El servidor alcanzó el máximo de usuarios"
            "bad_room" -> "Identificador de sala inválido"
            "no_room" -> "Esa sala no existe todavía. Quien la creó debe abrirla primero."
            "room_taken" -> "Esa sala ya existe y pertenece a otra persona"
            "room_full" -> "La sala alcanzó el máximo de miembros"
            "room_cap" -> "Alcanzaste el máximo de salas que puedes crear"
            "dm_cap" -> "Alcanzaste el máximo de chats directos"
            "no_user" -> "No existe ningún usuario con ese nombre"
            "self_dm" -> "No puedes crear un chat contigo mismo"
            "peer_no_key" -> "Esa persona aún no actualiza a la nueva versión (chats directos)"
            "bad_pk" -> "Clave del teléfono inválida"
            "too_many" -> "Lote demasiado grande; inténtalo de nuevo"
            "rate_limited" -> "Demasiadas peticiones seguidas; espera un momento"
            "unauthorized" -> "Tu sesión expiró; inicia sesión de nuevo"
            "network" -> "Sin conexión o el servidor no responde"
            else -> if (http >= 500) "Error del servidor ($http)" else "Error $http: $code"
        }
}

/** Respuesta de login/registro. */
data class AuthResp(val uid: String, val uname: String, val token: String, val exp: String)

/** D93: ISO-8601 (con Z o +00:00, como lo devuelve Postgres) → epoch ms.
 *  null si no se puede parsear (campo vacío de un servidor viejo). */
fun parseIsoMs(iso: String): Long? = try {
    java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
} catch (_: Exception) { null }

/** Sala listada por el servidor: [room_id, owner(0/1)]. */
data class ServerRoom(val roomId: String, val owner: Boolean)

/**
 * Cliente HTTP de las Edge Functions (D2: polling HTTP, nada de WebSocket).
 * OkHttp gestiona gzip solo (Accept-Encoding automático) y callTimeout acota
 * las esperas en redes lentas — sin callTimeout una respuesta colgada no acaba nunca.
 */
class Api(private val baseUrl: () -> String) {

    private val base get() = baseUrl().trimEnd('/')
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .callTimeout(35, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun call(method: String, path: String, body: String?, token: String?): Pair<Int, String> {
        val b = Request.Builder()
            .url(base + path)
            .header("apikey", C.ANON_KEY)
        token?.let { b.header("Authorization", "Bearer $it") }
        when (method) {
            "POST" -> b.post((body ?: "").toRequestBody(JSON))
            "GET" -> b.get()
            else -> b.method(method, null)
        }
        client.newCall(b.build()).execute().use { r ->
            return r.code to (r.body?.string() ?: "")
        }
    }

    private fun post(path: String, json: JSONObject, token: String? = null): Pair<Int, JSONObject> {
        val (code, raw) = call("POST", path, json.toString(), token)
        val parsed = if (raw.isBlank()) JSONObject() else try {
            JSONObject(raw)
        } catch (_: Exception) { JSONObject().put("e", "bad_json") }
        return code to parsed
    }

    private fun get(path: String, token: String?): Pair<Int, String> = call("GET", path, null, token)

    private fun JSONObject?.errOr(default: String): String = this?.optString("e")?.takeIf { it.isNotEmpty() } ?: default

    // ---------- auth ----------

    suspend fun register(u: String, p: String, d: String, invite: String, pubB64: String? = null): AuthResp =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("a", "reg").put("u", u).put("p", p).put("d", d).put("ic", invite)
            pubB64?.let { body.put("pk", it) }
            val (code, j) = post("/auth", body)
            when (code) {
                201 -> AuthResp(j.getString("uid"), j.getString("u"), j.getString("tok"), j.optString("exp"))
                else -> throw ApiException(code, j.errOr("reg_failed"))
            }
        }

    suspend fun login(u: String, p: String, d: String, pubB64: String? = null): AuthResp =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("a", "in").put("u", u).put("p", p).put("d", d)
            pubB64?.let { body.put("pk", it) }
            val (code, j) = post("/auth", body)
            when (code) {
                200 -> AuthResp(j.getString("uid"), j.getString("u"), j.getString("tok"), j.optString("exp"))
                else -> throw ApiException(code, j.errOr("login_failed"))
            }
        }

    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        try { post("/auth", JSONObject().put("a", "out"), token) } catch (_: Exception) { }
    }

    /** D93: datos de la sesión actual (GET /auth) — alimenta el contador de
     *  días restantes de Ajustes. Best-effort: null sin red o sesión inválida. */
    suspend fun whoami(token: String): AuthResp? = withContext(Dispatchers.IO) {
        try {
            val (code, raw) = get("/auth", token)
            if (code != 200 || raw.isBlank()) return@withContext null
            val j = JSONObject(raw)
            AuthResp(j.getString("uid"), j.getString("u"), "", j.optString("exp"))
        } catch (_: Exception) { null }
    }

    // ---------- salas (D41-D43) ----------

    /** Crea la sala: room_id lo genera el CLIENTE (HKDF de roomKey). Idempotente. */
    suspend fun roomNew(token: String, roomId: String) = withContext(Dispatchers.IO) {
        val (code, j) = post("/auth", JSONObject().put("a", "room_new").put("r", roomId), token)
        if (code != 201) throw ApiException(code, j.errOr("room_new_failed"))
    }

    /** B se une con el room_id derivado del código de invitación. */
    suspend fun roomJoin(token: String, roomId: String) = withContext(Dispatchers.IO) {
        val (code, j) = post("/auth", JSONObject().put("a", "room_join").put("r", roomId), token)
        if (code != 200) throw ApiException(code, j.errOr("room_join_failed"))
    }

    /** Salir de la sala + borrado local (v0.6.0, D70). Si queda alguien, la sala vive. */
    suspend fun roomLeave(token: String, roomId: String) = withContext(Dispatchers.IO) {
        val (code, j) = post("/auth", JSONObject().put("a", "room_leave").put("r", roomId), token)
        if (code != 200) throw ApiException(code, j.errOr("room_leave_failed"))
    }

    /** Salas propias según el servidor (multi-dispositivo: sin roomKey no se descifra nada). */
    suspend fun rooms(token: String): List<ServerRoom> = withContext(Dispatchers.IO) {
        val (code, j) = post("/auth", JSONObject().put("a", "rooms"), token)
        if (code != 200) throw ApiException(code, j.errOr("rooms_failed"))
        val arr: JSONArray = j.getJSONArray("rooms")
        buildList {
            for (i in 0 until arr.length()) {
                val r = arr.getJSONArray(i)
                add(ServerRoom(r.getString(0), r.getInt(1) == 1))
            }
        }
    }

    // ---------- chats directos (D74) ----------

    /** (Re)sube la pública X25519 de este teléfono. Best-effort. */
    suspend fun uploadPub(token: String, pubB64: String) = withContext(Dispatchers.IO) {
        try {
            post("/auth", JSONObject().put("a", "pk").put("pk", pubB64), token)
        } catch (_: Exception) {
        }
    }

    /** Busca usuarios por prefijo de @nombre (máx 10, sin yo). */
    suspend fun searchUsers(token: String, q: String): List<FoundUser> = withContext(Dispatchers.IO) {
        val (code, j) = post("/auth", JSONObject().put("a", "search").put("q", q), token)
        if (code != 200) throw ApiException(code, j.errOr("search_failed"))
        val arr: JSONArray = j.getJSONArray("r")
        buildList {
            for (i in 0 until arr.length()) {
                val r = arr.getJSONArray(i)
                add(FoundUser(r.getString(0), r.getString(1), r.optString(2).takeIf { it.isNotEmpty() && it != "null" }))
            }
        }
    }

    /** Crea el chat directo de la pareja (idempotente). wrappedB64 = roomKey envuelta para el par. */
    suspend fun dmNew(token: String, roomId: String, peerUname: String, wrappedB64: String): DmResp =
        withContext(Dispatchers.IO) {
            val (code, j) = post(
                "/auth",
                JSONObject().put("a", "dm_new").put("r", roomId).put("peer", peerUname).put("w", wrappedB64),
                token,
            )
            when (code) {
                200, 201 -> DmResp(
                    roomId = j.getString("room"),
                    exists = j.optInt("exists", 0) == 1,
                    wrappedForMeB64 = j.optString("w").takeIf { it.isNotEmpty() && it != "null" },
                )
                else -> throw ApiException(code, j.errOr("dm_failed"))
            }
        }

    /** Confirma que el wrap del chat directo ya fue recogido y procesado. */
    suspend fun dmAck(token: String, roomId: String) = withContext(Dispatchers.IO) {
        try {
            post("/auth", JSONObject().put("a", "dm_ack").put("r", roomId), token)
        } catch (_: Exception) {
        }
    }

    /**
     * Broadcast directo por Realtime REST (anon key, canales no privados —
     * hallazgos 10-11). Lo usa la señalización de llamadas; el timbre del
     * receptor lo recibe por el WebSocket. Topic SIN prefijo "realtime:"
     * (hallazgo 10). NO transporta secretos: solo despertares y metadatos
     * de señalización.
     */
    suspend fun broadcast(topic: String, event: String, payload: JSONObject): Boolean =
        withContext(Dispatchers.IO) {
            // base = https://<host>/functions/v1 → root = https://<host>
            val root = base.substringBefore("/functions/v1")
            if (root.isEmpty() || root == base) return@withContext false
            return@withContext try {
                val body = JSONObject()
                    .put("messages", JSONArray().put(
                        JSONObject().put("topic", topic).put("event", event).put("payload", payload),
                    ))
                    .toString()
                    .toRequestBody(JSON)
                val req = Request.Builder()
                    .url(root.removeSuffix("/") + "/realtime/v1/api/broadcast")
                    .header("apikey", C.ANON_KEY)
                    .post(body)
                    .build()
                client.newCall(req).execute().use { it.code in 200..299 }
            } catch (_: Exception) {
                false
            }
        }

    // ---------- turn (B6/R3: ICE servers para llamadas) ----------

    /**
     * GET /turn → STUN + TURN (si el servidor tiene credenciales). Las
     * credenciales NUNCA van en el APK: las sirve el backend con sesión
     * válida y se pueden rotar sin tocar la app.
     */
    suspend fun fetchTurn(token: String): List<IceServerCfg> =
        withContext(Dispatchers.IO) {
            try {
                val (code, raw) = get("/turn", token)
                if (code != 200) return@withContext emptyList()
                val arr = JSONObject(raw).optJSONArray("s") ?: return@withContext emptyList()
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            IceServerCfg(
                                url = o.optString("u", ""),
                                username = if (o.has("un")) o.optString("un") else null,
                                password = if (o.has("pw")) o.optString("pw") else null,
                            )
                        )
                    }
                }
                list.filter { it.url.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
        }

    // ---------- sync ----------

    /**
     * Sin `room`: sync incremental global (cursor last_seq).
     * Con `room` (B1): recuperación del historial de ESA sala desde su propio
     * cursor — los mensajes previos al join quedan por debajo del cursor
     * global y jamás se servirían por la vía normal.
     */
    suspend fun sync(
        token: String,
        sinceSeq: Long,
        limit: Int = C.SYNC_LIMIT,
        room: String? = null,
    ): SyncResp =
        withContext(Dispatchers.IO) {
            val qs = if (room != null) "/sync?s=$sinceSeq&l=$limit&room=$room"
                     else "/sync?s=$sinceSeq&l=$limit"
            val (code, raw) = get(qs, token)
            when {
                code == 204 -> SyncResp(sinceSeq, emptyList())
                code == 200 -> {
                    val j = JSONObject(raw) // OkHttp ya descomprimió gzip si lo había
                    val arr: JSONArray = j.getJSONArray("m")
                    val rows = buildList {
                        for (i in 0 until arr.length()) {
                            val r = arr.getJSONArray(i)
                            add(
                                SyncRow(
                                    msgId = r.getString(0),
                                    roomId = r.getString(1),
                                    senderId = r.getString(2),
                                    seq = r.getLong(3),
                                    nonceB64 = r.getString(4),
                                    ctB64 = r.getString(5),
                                    senderUname = r.getString(6),
                                    ts = r.getLong(7),
                                )
                            )
                        }
                    }
                    // rd (v0.6.0): [[room_id, seq_entregado_por_otros], ...] — opcional
                    val rdArr = j.optJSONArray("rd")
                    val rd = if (rdArr != null) buildList {
                        for (i in 0 until rdArr.length()) {
                            val r = rdArr.getJSONArray(i)
                            add(r.getString(0) to r.getLong(1))
                        }
                    } else emptyList()
                    // dm (v0.7.0, D74): [[roomId, fromUid, fromUname, fromPubB64, wrappedB64]]
                    val dmArr = j.optJSONArray("dm")
                    val dm = if (dmArr != null) buildList {
                        for (i in 0 until dmArr.length()) {
                            val r = dmArr.getJSONArray(i)
                            add(
                                DmPickup(
                                    roomId = r.getString(0),
                                    fromUid = r.getString(1),
                                    fromUname = r.getString(2),
                                    fromPubB64 = r.optString(3).takeIf { it.isNotEmpty() && it != "null" },
                                    wrappedB64 = r.getString(4),
                                ),
                            )
                        }
                    } else emptyList()
                    SyncResp(j.getLong("n"), rows, rd, dm)
                }
                else -> {
                    val e = try { JSONObject(raw).optString("e") } catch (_: Exception) { "" }
                    throw ApiException(code, e.ifEmpty { "sync_failed" })
                }
            }
        }

    // ---------- outbox ----------

    /** Envía un lote a UNA sala: m = [[msg_id, ct_b64, nonce_b64, ts_ms], ...]. Devuelve los acked. */
    suspend fun outbox(token: String, room: String, msgs: List<Array<Any>>): List<String> =
        withContext(Dispatchers.IO) {
            val m = JSONArray()
            msgs.forEach { row ->
                val a = JSONArray()
                row.forEach { a.put(it) }
                m.put(a)
            }
            val (code, j) = post("/outbox", JSONObject().put("room", room).put("m", m), token)
            when (code) {
                200 -> {
                    val acked = j.getJSONArray("a")
                    buildList { for (i in 0 until acked.length()) add(acked.getString(i)) }
                }
                else -> throw ApiException(code, j.errOr("send_failed"))
            }
        }

    /** Latido + latencia en ms (chequeo de red en Ajustes). */
    suspend fun heartbeat(): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val (code, _) = get("/heartbeat", null)
        if (code == 200) System.currentTimeMillis() - t0 else throw ApiException(code, "heartbeat")
    }

    // ---------- adjuntos E2EE (v0.7.0, D76) ----------

    /** Cliente para Storage: sin callTimeout (los archivos son grandes). */
    private val fileClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * S0'/D109 (B8): subida EN STREAMING desde archivo — el cuerpo sale del
     * disco por bloques de 32 KB, nunca entero en RAM. La burbuja de progreso
     * sigue viva con onProgress.
     */
    suspend fun storageUploadFile(path: String, file: java.io.File, onProgress: (Float) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            val root = base.substringBefore("/functions/v1")
            val total = file.length().coerceAtLeast(1)
            val body = object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = file.length()
                override fun writeTo(sink: BufferedSink) {
                    java.io.FileInputStream(file).use { src ->
                        val buf = ByteArray(32 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n == -1) break
                            sink.write(buf, 0, n)
                            sent += n
                            onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val req = Request.Builder()
                .url(root.removeSuffix("/") + "/storage/v1/object/" + C.ATTACH_BUCKET + "/" + path)
                .header("apikey", C.ANON_KEY)
                .header("Authorization", "Bearer " + C.ANON_KEY)
                .post(body)
                .build()
            fileClient.newCall(req).execute().use { r ->
                if (r.code != 200) {
                    throw ApiException(
                        r.code,
                        if (r.code == 413) "file_too_big" else "upload_failed",
                    )
                }
            }
        }
    }

    /** URL firmada de descarga (solo miembros de la sala del path, /files). */
    suspend fun filesUrlDl(token: String, path: String): String = withContext(Dispatchers.IO) {
        val (code, j) = post("/files", JSONObject().put("a", "urlDl").put("p", path), token)
        when {
            code == 200 -> j.getString("u")
            code == 404 -> throw ApiException(404, "no_room")
            else -> throw ApiException(code, j.errOr("storage_error"))
        }
    }

    /** GET binario de una URL firmada. */
    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("apikey", C.ANON_KEY).get().build()
        fileClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "download_failed")
            r.body?.bytes() ?: throw ApiException(r.code, "download_failed")
        }
    }
}
