package cu.alain.chat.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import cu.alain.chat.net.ApiException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────────── ViewModel ─────────────────────────────

class RoomsViewModel(private val app: ChatApp) : AndroidViewModel(app) {

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

    var showAdd by remember { mutableStateOf(false) }
    var addMode by remember { mutableStateOf(0) }              // 0=crear 1=unirme
    var roomName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    var ready by remember { mutableStateOf<RoomJoined?>(null) } // SAS + código tras crear/unir

    var lockedRoom by remember { mutableStateOf<Room?>(null) }  // sala sin clave → pedir código
    var restoreCode by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }

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
                    RoomRow(item = item, hasKey = hasKey) {
                        if (hasKey) onOpenRoom(room)
                        else { lockedRoom = room; restoreCode = ""; restoreError = null }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true; addError = null; addMode = 0; roomName = ""; inviteCode = "" },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Icon(Icons.Default.Add, "Nueva sala") }
    }

    // ── diálogo crear / unirme ──
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (busy == null) showAdd = false },
            title = { Text(if (addMode == 0) "Crear una sala" else "Unirme con código") },
            text = {
                Column {
                    Text(
                        if (addMode == 0)
                            "La app genera una clave secreta para la sala. Recibirás un CÓDIGO DE INVITACIÓN para dárselo a la otra persona: es el único modo de entrar, guárdalo bien."
                        else
                            "Pega el código de invitación que te pasó la otra persona (se copia entero, con guiones).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = { addMode = 0 }) {
                            Text(
                                "Crear", fontWeight = if (addMode == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (addMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { addMode = 1 }) {
                            Text(
                                "Unirme", fontWeight = if (addMode == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (addMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (addMode == 0) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(), value = roomName,
                            onValueChange = { roomName = it },
                            label = { Text("Nombre de la sala (opcional)") }, singleLine = true,
                        )
                    } else {
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
                    addError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = busy == null && (addMode == 0 || inviteCode.isNotBlank()),
                    onClick = {
                        addError = null
                        if (addMode == 0) {
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
                    Text(if (addMode == 0) "Crear sala" else "Unirme")
                }
            },
            dismissButton = { TextButton({ showAdd = false }) { Text("Cancelar") } },
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

@Composable
private fun RoomRow(
    item: cu.alain.chat.db.RoomListItem,
    hasKey: Boolean,
    onClick: () -> Unit,
) {
    val title = (item.peerName ?: item.name).let { if (hasKey) it else "$it (sin clave)" }
    val unread = item.lastTs != null && item.lastMine == false
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasKey) {
            cu.alain.chat.ui.AvatarView(
                photoUid = item.peerUid,
                photo = item.peerPhoto == true,
                avatarJson = item.peerAvatarJson,
                fallbackInitial = title,
                size = 48.dp,
            )
        } else {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Lock, "Sin clave",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        item.lastTs?.let {
            Text(
                SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
