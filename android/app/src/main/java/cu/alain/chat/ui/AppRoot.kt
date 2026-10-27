package cu.alain.chat.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.db.Room
import cu.alain.chat.engine.AppState
import cu.alain.chat.notify.Notifier
import cu.alain.chat.ui.screens.AuthScreens
import cu.alain.chat.ui.screens.ChatScreen
import cu.alain.chat.ui.screens.ProfileScreen
import cu.alain.chat.ui.screens.RoomSettingsScreen
import cu.alain.chat.ui.screens.RoomsScreen
import cu.alain.chat.ui.screens.SettingsScreen

/** Navegación interna de la zona Main (pila simple). */
private sealed interface Screen {
    data object Home : Screen
    data class Chat(val room: Room) : Screen
    data object Settings : Screen
    data class Profile(val firstRun: Boolean) : Screen
    data class RoomSettings(val roomId: String) : Screen
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
        if (state is AppState.Main) {
            // login hecho (o app abierta con sesión): timbre foreground (D63)
            cu.alain.chat.notify.Doorbell.ensureStarted(ctx)
            if (Build.VERSION.SDK_INT >= 33 && !app.prefs.askedNotifPermission) {
                app.prefs.askedNotifPermission = true
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    when (state) {
        AppState.Login -> AuthScreens(state, app)

        AppState.Main -> {
            val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
            val current = stack.last()

            // Botón ATRÁS del teléfono (v0.6.0): navega hacia atrás en vez de
            // cerrar la app desde cualquier pantalla. En la raíz (lista de
            // chats) el atrás sale de la app — comportamiento normal. En el
            // onboarding de perfil NO hay atrás: hay que poner nombre (o
            // "Ahora no"), si no el efecto lo re-abriría en bucle.
            val onboarding = current is Screen.Profile && current.firstRun
            BackHandler(enabled = stack.size > 1 && !onboarding) {
                stack.removeAt(stack.lastIndex)
            }

            // Tocar una notificación abre ESA sala (D63): el extra llega por
            // MainActivity (onCreate/onNewIntent) y aquí se consume.
            val pendingRoom by app.pendingOpenRoom.collectAsState()
            LaunchedEffect(pendingRoom) {
                val rid = pendingRoom ?: return@LaunchedEffect
                app.pendingOpenRoom.value = null
                val room = app.db.roomDao().get(rid) ?: return@LaunchedEffect
                stack.removeAll { it is Screen.Chat || it is Screen.Settings || it is Screen.Profile || it is Screen.RoomSettings }
                stack.add(Screen.Chat(room))
            }

            // Exención de batería (D67): una sola vez, con explicación. Sin
            // ella el sistema puede congelar el timbre con la pantalla apagada.
            var askBattery by remember { mutableStateOf(false) }
            LaunchedEffect(state) {
                if (!app.prefs.askedBattery) {
                    app.prefs.askedBattery = true
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) askBattery = true
                }
            }
            if (askBattery) {
                AlertDialog(
                    onDismissRequest = { askBattery = false },
                    title = { Text("Permitir avisos con la pantalla apagada") },
                    text = {
                        Text(
                            "Para que los mensajes nuevos avisen al instante con la app cerrada, " +
                                "Android debe eximir a Cerlita Chat del ahorro de batería.\n\n" +
                                "En el próximo diálogo elige «Permitir».\n\n" +
                                "En Xiaomi/Redmi revisa además: Autoinicio activado y batería " +
                                "«Sin restricciones» para la app.",
                        )
                    },
                    confirmButton = {
                        TextButton({
                            askBattery = false
                            try {
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + ctx.packageName),
                                    ),
                                )
                            } catch (_: Exception) {
                                // algún OEM no lo soporta: queda el ajuste manual
                            }
                        }) { Text("Permitir") }
                    },
                    dismissButton = { TextButton({ askBattery = false }) { Text("Ahora no") } },
                )
            }

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
                                is Screen.RoomSettings -> Text("Ajustes de la sala")
                            }
                        },
                        navigationIcon = {
                            // atrás visible en toda sub-pantalla menos el onboarding
                            // de perfil (v0.6.0: antes faltaba en «Editar perfil»)
                            if (stack.size > 1 &&
                                !(current is Screen.Profile && current.firstRun)
                            ) {
                                IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                                }
                            }
                        },
                        actions = {
                            when (current) {
                                is Screen.Home -> IconButton(onClick = { stack.add(Screen.Settings) }) {
                                    Icon(Icons.Default.Settings, "Ajustes")
                                }
                                is Screen.Chat -> IconButton(onClick = {
                                    stack.add(Screen.RoomSettings(current.room.roomId))
                                }) {
                                    Icon(Icons.Default.Settings, "Ajustes de la sala")
                                }
                                else -> {}
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
                            stack.removeAll { it is Screen.Chat || it is Screen.RoomSettings }
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
                        is Screen.RoomSettings -> RoomSettingsScreen(
                            app = app,
                            roomId = c.roomId,
                            onDeleted = {
                                // la sala murió: volver a la lista de chats
                                stack.removeAll { it is Screen.Chat || it is Screen.RoomSettings }
                                Notifier.cancelRoom(ctx, c.roomId)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Título del chat abierto: si la sala tiene foto/nombre propio (D69) se
 *  muestran; si no, el perfil del par (nombre + avatar, D61). */
@Composable
private fun ChatTitle(app: ChatApp, room: Room) {
    val meUid = app.engine.session?.uid ?: ""
    val header by app.db.roomListDao().observeRoomHeader(room.roomId, meUid)
        .collectAsState(initial = null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (header?.roomPhoto == true) {
            AvatarView(
                photoUid = "room-${room.roomId.lowercase()}",
                photo = true,
                avatarJson = null,
                fallbackInitial = header?.name ?: room.name,
                size = 32.dp,
                photoVersion = 0L,
            )
        } else {
            AvatarView(
                photoUid = header?.peerUid,
                photo = header?.peerPhoto == true,
                avatarJson = header?.peerAvatarJson,
                fallbackInitial = header?.peerName ?: room.name,
                size = 32.dp,
                photoVersion = 0L,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (header?.roomPhoto == true) Text(header?.name ?: room.name)
        else Text(header?.peerName ?: room.name)
    }
}
