package cu.alain.chat.notify

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import cu.alain.chat.ChatApp
import cu.alain.chat.net.Ntfy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM (D121) — la 5ª capa de despertar, por el canal persistente de Google
 * Play Services.
 *
 * Por qué SIEMPRE convive con las otras cuatro (D63 timbre WS, D104 ntfy,
 * D105 alarma, worker 15 min): FCM solo existe donde hay Play Services —
 * en Cuba/media bola eso NO se da por sentado. El mismo principio de
 * siempre: cada canal es best-effort, el /sync es la fuente de verdad y
 * NINGUNA capa decide, solo acelera.
 *
 * Qué viaja por Google: el MISMO ping cifrado de ntfy ({"v":1,"r":…,"m":…},
 * AEAD con la roomKey que Google nunca ve) o el despertador puro. Nada más.
 * El servidor lo construye en el outbox igual que el emisor lo publica en
 * ntfy — un solo formato, un solo parser (Ntfy.parsePing), un solo pipeline.
 *
 * Degradación sin Play Services: no se pide nada especial — el Task de
 * getToken simplemente falla (callback de fallo, silencioso) y no se
 * registra nada; las capas existentes cubren igual que hoy. Sin token
 * en el servidor, el outbox no tiene a quién enviarle FCM.
 *
 * Entrega con la app MUERTA: el proceso arranca, ChatApp.onCreate() monta
 * el service locator completo y el mensaje entra por el mismo pipeline —
 * sin código nuevo de UI, sin estado especial.
 */
object FcmPush {

    /** En prefs: el último token YA registrado en el servidor (evita posts repetidos). */
    const val PREF_SENT = "fcm_sent"

    /**
     * Pide el token (una vez por rotación) y lo registra en ESTA sesión del
     * servidor. Llama syncDoorbell (login/logout/arranque). Best-effort
     * total: sin Play Services el Task falla en silencio y el próximo ciclo
     * lo reintenta; cualquier fallo se reintenta en el próximo arranque/login.
     */
    fun ensureRegistered(app: ChatApp) {
        if (app.store.session == null) return
        val sent = app.prefs.sentFcmToken
        if (sent != null) return // registrado y sin rotar: nada que hacer
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { t -> app.registerFcmToken(t) }
                .addOnFailureListener { /* sin GMS real o red: el próximo ciclo lo reintenta */ }
        } catch (_: Exception) {
            //Firebase no inicializable en este dispositivo: degradación silenciosa
        }
    }

    /**
     * Un ping FCM → el MISMO pipeline del receptor ntfy (D104): ingest del
     * sobre cifrado (sin round-trip) + notificación con texto ya descifrado
     * + /sync de confirmación que trae seq, recibos y el resto del lote.
     */
    suspend fun handlePing(app: ChatApp, payload: String) {
        try {
            val ping = Ntfy.parsePing(payload) ?: return // inyección/basura: silencio total
            if (ping.rows.isNotEmpty()) {
                val newMsgs = app.engine.ingestFromPush(ping.rows)
                if (newMsgs.isNotEmpty()) app.announceIncoming(newMsgs)
            }
            app.engine.syncOnce() // puro o con filas: siempre confirma contra la fuente de verdad
        } catch (_: Exception) {
            // best-effort: el worker de 15 min es la red de seguridad
        }
    }
}

/**
 * Servicio FCM — lo instancia el sistema al llegar el data message (high
 * priority), con la app viva O muerta. En manifest: exported=false + filtro
 * MESSAGING_EVENT (patrón estándar de Firebase).
 */
class CerlitaFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(msg: RemoteMessage) {
        val p = msg.data["p"] ?: return // despertador sin ping: nada (formato propio)
        val app = applicationContext as? ChatApp ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            FcmPush.handlePing(app, p)
        }
    }

    /** Rotación de token (Google lo rota; uninstall/reinstall también): re-registrar. */
    override fun onNewToken(token: String) {
        val app = applicationContext as? ChatApp ?: return
        app.prefs.sentFcmToken = null // fuerza re-registro en el próximo ensureRegistered
        app.registerFcmToken(token)
    }
}
