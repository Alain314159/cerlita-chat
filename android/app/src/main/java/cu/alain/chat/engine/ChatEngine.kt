package cu.alain.chat.engine

import android.util.Base64
import cu.alain.chat.core.C
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.db.Message
import cu.alain.chat.db.OutboxMsg
import cu.alain.chat.db.PendingMsg
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
    data object Setup : AppState()   // hay sesión pero falta la frase E2EE
    data object Unlock : AppState()  // hay frase guardada pero está bloqueada
    data object Main : AppState()
}

/**
 * Motor del chat: único dueño de la rootKey (solo en RAM), del pipeline
 * enviar→outbox→ack y del sondeo recibir→descifrar→Room.
 *
 * Hilos: los métodos suspend se llaman desde ViewModels/Workers; el polling
 * de primer plano vive en [scope] y se enciende/apaga desde MainActivity.
 */
class ChatEngine(
    private val db: AppDatabase,
    private val store: SessionStore,
    private val api: cu.alain.chat.net.Api,
    /** Contacto guardado (MVP 1-a-1): para atribuir ecos de mensajes propios enviados desde otro dispositivo. */
    private val savedPeer: () -> String?,
    private val onNewMessages: (Int) -> Unit,
) {

    @Volatile
    private var rootKey: ByteArray? = null

    private val _state = MutableStateFlow<AppState>(AppState.Login)
    val state: StateFlow<AppState> = _state

    /** Contador de mensajes nuevos para el badge/aviso (se consume en la UI). */
    val lastSyncResult = MutableStateFlow(0)

    private var pollJob: Job? = null
    private var pollInterval = C.POLL_ACTIVE_MS
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ────────────────────────── estado / sesión ──────────────────────────

    fun refreshState() {
        _state.value = when {
            store.session == null -> AppState.Login
            store.kx == null -> AppState.Setup
            rootKey == null -> AppState.Unlock
            else -> AppState.Main
        }
    }

    val session get() = store.session
    fun unlocked() = rootKey != null

    /** Login/registro correcto: guarda la sesión cifrada por Keystore. */
    fun onAuthed(uid: String, uname: String, token: String, did: String) {
        store.saveSession(cu.alain.chat.vault.Session(token, uid, uname, did))
        refreshState()
        // puede haber mensajes pendientes del worker de fondo ya guardados en bruto
        if (rootKey != null) scope.launch { drainPending() }
    }

    /** Primera configuración de la frase E2EE (tras registro, o login sin kx). */
    fun setupE2ee(passphrase: CharArray) {
        val salt = Crypto.newSalt16()
        val root = Crypto.deriveRootKey(passphrase, salt)
        store.saveKx(salt, Crypto.checkValue(root))
        rootKey = root
        refreshState()
        scope.launch { drainPending() }
    }

    /** Desbloqueo con la frase: re-deriva y valida contra el check local (D36). */
    fun unlock(passphrase: CharArray): Boolean {
        val kx = store.kx ?: return false
        val root = Crypto.deriveRootKey(passphrase, b64d(kx.saltB64))
        val ok = Crypto.constEq(Crypto.checkValue(root), b64d(kx.checkB64))
        if (ok) {
            rootKey = root
            refreshState()
            scope.launch { drainPending() }
        } else {
            Crypto.zero(root)
        }
        return ok
    }

    /** Bloquear YA (botón o salir de la app): la rootKey se borra de RAM. */
    fun lock() {
        Crypto.zero(rootKey)
        rootKey = null
        stopPolling()
        refreshState()
    }

    /** Cerrar sesión: revoca en el servidor (best-effort), borra sesión, frase y datos. */
    fun logout(apiLogout: Boolean) {
        val s = store.session
        if (apiLogout && s != null) {
            scope.launch { try { api.logout(s.token) } catch (_: Exception) {} }
        }
        Crypto.zero(rootKey)
        rootKey = null
        stopPolling()
        store.clearSession()
        store.clearKx()
        scope.launch {
            db.msgDao().wipe(); db.outboxDao().wipe(); db.pendingDao().wipe()
            db.metaDao().setLong("last_seq", 0)
        }
        refreshState()
    }

    // ────────────────────────── envío ──────────────────────────

    /**
     * Cifra y encola un mensaje. Devuelve Ok(msgId) para la UI inmediata
     * (burbuja "enviando") o Err con mensaje en español.
     */
    suspend fun send(peer: String, text: String): Result<String> {
        val s = store.session ?: return Result.failure(IllegalStateException("sin sesión"))
        val root = rootKey ?: return Result.failure(IllegalStateException("bloqueado"))
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("vacío"))

        val msgId = Crypto.uuid7()
        val ts = System.currentTimeMillis()
        val wire = Crypto.frame(text)
        val convKey = Crypto.convKey(root, s.uname, peer)
        val mk = Crypto.msgKey(convKey, msgId)
        val aad = Crypto.aad(msgId, s.uid, s.uname, peer)
        val (nonce, ct) = Crypto.seal(mk, wire, aad)

        if (ct.size < C.MIN_CT || ct.size > C.MAX_CT) {
            return Result.failure(IllegalArgumentException("El mensaje es demasiado largo"))
        }

        db.msgDao().insert(Message(msgId, peer, s.uname, mine = true, body = text, ts = ts, state = C.ST_SENDING))
        db.outboxDao().insert(OutboxMsg(msgId, peer, b64(ct), b64(nonce), ts))
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
        all.groupBy { it.peerUname }.forEach { (peer, msgs) ->
            msgs.chunked(C.MAX_BATCH).forEach { chunk ->
                val payload = chunk.map { arrayOf<Any>(it.msgId, it.ctB64, it.nonceB64, it.ts) }
                try {
                    val ok = api.outbox(s.token, peer, payload)
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
                            // permanente: destinatario inválido, mensaje corrupto…
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
     * Devuelve nº de mensajes nuevos, o -1 si hubo error de red/auth.
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
     * Procesa una fila del sync. Si está bloqueado, la archiva cifrada en
     * `pending` para descifrarla al desbloquear. Devuelve true si es nueva.
     */
    private suspend fun ingest(row: SyncRow, s: cu.alain.chat.vault.Session): Boolean {
        if (db.msgDao().exists(row.msgId)) return false // eco de un mensaje propio ya en Room
        val root = rootKey
        if (root == null) {
            db.pendingDao().insert(
                PendingMsg(row.msgId, row.senderUname, row.senderId, row.nonceB64, row.ctB64, row.ts, row.seq)
            )
            return true
        }
        val msg = decryptRow(row, s, root)
        if (msg != null) db.msgDao().insert(msg)
        return true
    }

    private suspend fun decryptRow(
        row: SyncRow,
        s: cu.alain.chat.vault.Session,
        root: ByteArray,
    ): Message? {
        val mine = row.senderId.equals(s.uid, ignoreCase = true)
        // Eco propio desde OTRO dispositivo: el sync no dice el destinatario;
        // en el MVP de contacto único se atribuye al peer guardado. Si no hay
        // peer guardado se descarta (last_seq ya avanzó: no se re-descarga).
        val peer = when {
            !mine -> row.senderUname
            else -> savedPeer() ?: return null
        }
        return try {
            val convKey = Crypto.convKey(root, s.uname, row.senderUname)
            val mk = Crypto.msgKey(convKey, row.msgId)
            val wire = Crypto.open(mk, b64d(row.nonceB64), b64d(row.ctB64), Crypto.aad(row.msgId, row.senderId, s.uname, row.senderUname))
            val body = if (wire != null) (Crypto.unframe(wire) ?: "[mensaje con formato desconocido]") else "[no se pudo descifrar]"
            Message(
                msgId = row.msgId,
                peerUname = peer,
                senderUname = row.senderUname,
                mine = mine,
                body = body,
                ts = row.ts,
                state = if (wire != null) C.ST_RECEIVED else C.ST_UNREADABLE,
                seq = row.seq,
            )
        } catch (_: Exception) {
            Message(row.msgId, peer, row.senderUname, mine, "[error]", row.ts, C.ST_UNREADABLE, row.seq)
        }
    }

    /** Descifra todo lo acumulado en pending (tras unlock/setup). */
    private suspend fun drainPending() {
        val s = store.session ?: return
        val root = rootKey ?: return
        val rows = db.pendingDao().all()
        if (rows.isEmpty()) return
        val ids = mutableListOf<String>()
        for (p in rows) {
            if (!db.msgDao().exists(p.msgId)) {
                val msg = decryptRow(
                    SyncRow(p.msgId, p.senderId, p.seq, p.nonceB64, p.ctB64, p.senderUname, p.ts),
                    s, root,
                )
                if (msg != null) db.msgDao().insert(msg)
            }
            ids.add(p.msgId)
        }
        db.pendingDao().delete(ids)
        if (ids.isNotEmpty()) { lastSyncResult.value = ids.size; onNewMessages(ids.size) }
    }

    // ────────────────────────── polling adaptativo (D15) ──────────────────────────

    /** Resetea el intervalo (hay actividad: envío, mensaje nuevo, FCM…). */
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
