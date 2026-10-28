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

    /**
     * v0.6.0 (DB v4) → v0.7.0 (DB v5): chats directos + registro de llamadas.
     * Crea un chat.db v4 EXACTO (v2 + las columnas de v3/v4 aplicadas a mano,
     * SIN las de v5), lo abre con la AppDatabase actual → MIGRATION_4_5.
     */
    @Test
    fun upgradeV4toV5DmAndCalls() = runBlocking {
        // del v2 del @Before, subir a v4 con los statements EXACTOS de la historia
        val raw = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("chat.db"), null)
        // v2→3 (perfiles, D61)
        raw.execSQL("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        raw.execSQL("ALTER TABLE messages ADD COLUMN senderUid TEXT NOT NULL DEFAULT ''")
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `profiles` (`uid` TEXT NOT NULL PRIMARY KEY, " +
                "`displayName` TEXT NOT NULL, `avatarJson` TEXT NOT NULL, `photo` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `room_peer` (`roomId` TEXT NOT NULL, `uid` TEXT NOT NULL, " +
                "PRIMARY KEY(`roomId`, `uid`))"
        )
        // v3→4 (unread + foto de sala, D69)
        raw.execSQL("ALTER TABLE rooms ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
        raw.execSQL("ALTER TABLE rooms ADD COLUMN photo INTEGER NOT NULL DEFAULT 0")
        raw.execSQL("UPDATE rooms SET unread = 2, photo = 1 WHERE roomId = 'sala123'")
        raw.version = 4
        raw.close()

        val db5 = AppDatabase.build(ctx)
        try {
            // la sala migrada conserva sus datos y gana kind=0 (grupo) por defecto
            val room = db5.roomDao().get("sala123")
            assertEquals("Mi amor", room!!.name)
            assertEquals(0, room.kind)
            assertEquals(null, room.peerUid)
            assertEquals(2, room.unread)
            assertTrue(room.photo)

            // un chat directo nuevo funciona de punta a punta
            db5.roomDao().upsert(
                cu.alain.chat.db.Room(
                    "dm-1", "@amelia", false, "654321", 1700000099999, 0, false,
                    cu.alain.chat.core.C.ROOM_DM, "uid-amelia",
                )
            )
            val dm = db5.roomDao().get("dm-1")
            assertEquals(cu.alain.chat.core.C.ROOM_DM, dm!!.kind)
            assertEquals("uid-amelia", dm.peerUid)

            // el registro de llamadas inserta y consulta (tabla nueva)
            db5.callLogDao().insert(
                cu.alain.chat.db.CallLog(
                    roomId = "dm-1", peerUid = "uid-amelia", peerName = "Amor",
                    kind = cu.alain.chat.core.C.CALL_VIDEO, dir = cu.alain.chat.core.C.CALL_OUT,
                    status = cu.alain.chat.core.C.CALL_COMPLETED, startedAt = 1700000100000,
                    answeredAt = 1700000100500, duration = 120,
                )
            )
            val calls = db5.callLogDao().observeAll().first()
            assertEquals(1, calls.size)
            assertEquals(120L, calls[0].duration)
            assertTrue(calls[0].id > 0)
        } finally {
            db5.close()
        }
    }

    /**
     * v0.7.1 (DB v5) → v0.8.0 (DB v6): índices en messages (B3 auditoría).
     * Crea un chat.db v5 EXACTO (historia completa aplicada a mano), lo abre
     * con la AppDatabase actual → MIGRATION_5_6 + validación de Room (que
     * verifica los índices uno a uno por nombre y columnas).
     */
    @Test
    fun upgradeV5toV6Indexes() = runBlocking {
        // del v2 del @Before, subir a v5 con TODA la cadena EXACTA de la historia
        val raw = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("chat.db"), null)
        // v2→3 (perfiles, D61)
        raw.execSQL("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        raw.execSQL("ALTER TABLE messages ADD COLUMN senderUid TEXT NOT NULL DEFAULT ''")
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `profiles` (`uid` TEXT NOT NULL PRIMARY KEY, " +
                "`displayName` TEXT NOT NULL, `avatarJson` TEXT NOT NULL, `photo` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `room_peer` (`roomId` TEXT NOT NULL, `uid` TEXT NOT NULL, " +
                "PRIMARY KEY(`roomId`, `uid`))"
        )
        // v3→4 (unread + foto de sala, D69)
        raw.execSQL("ALTER TABLE rooms ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
        raw.execSQL("ALTER TABLE rooms ADD COLUMN photo INTEGER NOT NULL DEFAULT 0")
        // v4→5 (chats directos + registro de llamadas, D74)
        raw.execSQL("ALTER TABLE rooms ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        raw.execSQL("ALTER TABLE rooms ADD COLUMN peerUid TEXT")
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `calls` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`roomId` TEXT NOT NULL, `peerUid` TEXT NOT NULL, `peerName` TEXT NOT NULL, " +
                "`kind` INTEGER NOT NULL, `dir` INTEGER NOT NULL, `status` INTEGER NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `answeredAt` INTEGER NOT NULL, `duration` INTEGER NOT NULL)"
        )
        // más historial real: los índices se crean SOBRE filas existentes
        for (i in 3..50) {
            raw.execSQL(
                "INSERT INTO messages VALUES('m$i', 'sala123', 'alain', 1, 'msg $i', " +
                    "${1700000100000 + i}, 3, ${10 + i}, 0, 'uid-yo')"
            )
        }
        raw.version = 5
        raw.close()

        val db6 = AppDatabase.build(ctx)
        try {
            // abrir ya validó los índices (si falta alguno: IllegalStateException)
            // el historial completo sigue intacto tras crear los índices
            val chat = db6.msgDao().chat("sala123")
            assertEquals(50, chat.size) // m1, m2 + 48 insertados arriba

            // las queries calientes siguen funcionando sobre datos migrados
            val list = db6.roomListDao().observeRoomList("uid-yo").first()
            assertEquals(1, list.size)
            assertEquals("msg 50", list[0].lastBody)

            // escribir sobre la tabla indexada funciona igual
            db6.msgDao().insert(
                cu.alain.chat.db.Message(
                    "m51", "sala123", "alain", true, "post-migración", 1700000200000, 3, 100, 0, "uid-yo",
                )
            )
            assertEquals(51, db6.msgDao().chat("sala123").size)
        } finally {
            db6.close()
        }
    }
}
