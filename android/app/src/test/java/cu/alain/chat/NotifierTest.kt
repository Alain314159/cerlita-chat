package cu.alain.chat

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.db.Room
import cu.alain.chat.engine.NewMsg
import cu.alain.chat.notify.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests del Notifier v0.5.0 (D63/D66): canal por sala IMPORTANCE_HIGH,
 * MessagingStyle con el nombre del par, y la política única de aviso
 * (ajenos + recientes + no en la sala abierta en foreground).
 *
 * API 28: canales activos y SIN permiso runtime de notificaciones.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // mismo runtime que SmokeTest (carrera de fuentes si se mezcla)
@Config(sdk = [28])
class NotifierTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private val rid = "11111111-2222-3333-4444-555555555555"
    private val peerUid = "peer-uid-1"

    @Before
    fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java) // calificado (colisión Room, D54)
            .allowMainThreadQueries().build()
        db.roomDao().upsert(Room(rid, "Sala Prueba", owner = true, sas = "123456", createdAt = 1L))
        Unit
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val shadowNm get() = shadowOf(nm)

    /** El shadow expone las Notification crudas; presencia por id. */
    private fun hasId(id: Int): Boolean = shadowNm.getNotification(id) != null
    private fun posted(): List<Notification> = shadowNm.allNotifications

    @Test
    fun `canal por sala HIGH con estilo de conversación`() = runBlocking {
        val now = System.currentTimeMillis()
        Notifier.notifyNewMessages(
            ctx, db, myUid = "yo", inForeground = false, suppressRoom = null,
            msgs = listOf(NewMsg(rid, peerUid, "amelia", "Hola desde el test", now, mine = false)),
        )
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val ch = nm.getNotificationChannel("room_11111111")
        assertNotNull(ch, "debe existir el canal room_11111111")
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)

        val sbn = shadowNm.getNotification(rid.hashCode())
        assertNotNull(sbn, "debe existir la notificación de la sala")
        assertEquals("room_11111111", NotificationCompat.getChannelId(sbn))
        assertEquals("messages", sbn.group)

        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn)
        assertNotNull(style, "debe ser MessagingStyle")
        assertEquals(1, style!!.messages.size)
        assertEquals("Hola desde el test", style.messages[0].text)
        assertEquals("amelia", style.messages[0].person?.name?.toString())
        assertEquals("Sala Prueba", style.conversationTitle?.toString())
    }

    @Test
    fun `ecos propios y sala abierta NO notifican`() = runBlocking {
        val now = System.currentTimeMillis()
        Notifier.notifyNewMessages(
            ctx, db, myUid = "yo", inForeground = false, suppressRoom = null,
            msgs = listOf(
                NewMsg(rid, "yo", "yo", "eco multi-dispositivo", now, mine = true),
            ),
        )
        assertTrue(posted().isEmpty(), "el eco propio no avisa")

        Notifier.notifyNewMessages(
            ctx, db, myUid = "yo", inForeground = true, suppressRoom = rid,
            msgs = listOf(NewMsg(rid, peerUid, "amelia", "viendo este chat", now, mine = false)),
        )
        assertTrue(posted().isEmpty(), "la sala abierta en foreground no avisa")
    }

    /**
     * §3.2 (auditoría, v0.8.0): un mensaje importante aunque sea VIEJO ahora
     * SÍ avisa (antes: ventana de 30 min → el teléfono 2 h sin red y el
     * mensaje de hace 45 min llegaba en SILENCIO). Una notificación por sala.
     */
    @Test
    fun `un mensaje viejo suelto SI avisa (nunca mas mensajes mudos)`() = runBlocking {
        val now = System.currentTimeMillis()
        Notifier.notifyNewMessages(
            ctx, db, myUid = "yo", inForeground = false, suppressRoom = null,
            msgs = listOf(
                NewMsg(rid, peerUid, "amelia", "mensaje importante de hace 3 h", now - 3L * 60 * 60 * 1000, mine = false),
            ),
        )
        assertTrue(hasId(rid.hashCode()), "el mensaje viejo suelto debe avisar (1 por sala)")
    }

    /** Y la tanda ENORME y toda antigua (re-sync de un teléfono nuevo) no spam. */
    @Test
    fun `re-sync enorme y antiguo NO avisa`() = runBlocking {
        val now = System.currentTimeMillis()
        val viejos = (1..20).map { i ->
            NewMsg(rid, peerUid, "amelia", "historial $i", now - 48L * 60 * 60 * 1000 - i, mine = false)
        }
        Notifier.notifyNewMessages(
            ctx, db, myUid = "yo", inForeground = false, suppressRoom = null, msgs = viejos,
        )
        assertTrue(posted().isEmpty(), "20 mensajes de hace 2 días = historial, no noticia")
    }

    @Test
    fun `en foreground las OTRAS salas sí avisan`() = runBlocking {
        val now = System.currentTimeMillis()
        val otra = "99999999-8888-7777-6666-555555555555"
        db.roomDao().upsert(Room(otra, "Otra sala", false, "654321", 2L))
        Notifier.notifyNewMessages(
            ctx, db, myUid = "yo", inForeground = true, suppressRoom = rid,
            msgs = listOf(NewMsg(otra, peerUid, "amelia", "mientras chateas otra sala", now, mine = false)),
        )
        assertTrue(hasId(otra.hashCode()), "la otra sala sí debe avisar en foreground")
    }

    @Test
    fun `cancelRoom borra el aviso de la sala`() = runBlocking {
        val now = System.currentTimeMillis()
        Notifier.notifyNewMessages(
            ctx, db, myUid = "yo", inForeground = false, suppressRoom = null,
            msgs = listOf(NewMsg(rid, peerUid, "amelia", "hola", now, mine = false)),
        )
        assertTrue(hasId(rid.hashCode()))
        Notifier.cancelRoom(ctx, rid)
        assertTrue(!hasId(rid.hashCode()), "cancelRoom debe borrar el aviso")
    }

    /** D79: la notificación trae «Responder» (con RemoteInput) y «Marcar leído». */
    @Test
    fun `acciones responder y marcar leído presentes`() = runBlocking {
        val now = System.currentTimeMillis()
        Notifier.notifyNewMessages(
            ctx, db, myUid = "yo", inForeground = false, suppressRoom = null,
            msgs = listOf(NewMsg(rid, peerUid, "amelia", "contestame", now, mine = false)),
        )
        val sbn = shadowNm.getNotification(rid.hashCode())
        assertNotNull(sbn, "debe existir la notificación")
        val actions = sbn.actions
        assertNotNull(actions, "debe tener acciones")
        assertEquals(2, actions!!.size)
        assertEquals("Responder", actions[0].title)
        assertEquals("Marcar leído", actions[1].title)
        // el RemoteInput del action responde a la clave del ReplyReceiver
        val ri = actions[0].remoteInputs
        assertTrue(ri != null && ri.isNotEmpty(), "Responder debe llevar RemoteInput")
        assertEquals(cu.alain.chat.notify.ReplyReceiver.KEY_REPLY_TEXT, ri[0].resultKey)
        Unit
    }

    @Test
    fun `canal del servicio es MIN`() {
        Notifier.ensureServiceChannel(ctx)
        val ch = ctx.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(Notifier.SERVICE_CHANNEL)
        assertNotNull(ch)
        assertEquals(NotificationManager.IMPORTANCE_MIN, ch.importance)
    }

    @Test
    fun `sin mensajes no toca el NotificationManager`() = runBlocking {
        Notifier.notifyNewMessages(ctx, db, "yo", false, null, emptyList())
        assertNull(
            ctx.getSystemService(NotificationManager::class.java).getNotificationChannel("room_99999999"),
        )
    }
}
