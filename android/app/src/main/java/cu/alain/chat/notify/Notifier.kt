package cu.alain.chat.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import cu.alain.chat.MainActivity
import cu.alain.chat.R
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.engine.NewMsg
import cu.alain.chat.profile.AttachmentMsg

/**
 * Notificaciones de mensajes (reescrito en v0.5.0, D63/D66):
 *
 *  - UN canal por sala (IMPORTANCE_HIGH: sonido + heads-up) con el nombre de
 *    la sala → el usuario silencia/activa cada conversación desde Ajustes
 *    del sistema. El nombre del canal se refresca si renombran la sala.
 *  - MessagingStyle: conversación con el nombre real del par (perfil E2EE)
 *    y hasta 5 burbujas de contexto — el estándar de mensajería de Android.
 *  - Grupo "messages" + resumen: varias salas se apilan en una tarjeta.
 *  - Tocar la notificación abre ESA sala directamente (extra room).
 *
 * Política de cuándo NOTIFICAR (una sola, en [notifyNewMessages]):
 *  mensajes ajenos (los ecos propios multi-dispositivo no avisan) que no
 *  sean de la sala abierta en primer plano, con antigüedad ≤ 30 min
 *  (el historial que baja de golpe en un teléfono nuevo no spammea).
 */
object Notifier {

    const val SERVICE_CHANNEL = "service"
    private const val MESSAGES_CHANNEL = "messages"
    private const val GROUP = "messages"
    private const val SUMMARY_ID = 1001
    private const val MAX_BUBBLES = 5

    /** Antigüedad máxima para avisar (el resto es historial, no noticia). */
    private const val RECENT_MS = 30L * 60 * 1000

    private fun channelId(roomId: String) = "room_${roomId.take(8)}"
    private fun notifId(roomId: String) = roomId.hashCode()

    /** Canal del servicio foreground (IMPORTANCE_MIN: sin sonido ni barra). */
    fun ensureServiceChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                ctx.getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = ctx.getString(R.string.channel_service_desc) },
        )
    }

    /** Canal por sala; recrearlo actualiza el nombre si la sala cambió. */
    private fun ensureRoomChannel(ctx: Context, roomId: String, roomName: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                channelId(roomId), roomName.ifBlank { "Sala ${roomId.take(8)}" },
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = ctx.getString(R.string.channel_messages_desc) },
        )
    }

    fun canNotify(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) false else NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /**
     * Construye y publica las notificaciones de una tanda de mensajes nuevos.
     * Se llama desde el callback del engine (hilo IO): puede consultar la DB.
     *
     * @param suppressRoom sala abierta en primer plano (no se notifica).
     */
    suspend fun notifyNewMessages(
        ctx: Context,
        db: AppDatabase,
        myUid: String,
        inForeground: Boolean,
        suppressRoom: String?,
        msgs: List<NewMsg>,
    ) {
        if (msgs.isEmpty() || !canNotify(ctx)) return
        val now = System.currentTimeMillis()
        val fresh = msgs.filter { !it.mine && it.ts >= now - RECENT_MS }
        // la sala abierta en foreground se ve en vivo; el resto sí avisa
        val toNotify = fresh.filterNot { inForeground && it.roomId.equals(suppressRoom, ignoreCase = true) }
        if (toNotify.isEmpty()) return

        // nombre propio para el MessagingStyle (perfil E2EE o username)
        val me = Person.Builder()
            .setName(db.profileDao().get(myUid)?.displayName ?: "Yo")
            .build()

        val nm = NotificationManagerCompat.from(ctx)
        val byRoom = toNotify.groupBy { it.roomId }
        var total = 0

        byRoom.forEach { (roomId, roomMsgs) ->
            val room = db.roomDao().get(roomId)
            val roomName = room?.name ?: "Sala ${roomId.take(8)}"
            // título con el nombre del par (1-a-1, D61) si se conoce
            val peerUid = roomMsgs.map { it.senderUid }.filter { it.isNotEmpty() }.firstOrNull()
            val peerProfile = peerUid?.let { db.profileDao().get(it) }
            val title = peerProfile?.displayName ?: roomName
            ensureRoomChannel(ctx, roomId, title)

            val style = NotificationCompat.MessagingStyle(me)
                .setConversationTitle(title)
            roomMsgs.takeLast(MAX_BUBBLES).forEach { m ->
                val person = Person.Builder()
                    .setName(db.profileDao().get(m.senderUid)?.displayName ?: m.senderUname.ifBlank { "—" })
                    .build()
                // adjuntos (D76): el sobre E2EE no se muestra — sí su resumen
                val shown = AttachmentMsg.preview(m.body) ?: m.body
                style.addMessage(shown, m.ts, person)
            }

            val pi = PendingIntent.getActivity(
                ctx, roomId.hashCode(),
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(Doorbell.EXTRA_ROOM, roomId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, channelId(roomId))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setStyle(style)
                .setContentTitle(title)
                .setContentText(AttachmentMsg.preview(roomMsgs.last().body) ?: roomMsgs.last().body)
                .setNumber(roomMsgs.size)
                .setWhen(roomMsgs.last().ts)
                .setShowWhen(true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // API 24-25
                .setDefaults(NotificationCompat.DEFAULT_ALL)   // API 24-25
                .build()
            try {
                nm.notify(notifId(roomId), n)
                total += roomMsgs.size
            } catch (_: SecurityException) {
                // permiso revocado a mitad de carrera
            }
        }

        if (total > 0 && byRoom.size > 1) {
            // resumen del grupo: apila las salas en una sola carta
            val n = NotificationCompat.Builder(ctx, MESSAGES_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(
                    ctx.getString(R.string.notif_summary, total, byRoom.size),
                )
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            try {
                nm.notify(SUMMARY_ID, n)
            } catch (_: SecurityException) {
            }
        }
    }

    /** Al abrir una sala: fuera su notificación (y la del resumen si sobra). */
    fun cancelRoom(ctx: Context, roomId: String) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.cancel(notifId(roomId))
        // si ya no queda ninguna sala con aviso, el resumen también cae
        val active = nm.activeNotifications
        val anyRoom = active.any {
            it.id != SUMMARY_ID && it.id != Doorbell.SVC_NOTIF_ID &&
                it.notification?.group == GROUP && it.id != notifId(roomId)
        }
        if (!anyRoom) nm.cancel(SUMMARY_ID)
    }

    /** Cierre de sesión: limpiar avisos (la del servicio la quita el stop). */
    fun cancelAllMessages(ctx: Context) {
        NotificationManagerCompat.from(ctx).apply {
            activeNotifications
                .filter { it.notification?.group == GROUP || it.id == SUMMARY_ID }
                .forEach { cancel(it.id) }
        }
    }
}
