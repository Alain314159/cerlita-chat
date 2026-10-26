package cu.alain.chat.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.db.Room
import cu.alain.chat.engine.AppState
import cu.alain.chat.ui.screens.AuthScreens
import cu.alain.chat.ui.screens.ChatScreen
import cu.alain.chat.ui.screens.ProfileScreen
import cu.alain.chat.ui.screens.RoomsScreen
import cu.alain.chat.ui.screens.SettingsScreen

/** Navegación interna de la zona Main (pila simple). */
private sealed interface Screen {
    data object Home : Screen
    data class Chat(val room: Room) : Screen
    data object Settings : Screen
    data class Profile(val firstRun: Boolean) : Screen
}

/**
 * Raíz de la UI: el AppState del motor decide la pantalla (login/main).
 * Dentro de Main hay una pila de navegación mínima, sin librería de nav
 * (app de 4 pantallas: la nav manual pesa menos y no tiene sorpresas).
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

            // Onboarding de perfil (D61): sin perfil propio → pantalla de perfil
            // tras entrar. Con "Guardar" o "Ahora no" siempre queda uno (el
            // anuncio a las salas necesita un perfil que existir).
            LaunchedEffect(state, stack.size) {
                if (stack.size == 1 && stack[0] == Screen.Home) {
                    val s = app.engine.session
                    if (s != null && app.db.profileDao().get(s.uid) == null) {
                        stack.add(Screen.Profile(firstRun = true))
                    }
                }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            when (val c = current) {
                                Screen.Home -> Text("Chats")
                                is Screen.Chat -> ChatTitle(app, c.room)
                                Screen.Settings -> Text("Ajustes")
                                is Screen.Profile ->
                                    Text(if (c.firstRun) "Tu perfil" else "Editar perfil")
                            }
                        },
                        navigationIcon = {
                            if (stack.size > 1 && current !is Screen.Profile) {
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
                        Screen.Settings -> SettingsScreen(app) {
                            stack.add(Screen.Profile(firstRun = false))
                        }
                        is Screen.Profile -> ProfileScreen(
                            app = app,
                            firstRun = c.firstRun,
                            onDone = { stack.removeAt(stack.lastIndex) },
                        )
                    }
                }
            }
        }
    }
}

/** Título del chat abierto: avatar + nombre del par (D61), o nombre local. */
@Composable
private fun ChatTitle(app: ChatApp, room: Room) {
    val meUid = app.engine.session?.uid ?: ""
    val header by app.db.roomListDao().observeRoomHeader(room.roomId, meUid)
        .collectAsState(initial = null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarView(
            photoUid = header?.peerUid,
            photo = header?.peerPhoto == true,
            avatarJson = header?.peerAvatarJson,
            fallbackInitial = header?.peerName ?: room.name,
            size = 32.dp,
            photoVersion = 0L,
        )
        Spacer(Modifier.width(10.dp))
        Text(header?.peerName ?: room.name)
    }
}
