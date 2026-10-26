package cu.alain.chat.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cu.alain.chat.ChatApp
import cu.alain.chat.engine.AppState
import cu.alain.chat.ui.screens.AuthScreens
import cu.alain.chat.ui.screens.ChatScreen
import cu.alain.chat.ui.screens.ChatsScreen
import cu.alain.chat.ui.screens.SettingsScreen

/** Navegación interna de la zona Main (pila simple). */
private sealed interface Screen {
    data object Home : Screen
    data class Chat(val peer: String) : Screen
    data object Settings : Screen
}

/**
 * Raíz de la UI: el AppState del motor decide la pantalla (login/setup/unlock/main).
 * Dentro de Main hay una pila de navegación mínima, sin librería de nav
 * (app de 3 pantallas: la nav manual pesa menos y no tiene sorpresas).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(state: AppState, app: ChatApp, onForeground: (Boolean) -> Unit) {
    val ctx = LocalContext.current

    // Permiso de notificaciones (33+) — se pide una vez al entrar a Main
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(state) {
        if (state is AppState.Main && Build.VERSION.SDK_INT >= 33 &&
            !app.prefs.askedNotifPermission
        ) {
            app.prefs.askedNotifPermission = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (state) {
        AppState.Login, AppState.Setup, AppState.Unlock -> AuthScreens(state, app)

        AppState.Main -> {
            val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
            val current = stack.last()
            val chatPeer = remember { mutableStateOf<String?>(null) }

            // abrir un chat desde fuera (p. ej. al añadir contacto)
            LaunchedEffect(chatPeer.value) {
                chatPeer.value?.let { p ->
                    stack.removeAll { it is Screen.Chat }
                    stack.add(Screen.Chat(p))
                    chatPeer.value = null
                }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when (val c = current) {
                                    Screen.Home -> "Chats"
                                    is Screen.Chat -> c.peer
                                    Screen.Settings -> "Ajustes"
                                }
                            )
                        },
                        navigationIcon = {
                            if (stack.size > 1) {
                                IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                                }
                            }
                        },
                        actions = {
                            if (current is Screen.Home) {
                                IconButton(onClick = { stack.add(Screen.Settings) }) {
                                    Icon(Icons.Default.Settings, "Ajustes")
                                }
                            }
                        },
                    )
                },
            ) { pad ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad),
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (val c = current) {
                        Screen.Home -> ChatsScreen(app) { peer ->
                            chatPeer.value = peer
                        }
                        is Screen.Chat -> ChatScreen(app, c.peer)
                        Screen.Settings -> SettingsScreen(app)
                    }
                }
            }
        }
    }
}
