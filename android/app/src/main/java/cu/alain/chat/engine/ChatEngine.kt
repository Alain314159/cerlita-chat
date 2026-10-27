package cu.alain.chat.engine

import android.util.Base64
import cu.alain.chat.core.C
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.db.Message
import cu.alain.chat.db.OutboxMsg
import cu.alain.chat.db.PendingMsg
import cu.alain.chat.db.Profile
import cu.alain.chat.db.Room
import cu.alain.chat.db.RoomPeer
import cu.alain.chat.net.ApiException
import cu.alain.chat.net.SyncRow
import cu.alain.chat.profile.Photos
import cu.alain.chat.profile.ProfileMsg
import cu.alain.chat.profile.ReadReceipt
import cu.alain.chat.profile.RoomInfoMsg
import cu.alain.chat.vault.Session
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
import kotlinx.coroutines.sync.withLock
import java.io.File
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
 * Mensaje nuevo ya descifrado y legible — lo que el Notificador necesita para
 * el MessagingStyle (D63). Los ecos propios (mine) llegan por aquí también:
 * decidir si avisan es política del Notifier, no del motor.
 */
data class NewMsg(
    val roomId: String,
    val senderUid: String,
    val senderUname: String,
    val body: String,
    val ts: Long,
    val mine: Boolean,
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
    private val avatarsDir: File,
    private val onNewMessages: (List<NewMsg>) -> Unit,
    /** D72: avisa de login/logout para encender/apagar el timbre (lo cablea ChatApp). */
    private val onSessionChanged: () -> Unit = {},
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

    /**
     * true mientras la Activity está visible (lo fija MainActivity). Con la app
     * ABIERTA el polling nunca pasa de [C.POLL_FG_MAX_MS] (D71): aunque el timbre
     * esté muerto (OEM lo mató), un mensaje tarda ≤ 15 s en llegar con la sala
     * abierta. En background manda el timbre y el tope vuelve a 5 min.
     */
    @Volatile
    var inForeground: Boolean = false

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

    /** Código de invitación reconstruido de la clave del vault (ajustes de sala):
     *  con él un miembro nuevo entra, o este teléfono recupera el acceso. */
    fun getInviteCode(roomId: String): String? {
        val ks = keys[roomId.lowercase()] ?: return null
        return Crypto.encodeInvite(ks.second, ks.first)
    }

    /** Id de archivo de la foto de sala: avatars/room-<roomId>.jpg (D69). */
    private fun roomPhotoId(roomId: String) = "room-${roomId.lowercase()}"

    /** Login/registro correcto: guarda la sesión cifrada por Keystore y refresca salas. */
    fun onAuthed(uid: String, uname: String, token: String, did: String) {
        store.saveSession(cu.alain.chat.vault.Session(token, uid, uname, did))
        loadKeys()
        refreshState()
        onSessionChanged() // D72: enciende el timbre sin esperar al próximo onStart
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
            val finalName = name.trim().ifBlank { "Sala ${roomId.take(8)}" }
            val room = Room(
                roomId = roomId,
                name = finalName,
                owner = true,
                sas = sas,
                createdAt = System.currentTimeMillis(),
            )
            db.roomDao().upsert(room)
            store.putRoomKey(roomId, roomKey, roomSalt)
            keys = keys + (roomId to (roomKey to roomSalt))
            _keysVersion.value += 1
            // nombre COMPARTIDO (D69): SIEMPRE se anuncia — quien entre con el
            // código hereda por el historial el nombre real de la sala, aunque
            // no escriba ninguno (bug 5 de la v0.6.0)
            val ts = System.currentTimeMillis()
            sendControl(roomId, RoomInfoMsg.encode(finalName, null, ts), C.KIND_ROOM_INFO, finalName)
            db.metaDao().setLong("ri_${roomId.lowercase()}", ts)
            // anunciar mi perfil: los que entren después lo verán en el historial (D61)
            db.profileDao().get(s.uid)?.let { announceProfileTo(roomId, it); poke() }
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
            // presentarme a los que ya están en la sala (D61)
            db.profileDao().get(s.uid)?.let { announceProfileTo(roomId, it); poke() }
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
        // re-presentarme: los presentes no vieron nada de este teléfono (D61)
        store.session?.let { s ->
            db.profileDao().get(s.uid)?.let { announceProfileTo(roomId, it); poke() }
        }
        return Result.success(roomId)
    }

    // ────────────────────────── ajustes de sala (v0.6.0, D69/D70) ──────────────────────────

    /**
     * Cambia el nombre y/o la foto de la sala. Viaja E2EE como mensaje de
     * control kind=2 (latest-ts-wins): el otro miembro lo recibe y su sala se
     * renombra sola. foto: bytes nuevos | null+keepPhoto=true (no tocar) |
     * null+false (quitar). Sin clave en este teléfono: solo renombra local.
     */
    suspend fun setRoomInfo(
        roomId: String,
        name: String,
        photoBytes: ByteArray?,
        keepPhoto: Boolean,
    ): Result<Unit> {
        val n = name.trim()
        if (n.isEmpty() || n.length > RoomInfoMsg.MAX_NAME) {
            return Result.failure(IllegalArgumentException("El nombre debe tener entre 1 y ${RoomInfoMsg.MAX_NAME} caracteres"))
        }
        val cur = db.roomDao().get(roomId)
            ?: return Result.failure(IllegalStateException("La sala ya no existe en este teléfono"))
        if (!hasKey(roomId)) { // sala sin clave (2º dispositivo sin restaurar): local
            db.roomDao().rename(roomId, n)
            return Result.success(Unit)
        }
        val ts = System.currentTimeMillis()
        // foto para el mensaje: nueva | la existente (keep) | ninguna (quitar)
        val photoB64: String? = when {
            photoBytes != null -> Photos.bytesToB64(photoBytes)
            keepPhoto -> Photos.photoFile(avatarsDir, roomPhotoId(roomId)).takeIf { it.exists() }
                ?.readBytes()?.let { Photos.bytesToB64(it) }
            else -> null
        }
        val sent = sendControl(roomId, RoomInfoMsg.encode(n, photoB64, ts), C.KIND_ROOM_INFO, n)
        // aplicar localmente (el eco del servidor llegará y el guard ts lo saltará)
        when {
            photoBytes != null -> Photos.saveProfilePhoto(avatarsDir, roomPhotoId(roomId), photoBytes)
            !keepPhoto -> Photos.saveProfilePhoto(avatarsDir, roomPhotoId(roomId), null)
        }
        val photoNow = when {
            photoBytes != null -> true
            keepPhoto -> cur.photo
            else -> false
        }
        db.roomDao().upsert(cur.copy(name = n, photo = photoNow))
        db.metaDao().setLong("ri_${roomId.lowercase()}", ts)
        if (sent) poke()
        return Result.success(Unit)
    }

    /** Aplica un room-info descifrado: gana el ts mayor (como los perfiles, D61). */
    private suspend fun applyRoomInfo(roomId: String, p: RoomInfoMsg.Parsed) {
        try {
            val mkey = "ri_${roomId.lowercase()}"
            val known = db.metaDao().getLong(mkey) ?: 0L
            if (p.ts <= known) return
            val photoBytes = p.photoB64?.let { Photos.b64ToBytes(it) }
            Photos.saveProfilePhoto(avatarsDir, roomPhotoId(roomId), photoBytes) // null → borra
            db.roomDao().get(roomId)?.let {
                db.roomDao().upsert(it.copy(name = p.name, photo = photoBytes != null))
            }
            db.metaDao().setLong(mkey, p.ts)
        } catch (_: Exception) {
            // un anuncio corrupto jamás rompe la sincronización
        }
    }

    /** Vaciar el historial local de una sala (los mensajes del servidor no se tocan). */
    suspend fun clearHistory(roomId: String) {
        db.msgDao().clearForRoom(roomId)
        db.roomDao().resetUnread(roomId)
    }

    /**
     * Salir de la sala y borrar TODO lo local (D70). El leave del servidor DEBE
     * salir bien antes de borrar: si falla (sin red) no se toca nada — si no,
     * la sala reaparecería vacía en el próximo sync de salas. Si era el último
     * miembro, el servidor borra la sala entera (historial incluido).
     */
    suspend fun deleteRoom(roomId: String): Result<Unit> {
        val s = store.session ?: return Result.failure(IllegalStateException("sin sesión"))
        return try {
            api.roomLeave(s.token, roomId)
            keys[roomId.lowercase()]?.let { Crypto.zero(it.first); Crypto.zero(it.second) }
            keys = keys - roomId.lowercase()
            _keysVersion.value += 1
            store.removeRoomKey(roomId)
            db.roomDao().delete(roomId)
            db.msgDao().clearForRoom(roomId)
            db.outboxDao().deleteForRoom(roomId)
            db.pendingDao().deleteForRoom(roomId)
            db.roomPeerDao().deleteForRoom(roomId)
            Photos.saveProfilePhoto(avatarsDir, roomPhotoId(roomId), null)
            db.metaDao().delete("ri_${roomId.lowercase()}")
            db.metaDao().delete("rr_${roomId.lowercase()}")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ────────────────────────── recibos de lectura (D68) ──────────────────────────

    /**
     * Enviar recibo de lectura si hay mensajes del par sin acusar (lo llama la
     * ChatScreen al abrir y ante cada mensaje nuevo con la sala visible).
     * u = seq global máximo que tengo → el par marca ✓✓ azul todo lo suyo ≤ u.
     */
    suspend fun maybeSendReadReceipt(roomId: String) {
        if (!hasKey(roomId)) return
        val lastPeer = db.msgDao().maxPeerSeq(roomId)
        if (lastPeer <= 0) return                       // nada del par que acusar
        val mkey = "rr_${roomId.lowercase()}"
        val lastSent = db.metaDao().getLong(mkey) ?: 0L
        if (lastPeer <= lastSent) return                // ya acusé todo esto
        val u = db.msgDao().maxSeq(roomId)              // "vi todo hasta aquí"
        if (u <= lastSent) return
        if (sendControl(roomId, ReadReceipt.encode(u, System.currentTimeMillis()), C.KIND_READ, "")) {
            db.metaDao().setLong(mkey, u)
            poke()
        }
    }

    /** Llegó el recibo del par: marca leídos mis mensajes con seq ≤ u. */
    private suspend fun applyReadReceipt(roomId: String, upToSeq: Long) {
        try {
            db.msgDao().markReadUpTo(roomId, upToSeq, listOf(C.ST_SENT, C.ST_DELIVERED), C.ST_READ)
        } catch (_: Exception) {
        }
    }

    /** Encola un mensaje de control cifrado (kind≠0, invisible en el chat). */
    private suspend fun sendControl(roomId: String, plain: String, kind: Int, listBody: String): Boolean {
        val s = store.session ?: return false
        val key = keys[roomId.lowercase()] ?: return false
        val msgId = Crypto.uuid7()
        val ts = System.currentTimeMillis()
        val convKey = Crypto.convKey(key.first, roomId)
        val mk = Crypto.msgKey(convKey, msgId)
        val aad = Crypto.aad(msgId, s.uid, roomId)
        val (nonce, ct) = Crypto.seal(mk, Crypto.frame(plain), aad)
        if (ct.size < C.MIN_CT || ct.size > C.MAX_CT) return false
        db.msgDao().insert(
            Message(msgId, roomId, s.uname, mine = true, body = listBody, ts = ts,
                state = C.ST_SENDING, kind = kind, senderUid = s.uid)
        )
        db.outboxDao().insert(OutboxMsg(msgId, roomId, b64(ct), b64(nonce), ts))
        return true
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
        onSessionChanged() // D72: apaga el timbre (in-app y FGS) ya mismo
        scope.launch {
            db.roomDao().wipe(); db.msgDao().wipe(); db.outboxDao().wipe(); db.pendingDao().wipe()
            db.profileDao().wipe(); db.roomPeerDao().wipe()   // perfiles + pares (D61)
            avatarsDir.deleteRecursively()                     // fotos propias, de pares y de salas
            db.metaDao().setLong("last_seq", 0)
            db.metaDao().deleteByPrefix("ri_")                 // ts de room-info (D69)
            db.metaDao().deleteByPrefix("rr_")                 // últimos recibos de lectura (D68)
        }
        refreshState()
    }

    // ────────────────────────── perfiles (D61) ──────────────────────────

    /**
     * Guarda el perfil propio y lo anuncia cifrado a todas las salas con
     * clave. foto: bytes nuevos | null+keepPhoto=true (no tocar) | null+false (quitar).
     */
    suspend fun saveProfile(
        displayName: String,
        avatarJson: String,
        photoBytes: ByteArray?,
        keepPhoto: Boolean = false,
    ): Result<Unit> {
        val s = store.session ?: return Result.failure(IllegalStateException("sin sesión"))
        val name = displayName.trim()
        if (name.isEmpty() || name.length > ProfileMsg.MAX_NAME) {
            return Result.failure(IllegalArgumentException("El nombre debe tener entre 1 y ${ProfileMsg.MAX_NAME} caracteres"))
        }
        val ts = System.currentTimeMillis()
        val photoNow: Boolean
        if (photoBytes != null) {
            Photos.saveProfilePhoto(avatarsDir, s.uid, photoBytes)
            photoNow = true
        } else if (!keepPhoto) {
            Photos.saveProfilePhoto(avatarsDir, s.uid, null)
            photoNow = false
        } else {
            photoNow = Photos.photoFile(avatarsDir, s.uid).exists()
        }
        db.profileDao().upsert(
            Profile(s.uid, name, avatarJson.take(ProfileMsg.MAX_JSON), photoNow, ts)
        )
        broadcastProfile()
        return Result.success(Unit)
    }

    /** Anuncia el perfil propio a todas las salas con clave (tras editarlo). */
    private suspend fun broadcastProfile() {
        val s = store.session ?: return
        val p = db.profileDao().get(s.uid) ?: return
        var sent = false
        db.roomDao().all().forEach { room ->
            if (hasKey(room.roomId)) { announceProfileTo(room.roomId, p); sent = true }
        }
        if (sent) poke()
    }

    /** Anuncia a salas concretas (p. ej. donde apareció un par desconocido). */
    private suspend fun announceToRooms(roomIds: Collection<String>) {
        val s = store.session ?: return
        val p = db.profileDao().get(s.uid) ?: return
        var sent = false
        roomIds.forEach { rid ->
            if (hasKey(rid)) { announceProfileTo(rid, p); sent = true }
        }
        if (sent) poke()
    }

    /**
     * Encola el anuncio del perfil a una sala: un mensaje cifrado MÁS (kind=1,
     * invisible en el chat). Si con foto no cabe en el presupuesto del servidor,
     * reintenta sin foto — el nombre y el avatar nunca se sacrifican.
     */
    private suspend fun announceProfileTo(roomId: String, p: Profile) {
        val s = store.session ?: return
        val key = keys[roomId.lowercase()] ?: return
        val photoB64: String? = if (p.photo) {
            Photos.photoFile(avatarsDir, s.uid).takeIf { it.exists() }?.readBytes()
                ?.let { Photos.bytesToB64(it) }
        } else null

        val msgId = Crypto.uuid7()
        val ts = System.currentTimeMillis()
        val convKey = Crypto.convKey(key.first, roomId)
        val mk = Crypto.msgKey(convKey, msgId)
        val aad = Crypto.aad(msgId, s.uid, roomId)

        var (nonce, ct) = Crypto.seal(mk, Crypto.frame(ProfileMsg.encode(p.displayName, p.avatarJson, photoB64, p.updatedAt)), aad)
        if (ct.size > C.MAX_CT) {
            // foto fuera de presupuesto: anuncio sin foto (no debería ocurrir: Photos degrada)
            val (n2, c2) = Crypto.seal(mk, Crypto.frame(ProfileMsg.encode(p.displayName, p.avatarJson, null, p.updatedAt)), aad)
            nonce = n2; ct = c2
        }
        if (ct.size < C.MIN_CT || ct.size > C.MAX_CT) return

        db.msgDao().insert(
            Message(
                msgId, roomId, s.uname, mine = true, body = p.displayName, ts = ts,
                state = C.ST_SENDING, kind = 1, senderUid = s.uid,
            )
        )
        db.outboxDao().insert(OutboxMsg(msgId, roomId, b64(ct), b64(nonce), ts))
    }

    /**
     * Aplica un anuncio descifrado: la versión (ts) mayor gana. El eco propio
     * en un teléfono NUEVO restaura el perfil desde el historial; el eco en el
     * teléfono que lo envió no pasa del guard de exists().
     */
    private suspend fun applyProfile(uid: String, p: ProfileMsg.Parsed) {
        try {
            val existing = db.profileDao().get(uid)
            if (existing != null && existing.updatedAt >= p.ts) return
            val photoBytes = p.photoB64?.let { Photos.b64ToBytes(it) }
            Photos.saveProfilePhoto(avatarsDir, uid, photoBytes) // null → borra foto vieja
            db.profileDao().upsert(
                Profile(uid, p.name, p.avatarJson, photoBytes != null, p.ts)
            )
        } catch (_: Exception) {
            // un anuncio corrupto jamás rompe la sincronización
        }
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

        db.msgDao().insert(
            Message(
                msgId, roomId, s.uname, mine = true, body = text, ts = ts,
                state = C.ST_SENDING, senderUid = s.uid,
            )
        )
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

    /** Serializa los sync: el timbre, el polling, el worker y la UI pueden
     * dispararlos a la vez y el cursor (last_seq) avanzaría dos veces. */
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Un ciclo de sync: baja todo lo nuevo (hasta 204), descifra y guarda.
     * Devuelve nº de mensajes nuevos legibles, o -1/-2 si hubo error de red/auth.
     * Las notificaciones salen por [onNewMessages] (misma vía para timbre,
     * polling y worker — una sola política de aviso, D63).
     */
    suspend fun syncOnce(): Int = syncMutex.withLock {
        val s = store.session ?: return -1
        var lastSeq = db.metaDao().getLong("last_seq") ?: 0
        val fresh = mutableListOf<NewMsg>()
        val newPeerRooms = mutableSetOf<String>()
        try {
            while (true) {
                val resp = api.sync(s.token, lastSeq)
                // recibos de entrega (D68): lo que otros ya bajaron en mis salas.
                // Se aplica en CADA página (también en la 200-vacía-con-rd).
                resp.rd.forEach { (rid, seq) ->
                    try { db.msgDao().markDeliveredUpTo(rid, seq, C.ST_SENT, C.ST_DELIVERED) } catch (_: Exception) {}
                }
                if (resp.rows.isEmpty()) break
                for (row in resp.rows) {
                    ingest(row, s, newPeerRooms)?.let { fresh.add(it) }
                }
                lastSeq = resp.nextSeq
                db.metaDao().setLong("last_seq", lastSeq)
                if (resp.rows.size < C.SYNC_LIMIT) break
            }
            // pares desconocidos vistos → presentarme en esas salas (D61:
            // con last_seq global no pueden verme mensajes previos a su join)
            if (newPeerRooms.isNotEmpty()) announceToRooms(newPeerRooms)
            if (fresh.isNotEmpty()) {
                lastSyncResult.value = fresh.size
                onNewMessages(fresh)
            }
            fresh.size
        } catch (e: ApiException) {
            if (e.http == 401) -2 else -1
        }
    }

    /**
     * Procesa una fila del sync. Devuelve el mensaje NUEVO legible (kind=0)
     * para notificar, o null si es eco conocido / va a pending / es evento de
     * perfil (los anuncios no se notifican ni aparecen en el chat).
     */
    private suspend fun ingest(
        row: SyncRow,
        s: Session,
        newPeerRooms: MutableSet<String>,
    ): NewMsg? {
        if (db.msgDao().exists(row.msgId)) {
            // eco de un mensaje propio ya en Room. El seq del SERVIDOR nace
            // aquí (el insert local fue ANTES del envío): guardarlo para que
            // los recibos ✓✓ (markDeliveredUpTo/markReadUpTo) lo encuentren.
            // No se toca state/body: los locales mandan (ST_SENT/DELIVERED/READ).
            if (row.seq != null && row.senderId.equals(s.uid, ignoreCase = true)) {
                try { db.msgDao().fillSeq(row.msgId, row.seq) } catch (_: Exception) {}
            }
            return null
        }
        // par visto en la sala → lista de chats con SU nombre/avatar (D61).
        // Si es la 1ª vez: me presento (los mensajes previos a mi join no los vi).
        if (!row.senderId.equals(s.uid, ignoreCase = true) &&
            db.roomPeerDao().insertIfMissing(RoomPeer(row.roomId, row.senderId)) != -1L
        ) {
            newPeerRooms.add(row.roomId)
        }
        val key = keys[row.roomId.lowercase()]
        if (key == null) {
            // sala sin clave en este teléfono (2º dispositivo, clave perdida):
            // se archiva cifrada; al restaurar el código de invitación se drena.
            db.pendingDao().insert(
                PendingMsg(row.msgId, row.roomId, row.senderUname, row.senderId, row.nonceB64, row.ctB64, row.ts, row.seq)
            )
            return null
        }
        val msg = decryptRow(row, s, key.first)
        db.msgDao().insert(msg)
        // badge de no leídos (v0.6.0): mensaje de texto del par sin la sala abierta
        if (msg.kind == C.KIND_TEXT && !msg.mine) {
            try { db.roomDao().bumpUnread(msg.roomId) } catch (_: Exception) {}
        }
        return if (msg.kind == C.KIND_TEXT) NewMsg(
            msg.roomId, msg.senderUid, msg.senderUname, msg.body, msg.ts, msg.mine,
        ) else null
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
            if (wire == null) {
                Message(row.msgId, row.roomId, row.senderUname, mine, "[no se pudo descifrar]", row.ts, C.ST_UNREADABLE, row.seq, 0, row.senderId)
            } else {
                val body = Crypto.unframe(wire)
                    ?: return Message(row.msgId, row.roomId, row.senderUname, mine, "[mensaje con formato desconocido]", row.ts, C.ST_UNREADABLE, row.seq, 0, row.senderId)
                // anuncio de perfil (D61): actualiza el perfil del emisor (o el
                // propio, en un teléfono nuevo) y NO se muestra en el chat
                if (ProfileMsg.isProfile(body)) {
                    val p = ProfileMsg.parse(body)
                    if (p != null) {
                        applyProfile(row.senderId, p)
                        return Message(row.msgId, row.roomId, row.senderUname, mine, p.name, row.ts, C.ST_RECEIVED, row.seq, C.KIND_PROFILE, row.senderId)
                    }
                }
                // info de sala (D69): nombre/foto compartidos — invisible en el chat
                if (RoomInfoMsg.isRoomInfo(body)) {
                    val p = RoomInfoMsg.parse(body)
                    if (p != null) {
                        applyRoomInfo(row.roomId, p)
                        return Message(row.msgId, row.roomId, row.senderUname, mine, p.name, row.ts, C.ST_RECEIVED, row.seq, C.KIND_ROOM_INFO, row.senderId)
                    }
                }
                // recibo de lectura (D68): el par leyó hasta u → mis ✓✓ azules.
                // El eco propio no hace nada (ya está aplicado localmente).
                if (ReadReceipt.isRead(body)) {
                    val p = ReadReceipt.parse(body)
                    if (p != null) {
                        if (!mine) applyReadReceipt(row.roomId, p.upToSeq)
                        return Message(row.msgId, row.roomId, row.senderUname, mine, "", row.ts, C.ST_RECEIVED, row.seq, C.KIND_READ, row.senderId)
                    }
                }
                Message(row.msgId, row.roomId, row.senderUname, mine, body, row.ts, C.ST_RECEIVED, row.seq, C.KIND_TEXT, row.senderId)
            }
        } catch (_: Exception) {
            Message(row.msgId, row.roomId, row.senderUname, mine, "[error]", row.ts, C.ST_UNREADABLE, row.seq, 0, row.senderId)
        }
    }

    /** Descifra todo lo acumulado en pending (tras añadir una clave de sala). */
    private suspend fun drainPending() {
        val s = store.session ?: return
        val fresh = mutableListOf<NewMsg>()
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
                    if (msg.kind == 0) fresh.add(NewMsg(msg.roomId, msg.senderUid, msg.senderUname, msg.body, msg.ts, msg.mine))
                } else {
                    continue // sigue sin clave: permanece en pending
                }
            }
            ids.add(p.msgId)
        }
        db.pendingDao().delete(ids)
        if (fresh.isNotEmpty()) { lastSyncResult.value = fresh.size; onNewMessages(fresh) }
    }

    /** Descifra los pendientes de UNA sala (tras join/restore de su clave). */
    private suspend fun drainPendingForRoom(roomId: String) {
        val s = store.session ?: return
        val key = keys[roomId.lowercase()] ?: return
        val fresh = mutableListOf<NewMsg>()
        val rows = db.pendingDao().forRoom(roomId)
        val ids = mutableListOf<String>()
        for (p in rows) {
            if (!db.msgDao().exists(p.msgId)) {
                val msg = decryptRow(
                    SyncRow(p.msgId, p.roomId, p.senderId, p.seq, p.nonceB64, p.ctB64, p.senderUname, p.ts),
                    s, key.first,
                )
                db.msgDao().insert(msg)
                if (msg.kind == 0) fresh.add(NewMsg(msg.roomId, msg.senderUid, msg.senderUname, msg.body, msg.ts, msg.mine))
            }
            ids.add(p.msgId)
        }
        db.pendingDao().delete(ids)
        if (fresh.isNotEmpty()) { lastSyncResult.value = fresh.size; onNewMessages(fresh) }
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
                // D71: con la app abierta el sondeo nunca duerme más de 15 s —
                // el timbre debería hacerlo instantáneo, pero si el OEM lo mató
                // esto acota el daño sin quemar batería en background.
                val cap = if (inForeground) C.POLL_FG_MAX_MS else C.POLL_IDLE_MAX_MS
                pollInterval = when {
                    n > 0 -> C.POLL_ACTIVE_MS                    // hay actividad → rápido
                    n == -2 -> C.POLL_ACTIVE_MS                  // auth perdida: reintenta corto
                    else -> min((pollInterval * C.POLL_BACKOFF).toLong(), cap)
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
