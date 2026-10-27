package cu.alain.chat.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import cu.alain.chat.ChatApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Acciones de la notificación (D79):
 *  - RESPONDER: RemoteInput (respuesta directa sin abrir la app). El texto
 *    viaja cifrado igual que cualquier mensaje (engine.send + outbox).
 *  - MARCAR LEÍDO: baja el badge y manda el recibo ✓✓.
 *
 * goAsync: el sistema nos da ~10 s; el envío real lo hace el outbox con
 * reintentos (WorkSync) aunque la red tarde — aquí solo lo encolamos.
 */
class ReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "cu.alain.chat.action.REPLY"
        const val ACTION_MARK_READ = "cu.alain.chat.action.MARK_READ"
        const val EXTRA_ROOM = "room"
        const val KEY_REPLY_TEXT = "reply_text"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ChatApp ?: return
        val roomId = intent.getStringExtra(EXTRA_ROOM)?.takeIf { it.isNotEmpty() } ?: return
        val action = intent.action
        if (action != ACTION_REPLY && action != ACTION_MARK_READ) return

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                when (action) {
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) {
                            app.engine.send(roomId, text)
                                .onSuccess {
                                    cu.alain.chat.work.WorkSync.kickOutbox(app)
                                    app.engine.poke()
                                    // salió (o quedó en cola con reintentos): el aviso
                                    // de esa sala ya cumplió su función
                                    Notifier.cancelRoom(context, roomId)
                                }
                        }
                    }
                    ACTION_MARK_READ -> {
                        app.db.roomDao().resetUnread(roomId)
                        app.engine.maybeSendReadReceipt(roomId)
                        Notifier.cancelRoom(context, roomId)
                    }
                }
            } catch (_: Exception) {
                // sin sesión/clave: la notificación se queda como estaba
            } finally {
                pending.finish()
            }
        }
    }
}
