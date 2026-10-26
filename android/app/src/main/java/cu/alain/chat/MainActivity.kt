package cu.alain.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cu.alain.chat.engine.AppState
import cu.alain.chat.ui.AppRoot
import cu.alain.chat.ui.theme.ChatTheme
import cu.alain.chat.work.WorkSync

class MainActivity : ComponentActivity() {

    private val app get() = application as ChatApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        if (app.engine.state.value != AppState.Login) {
            WorkSync.syncNow(this)
            app.engine.startPolling()
        }
    }

    override fun onStop() {
        super.onStop()
        app.inForeground = false
        app.engine.stopPolling()
    }
}
