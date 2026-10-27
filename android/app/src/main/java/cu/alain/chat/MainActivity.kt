package cu.alain.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cu.alain.chat.engine.AppState
import cu.alain.chat.notify.Doorbell
import cu.alain.chat.ui.AppRoot
import cu.alain.chat.ui.theme.ChatTheme
import cu.alain.chat.work.WorkSync

class MainActivity : ComponentActivity() {

    private val app get() = application as ChatApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeOpenRoomFromIntent(intent)
        setContent {
            val state by app.engine.state.collectAsState()
            ChatTheme {
                AppRoot(
                    state = state,
                    app = app,
                    onForeground = { fg -> app.inForeground = fg },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.inForeground = true
        app.engine.inForeground = true   // D71: tope de polling 15 s con la app abierta
        if (app.engine.state.value != AppState.Login) {
            WorkSync.syncNow(this)
            app.engine.startPolling()
        }
        app.syncDoorbell() // D72: timbre in-app (o FGS si el usuario pidió instantáneo)
    }

    override fun onStop() {
        super.onStop()
        app.inForeground = false
        app.engine.inForeground = false
        app.engine.stopPolling() // cierra el polling; el timbre decide syncDoorbell
        app.syncDoorbell()       // D72: fuera el in-app; FGS solo en modo instantáneo
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeOpenRoomFromIntent(intent)
    }

    /** Tocar una notificación abre ESA sala (extra "room" del Notifier). */
    private fun maybeOpenRoomFromIntent(intent: Intent?) {
        val room = intent?.getStringExtra(Doorbell.EXTRA_ROOM) ?: return
        if (room.isNotEmpty()) app.pendingOpenRoom.value = room
    }
}
