package cu.alain.chat.calls

import cu.alain.chat.ChatApp
import cu.alain.chat.net.IceServerCfg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.webrtc.PeerConnection

/**
 * Configuración ICE de las llamadas (v0.8.0, B6/R3 auditoría).
 *
 * PROBLEMA: con solo STUN, dos teléfonos tras NAT simétrico (típico en redes
 * móviles cubanas) no consiguen ruta directa → la llamada muere en
 * «Conectando…» aunque la señalización llegue perfecta (era el ~15 % de las
 * llamadas tras el fix D81).
 *
 * SOLUCIÓN: TURN (relé). El backend sirve la lista por /turn con credenciales
 * que viven en secrets del servidor — el APK nunca las lleva, y se rotan sin
 * actualizar la app. El audio/vídeo sigue E2EE (DTLS-SRTP): el relay solo
 * transporta bytes opacos.
 *
 * Aquí: caché en memoria de 10 min (el ttl que devuelve el servidor) con
 * prefetch en el login — a la hora de llamar, la lista ya está (una llamada
 * NUNCA espera un fetch). Si no hay red o el fetch falla: STUN de siempre,
 * que es exactamente el comportamiento v0.7.x.
 */
object TurnConfig {

    private const val TTL_MS = 10 * 60 * 1000L
    private const val FETCH_TIMEOUT_MS = 4_000L

    private val mutex = Mutex()
    private var cached: List<PeerConnection.IceServer>? = null
    private var fetchedAt = 0L

    /** STUN de siempre — el piso mínimo garantizado sin red ni servidor. */
    private val STUN_FALLBACK = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478",
    ).map { PeerConnection.IceServer.builder(it).createIceServer() }

    private fun toIceServers(cfg: List<IceServerCfg>): List<PeerConnection.IceServer> =
        cfg.map {
            PeerConnection.IceServer.builder(it.url).apply {
                if (it.username != null && it.password != null) {
                    setUsername(it.username)
                    setPassword(it.password)
                }
            }.createIceServer()
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lo llama el login/onAuthed: calienta la caché sin bloquear a nadie. */
    fun prefetch(app: ChatApp) {
        val token = app.engine.session?.token ?: return
        scope.launch { fetch(app, token) }
    }

    private suspend fun fetch(app: ChatApp, token: String): List<PeerConnection.IceServer> =
        mutex.withLock {
            val now = System.currentTimeMillis()
            val c = cached
            if (c != null && now - fetchedAt < TTL_MS) return@withLock c
            val servers = try {
                withTimeout(FETCH_TIMEOUT_MS) {
                    val cfg = app.api.fetchTurn(token)
                    if (cfg.isEmpty()) null else toIceServers(cfg)
                }
            } catch (_: Exception) {
                null // sin red / servidor lento → STUN (comportamiento v0.7.x)
            }
            // servers==null → no cachear el fallo; se reintenta en el prefetch
            // o la próxima llamada. servers==STUN-only también se cachea.
            if (servers != null) { cached = servers; fetchedAt = now }
            servers ?: c ?: STUN_FALLBACK
        }

    /**
     * Lista para crear el PeerConnection YA. Si la caché está caliente la
     * devuelve al instante; si no, hace un fetch rápido (4 s tope) y si
     * incluso eso falla, STUN — la llamada no se cancela jamás por esto.
     */
    suspend fun ensure(app: ChatApp): List<PeerConnection.IceServer> {
        val token = app.engine.session?.token
            ?: return STUN_FALLBACK
        return fetch(app, token)
    }
}
