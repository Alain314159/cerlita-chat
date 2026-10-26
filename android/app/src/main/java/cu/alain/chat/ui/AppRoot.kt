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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cu.alain.chat.ChatApp
import cu.alain.chat.db.Room
import cu.alain.chat.engine.AppState
import cu.alain.chat.ui.screens.AuthScreens
import cu.alain.chat.ui.screens.ChatScreen
import cu.alain.chat.ui.screens.RoomsScreen
import cu.alain.chat.ui.screens.SettingsScreen

/** Navegación interna de la zona Main (pila simple). */
private sealed interface Screen {
    data object Home : Screen
    data class Chat(val room: Room) : Screen
    data object Settings : Screen
}

/**
 * Raíz de la UI: el AppState del motor decide la pantalla (login/main).
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
        AppState.Login -> AuthScreens(state, app)

        AppState.Main -> {
            val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
            val current = stack.last()

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when (val c = current) {
                                    Screen.Home -> "Chats"
                                    is Screen.Chat -> c.room.name
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
                        Screen.Home -> RoomsScreen(app) { room ->
                            stack.removeAll { it is Screen.Chat }
                            stack.add(Screen.Chat(room))
                        }
                        is Screen.Chat -> ChatScreen(app, c.room.roomId)
                        Screen.Settings -> SettingsScreen(app)
                    }
                }
            }
        }
    }
}
