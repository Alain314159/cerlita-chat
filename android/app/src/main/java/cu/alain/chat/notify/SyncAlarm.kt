package cu.alain.chat.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cu.alain.chat.ChatApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * S1 (D105): TERCERA capa de despertar — AlarmManager.
 *
 * Capas de aviso tras esta:
 *  1. Timbre WebSocket (FGS remoteMessaging o in-app): instantáneo.
 *  2. ntfy (D104): respaldo con sobre cifrado cuando Supabase/WS cae — su
 *     loop polea solo si el WS está caído; el SyncWorker de 15 min drena
 *     la caché cuando no hay proceso.
 *  3. ESTA alarma: setExactAndAllowWhileIdle cada ~4,5-6 min. En Doze es
 *     de las pocas excepciones que SÍ se ejecuta (WorkManager está clavado
 *     a 15 min y el FGS puede morir a manos del OEM aunque el usuario haya
 *     activado «avisos instantáneos»). Con ella, el peor caso con el FGS
 *     muerto baja de 15 min a ≤ 6 min (Doze profundo la espacia a ~9 min —
 *     límite del sistema, documentado).
 *
 * Degradación honesta: sin el permiso especial de alarmas exactas (Android
 * 13+ no lo concede por defecto) se usa setAndAllowWhileIdle (inelástica:
 * el sistema puede agruparla). Nada rompe: son capas, no dependencias.
 *
 * La alarma se replanifica sola tras cada disparo (goAsync: el sync corre
 * antes de que el proceso muera) y la replanifica también syncDoorbell (el
 * punto único de decisión D72) y el BootReceiver.
 */
class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as? ChatApp ?: return
        if (app.store.session == null) return // logout/limpieza: no replanificar
        val result = goAsync() // el proceso vive mientras corra el sync
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // mismo orden que el SyncWorker (D104): ntfy primero (trae el
                // sobre cifrado sin depender de Supabase), luego /sync
                try { NtfyChannel.pollOnce(app, "alarm") } catch (_: Exception) {}
                try { app.engine.syncOnce() } catch (_: Exception) {}
            } finally {
                SyncAlarm.schedule(ctx) // cadena viva
                result.finish()
            }
        }
    }
}

object SyncAlarm {

    private const val REQUEST_CODE = 4242
    private const val INTERVAL_MS = 4 * 60 * 1000L      // 4 min base
    private const val JITTER_MS = 2 * 60 * 1000L        // +0-2 min → 4-6 min

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext as? ChatApp ?: return
        if (app.store.session == null) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(ctx)
        val at = System.currentTimeMillis() + INTERVAL_MS + Random.nextLong(0, JITTER_MS)
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            // permiso revocado en caliente: degradación limpia
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (_: Exception) {}
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try { am.cancel(pendingIntent(ctx)) } catch (_: Exception) {}
    }

    private fun pendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            Intent(ctx, SyncAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
