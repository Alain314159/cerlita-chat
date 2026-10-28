package cu.alain.chat

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        hideSystemButtons()
        maybeOpenRoomFromIntent(intent)
        setContent {
            val state by app.engine.state.collectAsState()
            val theme by app.themeFlow.collectAsState()
            ChatTheme(mode = theme) {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // D91: los botones del teléfono se mantienen ocultos (el foco vuelve
        // tras un diálogo/permiso y Android los re-muestra si no re-ocultamos)
        if (hasFocus) hideSystemButtons()
    }

    /**
     * D91: ocultar los botones/barra de navegación del teléfono (como pidió el
     * usuario: más pantalla para el chat, como el mockup). La barra de estado
     * (hora/batería) SE QUEDA — el mockup la muestra. Deslizar desde el borde
     * los trae de vuelta un momento (transitorios, sin robar foco).
     */
    private fun hideSystemButtons() {
        val decor = window.decorView as? ViewGroup ?: return
        WindowCompat.getInsetsController(window, decor).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /** Tocar una notificación abre ESA sala (extra "room" del Notifier). */
    private fun maybeOpenRoomFromIntent(intent: Intent?) {
        val room = intent?.getStringExtra(Doorbell.EXTRA_ROOM) ?: return
        if (room.isNotEmpty()) app.pendingOpenRoom.value = room
    }
}
