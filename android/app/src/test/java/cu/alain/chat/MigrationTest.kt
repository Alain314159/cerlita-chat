package cu.alain.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import cu.alain.chat.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reproduce EXACTAMENTE la actualización v0.3.0 (DB v2) → v0.4.0 (DB v3) en un
 * teléfono real: crea un chat.db con el schema v2 de la v0.3.0 + datos, lo abre
 * con la AppDatabase v3 (dispara MIGRATION_2_3 + validación de Room) y ejecuta
 * las consultas nuevas (RoomListDao).
 *
 * Si la migración o la validación fallan → IllegalStateException("Migration
 * didn't properly handle…") → crash de la app al abrir sobre datos existentes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MigrationTest {

    private lateinit var ctx: Context

    @Before
    fun createV2Database() {
        ctx = ApplicationProvider.getApplicationContext()
        val dbFile = ctx.getDatabasePath("chat.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        // Schema v2 EXACTO (git 9b2f2cc — v0.3.0, generado por Room entonces)
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
            "CREATE TABLE `rooms` (`roomId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`owner` INTEGER NOT NULL, `sas` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`roomId`))"
        )
        db.execSQL(
            "CREATE TABLE `messages` (`msgId` TEXT NOT NULL, `roomId` TEXT NOT NULL, " +
                "`senderUname` TEXT NOT NULL, `mine` INTEGER NOT NULL, `body` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `state` INTEGER NOT NULL, `seq` INTEGER, " +
                "PRIMARY KEY(`msgId`))"
        )
        db.execSQL(
            "CREATE TABLE `outbox` (`msgId` TEXT NOT NULL, `roomId` TEXT NOT NULL, " +
                "`ctB64` TEXT NOT NULL, `nonceB64` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "PRIMARY KEY(`msgId`))"
        )
        db.execSQL(
            "CREATE TABLE `pending` (`msgId` TEXT NOT NULL, `roomId` TEXT NOT NULL, " +
                "`senderUname` TEXT NOT NULL, `senderId` TEXT NOT NULL, `nonceB64` TEXT NOT NULL, " +
                "`ctB64` TEXT NOT NULL, `ts` INTEGER NOT NULL, `seq` INTEGER NOT NULL, " +
                "PRIMARY KEY(`msgId`))"
        )
        db.execSQL(
            "CREATE TABLE `meta` (`k` TEXT NOT NULL, `v` TEXT NOT NULL, PRIMARY KEY(`k`))"
        )
        db.execSQL(
            "CREATE TABLE `room_master_table` (`id` INTEGER NOT NULL, `identity_hash` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'v2-hash-simulado')")

        // Datos de un teléfono real de pareja con historia
        db.execSQL(
            "INSERT INTO rooms VALUES('sala123', 'Mi amor', 1, '123456', 1700000000000)"
        )
        db.execSQL(
            "INSERT INTO messages VALUES('m1', 'sala123', 'alain', 1, 'hola mi amor', 1700000001000, 3, 5)"
        )
        db.execSQL(
            "INSERT INTO messages VALUES('m2', 'sala123', 'cerlita', 0, 'hola bebe', 1700000005000, 3, 6)"
        )
        db.execSQL("INSERT INTO outbox VALUES('o1', 'sala123', 'Y3Q=', 'bm9uY2U=', 1700000002000)")
        db.execSQL("INSERT INTO pending VALUES('p1', 'sala123', 'cerlita', 'uid-2', 'bg==', 'Y3Q=', 1700000004000, 3)")
        db.execSQL("INSERT INTO meta VALUES('last_seq', '7')")
        db.version = 2
        db.close()
    }

    @Test
    fun upgradeV2toV3MigratesAndQueries() = runBlocking {
        val db = AppDatabase.build(ctx) // con MIGRATION_2_3 + fallback real de la app
        try {
            // abrir dispara MIGRATION_2_3 + validación de schema (crash si está mal)
            val rooms = db.roomDao().all()
            assertEquals(1, rooms.size)
            assertEquals("Mi amor", rooms[0].name)

            // los mensajes viejos conservan kind=0 (texto) tras el DEFAULT
            val msgs = db.msgDao().chat("sala123")
            assertEquals(2, msgs.size)
            assertEquals(0, msgs[0].kind)
            assertEquals("", msgs[0].senderUid)

            // la tabla meta sigue intacta
            assertEquals(7L, db.metaDao().getLong("last_seq"))

            // la consulta nueva de la lista de chats funciona sobre datos migrados
            val list = db.roomListDao().observeRoomList("uid-yo").first()
            assertEquals(1, list.size)
            assertEquals("Mi amor", list[0].name)
            assertEquals("hola bebe", list[0].lastBody) // último mensaje texto
            assertTrue(list[0].peerUid == null)         // sin pares conocidos aún

            // insertar mensaje v3 con kind/senderUid funciona
            db.msgDao().insert(
                cu.alain.chat.db.Message(
                    "m3", "sala123", "alain", true, "texto nuevo", 1700000010000, 3, 7, 0, "uid-yo",
                )
            )
            // un evento de perfil no aparece en el chat
            db.msgDao().insert(
                cu.alain.chat.db.Message(
                    "mp1", "sala123", "alain", true, "Cerdita", 1700000011000, 3, 8, 1, "uid-yo",
                )
            )
            val chat = db.msgDao().chat("sala123")
            assertEquals(3, chat.size) // m1, m2, m3 — mp1 (kind=1) excluido
        } finally {
            db.close()
        }
    }
}
