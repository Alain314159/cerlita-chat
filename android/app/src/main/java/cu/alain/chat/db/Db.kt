package cu.alain.chat.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
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
)

/** Mensaje descifrado (o marcado ilegible) para la UI. */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val msgId: String,
    val roomId: String,         // sala a la que pertenece (emisor o receptor)
    val senderUname: String,
    val mine: Boolean,
    val body: String,
    val ts: Long,
    val state: Int,             // C.ST_*: sending/sent/failed/received/unreadable
    val seq: Long? = null,
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

    @Query("SELECT COUNT(*) FROM rooms")
    suspend fun count(): Int

    @Query("DELETE FROM rooms")
    suspend fun wipe()
}

@Dao
interface MsgDao {
    @Query("SELECT * FROM messages WHERE roomId = :room ORDER BY ts ASC, msgId ASC")
    fun observeChat(room: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE roomId = :room ORDER BY ts ASC, msgId ASC")
    suspend fun chat(room: String): List<Message>

    @Query("SELECT * FROM messages ORDER BY ts ASC")
    suspend fun all(): List<Message>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: Message): Long

    @Query("UPDATE messages SET state = :state WHERE msgId = :id")
    suspend fun setState(id: String, state: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE msgId = :id)")
    suspend fun exists(id: String): Boolean

    // Lista de conversaciones: último mensaje por sala (JOIN para respetar orden de rooms)
    @Query(
        """SELECT m.* FROM messages m
           INNER JOIN (SELECT roomId AS r, MAX(ts) AS mx FROM messages GROUP BY roomId) t
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
}

// ─────────────────────────── DB ───────────────────────────

@Database(
    entities = [Room::class, Message::class, OutboxMsg::class, PendingMsg::class, MetaRow::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun roomDao(): RoomDao
    abstract fun msgDao(): MsgDao
    abstract fun outboxDao(): OutboxDao
    abstract fun pendingDao(): PendingDao
    abstract fun metaDao(): MetaDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            // androidx.room.Room (calificado: la entidad local se llama Room)
            androidx.room.Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "chat.db")
                // v2 pre-lanzamiento sin usuarios reales: destructivo aceptable
                // (el protocolo rooms es incompatible con v1: mensajes por par
                // de usernames). Con usuarios reales → migraciones obligatorias.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
