package cu.alain.chat.notify

import cu.alain.chat.ChatApp
import cu.alain.chat.net.Ntfy
import cu.alain.chat.net.SyncRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Poll ntfy (D104) — el respaldo del timbre Realtime con el sobre cifrado.
 *
 * Un SOLO loop por proceso (lo enciende/apaga ChatApp.syncDoorbell, el punto
 * único de decisión D72). Estrategia de gasto (batería/datos, red ETECSA):
 *
 *  - WS del timbre CONECTADO → el timbre ya despierta al instante; NO se
 *    polea (cero gasto extra). El poll lo cubren el arranque del loop tras
 *    corte y el SyncWorker de ~15 min (drain barato de la caché de 12 h).
 *  - WS CAÍDO (con la app abierta o el FGS vivo) → poll cada 15 s mientras
 *    dure el corte: el canal ntfy no pasa por Supabase, así que es el que
 *    sigue funcionando cuando el problema ES Supabase.
 *  - App cerrada sin FGS → nada (OEM/batería): el SyncWorker de 15 min hace
 *    el poll en su pasada y notifica sin abrir la app.
 *
 * Cada poll: SIEMPRE con since (regla dura ntfy.sh — sin cursor relee 12 h de
 * caché y el egress se agota). El solape de 30 s lo deshace el dedup por msgId.
 * Tras el ingest, UN /sync de confirmación rellena seq (fillSeq), recibos y el
 * resto del lote — fuente de verdad de siempre (D63): el push acelera, nunca
 * decide.
 */
object NtfyChannel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    /** Estado del último poll, para Ajustes. */
    @Volatile var lastOk: Boolean = false
        private set
    @Volatile var lastAt: Long = 0
        private set
    @Volatile var lastMsgs: Int = 0
        private set
    @Volatile var lastErr: String? = null
        private set

    const val META_SINCE = "ntfy_since"
    private const val POLL_EVERY_MS = 15_000L
    private const val OVERLAP_S = 30
    private const val BOOT_WINDOW_S = 300
    private const val MAX_TOPICS = 20

    /** Para Ajustes: hace cuántos segundos fue el último poll OK. */
    fun secondsSinceOk(): Long {
        val t = lastAt
        return if (t <= 0) -1 else (System.currentTimeMillis() - t) / 1000
    }

    /**
     * Arranca el loop (idempotente). Solo decide; el poll pesado lo hace
     * pollOnce, que comparten el loop (WS caído) y el SyncWorker (15 min).
     */
    fun ensure(app: ChatApp) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                delay(POLL_EVERY_MS)
                try {
                    if (app.store.session == null) continue
                    val fgs = DoorbellService.running
                    val wsOk = if (fgs) DoorbellService.connected else app.doorbellConnected
                    if (fgs || app.inForeground) {
                        if (!wsOk) pollOnce(app, "ws-down") // respaldo activo solo en el corte
                    }
                } catch (_: Exception) {
                    // el loop nunca muere por un poll suelto
                }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /**
     * Un poll multi-tema + ingest del sobre + /sync de confirmación.
     * Devuelve nº de mensajes ingeridos por push (0 si no había nada), -1 si
     * falló la red. Es best-effort: nunca lanza.
     */
    suspend fun pollOnce(app: ChatApp, @Suppress("UNUSED_PARAMETER") reason: String): Int {
        try {
            if (app.store.session == null) return 0
            val rooms = app.db.roomDao().all()
            val byTopic = LinkedHashMap<String, String>() // topic → roomId (mis salas con clave)
            for (r in rooms) {
                val t = app.engine.ntfyTopicFor(r.roomId) ?: continue
                byTopic[t] = r.roomId.lowercase()
            }
            if (byTopic.isEmpty()) return 0

            val savedMs = app.db.metaDao().getLong(META_SINCE) ?: 0L
            val baseS = if (savedMs > 0) savedMs / 1000 else System.currentTimeMillis() / 1000 - BOOT_WINDOW_S
            val sinceS = baseS - OVERLAP_S

            val evs = app.ntfy.poll(byTopic.keys.take(MAX_TOPICS).toList(), sinceS.toInt())
            if (evs == null) {
                lastOk = false
                lastErr = "sin conexión"
                return -1
            }

            val toIngest = mutableListOf<SyncRow>()
            var pures = 0
            for (e in evs) {
                val rid = byTopic[e.topic] ?: continue        // de un tema que ya no es mío
                val ping = Ntfy.parsePing(e.message) ?: continue // attachment/basura: silencio total
                if (ping.roomId.lowercase() != rid) continue   // r ajeno en mi tema (el AEAD lo tirará igual)
                if (ping.rows.isEmpty()) { pures++; continue } // puro: solo despierta
                toIngest.addAll(ping.rows)
            }

            var fresh = 0
            if (toIngest.isNotEmpty()) {
                val newMsgs = app.engine.ingestFromPush(toIngest)
                if (newMsgs.isNotEmpty()) app.announceIncoming(newMsgs) // texto ya descifrado, MessagingStyle
                fresh = newMsgs.size
            }
            if (toIngest.isNotEmpty() || pures > 0) {
                app.engine.syncOnce() // confirmación: seq, recibos y el resto del lote
            }

            app.db.metaDao().setLong(META_SINCE, System.currentTimeMillis())
            lastOk = true
            lastAt = System.currentTimeMillis()
            lastMsgs = fresh
            lastErr = null
            return fresh
        } catch (e: Exception) {
            lastOk = false
            lastErr = e.message?.take(60) ?: "error"
            return -1
        }
    }
}
