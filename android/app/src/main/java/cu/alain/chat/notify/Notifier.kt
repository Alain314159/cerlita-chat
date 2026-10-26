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
import cu.alain.chat.MainActivity
import cu.alain.chat.R

/** Notificación local de mensajes nuevos (fallback sin FCM, D9). */
object Notifier {

    private const val CHAT_CHANNEL = "messages"
    private const val ID_NEW = 1001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                ctx.getString(R.string.channel_messages).let { CHAT_CHANNEL },
                ctx.getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = ctx.getString(R.string.channel_messages_desc) }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun canNotify(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) false else NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun notifyNew(ctx: Context, count: Int) {
        if (!canNotify(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHAT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Mensajes nuevos")
            .setContentText(if (count == 1) "Tienes 1 mensaje nuevo" else "Tienes $count mensajes nuevos")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setNumber(count)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_NEW, n)
        } catch (_: SecurityException) {
            // permiso revocado a mitad de carrera
        }
    }
}
