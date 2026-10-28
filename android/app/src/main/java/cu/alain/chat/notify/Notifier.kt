package cu.alain.chat.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import cu.alain.chat.MainActivity
import cu.alain.chat.R
import cu.alain.chat.db.AppDatabase
import cu.alain.chat.engine.NewMsg
import cu.alain.chat.profile.AttachmentMsg
import cu.alain.chat.profile.AvatarConfig
import cu.alain.chat.ui.AvatarCatalog
import cu.alain.chat.ui.decodeProfilePhoto
import java.io.File

/**
 * Notificaciones de mensajes (rediseñado v0.7.1, D79):
 *
 *  - UN canal por sala (IMPORTANCE_HIGH: sonido + heads-up) con el nombre de
 *    la sala → el usuario silencia/activa cada conversación desde Ajustes
 *    del sistema.
 *  - MessagingStyle con AVATAR de verdad: foto de sala → foto del par →
 *    avatar por capas (cerdita/koalita) → inicial coral. Circular.
 *  - Acción «Responder» (RemoteInput): contestar sin abrir la app.
 *  - Acción «Marcar leído».
 *  - Color acento Cerlita + apodo personal (D78) en el título.
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

    /** Acento del tema Cerlita (coral del mockup, D77). */
    private const val ACCENT = 0xFFD87884.toInt()

    /** Umbral anti-re-sync (§3.2 auditoría): una tanda ENORME y toda antigua
     *  es historial de un teléfono nuevo, no noticia — no se notifica. */
    private const val RESYNC_COUNT = 12
    private const val RESYNC_MS = 24L * 60 * 60 * 1000

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
        // §3.2 (auditoría): antes, todo lo de hace >30 min llegaba EN SILENCIO
        // — el teléfono 2 h sin red y el mensaje importante de hace 45 min no
        // avisaba NADA. Regla nueva: SIEMPRE se avisa del último de cada sala
        // (una notificación por sala con contador — nada de spam), y la única
        // tanda que se descarta es la re-sincronización evidente: enorme y
        // toda con más de 24 h (historial de un teléfono nuevo, no noticia).
        val fresh = msgs.filter { !it.mine }
        // la sala abierta en foreground se ve en vivo; el resto sí avisa
        val toNotify = fresh.filterNot { inForeground && it.roomId.equals(suppressRoom, ignoreCase = true) }
            .groupBy { it.roomId }
            .filterNot { (_, rms) ->
                rms.size > RESYNC_COUNT && rms.all { it.ts < now - RESYNC_MS }
            }
            .flatMap { it.value }
        if (toNotify.isEmpty()) return

        val appCtx = ctx.applicationContext as? cu.alain.chat.ChatApp

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
            // título con el APODO personal (D78) o el nombre del par (1-a-1, D61)
            val peerUid = roomMsgs.map { it.senderUid }.filter { it.isNotEmpty() }.firstOrNull()
            val peerProfile = peerUid?.let { db.profileDao().get(it) }
            val title = peerUid?.let { appCtx?.prefs?.nicknameOf(it) }
                ?: peerProfile?.displayName ?: roomName
            ensureRoomChannel(ctx, roomId, title)

            // avatar de la conversación (D79): foto sala → foto par → capas → inicial
            val avatar = buildAvatar(ctx, db, roomId, peerUid, peerProfile?.avatarJson, title)

            val style = NotificationCompat.MessagingStyle(me)
                .setConversationTitle(title)
            roomMsgs.takeLast(MAX_BUBBLES).forEach { m ->
                val senderName = db.profileDao().get(m.senderUid)?.let { p ->
                    appCtx?.prefs?.nicknameOf(m.senderUid) ?: p.displayName
                } ?: m.senderUname.ifBlank { "—" }
                val person = Person.Builder()
                    .setName(senderName)
                    .apply { avatar?.let { a -> setIcon(IconCompat.createWithBitmap(a)) } }
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

            // ── acción RESPONDER sin abrir la app (D79) ──
            val remoteInput = RemoteInput.Builder(ReplyReceiver.KEY_REPLY_TEXT)
                .setLabel("Escribe tu respuesta…")
                .build()
            val replyPi = PendingIntent.getBroadcast(
                ctx, "reply$roomId".hashCode(),
                Intent(ctx, ReplyReceiver::class.java).apply {
                    action = ReplyReceiver.ACTION_REPLY
                    putExtra(ReplyReceiver.EXTRA_ROOM, roomId)
                },
                // MUTABLE: el sistema inyecta el texto de la respuesta
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val replyAction = NotificationCompat.Action.Builder(0, "Responder", replyPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .build()

            // ── acción MARCAR LEÍDO ──
            val readPi = PendingIntent.getBroadcast(
                ctx, "read$roomId".hashCode(),
                Intent(ctx, ReplyReceiver::class.java).apply {
                    action = ReplyReceiver.ACTION_MARK_READ
                    putExtra(ReplyReceiver.EXTRA_ROOM, roomId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val readAction = NotificationCompat.Action.Builder(0, "Marcar leído", readPi).build()

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
                .setColor(ACCENT)                       // acento coral (D77)
                .setLargeIcon(avatar)                   // avatar grande (heads-up)
                .addAction(replyAction)                 // responder directo (D79)
                .addAction(readAction)                  // marcar leído
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
                .setColor(ACCENT)
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

    // ────────────────────────── avatar (D79) ──────────────────────────

    /**
     * Avatar de la conversación en este orden: foto de sala personalizada →
     * foto de perfil del par → avatar por capas (cerdita/koalita con ropa y
     * accesorios) → inicial coral. Siempre circular y de 128 px.
     */
    private fun buildAvatar(
        ctx: Context,
        db: AppDatabase,
        roomId: String,
        peerUid: String?,
        peerAvatarJson: String?,
        name: String,
    ): Bitmap {
        val dir = File(ctx.filesDir, "avatars")
        // 1. foto de la sala (D69)
        decodeProfilePhoto(File(dir, "room-${roomId.lowercase()}.jpg").absolutePath, maxPx = 128)
            ?.let { return circleCrop(it) }
        // 2. foto de perfil del par (D61)
        if (peerUid != null) {
            decodeProfilePhoto(File(dir, "$peerUid.jpg").absolutePath, maxPx = 128)
                ?.let { return circleCrop(it) }
            // 3. avatar por capas (D61): cerdita/koalita + ropa + accesorios
            AvatarConfig.parse(peerAvatarJson)?.let { cfg ->
                layeredAvatar(ctx, cfg)?.let { return circleCrop(it) }
            }
        }
        // 4. inicial coral
        return letterTile(name)
    }

    /** Rasteriza el avatar por capas (vector) a un bitmap 128×128. */
    private fun layeredAvatar(ctx: Context, cfg: AvatarConfig): Bitmap? {
        val base = AvatarCatalog.base(cfg.base) ?: return null
        val family = base.family
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // D92: mismo margen interno que AvatarView — el sistema recorta el
        // icono a círculo; sin margen las orejas/pies quedaban cortados.
        val inset = (size * 0.07f).toInt()
        fun drawLayer(res: Int) {
            val d = ContextCompat.getDrawable(ctx, res) ?: return
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(canvas)
        }
        // D92: decoración (DETRÁS) → base → ropa (ENCIMA del torso) →
        // accesorios (ENCIMA). Con cuerpo completo la camiseta tapa el pecho.
        AvatarCatalog.decorRes(cfg.decor, family)?.let(::drawLayer)
        drawLayer(base.res)
        AvatarCatalog.clothesRes(cfg.clothes, family)?.let(::drawLayer)
        listOf(AvatarCatalog.SLOT_HEAD, AvatarCatalog.SLOT_EYES, AvatarCatalog.SLOT_CHEST)
            .forEach { slot -> AvatarCatalog.accRes(cfg.acc?.get(slot), family)?.let(::drawLayer) }
        return bmp
    }

    /** Recorta cuadrado (centrado) + círculo. */
    private fun circleCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val square = if (src.width == src.height) src
        else Bitmap.createBitmap(
            src,
            (src.width - side) / 2, (src.height - side) / 2, side, side,
        )
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        c.drawCircle(side / 2f, side / 2f, side / 2f, paint)
        return out
    }

    /** Círculo coral con la inicial (lo que muestra WhatsApp para desconocidos). */
    private fun letterTile(name: String): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(
            size / 2f, size / 2f, size / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT },
        )
        val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (tp.ascent() + tp.descent()) / 2f
        c.drawText(initial.toString(), size / 2f, y, tp)
        return bmp
    }
}
