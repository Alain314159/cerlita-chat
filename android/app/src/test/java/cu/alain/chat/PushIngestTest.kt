package cu.alain.chat

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import cu.alain.chat.crypto.Crypto
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.db.Room
import cu.alain.chat.db.RoomPeer
import cu.alain.chat.engine.ChatEngine
import cu.alain.chat.net.Api
import cu.alain.chat.net.SyncRow
import cu.alain.chat.vault.Session
import cu.alain.chat.vault.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * D104: ingest de filas llegadas por el canal ntfy (el sobre cifrado del
 * ping). Robolectric con UN sdk por clase (regla H14 de v0.5.0). El flujo:
 * push ajeno → NewMsg visible al instante; replay → dedup por PK; «/sync de
 * confirmación» (misma fila con seq) → fillSeq rellena la seq para que los
 * recibos ✓✓ encuentren el mensaje; eco propio → NewMsg mine=true (la
 * política de avisar es del Notifier, D63); sala sin clave → pending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushIngestTest {

    private lateinit var db: AppDatabase
    private lateinit var store: SessionStore
    private lateinit var engine: ChatEngine

    private val myUid = "99999999-9999-9999-9999-999999999999"
    private val peerUid = "88888888-8888-8888-8888-888888888888"
    private val rid = "aaaa1111-2222-3333-4444-555555555555"
    private val roomKey = ByteArray(32) { (it + 1).toByte() }

    companion object {
        // H5/D104: el AndroidKeyStore no existe en Robolectric → vault
        // transparente SOLO durante esta clase; se restaura al salir.
        private var origSeal: ((ByteArray, String) -> ByteArray)? = null
        private var origUnseal: ((ByteArray, String) -> ByteArray?)? = null

        @JvmStatic
        @BeforeClass
        fun fakeVault() {
            origSeal = cu.alain.chat.crypto.KeyVault.sealFn
            origUnseal = cu.alain.chat.crypto.KeyVault.unsealFn
            cu.alain.chat.crypto.KeyVault.sealFn = { p, _ -> p.copyOf() }
            cu.alain.chat.crypto.KeyVault.unsealFn = { b, _ -> b.copyOf() }
        }

        @JvmStatic
        @AfterClass
        fun restoreVault() {
            origSeal?.let { cu.alain.chat.crypto.KeyVault.sealFn = it }
            origUnseal?.let { cu.alain.chat.crypto.KeyVault.unsealFn = it }
        }
    }

    @Before
    fun setUp() = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java) // calificado (colisión Room, D54)
            .allowMainThreadQueries()
            .build()
        store = SessionStore(ctx)
        store.saveSession(Session("tok-test", myUid, "amelia", "did-test"))
        store.putRoomKey(rid, roomKey, ByteArray(16) { (it + 2).toByte() })
        db.roomDao().upsert(Room(rid, "Sala prueba", true, "123456", 1L))
        db.roomPeerDao().insertIfMissing(RoomPeer(rid, peerUid)) // evita announceToRooms (red)

        engine = ChatEngine(
            db = db,
            store = store,
            api = Api { "https://invalido-test.invalid/fake" },
            avatarsDir = File(ctx.filesDir, "avatars"),
            onNewMessages = { /* el aviso lo dispara el POLLER (announceIncoming) — D63 */ },
        )
        engine.refreshState()
        engine.loadKeys()
    }

    /** Cifra como lo haría el EMISOR (peer) al enviar por el outbox. */
    private fun sealFrom(peer: String, msgId: String, text: String): SyncRow {
        val convKey = Crypto.convKey(roomKey, rid)
        val msgKey = Crypto.msgKey(convKey, msgId)
        val (nonce, ct) = Crypto.seal(msgKey, Crypto.frame(text), Crypto.aad(msgId, peer, rid))
        return SyncRow(
            msgId = msgId, roomId = rid, senderId = peer, seq = 0,
            nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP),
            senderUname = "alain", ts = 1793300000000,
        )
    }

    @Test
    fun `push ajeno se ve al instante, el replay no duplica y el sync rellena la seq`() = runBlocking {
        val row = sealFrom(peerUid, "msg-push-1", "hola por ntfy")

        // 1) push: NewMsg visible SIN round-trip a Supabase. El POLLER anuncia
        // (announceIncoming) solo si la lista no viene vacía — la política de
        // avisar es del Notifier (D63), el ingester solo devuelve.
        val fresh = engine.ingestFromPush(listOf(row))
        assertEquals(1, fresh.size)
        assertEquals("hola por ntfy", fresh[0].body)
        assertEquals(false, fresh[0].mine)
        assertEquals(rid, fresh[0].roomId) // lo que el MessagingStyle necesita

        val saved = db.msgDao().all().first { it.msgId == "msg-push-1" }
        assertEquals("hola por ntfy", saved.body)
        assertNull(saved.seq) // aún sin seq: NULL, no 0 — fillSeq (seq IS NULL) la rellenará

        // 2) replay del mismo ping (llega dos veces: timbre + ntfy) → dedup por PK:
        // lista vacía → el poller NO vuelve a anunciar
        val again = engine.ingestFromPush(listOf(row))
        assertTrue(again.isEmpty())
        assertEquals(1, db.msgDao().all().size) // y sigue habiendo UN solo mensaje en DB

        // 3) «/sync de confirmación»: la misma fila CON seq del servidor → fillSeq
        val withSeq = row.copy(seq = 7)
        val third = engine.ingestFromPush(listOf(withSeq))
        assertTrue(third.isEmpty())
        val filled = db.msgDao().all().first { it.msgId == "msg-push-1" }
        assertEquals(7L, filled.seq) // ahora los ✓✓ (markDeliveredUpTo) lo encuentran
    }

    @Test
    fun `eco propio por push llega como mine y no rompe`() = runBlocking {
        val eco = sealFrom(myUid, "msg-eco-1", "eco propio multi-dispositivo")
        val fresh = engine.ingestFromPush(listOf(eco))
        assertEquals(1, fresh.size)
        assertTrue(fresh[0].mine)
        assertEquals("eco propio multi-dispositivo", db.msgDao().all().first { it.msgId == "msg-eco-1" }.body)
    }

    @Test
    fun `sala sin clave va a pending y nada revienta`() = runBlocking {
        val other = "bbbb6666-7777-8888-9999-000000000000"
        val convKey = Crypto.convKey(roomKey, other)
        val msgKey = Crypto.msgKey(convKey, "msg-pend-1")
        val (nonce, ct) = Crypto.seal(msgKey, Crypto.frame("secreto en sala sin clave"), Crypto.aad("msg-pend-1", peerUid, other))
        val row = SyncRow(
            "msg-pend-1", other, peerUid, 0,
            Base64.encodeToString(nonce, Base64.NO_WRAP),
            Base64.encodeToString(ct, Base64.NO_WRAP),
            "alain", 1793300000001,
        )
        val fresh = engine.ingestFromPush(listOf(row))
        assertTrue(fresh.isEmpty()) // no se notifica: aún no se puede descifrar
        assertEquals(1, db.pendingDao().all().size) // archivado cifrado hasta tener la clave
    }

    @Test
    fun `tema del receptor derivable desde el engine`() {
        val t = engine.ntfyTopicFor(rid)
        assertNotNull(t)
        assertEquals(Crypto.ntfyTopic(roomKey, rid), t)
        assertEquals(27, t!!.length)
    }
}
