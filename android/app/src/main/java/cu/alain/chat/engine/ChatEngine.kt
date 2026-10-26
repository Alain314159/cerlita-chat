package cu.alain.chat.engine

import android.util.Base64
import cu.alain.chat.core.C
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.db.Message
import cu.alain.chat.db.OutboxMsg
import cu.alain.chat.db.PendingMsg
import cu.alain.chat.db.Room
import cu.alain.chat.net.ApiException
import cu.alain.chat.net.SyncRow
import cu.alain.chat.vault.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

/** Estado global de la app (dirige la navegación). */
sealed class AppState {
    data object Login : AppState()
    data object Main : AppState()
}

/** Sala recién creada o unida: lo que la UI necesita para la pantalla de SAS/código. */
data class RoomJoined(
    val room: Room,
    val inviteCode: String,   // solo al CREAR; "" al unirse (B ya lo tiene)
    val sas: String,
)

/**
 * Motor del chat (modelo rooms, D54).
 *
 * Único dueño de las roomKeys en RAM (cache del vault sellado por Keystore):
 * el vault se puede re-leer en cualquier momento — no hay frase que teclear,
 * así que el desbloqueo es instantáneo (mejor UX para el objetivo de primera
 * ejecución <60 s, D52). En reposo las claves están cifradas por hardware.
 *
 * Hilos: los métodos suspend se llaman desde ViewModels/Workers; el polling
 * de primer plano vive en [scope] y se enciende/apaga desde MainActivity.
 */
class ChatEngine(
    private val db: AppDatabase,
    private val store: SessionStore,
    private val api: cu.alain.chat.net.Api,
    private val onNewMessages: (Int) -> Unit,
) {

    /** roomId → (roomKey, roomSalt). RAM solamente; persisten selladas en el vault. */
    @Volatile
    private var keys: Map<String, Pair<ByteArray, ByteArray>> = emptyMap()

    /** Se incrementa al cambiar el vault: la UI refresca candados de salas. */
    private val _keysVersion = MutableStateFlow(0)
    val keysVersion: StateFlow<Int> = _keysVersion

    private val _state = MutableStateFlow<AppState>(AppState.Login)
    val state: StateFlow<AppState> = _state

    /** Contador de mensajes nuevos para el badge/aviso (se consume en la UI). */
    val lastSyncResult = MutableStateFlow(0)

    private var pollJob: Job? = null
    private var pollInterval = C.POLL_ACTIVE_MS
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ────────────────────────── estado / sesión ──────────────────────────

    fun refreshState() {
        _state.value = if (store.session == null) AppState.Login else AppState.Main
        if (store.session != null && keys.isEmpty()) loadKeys()
    }

    val session get() = store.session

    /** Recarga el vault sellado a RAM. Barato: una lectura AES-GCM del Keystore. */
    fun loadKeys() {
        keys = store.roomKeys().mapValues { (_, ks) ->
            Base64.decode(ks.first, Base64.NO_WRAP) to Base64.decode(ks.second, Base64.NO_WRAP)
        }
        _keysVersion.value += 1
    }

    /**
     * Arranque del proceso con sesión viva: claves a RAM, salas del servidor
     * (multi-dispositivo) y pendientes que ya se puedan descifrar.
     */
    fun onProcessStart() {
        if (store.session == null) return
        loadKeys()
        scope.launch {
            syncRoomsFromServer()
            drainPending()
        }
    }

    fun hasKey(roomId: String): Boolean = keys.containsKey(roomId.lowercase())

    /** Login/registro correcto: guarda la sesión cifrada por Keystore y refresca salas. */
    fun onAuthed(uid: String, uname: String, token: String, did: String) {
        store.saveSession(cu.alain.chat.vault.Session(token, uid, uname, did))
        loadKeys()
        refreshState()
        scope.launch {
            syncRoomsFromServer()
            drainPending() // mensajes que llegaron de salas ya conocidas
        }
    }

    /**
     * Fusiona la lista de salas del servidor (multi-dispositivo). Las salas nuevas
     * aparecen con candado: sin roomKey no se descifra nada — el código de
     * invitación se vuelve a introducir para recuperar el acceso (E2EE).
     */
    suspend fun syncRoomsFromServer() {
        val s = store.session ?: return
        try {
            val server = api.rooms(s.token)
            val known = db.roomDao().all().associateBy { it.roomId }
            val missing = server.filter { it.roomId !in known }.map {
                Room(
                    roomId = it.roomId,
                    name = "Sala ${it.roomId.take(8)}",
                    owner = it.owner,
                    sas = "",
                    createdAt = System.currentTimeMillis(),
                )
            }
            if (missing.isNotEmpty()) db.roomDao().addMissing(missing)
        } catch (_: Exception) {
            // sin red / sesión caída: las salas locales mandan, se reintenta al abrir
        }
    }

    // ────────────────────────── salas ──────────────────────────

    /**
     * Crear sala (A): genera roomKey/roomSalt, deriva roomId, registra en el
     * servidor y guarda clave en el vault. El código de invitación se devuelve
     * para compartirlo FUERA de banda (D42) — jamás viaja al servidor.
     */
    suspend fun createRoom(name: String): Result<RoomJoined> {
        val s = store.session ?: return Result.failure(IllegalStateException("sin sesión"))
        val roomKey = Crypto.newRoomKey()
        val roomSalt = Crypto.newSalt16()
        val roomId = Crypto.roomId(roomKey, roomSalt)
        val sas = Crypto.sas6(roomKey, roomSalt)
        return try {
            api.roomNew(s.token, roomId)
            val room = Room(
                roomId = roomId,
                name = name.ifBlank { "Sala ${roomId.take(8)}" },
                owner = true,
                sas = sas,
                createdAt = System.currentTimeMillis(),
            )
            db.roomDao().upsert(room)
            store.putRoomKey(roomId, roomKey, roomSalt)
            keys = keys + (roomId to (roomKey to roomSalt))
            _keysVersion.value += 1
            Result.success(RoomJoined(room, Crypto.encodeInvite(roomSalt, roomKey), sas))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unirse a sala (B): decodifica el código (la roomKey va DENTRO, D42),
     * deriva el mismo roomId y se une. No hay sas_confirmed: el SAS se compara
     * por voz entre ambos clientes (D43) y el servidor solo registra membresía.
     */
    suspend fun joinRoom(code: String, name: String): Result<RoomJoined> {
        val s = store.session ?: return Result.failure(IllegalStateException("sin sesión"))
        val secret = Crypto.decodeInvite(code)
            ?: return Result.failure(IllegalArgumentException("El código de invitación no es válido o tiene errores de tecleo"))
        val (roomKey, roomSalt) = secret
        val roomId = Crypto.roomId(roomKey, roomSalt)
        val sas = Crypto.sas6(roomKey, roomSalt)
        return try {
            api.roomJoin(s.token, roomId)
            val room = Room(
                roomId = roomId,
                name = name.ifBlank { "Sala ${roomId.take(8)}" },
                owner = db.roomDao().get(roomId)?.owner ?: false,
                sas = sas,
                createdAt = System.currentTimeMillis(),
            )
            db.roomDao().upsert(room)
            store.putRoomKey(roomId, roomKey, roomSalt)
            keys = keys + (roomId to (roomKey to roomSalt))
            _keysVersion.value += 1
            drainPendingForRoom(roomId) // mensajes que llegaron antes de la clave
            Result.success(RoomJoined(room, "", sas))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Añade la clave a una sala conocida sin clave (recuperación en 2º dispositivo). */
    suspend fun restoreRoomKey(code: String): Result<String> {
        val secret = Crypto.decodeInvite(code)
            ?: return Result.failure(IllegalArgumentException("Código inválido o con errores de tecleo"))
        val (roomKey, roomSalt) = secret
        val roomId = Crypto.roomId(roomKey, roomSalt)
        if (db.roomDao().get(roomId) == null) {
            return Result.failure(IllegalStateException("Ese código no corresponde a ninguna sala tuya en el servidor"))
        }
        store.putRoomKey(roomId, roomKey, roomSalt)
        keys = keys + (roomId to (roomKey to roomSalt))
        _keysVersion.value += 1
        drainPendingForRoom(roomId)
        return Result.success(roomId)
    }

    /** Cerrar sesión: revoca en el servidor (best-effort), borra TODO lo local. */
    fun logout(apiLogout: Boolean) {
        val s = store.session
        if (apiLogout && s != null) {
            scope.launch { try { api.logout(s.token) } catch (_: Exception) {} }
        }
        keys.values.forEach { Crypto.zero(it.first); Crypto.zero(it.second) }
        keys = emptyMap()
        _keysVersion.value += 1
        stopPolling()
        store.clearSession()
        store.clearRoomKeys()
        scope.launch {
            db.roomDao().wipe(); db.msgDao().wipe(); db.outboxDao().wipe(); db.pendingDao().wipe()
            db.metaDao().setLong("last_seq", 0)
        }
        refreshState()
    }

    // ────────────────────────── envío ──────────────────────────

    /**
     * Cifra y encola un mensaje para una sala. Devuelve Ok(msgId) para la UI
     * inmediata (burbuja "enviando") o Err con mensaje en español.
     */
    suspend fun send(roomId: String, text: String): Result<String> {
        val s = store.session ?: return Result.failure(IllegalStateException("sin sesión"))
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("vacío"))
        val key = keys[roomId.lowercase()]
            ?: return Result.failure(IllegalStateException("esta sala no tiene clave en este teléfono"))

        val msgId = Crypto.uuid7()
        val ts = System.currentTimeMillis()
        val wire = Crypto.frame(text)
        val convKey = Crypto.convKey(key.first, roomId)
        val mk = Crypto.msgKey(convKey, msgId)
        val aad = Crypto.aad(msgId, s.uid, roomId)
        val (nonce, ct) = Crypto.seal(mk, wire, aad)

        if (ct.size < C.MIN_CT || ct.size > C.MAX_CT) {
            return Result.failure(IllegalArgumentException("El mensaje es demasiado largo"))
        }

        db.msgDao().insert(Message(msgId, roomId, s.uname, mine = true, body = text, ts = ts, state = C.ST_SENDING))
        db.outboxDao().insert(OutboxMsg(msgId, roomId, b64(ct), b64(nonce), ts))
        poke()
        return Result.success(msgId)
    }

    /**
     * Drenar el outbox (lo llama el OutboxWorker y tras un envío).
     * Devuelve nº de mensajes acked. Errores 4xx permanentes marcan FAILED.
     */
    suspend fun drainOutbox(onAuthLost: () -> Unit = {}): Int {
        val s = store.session ?: return 0
        val all = db.outboxDao().batch()
        if (all.isEmpty()) return 0
        var acked = 0
        all.groupBy { it.roomId }.forEach { (roomId, msgs) ->
            if (!hasKey(roomId)) {
                // sala sin clave (no debería ocurrir: send() lo impide) → no enviar
                db.outboxDao().delete(msgs.map { it.msgId })
                msgs.forEach { db.msgDao().setState(it.msgId, C.ST_FAILED) }
                return@forEach
            }
            msgs.chunked(C.MAX_BATCH).forEach { chunk ->
                val payload = chunk.map { arrayOf<Any>(it.msgId, it.ctB64, it.nonceB64, it.ts) }
                try {
                    val ok = api.outbox(s.token, roomId, payload)
                    acked += ok.size
                    db.outboxDao().delete(chunk.map { it.msgId })
                    chunk.forEach { m ->
                        if (m.msgId in ok) db.msgDao().setState(m.msgId, C.ST_SENT)
                        // los no-acked se quedan en outbox para el siguiente intento
                    }
                } catch (e: ApiException) {
                    when {
                        e.http == 401 -> { onAuthLost(); return acked }
                        e.http >= 400 && e.http < 500 && e.code != "too_many" -> {
                            // permanente: sala borrada, mensaje corrupto…
                            db.outboxDao().delete(chunk.map { it.msgId })
                            chunk.forEach { db.msgDao().setState(it.msgId, C.ST_FAILED) }
                        }
                        else -> throw e // 5xx / red: el worker reintenta con backoff
                    }
                }
            }
        }
        return acked
    }

    // ────────────────────────── recepción ──────────────────────────

    /**
     * Un ciclo de sync: baja todo lo nuevo (hasta 204), descifra y guarda.
     * Devuelve nº de mensajes nuevos legibles, o -1/-2 si hubo error de red/auth.
     */
    suspend fun syncOnce(): Int {
        val s = store.session ?: return -1
        var lastSeq = db.metaDao().getLong("last_seq") ?: 0
        var total = 0
        try {
            while (true) {
                val resp = api.sync(s.token, lastSeq)
                if (resp.rows.isEmpty()) break
                for (row in resp.rows) {
                    if (ingest(row, s)) total++
                }
                lastSeq = resp.nextSeq
                db.metaDao().setLong("last_seq", lastSeq)
                if (resp.rows.size < C.SYNC_LIMIT) break
            }
            if (total > 0) { lastSyncResult.value = total; onNewMessages(total) }
            return total
        } catch (e: ApiException) {
            return if (e.http == 401) -2 else -1
        }
    }

    /**
     * Procesa una fila del sync. Devuelve true si es un mensaje nuevo LEGIBLE
     * (los de salas sin clave van a pending y no cuentan como "nuevos": el
     * usuario no podría leerlos todavía).
     */
    private suspend fun ingest(row: SyncRow, s: cu.alain.chat.vault.Session): Boolean {
        if (db.msgDao().exists(row.msgId)) return false // eco de un mensaje propio ya en Room
        val key = keys[row.roomId.lowercase()]
        if (key == null) {
            // sala sin clave en este teléfono (2º dispositivo, clave perdida):
            // se archiva cifrada; al restaurar el código de invitación se drena.
            db.pendingDao().insert(
                PendingMsg(row.msgId, row.roomId, row.senderUname, row.senderId, row.nonceB64, row.ctB64, row.ts, row.seq)
            )
            return false
        }
        val msg = decryptRow(row, s, key.first)
        if (msg != null) db.msgDao().insert(msg)
        return true
    }

    private suspend fun decryptRow(
        row: SyncRow,
        s: cu.alain.chat.vault.Session,
        roomKey: ByteArray,
    ): Message {
        val mine = row.senderId.equals(s.uid, ignoreCase = true)
        return try {
            val convKey = Crypto.convKey(roomKey, row.roomId)
            val mk = Crypto.msgKey(convKey, row.msgId)
            val wire = Crypto.open(mk, b64d(row.nonceB64), b64d(row.ctB64), Crypto.aad(row.msgId, row.senderId, row.roomId))
            val body = if (wire != null) (Crypto.unframe(wire) ?: "[mensaje con formato desconocido]") else "[no se pudo descifrar]"
            Message(
                msgId = row.msgId,
                roomId = row.roomId,
                senderUname = row.senderUname,
                mine = mine,
                body = body,
                ts = row.ts,
                state = if (wire != null) C.ST_RECEIVED else C.ST_UNREADABLE,
                seq = row.seq,
            )
        } catch (_: Exception) {
            Message(row.msgId, row.roomId, row.senderUname, mine, "[error]", row.ts, C.ST_UNREADABLE, row.seq)
        }
    }

    /** Descifra todo lo acumulado en pending (tras añadir una clave de sala). */
    private suspend fun drainPending() {
        val s = store.session ?: return
        var n = 0
        val rows = db.pendingDao().all()
        if (rows.isEmpty()) return
        val ids = mutableListOf<String>()
        for (p in rows) {
            if (!db.msgDao().exists(p.msgId)) {
                val key = keys[p.roomId.lowercase()]
                if (key != null) {
                    val msg = decryptRow(
                        SyncRow(p.msgId, p.roomId, p.senderId, p.seq, p.nonceB64, p.ctB64, p.senderUname, p.ts),
                        s, key.first,
                    )
                    db.msgDao().insert(msg)
                    n++
                } else {
                    continue // sigue sin clave: permanece en pending
                }
            }
            ids.add(p.msgId)
        }
        db.pendingDao().delete(ids)
        if (n > 0) { lastSyncResult.value = n; onNewMessages(n) }
    }

    /** Descifra los pendientes de UNA sala (tras join/restore de su clave). */
    private suspend fun drainPendingForRoom(roomId: String) {
        val s = store.session ?: return
        val key = keys[roomId.lowercase()] ?: return
        var n = 0
        val rows = db.pendingDao().forRoom(roomId)
        val ids = mutableListOf<String>()
        for (p in rows) {
            if (!db.msgDao().exists(p.msgId)) {
                val msg = decryptRow(
                    SyncRow(p.msgId, p.roomId, p.senderId, p.seq, p.nonceB64, p.ctB64, p.senderUname, p.ts),
                    s, key.first,
                )
                db.msgDao().insert(msg)
                n++
            }
            ids.add(p.msgId)
        }
        db.pendingDao().delete(ids)
        if (n > 0) { lastSyncResult.value = n; onNewMessages(n) }
    }

    // ────────────────────────── polling adaptativo (D15) ──────────────────────────

    /** Resetea el intervalo (hay actividad: envío, mensaje nuevo…). */
    fun poke() { pollInterval = C.POLL_ACTIVE_MS }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            poke()
            while (isActive) {
                val n = try { syncOnce() } catch (_: Exception) { -1 }
                pollInterval = when {
                    n > 0 -> C.POLL_ACTIVE_MS                    // hay actividad → rápido
                    n == -2 -> C.POLL_ACTIVE_MS                  // auth perdida: reintenta corto
                    else -> min((pollInterval * C.POLL_BACKOFF).toLong(), C.POLL_IDLE_MAX_MS)
                }
                // jitter completo ±40% para no sincronizar los 2 teléfonos
                val jit = (pollInterval * (0.6 + Random.nextDouble(0.8))).toLong()
                delay(jit)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ────────────────────────── util ──────────────────────────

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
