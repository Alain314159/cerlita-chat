package cu.alain.chat.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─────────────────────────── entidades (v2: modelo rooms, D54) ───────────────────────────

/** Sala local: metadatos. La roomKey NO vive aquí (vault sellado por Keystore). */
@Entity(tableName = "rooms")
data class Room(
    @PrimaryKey val roomId: String,
    val name: String,
    val owner: Boolean,
    val sas: String,            // 6 dígitos, se muestra en la sala (D43)
    val createdAt: Long,
    val unread: Int = 0,        // mensajes del par sin leer (badge, v0.6.0)
    val photo: Boolean = false, // foto de sala E2EE en avatars/room-<id>.jpg (D69)
    val kind: Int = 0,          // 0=grupo · 1=chat directo (v0.7.0, D74)
    val peerUid: String? = null, // en DM: uid del otro (nombre/avatar vienen de su perfil)
)

/** Mensaje descifrado (o marcado ilegible) para la UI. */
@Entity(
    tableName = "messages",
    // B3 (auditoría): sin índices TODAS las queries de la UI eran escaneo
    // completo de tabla — y al ser Flow, cada INSERT las re-disparaba.
    // Con ~500 mensajes empieza el jank; con 5 000 es insostenible.
    indices = [
        Index(value = ["roomId", "kind", "ts", "msgId"], name = "idx_msg_room_kind_ts"),
        Index(value = ["roomId", "mine"], name = "idx_msg_room_mine"),
        Index(value = ["state"], name = "idx_msg_state"),
    ],
)
data class Message(
    @PrimaryKey val msgId: String,
    val roomId: String,         // sala a la que pertenece (emisor o receptor)
    val senderUname: String,
    val mine: Boolean,
    val body: String,
    val ts: Long,
    val state: Int,             // C.ST_*: sending/sent/failed/received/unreadable
    val seq: Long? = null,
    val kind: Int = 0,          // 0=texto · 1=evento de perfil (D61; invisible en el chat)
    val senderUid: String = "", // uid del emisor (perfiles/avatares; "" en filas pre-v3)
    /** S2/D110: msgId del mensaje citado (responder). Viaja E2EE DENTRO del
     *  plaintext (prefijo \u0000Q) — el servidor sigue sin ver relaciones. */
    val replyTo: String? = null,
)

/** Mensaje pendiente de enviar (persiste el ciphertext hasta el ack del servidor). */
@Entity(tableName = "outbox")
data class OutboxMsg(
    @PrimaryKey val msgId: String,
    val roomId: String,
    val ctB64: String,
    val nonceB64: String,
    val ts: Long,
)

/**
 * Fila cruda recibida por sync de una sala SIN clave en este teléfono (p. ej.
 * segundo dispositivo antes de re-introducir el código de invitación): se
 * guarda cifrada tal cual y se descifra en cuanto llegue la roomKey.
 */
@Entity(tableName = "pending")
data class PendingMsg(
    @PrimaryKey val msgId: String,
    val roomId: String,
    val senderUname: String,
    val senderId: String,
    val nonceB64: String,
    val ctB64: String,
    val ts: Long,
    val seq: Long,
)

/** Clave-valor interna (last_seq, etc.). */
@Entity(tableName = "meta")
data class MetaRow(@PrimaryKey val k: String, val v: String)

// ───────────── entidades v3 (perfiles, D61) ─────────────

/**
 * Perfil de un usuario (el mío y el de los pares que anunciaron en mis salas).
 * El avatar es UNA de dos cosas, con prioridad foto:
 *  - photo=1  → archivo local avatars/<uid>.jpg (llegó E2EE dentro del anuncio)
 *  - avatarJson → configuración del avatar por capas (base/ropa/accesorios)
 * Llega SIEMPRE cifrado como mensaje kind=1: el servidor jamás ve nombres ni fotos.
 */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey val uid: String,
    val displayName: String,    // 1..40 chars, lo que el usuario quiere que vean
    val avatarJson: String,     // AvatarConfig JSON ("" si no hay avatar)
    val photo: Boolean,         // true → existe avatars/<uid>.jpg
    val updatedAt: Long,        // versión: gana el anuncio con ts mayor
)

/**
 * Quién hemos visto en cada sala (de los mensajes recibidos). Da el par de una
 * sala 1-a-1 para la lista de chats (nombre + avatar del OTRO, no el nombre
 * local de la sala). Se reconstruye solo: el re-sync desde 0 lo repobla.
 */
@Entity(tableName = "room_peer", primaryKeys = ["roomId", "uid"])
data class RoomPeer(
    val roomId: String,
    val uid: String,
)

/**
 * S2/D110: reacción de un usuario a un mensaje — UNA por usuario y mensaje
 * (la nueva pisa la anterior, como WhatsApp). Viaja E2EE como mensaje de
 * control kind=6; el servidor solo ve ciphertext.
 */
@Entity(tableName = "reactions", primaryKeys = ["msgId", "uid"])
data class Reaction(
    val msgId: String,
    val uid: String,
    val emoji: String,
)

// ───────────── entidades v5 (v0.7.0: llamadas) ─────────────

/** Registro LOCAL de llamadas (no se sincroniza: es la historia del teléfono). */
@Entity(tableName = "calls")
data class CallLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: String,
    val peerUid: String,
    val peerName: String,       // nombre del par en el momento (perfil E2EE o @user)
    val kind: Int,              // 0=audio · 1=vídeo
    val dir: Int,               // 0=saliente · 1=entrante
    val status: Int,            // CALL_*: completed/missed/declined/cancelled
    val startedAt: Long,
    val answeredAt: Long = 0,
    val duration: Long = 0,     // segundos (si fue contestada)
)

// ─────────────────────────── DAOs ───────────────────────────

@Dao
interface RoomDao {
    @Query("SELECT * FROM rooms ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Room>>

    @Query("SELECT * FROM rooms ORDER BY createdAt ASC")
    suspend fun all(): List<Room>

    @Query("SELECT * FROM rooms WHERE roomId = :id LIMIT 1")
    suspend fun get(id: String): Room?

    /** Insert o update completo (crear sala / unirme: datos completos en mano). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: Room)

    /** Fusión desde el servidor: NO pisa el nombre local (solo añade las nuevas). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMissing(rooms: List<Room>)

    @Query("DELETE FROM rooms WHERE roomId = :id")
    suspend fun delete(id: String)

    // ── v0.6.0: no leídos y foto de sala ──

    /** +1 mensaje del par sin abrir (badge de la lista de chats). */
    @Query("UPDATE rooms SET unread = unread + 1 WHERE roomId = :id")
    suspend fun bumpUnread(id: String)

    /** Al abrir la sala: contador a cero. */
    @Query("UPDATE rooms SET unread = 0 WHERE roomId = :id")
    suspend fun resetUnread(id: String)

    /** Cambia SOLO el nombre local (renombrar sin foto, o sala sin clave). */
    @Query("UPDATE rooms SET name = :name WHERE roomId = :id")
    suspend fun rename(id: String, name: String)

    @Query("SELECT COUNT(*) FROM rooms")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM rooms")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM rooms")
    suspend fun wipe()
}

@Dao
interface MsgDao {
    @Query("SELECT * FROM messages WHERE roomId = :room AND kind = 0 ORDER BY ts ASC, msgId ASC")
    fun observeChat(room: String): Flow<List<Message>>

    /**
     * S0'/D108 (U2): ventana del final del historial — los últimos `limit`
     * mensajes. Antes observeChat re-emitía TODO el historial con cada tick
     * de estado (✓→✓✓): con 5 000 mensajes cada cambio recomponía la lista
     * entera. Ahora el Flow solo emite la ventana y la UI sube el límite al
     * hacer scroll arriba (carga bajo demanda estilo Telegram). La subquery
     * DESC+LIMIT usa el índice idx_msg_room_kind_ts.
     */
    @Query(
        """SELECT * FROM (
             SELECT * FROM messages WHERE roomId = :room AND kind = 0
             ORDER BY ts DESC, msgId DESC LIMIT :limit
           ) ORDER BY ts ASC, msgId ASC"""
    )
    fun observeChatWindow(room: String, limit: Int): Flow<List<Message>>

    /** ¿Cuántos mensajes tiene la sala en total? (saber si queda historia) */
    @Query("SELECT COUNT(*) FROM messages WHERE roomId = :room AND kind = 0")
    suspend fun countVisible(room: String): Int

    @Query("SELECT * FROM messages WHERE roomId = :room AND kind = 0 ORDER BY ts ASC, msgId ASC")
    suspend fun chat(room: String): List<Message>

    /** Todo el historial TEXTO (el export excluye eventos de perfil, D61). */
    @Query("SELECT * FROM messages WHERE kind = 0 ORDER BY ts ASC")
    suspend fun all(): List<Message>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: Message): Long

    @Query("UPDATE messages SET state = :state WHERE msgId = :id")
    suspend fun setState(id: String, state: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE msgId = :id)")
    suspend fun exists(id: String): Boolean

    /** Body completo de un mensaje (reintento de adjuntos, D76). */
    @Query("SELECT body FROM messages WHERE msgId = :id LIMIT 1")
    suspend fun chatBody(id: String): String?

    /** Subidas de adjuntos huérfanas: «enviando» sin fila en outbox (proceso
     *  muerto a mitad de subida) → FALLIDAS para que el usuario reintente. */
    @Query(
        "UPDATE messages SET state = :failed WHERE state = :sending AND mine = 1 " +
            "AND msgId NOT IN (SELECT msgId FROM outbox)"
    )
    suspend fun failOrphanSending(failed: Int = 2, sending: Int = 0)

    /** Eco propio (D68): guarda el seq del SERVIDOR de un mensaje ya insertado
     *  localmente al enviarlo (el seq nace en el servidor). Sin esto, los ✓✓
     *  de entrega/lectura jamás encontrarían mis mensajes (seq IS NOT NULL). */
    @Query("UPDATE messages SET seq = :seq WHERE msgId = :id AND seq IS NULL")
    suspend fun fillSeq(id: String, seq: Long)

    // ── v0.6.0: recibos de entrega y lectura (D68) ──

    /** Marca ✓✓ entregados mis mensajes enviados con seq ≤ seq (el otro los bajó). */
    @Query(
        "UPDATE messages SET state = :delivered WHERE roomId = :room AND mine = 1 " +
            "AND state = :sent AND seq IS NOT NULL AND seq <= :seq"
    )
    suspend fun markDeliveredUpTo(room: String, seq: Long, sent: Int, delivered: Int)

    /** Marca ✓✓ leídos mis mensajes con seq ≤ seq (recibo E2EE kind=3 del par). */
    @Query(
        "UPDATE messages SET state = :read WHERE roomId = :room AND mine = 1 " +
            "AND state IN (:from) AND seq IS NOT NULL AND seq <= :seq"
    )
    suspend fun markReadUpTo(room: String, seq: Long, from: List<Int>, read: Int)

    /** Seq global máximo de la sala (para el recibo de lectura: "leí hasta aquí"). 0 si nada tiene seq (offline). */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE roomId = :room")
    suspend fun maxSeq(room: String): Long

    /** Último seq de mensajes DEL PAR (¿tengo algo nuevo que acusar lectura?). */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE roomId = :room AND mine = 0")
    suspend fun maxPeerSeq(room: String): Long

    /** Vaciar historial local de una sala (los mensajes del servidor no se tocan). */
    @Query("DELETE FROM messages WHERE roomId = :room")
    suspend fun clearForRoom(room: String)

    // ── S2/D110: editar / borrar / citar ──

    /** Aplica una edición SOLO si el mensaje es del editor y es texto. */
    @Query("UPDATE messages SET body = :text WHERE msgId = :msgId AND senderUid = :sender AND kind = 0")
    suspend fun applyEdit(msgId: String, sender: String, text: String)

    /** Tumba un mensaje borrado (para todos): cuerpo fuera, estado DELETED. */
    @Query("UPDATE messages SET body = '', state = :deleted WHERE msgId = :msgId AND senderUid = :sender AND kind = 0")
    suspend fun applyDelete(msgId: String, sender: String, deleted: Int)

    /** Mensaje citado por id (la burbuja muestra su avance). */
    @Query("SELECT * FROM messages WHERE msgId = :msgId LIMIT 1")
    suspend fun byId(msgId: String): Message?

    // Lista de conversaciones: último mensaje TEXTO por sala
    @Query(
        """SELECT m.* FROM messages m
           INNER JOIN (SELECT roomId AS r, MAX(ts) AS mx FROM messages WHERE kind = 0 GROUP BY roomId) t
           ON m.roomId = t.r AND m.ts = t.mx
           ORDER BY m.ts DESC"""
    )
    fun observeChats(): Flow<List<Message>>

    @Query("DELETE FROM messages")
    suspend fun wipe()
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: OutboxMsg)

    @Query("SELECT * FROM outbox ORDER BY ts ASC LIMIT 128")
    suspend fun batch(): List<OutboxMsg>

    @Query("DELETE FROM outbox WHERE msgId IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    /** Eliminar pendientes de una sala (al borrar la sala localmente). */
    @Query("DELETE FROM outbox WHERE roomId = :room")
    suspend fun deleteForRoom(room: String)

    @Query("DELETE FROM outbox")
    suspend fun wipe()
}

@Dao
interface PendingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: PendingMsg)

    @Query("SELECT * FROM pending ORDER BY seq ASC")
    suspend fun all(): List<PendingMsg>

    @Query("SELECT * FROM pending WHERE roomId = :room ORDER BY seq ASC")
    suspend fun forRoom(room: String): List<PendingMsg>

    @Query("DELETE FROM pending WHERE roomId = :room")
    suspend fun deleteForRoom(room: String)

    @Query("DELETE FROM pending WHERE msgId IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM pending")
    suspend fun wipe()
}

@Dao
interface MetaDao {
    @Query("SELECT v FROM meta WHERE k = :k")
    suspend fun get(k: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(row: MetaRow)

    suspend fun getLong(k: String): Long? = get(k)?.toLongOrNull()

    suspend fun setLong(k: String, v: Long) = set(MetaRow(k, v.toString()))

    @Query("DELETE FROM meta WHERE k = :k")
    suspend fun delete(k: String)

    @Query("DELETE FROM meta WHERE k LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)

    /** J0/D111: récords E2EE de juegos («gm_<room>_<game>_r» → "score|holder"). */
    @Query("SELECT k, v FROM meta WHERE k LIKE 'gm_%'")
    suspend fun gameMetas(): List<MetaRow>
}

// ───────────── DAOs v3 (perfiles) ─────────────

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE uid = :uid LIMIT 1")
    suspend fun get(uid: String): Profile?

    @Query("SELECT * FROM profiles WHERE uid = :uid LIMIT 1")
    fun observe(uid: String): Flow<Profile?>

    @Query("SELECT * FROM profiles")
    fun observeAll(): Flow<List<Profile>>

    /** true si la fila era NUEVA (lo usa el trigger de anuncio a par desconocido). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(p: Profile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: Profile)

    @Query("DELETE FROM profiles")
    suspend fun wipe()
}

@Dao
interface RoomPeerDao {
    /** Inserta (roomId, uid) si no existe. Devuelve true si era nuevo. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(peer: RoomPeer): Long

    @Query("SELECT COUNT(*) FROM room_peer WHERE roomId = :room")
    suspend fun countFor(room: String): Int

    /** Miembros vistos en una sala (para Ajustes de la sala). */
    @Query("SELECT uid FROM room_peer WHERE roomId = :room")
    suspend fun peersOf(room: String): List<String>

    @Query("DELETE FROM room_peer WHERE roomId = :room")
    suspend fun deleteForRoom(room: String)

    @Query("DELETE FROM room_peer")
    suspend fun wipe()
}

@Dao
interface ReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: Reaction)

    @Query("DELETE FROM reactions WHERE msgId = :msgId AND uid = :uid")
    suspend fun remove(msgId: String, uid: String)

    /** Mi reacción actual a un mensaje (null = ninguna). */
    @Query("SELECT * FROM reactions WHERE msgId = :msgId AND uid = :uid LIMIT 1")
    suspend fun get(msgId: String, uid: String): Reaction?

    /** Reacciones de una sala (la UI las agrupa por msgId). */
    @Query("SELECT * FROM reactions WHERE msgId IN (SELECT msgId FROM messages WHERE roomId = :room)")
    fun observeForRoom(room: String): Flow<List<Reaction>>

    @Query("DELETE FROM reactions WHERE msgId IN (SELECT msgId FROM messages WHERE roomId = :room)")
    suspend fun deleteForRoom(room: String)

    @Query("DELETE FROM reactions")
    suspend fun wipe()
}

@Dao
interface CallLogDao {
    @Query("SELECT * FROM calls ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CallLog>>

    @Insert
    suspend fun insert(c: CallLog): Long

    @Query("UPDATE calls SET status = :status, answeredAt = :answeredAt, duration = :duration WHERE id = :id")
    suspend fun finish(id: Long, status: Int, answeredAt: Long, duration: Long)

    @Query("DELETE FROM calls")
    suspend fun wipe()
}

/** Fila de la lista de chats: sala + perfil del par + último mensaje texto. */
data class RoomListItem(
    val roomId: String,
    val name: String,
    val owner: Boolean,
    val createdAt: Long,
    val sas: String,
    val unread: Int,
    val roomPhoto: Boolean,
    val kind: Int,
    val peerUid: String?,
    val peerName: String?,
    val peerAvatarJson: String?,
    val peerPhoto: Boolean?,
    val lastBody: String?,
    val lastTs: Long?,
    val lastMine: Boolean?,
)

/** Cabecera del chat abierto: avatar + nombre del par (o nombre local de sala). */
data class RoomHeader(
    val roomId: String,
    val name: String,
    val roomPhoto: Boolean,
    val peerName: String?,
    val peerAvatarJson: String?,
    val peerPhoto: Boolean?,
    val peerUid: String?,
)

@Dao
interface RoomListDao {
    /**
     * Lista de chats con el perfil del par (1-a-1: cualquier par de la sala).
     * Salas nuevas (sin mensajes) arriba; el resto por último mensaje.
     * kind=0: los eventos de perfil nunca son vista previa.
     */
    @Query(
        """SELECT r.roomId AS roomId, r.name AS name, r.owner AS owner, r.createdAt AS createdAt,
           r.sas AS sas, r.unread AS unread, r.photo AS roomPhoto, r.kind AS kind,
           (SELECT COUNT(*) FROM room_peer rp2 WHERE rp2.roomId = r.roomId) > 0 AS hasPeers,
           p.uid AS peerUid,
           p.displayName AS peerName, p.avatarJson AS peerAvatarJson, p.photo AS peerPhoto,
           (SELECT m.body FROM messages m WHERE m.roomId = r.roomId AND m.kind = 0
              ORDER BY m.ts DESC, m.msgId DESC LIMIT 1) AS lastBody,
           (SELECT m.ts FROM messages m WHERE m.roomId = r.roomId AND m.kind = 0
              ORDER BY m.ts DESC, m.msgId DESC LIMIT 1) AS lastTs,
           (SELECT m.mine FROM messages m WHERE m.roomId = r.roomId AND m.kind = 0
              ORDER BY m.ts DESC, m.msgId DESC LIMIT 1) AS lastMine
           FROM rooms r
           LEFT JOIN room_peer rp ON rp.roomId = r.roomId AND rp.uid != :meUid
           LEFT JOIN profiles p ON p.uid = rp.uid
           GROUP BY r.roomId
           ORDER BY (lastTs IS NULL) DESC, lastTs DESC, r.createdAt ASC"""
    )
    fun observeRoomList(meUid: String): Flow<List<RoomListItem>>

    /** Cabecera de UNA sala (título del chat: avatar + nombre del par). */
    @Query(
        """SELECT r.roomId AS roomId, r.name AS name, r.photo AS roomPhoto,
           p.displayName AS peerName, p.avatarJson AS peerAvatarJson,
           p.photo AS peerPhoto, p.uid AS peerUid
           FROM rooms r
           LEFT JOIN room_peer rp ON rp.roomId = r.roomId AND rp.uid != :meUid
           LEFT JOIN profiles p ON p.uid = rp.uid
           WHERE r.roomId = :roomId
           GROUP BY r.roomId LIMIT 1"""
    )
    fun observeRoomHeader(roomId: String, meUid: String): Flow<RoomHeader?>
}

// ─────────────────────────── DB ───────────────────────────

/**
 * v2 → v3 (D61, perfiles): columnas kind/senderUid en messages + tablas
 * profiles y room_peer. Migración REAL (ya hay teléfonos reales con v0.3.0):
 * no se puede ser destructivo desde aquí en adelante.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN senderUid TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profiles` (" +
                "`uid` TEXT NOT NULL PRIMARY KEY, `displayName` TEXT NOT NULL, " +
                "`avatarJson` TEXT NOT NULL, `photo` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `room_peer` (" +
                "`roomId` TEXT NOT NULL, `uid` TEXT NOT NULL, PRIMARY KEY(`roomId`, `uid`))"
        )
    }
}

/**
 * v3 → v4 (v0.6.0): columnas unread/photo en rooms (badge de no leídos +
 * foto de sala E2EE). Migración REAL: hay teléfonos reales con v0.4.1/v0.5.0.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rooms ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rooms ADD COLUMN photo INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v4 → v5 (v0.7.0): chats directos (kind/peerUid en rooms) + registro LOCAL
 * de llamadas. Migración REAL: hay teléfonos reales con v0.6.0.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rooms ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rooms ADD COLUMN peerUid TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calls` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`roomId` TEXT NOT NULL, `peerUid` TEXT NOT NULL, `peerName` TEXT NOT NULL, " +
                "`kind` INTEGER NOT NULL, `dir` INTEGER NOT NULL, `status` INTEGER NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `answeredAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL)"
        )
    }
}

/**
 * v5 → v6 (v0.8.0): índices en messages (B3 auditoría). Las 4 queries calientes
 * de la UI (observeChat, observeChats, subconsultas de observeRoomList,
 * failOrphanSending) pasan de escaneo completo a búsqueda por índice.
 * Migración REAL: hay teléfonos reales con v0.7.1 y meses de historial.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Los nombres deben coincidir EXACTAMENTE con los de @Entity(indices=...)
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_msg_room_kind_ts` ON `messages` (`roomId`, `kind`, `ts`, `msgId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_msg_room_mine` ON `messages` (`roomId`, `mine`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_msg_state` ON `messages` (`state`)")
    }
}

/**
 * v6 → v7 (S2/D110): columna replyTo en messages (citas) + tabla reactions.
 * La tabla FTS messages_fts NO va aquí: la crea el callback onOpen (idempotente)
 * para que exista igual en instalaciones NUEVAS y migradas — Room no gestiona
 * tablas virtuales externas al schema.
 * Migración REAL: hay teléfonos reales con v0.14.0 y meses de historial.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `replyTo` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reactions` (`msgId` TEXT NOT NULL, `uid` TEXT NOT NULL, " +
                "`emoji` TEXT NOT NULL, PRIMARY KEY(`msgId`, `uid`))"
        )
    }
}

/**
 * S2/D110: FTS4 sobre el cuerpo de los mensajes, para la búsqueda global.
 *
 * Por qué una tabla virtual PROPIA (con msgId+body) y no @Fts4(contentEntity):
 * Room no crea los triggers de sincronización para FTS de contenido externo —
 * solo crea la tabla, y el índice quedaría huérfano al primer INSERT. Aquí el
 * rowid del FTS = rowid de messages y tres triggers (insert/delete/update de
 * body) la mantienen al día sin tocar el código de escritura existente. Todo
 * vive en el callback onOpen (idempotente, CREATE IF NOT EXISTS): instalaciones
 * nuevas y migradas quedan idénticas, y Room ignora tablas que no son suyas.
 */
private val FTS_CALLBACK = object : androidx.room.RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING fts4(`msgId`, `body`)")
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `msgs_fts_ai` AFTER INSERT ON `messages` " +
                "WHEN new.kind = 0 BEGIN " +
                "INSERT INTO messages_fts(rowid, msgId, body) VALUES (new.rowid, new.msgId, new.body); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `msgs_fts_ad` AFTER DELETE ON `messages` BEGIN " +
                "DELETE FROM messages_fts WHERE rowid = old.rowid; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `msgs_fts_au` AFTER UPDATE OF body ON `messages` BEGIN " +
                "DELETE FROM messages_fts WHERE rowid = old.rowid; " +
                "INSERT INTO messages_fts(rowid, msgId, body) VALUES (new.rowid, new.msgId, new.body); END"
        )
        // repoblado incremental (una vez): el historial previo al primer arranque
        // con FTS. Barato: solo inserta los rowid que faltan.
        db.execSQL(
            "INSERT INTO messages_fts(rowid, msgId, body) " +
                "SELECT rowid, msgId, body FROM messages WHERE kind = 0 " +
                "AND rowid NOT IN (SELECT rowid FROM messages_fts)"
        )
    }
}

@Database(
    entities = [Room::class, Message::class, OutboxMsg::class, PendingMsg::class, MetaRow::class,
        Profile::class, RoomPeer::class, CallLog::class, Reaction::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun roomDao(): RoomDao
    abstract fun msgDao(): MsgDao
    abstract fun outboxDao(): OutboxDao
    abstract fun pendingDao(): PendingDao
    abstract fun metaDao(): MetaDao
    abstract fun profileDao(): ProfileDao
    abstract fun roomPeerDao(): RoomPeerDao
    abstract fun roomListDao(): RoomListDao
    abstract fun callLogDao(): CallLogDao
    abstract fun reactionDao(): ReactionDao

    /**
     * S2/D110: búsqueda global FTS4 — query CRUDA: Room no conoce la tabla
     * virtual messages_fts (la crea el callback onOpen, no es una @Entity),
     * así que KSP no puede validarla. Mapeo a mano del cursor a Message.
     * Los términos llegan escapados con comillas (ftsEscape en la UI).
     * suspend + IO interna: Room prohíbe query() en main thread.
     */
    suspend fun searchRaw(q: String): List<Message> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = mutableListOf<Message>()
            try {
                query(
                    androidx.sqlite.db.SimpleSQLiteQuery(
                        "SELECT m.msgId, m.roomId, m.senderUname, m.mine, m.body, m.ts, m.state, " +
                            "m.seq, m.kind, m.senderUid, m.replyTo " +
                            "FROM messages m JOIN messages_fts ON messages_fts.rowid = m.rowid " +
                            "WHERE messages_fts MATCH ? AND m.kind = 0 " +
                            "ORDER BY m.ts DESC LIMIT 200",
                        arrayOf(q),
                    ),
                ).use { c ->
                    val iReply = c.getColumnIndex("replyTo")
                    while (c.moveToNext()) {
                        out.add(
                            Message(
                                msgId = c.getString(0),
                                roomId = c.getString(1),
                                senderUname = c.getString(2),
                                mine = c.getInt(3) != 0,
                                body = c.getString(4),
                                ts = c.getLong(5),
                                state = c.getInt(6),
                                seq = if (c.isNull(7)) null else c.getLong(7),
                                kind = c.getInt(8),
                                senderUid = c.getString(9),
                                replyTo = if (iReply >= 0 && !c.isNull(iReply)) c.getString(iReply) else null,
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                // sin FTS todavía (primera apertura) o query inválida: sin resultados
            }
            out
        }

    companion object {
        fun build(ctx: Context): AppDatabase =
            // androidx.room.Room (calificado: la entidad local se llama Room)
            androidx.room.Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "chat.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .addCallback(FTS_CALLBACK)
                // B7 (auditoría): SIN fallback destructivo. Antes, una migración
                // olvidada borraba el historial EN SILENCIO. Ahora: crash visible
                // en desarrollo (y en CI, MigrationTest obligatorio por salto) en
                // vez de pérdida de datos invisible para el usuario.
                .build()
    }
}
