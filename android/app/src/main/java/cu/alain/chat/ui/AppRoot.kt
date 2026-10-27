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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.calls.CallDispatcher
import cu.alain.chat.db.Room
import cu.alain.chat.engine.AppState
import cu.alain.chat.notify.Notifier
import cu.alain.chat.ui.screens.AuthScreens
import cu.alain.chat.ui.screens.CallsScreen
import cu.alain.chat.ui.screens.ChatScreen
import cu.alain.chat.ui.screens.ProfileScreen
import cu.alain.chat.ui.screens.RoomSettingsScreen
import cu.alain.chat.ui.screens.RoomsScreen
import cu.alain.chat.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

/** Navegación interna de la zona Main (pila simple sobre las pestañas). */
private sealed interface Screen {
    data object Home : Screen
    data class Chat(val room: Room) : Screen
    data class Profile(val firstRun: Boolean) : Screen
    data class RoomSettings(val roomId: String) : Screen
}

/**
 * Raíz de la UI (v0.7.0): el AppState del motor decide login/main. Dentro de
 * Main hay PESTAÑAS INFERIORES (Chats · Llamadas · Ajustes, como el mockup —
 * sin «estados») y una pila mínima encima (chat, perfil, ajustes de sala).
 * Sin librería de nav: la manual pesa menos y no tiene sorpresas.
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

            // pestaña seleccionada (v0.7.0): vive AQUÍ para que el título la lea
            var tab by rememberSaveable { mutableIntStateOf(0) }

            // Llamada en curso (D75): overlay a pantalla completa ENCIMA de todo
            // (la UI sigue compuesta: la pila de navegación no se pierde)
            val callState by CallDispatcher.state.collectAsState()
            Box(Modifier.fillMaxSize()) {

                // Botón ATRÁS del teléfono (v0.6.0): navega hacia atrás en vez de
                // cerrar la app. En las pestañas el atrás sale de la app — normal.
                // En el onboarding de perfil NO hay atrás: hay que poner nombre.
                val onboarding = current is Screen.Profile && current.firstRun
                BackHandler(enabled = stack.size > 1 && !onboarding && callState == null) {
                    stack.removeAt(stack.lastIndex)
                }

                // Tocar una notificación abre ESA sala (D63).
                val pendingRoom by app.pendingOpenRoom.collectAsState()
                LaunchedEffect(pendingRoom) {
                    val rid = pendingRoom ?: return@LaunchedEffect
                    app.pendingOpenRoom.value = null
                    val room = app.db.roomDao().get(rid) ?: return@LaunchedEffect
                    stack.removeAll { it is Screen.Chat || it is Screen.RoomSettings }
                    stack.add(Screen.Chat(room))
                }

                // Exención de batería (D67): una sola vez, con explicación.
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

                // Onboarding de perfil (D61): sin perfil propio → pantalla de
                // perfil tras entrar. Con "Guardar" o "Ahora no" siempre queda uno.
                LaunchedEffect(state, stack.size) {
                    if (stack.size == 1 && stack[0] == Screen.Home) {
                        val s = app.engine.session
                        if (s != null && app.db.profileDao().get(s.uid) == null) {
                            stack.add(Screen.Profile(firstRun = true))
                        }
                    }
                }

                Scaffold(
                    // D77: en Cerlita el chat tiene su propio fondo (más rosado que
                    // las listas, como el mockup); la barra superior va a juego
                    containerColor = if (current is Screen.Chat)
                        cu.alain.chat.ui.theme.LocalChatPalette.current.chatWallpaper
                            ?: MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = if (current is Screen.Chat)
                                    cu.alain.chat.ui.theme.LocalChatPalette.current.chatWallpaper
                                        ?: MaterialTheme.colorScheme.background
                                else MaterialTheme.colorScheme.background,
                            ),
                            title = {
                                when (val c = current) {
                                    Screen.Home -> Text(
                                        when (tab) {
                                            1 -> "Llamadas"
                                            2 -> "Ajustes"
                                            else -> "Chats"
                                        }
                                    )
                                    is Screen.Chat -> ChatTitle(app, c.room)
                                    is Screen.Profile ->
                                        Text(if (c.firstRun) "Tu perfil" else "Editar perfil")
                                    is Screen.RoomSettings -> Text("Ajustes de la sala")
                                }
                            },
                            navigationIcon = {
                                // atrás visible en toda sub-pantalla menos el
                                // onboarding de perfil (v0.6.0)
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
                                    is Screen.Chat -> {
                                        // llamadas (D75): solo en chats directos
                                        if (current.room.kind == cu.alain.chat.core.C.ROOM_DM) {
                                            IconButton(onClick = { startCall(app, current.room, video = false) }) {
                                                Icon(Icons.Default.Call, "Llamada de voz")
                                            }
                                            IconButton(onClick = { startCall(app, current.room, video = true) }) {
                                                Icon(Icons.Default.Videocam, "Videollamada")
                                            }
                                        }
                                        IconButton(onClick = {
                                            stack.add(Screen.RoomSettings(current.room.roomId))
                                        }) {
                                            Icon(Icons.Default.Settings, "Ajustes de la sala")
                                        }
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
                    ) {
                        when (val c = current) {
                            Screen.Home -> HomeTabs(
                                app = app,
                                tab = tab,
                                onTab = { tab = it },
                                onOpenRoom = { room ->
                                    stack.removeAll { it is Screen.Chat || it is Screen.RoomSettings }
                                    stack.add(Screen.Chat(room))
                                },
                                onEditProfile = { stack.add(Screen.Profile(firstRun = false)) },
                            )
                            is Screen.Chat -> ChatScreen(app, c.room.roomId)
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
            } // fin Box UI normal

            if (callState != null) {
                // se dibuja al final: ENCIMA de todo
                cu.alain.chat.calls.CallScreenOverlay(app)
            }
        }
    }
}

/**
 * Pestañas inferiores (v0.7.0, como el mockup): Chats · Llamadas · Ajustes.
 * No existe la pestaña de «estados» — decisión de la pareja.
 */
@Composable
private fun HomeTabs(
    app: ChatApp,
    tab: Int,
    onTab: (Int) -> Unit,
    onOpenRoom: (Room) -> Unit,
    onEditProfile: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> RoomsScreen(app, onOpenRoom)
                1 -> CallsScreen(app)
                else -> SettingsScreen(app, onEditProfile = onEditProfile)
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { onTab(0) },
                icon = { Icon(Icons.Default.Chat, "Chats") },
                label = { Text("Chats") },
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { onTab(1) },
                icon = { Icon(Icons.Default.Call, "Llamadas") },
                label = { Text("Llamadas") },
            )
            NavigationBarItem(
                selected = tab == 2,
                onClick = { onTab(2) },
                icon = { Icon(Icons.Default.Settings, "Ajustes") },
                label = { Text("Ajustes") },
            )
        }
    }
}

/** Título del chat abierto: si la sala tiene foto/nombre propio (D69) se
 *  muestran; si no, el perfil del par (nombre + avatar, D61). El apodo personal
 *  (D78) gana en los chats directos: tocar el título lo edita. */
@Composable
private fun ChatTitle(app: ChatApp, room: Room) {
    val meUid = app.engine.session?.uid ?: ""
    val header by app.db.roomListDao().observeRoomHeader(room.roomId, meUid)
        .collectAsState(initial = null)
    val nicknames by app.nicknames.collectAsState()
    var rename by remember { mutableStateOf(false) }
    val peerUid = header?.peerUid
    val shownName = when {
        peerUid != null ->
            nicknames[peerUid.lowercase()] ?: header?.peerName ?: room.name
        else -> header?.name ?: room.name
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(
            enabled = room.kind == cu.alain.chat.core.C.ROOM_DM && peerUid != null,
        ) { rename = true },
    ) {
        if (header?.roomPhoto == true) {
            AvatarView(
                photoUid = "room-${room.roomId.lowercase()}",
                photo = true,
                avatarJson = null,
                fallbackInitial = shownName,
                size = 32.dp,
                photoVersion = 0L,
            )
        } else {
            AvatarView(
                photoUid = peerUid,
                photo = header?.peerPhoto == true,
                avatarJson = header?.peerAvatarJson,
                fallbackInitial = shownName,
                size = 32.dp,
                photoVersion = 0L,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(shownName)
    }
    if (rename && peerUid != null) {
        NicknameDialog(
            initial = nicknames[peerUid.lowercase()] ?: "",
            realName = header?.peerName ?: room.name,
            onSave = { app.setNickname(peerUid, it); rename = false },
            onDismiss = { rename = false },
        )
    }
}

/** Diálogo de apodo personal (D78): solo afecta a este teléfono. */
@Composable
fun NicknameDialog(
    initial: String,
    realName: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nombre de tu contacto") },
        text = {
            Column {
                Text(
                    "Ponle el nombre que quieras ver. Es solo para ti: la otra " +
                        "persona no se entera. Déjalo vacío para usar su nombre " +
                        "real ($realName).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 40) text = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim().ifBlank { null }) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Arranca una llamada con el par del chat directo (nombre de su perfil). */
private fun startCall(app: ChatApp, room: Room, video: Boolean) {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    scope.launch {
        val peerName = app.db.profileDao().get(room.peerUid ?: "")?.displayName
            ?: app.db.roomPeerDao().peersOf(room.roomId)
                .firstOrNull { !it.equals(app.engine.session?.uid ?: "", ignoreCase = true) }
                ?.let { app.db.profileDao().get(it)?.displayName }
            ?: "@${room.name.removePrefix("@")}"
        CallDispatcher.startOutgoing(app, room, peerName, video)
    }
}
