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

/** Respuesta exitosa de /sync (204 → rows vacías). */
data class SyncResp(val nextSeq: Long, val rows: List<SyncRow>)

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
            "too_many" -> "Lote demasiado grande; inténtalo de nuevo"
            "unauthorized" -> "Tu sesión expiró; inicia sesión de nuevo"
            "network" -> "Sin conexión o el servidor no responde"
            else -> if (http >= 500) "Error del servidor ($http)" else "Error $http: $code"
        }
}

/** Respuesta de login/registro. */
data class AuthResp(val uid: String, val uname: String, val token: String, val exp: String)

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

    suspend fun register(u: String, p: String, d: String, invite: String): AuthResp = withContext(Dispatchers.IO) {
        val (code, j) = post("/auth", JSONObject()
            .put("a", "reg").put("u", u).put("p", p).put("d", d).put("ic", invite))
        when (code) {
            201 -> AuthResp(j.getString("uid"), j.getString("u"), j.getString("tok"), j.optString("exp"))
            else -> throw ApiException(code, j.errOr("reg_failed"))
        }
    }

    suspend fun login(u: String, p: String, d: String): AuthResp = withContext(Dispatchers.IO) {
        val (code, j) = post("/auth", JSONObject()
            .put("a", "in").put("u", u).put("p", p).put("d", d))
        when (code) {
            200 -> AuthResp(j.getString("uid"), j.getString("u"), j.getString("tok"), j.optString("exp"))
            else -> throw ApiException(code, j.errOr("login_failed"))
        }
    }

    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        try { post("/auth", JSONObject().put("a", "out"), token) } catch (_: Exception) { }
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

    // ---------- sync ----------

    suspend fun sync(token: String, sinceSeq: Long, limit: Int = C.SYNC_LIMIT): SyncResp =
        withContext(Dispatchers.IO) {
            val (code, raw) = get("/sync?s=$sinceSeq&l=$limit", token)
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
                    SyncResp(j.getLong("n"), rows)
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
}
