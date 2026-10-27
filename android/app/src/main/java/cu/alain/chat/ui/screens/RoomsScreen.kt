package cu.alain.chat.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cu.alain.chat.ChatApp
import cu.alain.chat.core.C
import cu.alain.chat.db.Room
import cu.alain.chat.engine.RoomJoined
import cu.alain.chat.net.FoundUser
import cu.alain.chat.net.ApiException
import cu.alain.chat.notify.Notifier
import cu.alain.chat.ui.NicknameDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────────── ViewModel ─────────────────────────────

/** D62: constructor (Application) — obligatorio para AndroidViewModelFactory (ver AuthViewModel). */
class RoomsViewModel(app: Application) : AndroidViewModel(app) {

    private val app get() = getApplication<ChatApp>()

    val busy = androidx.compose.runtime.mutableStateOf<String?>(null)

    fun create(name: String, onDone: (Result<RoomJoined>) -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            val r = app.engine.createRoom(name)
            busy.value = null
            if (r.isFailure) r.exceptionOrNull()?.let { onDone(Result.failure(it)) } else onDone(r)
        }
    }

    fun join(code: String, name: String, onDone: (Result<RoomJoined>) -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            val r = app.engine.joinRoom(code, name)
            busy.value = null
            onDone(r)
        }
    }

    fun restoreKey(code: String, onDone: (Result<String>) -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            val r = app.engine.restoreRoomKey(code)
            busy.value = null
            onDone(r)
        }
    }

    /** Chat directo 1-a-1 con un usuario buscado por @nombre (v0.7.0, D74). */
    fun createDm(uname: String, onDone: (Result<Room>) -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            val r = app.engine.createDm(uname)
            busy.value = null
            onDone(r)
        }
    }
}

// ───────────────────────────── Pantalla ─────────────────────────────

/** Lista de salas + crear sala / unirse con código de invitación (D41-D43).
 *  Desde v0.4.0 cada fila muestra el PERFIL del par (nombre elegido + foto o
 *  avatar, D61) cuando lo conocemos; si no, el nombre local de la sala. */
@Composable
fun RoomsScreen(app: ChatApp, onOpenRoom: (Room) -> Unit) {

    val vm: RoomsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val meUid = app.engine.session?.uid ?: ""
    val items by app.db.roomListDao().observeRoomList(meUid).collectAsState(initial = emptyList())
    val keysVersion by app.engine.keysVersion.collectAsState()
    val nicknames by app.nicknames.collectAsState() // D78: apodos personales

    var showAdd by remember { mutableStateOf(false) }
    var addMode by remember { mutableStateOf(0) }              // 0=chat directo 1=grupo 2=unirme
    var roomName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    // ── búsqueda de usuarios (chat directo, D74) ──
    var searchQ by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoundUser>?>(null) } // null = aún sin buscar
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var creatingDm by remember { mutableStateOf<String?>(null) }       // uname en curso
    LaunchedEffect(searchQ) {
        searchError = null
        val q = searchQ.trim().lowercase().removePrefix("@")
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(350) // debounce
        if (q != searchQ.trim().lowercase().removePrefix("@")) return@LaunchedEffect
        val r = try { app.api.searchUsers(app.engine.session!!.token, q) } catch (e: Exception) { null }
        searching = false
        if (r == null) searchError = "Sin conexión o el servidor no respondió"
        else results = r
    }

    var ready by remember { mutableStateOf<RoomJoined?>(null) } // SAS + código tras crear/unir

    var lockedRoom by remember { mutableStateOf<Room?>(null) }  // sala sin clave → pedir código
    var restoreCode by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }

    // ── menú long-press (v0.6.0, bug 6): vaciar / eliminar / marcar leído ──
    // ── y renombrar contacto (D78) en chats directos ──
    val scope = rememberCoroutineScope()
    var clearRoom by remember { mutableStateOf<cu.alain.chat.db.RoomListItem?>(null) }
    var deleteRoom by remember { mutableStateOf<cu.alain.chat.db.RoomListItem?>(null) }
    var renameItem by remember { mutableStateOf<cu.alain.chat.db.RoomListItem?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    val busy = vm.busy.value

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Aún no tienes conversaciones",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Crea una sala y comparte el código de invitación\ncon la otra persona (en persona o por WhatsApp)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.roomId }) { item ->
                    // keysVersion: leerlo aquí registra la dependencia → el candado
                    // de la sala se refresca al cambiar el vault (join/restore/logout)
                    val hasKey = keysVersion.let { app.engine.hasKey(item.roomId) }
                    val room = Room(
                        roomId = item.roomId, name = item.name, owner = item.owner,
                        sas = item.sas, createdAt = item.createdAt,
                    )
                    RoomRow(
                        item = item, hasKey = hasKey,
                        nickname = item.peerUid?.let { nicknames[it.lowercase()] },
                        onClick = {
                            if (hasKey) onOpenRoom(room)
                            else { lockedRoom = room; restoreCode = ""; restoreError = null }
                        },
                        onMarkRead = { scope.launch { app.db.roomDao().resetUnread(item.roomId) } },
                        onClear = { clearRoom = item },
                        onDelete = { deleteRoom = item; deleteError = null },
                        onRename = { renameItem = item },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                showAdd = true; addError = null; addMode = 0; roomName = ""; inviteCode = ""
                searchQ = ""; results = null; searchError = null
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Icon(Icons.Default.Add, "Nuevo chat") }
    }

    // ── diálogo nuevo chat: directo / grupo / unirme (v0.7.0) ──
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (busy == null && creatingDm == null) showAdd = false },
            title = { Text("Nuevo chat") },
            text = {
                Column {
                    Row {
                        TextButton(onClick = { addMode = 0; addError = null; searchError = null }) {
                            Icon(Icons.Default.PersonSearch, null, Modifier.size(15.dp),
                                tint = if (addMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text("Chat directo", fontWeight = if (addMode == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (addMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { addMode = 1; addError = null }) {
                            Icon(Icons.Default.Groups, null, Modifier.size(15.dp),
                                tint = if (addMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text("Grupo", fontWeight = if (addMode == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (addMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { addMode = 2; addError = null }) {
                            Text("Unirme", fontWeight = if (addMode == 2) FontWeight.Bold else FontWeight.Normal,
                                color = if (addMode == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when (addMode) {
                        0 -> {
                            Text(
                                "Busca a la otra persona por su @usuario y el chat se crea solo, " +
                                    "sin códigos. El cifrado de extremo a extremo se mantiene.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(), value = searchQ,
                                onValueChange = { searchQ = it.lowercase() },
                                label = { Text("@usuario") }, singleLine = true,
                            )
                            when {
                                searching -> {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Buscando…", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                searchError != null -> {
                                    Spacer(Modifier.height(6.dp))
                                    Text(searchError!!, color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                results?.isEmpty() == true -> {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Nadie con ese nombre todavía. El @usuario es el que puso al registrarse.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                results != null -> {
                                    Spacer(Modifier.height(6.dp))
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        results!!.forEach { u ->
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .combinedClickable(
                                                        onClick = {
                                                            if (creatingDm == null && busy == null) {
                                                                creatingDm = u.uname
                                                                addError = null
                                                                vm.createDm(u.uname) { r ->
                                                                    creatingDm = null
                                                                    r.onSuccess { room ->
                                                                        showAdd = false
                                                                        onOpenRoom(room)
                                                                    }.onFailure { e -> addError = errText(e) }
                                                                }
                                                            }
                                                        },
                                                    )
                                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Surface(
                                                    color = if (u.pubB64 != null) MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = CircleShape,
                                                ) {
                                                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                                        Text(
                                                            u.uname.take(1).uppercase(),
                                                            style = MaterialTheme.typography.titleMedium,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        )
                                                    }
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text("@${u.uname}", style = MaterialTheme.typography.bodyLarge)
                                                    Text(
                                                        if (u.pubB64 != null) "Listo para chatear"
                                                        else "Aún no actualiza la app",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                if (creatingDm == u.uname) {
                                                    CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            Text(
                                "La app genera una clave secreta para el grupo. Recibirás un CÓDIGO DE " +
                                    "INVITACIÓN para dárselo a los demás: es el único modo de entrar, guárdalo bien.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(), value = roomName,
                                onValueChange = { roomName = it },
                                label = { Text("Nombre del grupo (opcional)") }, singleLine = true,
                            )
                        }
                        else -> {
                            Text(
                                "Pega el código de invitación que te pasó la otra persona (se copia entero, con guiones).",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(), value = inviteCode,
                                onValueChange = { inviteCode = it.uppercase() },
                                label = { Text("Código de invitación") },
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(), value = roomName,
                                onValueChange = { roomName = it },
                                label = { Text("Nombre de la sala (opcional)") }, singleLine = true,
                            )
                        }
                    }
                    addError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = when (addMode) {
                        0 -> false // el chat directo se abre tocando un resultado
                        1 -> busy == null
                        else -> busy == null && inviteCode.isNotBlank()
                    },
                    onClick = {
                        addError = null
                        if (addMode == 1) {
                            vm.create(roomName) { r ->
                                r.onSuccess { showAdd = false; ready = it }
                                    .onFailure { e -> addError = errText(e) }
                            }
                        } else {
                            vm.join(inviteCode, roomName) { r ->
                                r.onSuccess { showAdd = false; ready = it }
                                    .onFailure { e -> addError = errText(e) }
                            }
                        }
                    },
                ) {
                    if (busy == "") {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (addMode == 1) "Crear grupo" else "Unirme")
                }
            },
            dismissButton = {
                TextButton({ if (busy == null && creatingDm == null) showAdd = false }) { Text("Cancelar") }
            },
        )
    }

    // ── SAS + código de invitación (tras crear o unir) ──
    ready?.let { r ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Sala lista") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // SAS (D43): comparación por voz, cliente-cliente
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                sasLabel(r.sas),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "Número de seguridad",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Dí en voz alta este número cuando hables con la otra persona. Si en el otro teléfono aparece EL MISMO número, la sala es solo de ustedes dos.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (r.inviteCode.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("Código de invitación", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Dáselo a la otra persona por un canal que controles: en persona, WhatsApp, un papel. Quien tenga el código entra a la sala.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            SelectionContainer {
                                Text(
                                    r.inviteCode,
                                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(r.inviteCode)) }) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copiar")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, r.inviteCode)
                                }, "Enviar código de invitación"))
                            }) {
                                Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Compartir")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { val room = r.room; ready = null; onOpenRoom(room) }) {
                    Text("Abrir la sala")
                }
            },
        )
    }

    // ── renombrar contacto (D78): apodo personal, solo en este teléfono ──
    renameItem?.let { item ->
        val uid = item.peerUid
        if (uid != null) {
            NicknameDialog(
                initial = nicknames[uid.lowercase()] ?: "",
                realName = item.peerName ?: item.name,
                onSave = { app.setNickname(uid, it); renameItem = null },
                onDismiss = { renameItem = null },
            )
        }
    }

    // ── confirmación: vaciar historial (long-press, v0.6.0) ──
    clearRoom?.let { item ->
        AlertDialog(
            onDismissRequest = { clearRoom = null },
            title = { Text("¿Vaciar el historial?") },
            text = {
                Text("Se borran los mensajes de «${item.peerName ?: item.name}» SOLO en este " +
                    "teléfono. Los del otro teléfono y los del servidor no se tocan.")
            },
            confirmButton = {
                TextButton(onClick = {
                    clearRoom = null
                    scope.launch { app.engine.clearHistory(item.roomId) }
                }) { Text("Vaciar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ clearRoom = null }) { Text("Cancelar") } },
        )
    }

    // ── confirmación: eliminar sala (long-press, v0.6.0/D70) ──
    deleteRoom?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteRoom = null },
            title = { Text("¿Eliminar la sala?") },
            text = {
                Column {
                    Text(
                        "Sales de «${item.peerName ?: item.name}» y se borran sus mensajes y su clave " +
                            "de este teléfono. La otra persona conserva su copia. Si eras el último " +
                            "miembro, la sala se borra del servidor por completo. Necesita conexión " +
                            "(para volver a entrar necesitarás el código de invitación)."
                    )
                    deleteError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true; deleteError = null
                        scope.launch {
                            val r = app.engine.deleteRoom(item.roomId)
                            deleting = false
                            r.fold(
                                onSuccess = {
                                    deleteRoom = null
                                    Notifier.cancelRoom(ctx, item.roomId)
                                },
                                onFailure = { e ->
                                    deleteError = (e as? ApiException)?.userMsg
                                        ?: "Sin conexión: la sala no se puede eliminar ahora"
                                },
                            )
                        }
                    },
                ) {
                    if (deleting) {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ if (!deleting) deleteRoom = null }) { Text("Cancelar") } },
        )
    }

    // ── sala sin clave en este teléfono (2º dispositivo / clave perdida) ──
    lockedRoom?.let { room ->
        AlertDialog(
            onDismissRequest = { if (busy == null) lockedRoom = null },
            title = { Text("Sala sin clave") },
            text = {
                Column {
                    Text(
                        "«${room.name}» está en el servidor pero este teléfono no tiene su clave " +
                            "(el cifrado es de extremo a extremo: las claves nunca viajan). " +
                            "Pide el código de invitación original y pégalo aquí para recuperar el acceso.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(), value = restoreCode,
                        onValueChange = { restoreCode = it.uppercase() },
                        label = { Text("Código de invitación") }, singleLine = true,
                    )
                    restoreError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = busy == null && restoreCode.isNotBlank(),
                    onClick = {
                        restoreError = null
                        vm.restoreKey(restoreCode) { r ->
                            r.onSuccess { lockedRoom = null }
                                .onFailure { e -> restoreError = errText(e) }
                        }
                    },
                ) {
                    if (busy == "") {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Recuperar acceso")
                }
            },
            dismissButton = { TextButton({ lockedRoom = null }) { Text("Cancelar") } },
        )
    }
}

private fun errText(e: Throwable): String =
    (e as? ApiException)?.userMsg ?: (e.message ?: "Sin conexión o servidor caído")

/** 123456 → "123 456" (más fácil de leer en voz alta). */
private fun sasLabel(sas: String): String =
    if (sas.length == 6) "${sas.substring(0, 3)} ${sas.substring(3)}" else sas

/** Fila de la lista de chats: click abre, long-press abre el menú (v0.6.0).
 *  Sala con foto/nombre propios (D69) → se muestra tal cual; si no, el perfil
 *  del par (D61) o su APODO personal (D78); si no hay clave, candado. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomRow(
    item: cu.alain.chat.db.RoomListItem,
    hasKey: Boolean,
    nickname: String?,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val custom = item.roomPhoto && hasKey
    val baseName = nickname ?: (item.peerName ?: item.name)
    val title = when {
        custom -> item.name
        hasKey -> baseName
        else -> "$baseName (sin clave)"
    }
    val unread = item.unread > 0
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                !hasKey -> Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Lock, "Sin clave",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                custom -> cu.alain.chat.ui.AvatarView(
                    photoUid = "room-${item.roomId.lowercase()}",
                    photo = true,
                    avatarJson = null,
                    fallbackInitial = title,
                    size = 48.dp,
                )
                else -> cu.alain.chat.ui.AvatarView(
                    photoUid = item.peerUid,
                    photo = item.peerPhoto == true,
                    avatarJson = item.peerAvatarJson,
                    fallbackInitial = title,
                    size = 48.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                )
                if (!item.lastBody.isNullOrEmpty()) {
                    Text(
                        item.lastBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (unread) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                item.lastTs?.let {
                    Text(
                        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (unread) {
                    Spacer(Modifier.height(4.dp))
                    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                        Text(
                            if (item.unread > 99) "99+" else "${item.unread}",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (item.peerUid != null) {
                DropdownMenuItem(
                    text = { Text("Renombrar contacto") },
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                    onClick = { menu = false; onRename() },
                )
            }
            if (item.unread > 0) {
                DropdownMenuItem(
                    text = { Text("Marcar como leído") },
                    leadingIcon = { Icon(Icons.Default.DoneAll, null) },
                    onClick = { menu = false; onMarkRead() },
                )
            }
            DropdownMenuItem(
                text = { Text("Vaciar historial") },
                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                onClick = { menu = false; onClear() },
            )
            DropdownMenuItem(
                text = { Text("Eliminar sala", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}
