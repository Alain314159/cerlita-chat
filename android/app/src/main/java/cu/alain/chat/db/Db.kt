package cu.alain.chat.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// ─────────────────────────── entidades ───────────────────────────

/** Mensaje descifrado (o marcado ilegible) para la UI. */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val msgId: String,
    val peerUname: String,     // con quién hablo (aunque yo sea el emisor)
    val senderUname: String,
    val mine: Boolean,
    val body: String,
    val ts: Long,
    val state: Int,            // C.ST_*: sending/sent/failed/received/unreadable
    val seq: Long? = null,
)

/** Mensaje pendiente de enviar (persiste el ciphertext hasta el ack del servidor). */
@Entity(tableName = "outbox")
data class OutboxMsg(
    @PrimaryKey val msgId: String,
    val peerUname: String,
    val ctB64: String,
    val nonceB64: String,
    val ts: Long,
)

/**
 * Fila cruda recibida por sync MIENTRAS la app está bloqueada: se guarda
 * cifrada tal cual y se descifra al desbloquear (la rootKey nunca persiste).
 */
@Entity(tableName = "pending")
data class PendingMsg(
    @PrimaryKey val msgId: String,
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
interface MsgDao {
    @Query("SELECT * FROM messages WHERE peerUname = :peer OR (senderUname = :peer AND mine = 0) ORDER BY ts ASC, msgId ASC")
    fun observeChat(peer: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE peerUname = :peer OR (senderUname = :peer AND mine = 0) ORDER BY ts ASC, msgId ASC")
    suspend fun chat(peer: String): List<Message>

    @Query("SELECT * FROM messages ORDER BY ts ASC")
    suspend fun all(): List<Message>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: Message): Long

    @Query("UPDATE messages SET state = :state WHERE msgId = :id")
    suspend fun setState(id: String, state: Int)

    @Query("UPDATE messages SET body = :body, state = :state WHERE msgId = :id")
    suspend fun setBodyState(id: String, body: String, state: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE msgId = :id)")
    suspend fun exists(id: String): Boolean

    // Lista de conversaciones: último mensaje por peer
    @Query(
        """SELECT m.* FROM messages m
           INNER JOIN (SELECT peerUname AS p, MAX(ts) AS mx FROM messages GROUP BY peerUname) t
           ON m.peerUname = t.p AND m.ts = t.mx
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
    entities = [Message::class, OutboxMsg::class, PendingMsg::class, MetaRow::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun msgDao(): MsgDao
    abstract fun outboxDao(): OutboxDao
    abstract fun pendingDao(): PendingDao
    abstract fun metaDao(): MetaDao

    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "chat.db")
                // v1 pre-lanzamiento sin usuarios: destructivo aceptable;
                // con usuarios reales → migraciones obligatorias (regla del skill)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
