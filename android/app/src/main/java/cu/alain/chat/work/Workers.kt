package cu.alain.chat.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cu.alain.chat.ChatApp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Trabajo en segundo plano (D8):
 *  - SyncWorker periódico cada ~15 min (mínimo del sistema): baja y descifra;
 *    si hay mensajes nuevos y la app NO está en primer plano → notificación.
 *  - OutboxWorker puntual tras cada envío: drena el outbox con reintentos.
 *
 * El backoff exponencial de WorkManager no trae jitter; se añade un retardo
 * aleatorio (+0-30 s) en el camino de reintento para desincronizar los
 * teléfonos cuando la red vuelve tras un apagón (evita la tormenta de reconexión).
 */
object WorkSync {
    private const val SYNC_PERIODIC = "sync_periodic"
    private const val OUTBOX = "outbox_once"
    private const val SYNC_ONCE = "sync_once"

    private fun net() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            SYNC_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net())
                .build(),
        )
    }

    fun kickOutbox(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            OUTBOX,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(net())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun syncNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SYNC_ONCE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(net())
                .build(),
        )
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ChatApp
        // D104: drain ntfy primero (caché de 12 h: pings que llegaron mientras
        // no había proceso) — el ping puede traer el sobre cifrado: se ingiere
        // y notifica SIN abrir la app. Best-effort: si ntfy falla, el /sync de
        // abajo trae lo mismo por la vía clásica.
        try { cu.alain.chat.notify.NtfyChannel.pollOnce(app, "worker") } catch (_: Exception) {}
        // Las notificaciones salen del callback del engine (misma política que
        // timbre Realtime, D63) — aquí solo se sincroniza.
        app.engine.syncOnce()
        return Result.success() // el periódico nunca se rinde por red: reintenta en 15 min
    }
}

class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ChatApp
        return try {
            app.engine.drainOutbox(onAuthLost = { app.engine.refreshState() })
            // queda algo sin ack (mensaje partido entre lotes) → reintenta
            val remaining = app.db.outboxDao().batch().isNotEmpty()
            if (remaining) {
                delay(Random.nextLong(3_000, 30_000)) // jitter (D8)
                Result.retry()
            } else Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 10) {
                delay(Random.nextLong(3_000, 30_000)) // jitter (D8)
                Result.retry()
            } else Result.failure() // se reintentará al próximo envío o apertura
        }
    }
}
