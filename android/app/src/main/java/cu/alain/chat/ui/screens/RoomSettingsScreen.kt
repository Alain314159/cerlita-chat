package cu.alain.chat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.profile.Photos
import cu.alain.chat.profile.RoomInfoMsg
import cu.alain.chat.ui.AvatarView
import cu.alain.chat.work.WorkSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes de UNA sala (v0.6.0, D69/D70) — se abre con el engranaje del chat:
 *
 *  - Nombre y FOTO de la sala, compartidos E2EE con el otro miembro (kind=2,
 *    latest-ts-wins): los dos teléfonos se actualizan solos.
 *  - Código de invitación (reconstruido de la clave del vault) + SAS.
 *  - Miembros vistos en la sala.
 *  - Vaciar historial local / Eliminar la sala (leave server-side: si era el
 *    último, el servidor borra la sala entera).
 */
@Composable
fun RoomSettingsScreen(app: ChatApp, roomId: String, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val meUid = app.engine.session?.uid ?: ""

    val roomState by app.db.roomDao().observeAll()
        .collectAsState(initial = emptyList())
    val room = roomState.firstOrNull { it.roomId == roomId }

    var name by remember(roomId) { mutableStateOf("") }
    var hasPhoto by remember(roomId) { mutableStateOf(false) }
    var newPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var loaded by remember(roomId) { mutableStateOf(false) }
    LaunchedEffect(room?.roomId, room?.photo, loaded) {
        if (room != null && !loaded) {
            name = room.name
            hasPhoto = room.photo
            loaded = true
        }
    }

    // miembros vistos (perfiles E2EE) + yo
    val profiles by app.db.profileDao().observeAll().collectAsState(initial = emptyList())
    var members by remember(roomId) { mutableStateOf<List<Pair<String, cu.alain.chat.db.Profile?>>>(emptyList()) }
    LaunchedEffect(roomId, profiles.size) {
        val uids = app.db.roomPeerDao().peersOf(roomId)
        members = (uids + meUid).distinct().map { uid ->
            uid to app.db.profileDao().get(uid)
        }
    }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    val inviteCode = remember(roomId) { app.engine.getInviteCode(roomId) }
    val hasKey = app.engine.hasKey(roomId)

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { Photos.loadSquareJpeg(ctx, uri) }
                if (bytes != null) {
                    newPhotoBytes = bytes
                    hasPhoto = true
                } else {
                    error = "No se pudo leer esa imagen"
                }
            }
        }
    }

    if (room == null) {
        // la sala se borró (p. ej. desde la lista): nada que mostrar
        Box(Modifier.fillMaxSize())
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── foto + nombre de la sala ──
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AvatarView(
                    photoUid = "room-${roomId.lowercase()}",
                    photo = hasPhoto,
                    avatarJson = null,
                    fallbackInitial = name.ifBlank { "?" },
                    size = 96.dp,
                    pendingPhotoBytes = newPhotoBytes,
                )
                Spacer(Modifier.height(8.dp))
                if (hasPhoto) {
                    TextButton(onClick = { hasPhoto = false; newPhotoBytes = null }) {
                        Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Quitar foto")
                    }
                } else {
                    OutlinedButton(onClick = { pickPhoto.launch("image/*") }) {
                        Icon(Icons.Default.AddAPhoto, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ponerle foto a la sala")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "El nombre y la foto se comparten cifrados con la otra " +
                        "persona de la sala: se le cambian solos en su teléfono.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { if (it.length <= RoomInfoMsg.MAX_NAME) name = it; error = null },
                    label = { Text("Nombre de la sala") },
                    singleLine = true,
                    isError = error != null,
                    enabled = hasKey,
                )
                if (!hasKey) {
                    Text(
                        "Esta sala no tiene su clave en este teléfono: el nombre solo cambia aquí.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !saving && name.isNotBlank(),
                    onClick = {
                        saving = true; error = null
                        scope.launch {
                            val keep = hasPhoto && newPhotoBytes == null
                            val r = app.engine.setRoomInfo(roomId, name, newPhotoBytes, keep)
                            saving = false
                            r.fold(
                                onSuccess = {
                                    newPhotoBytes = null
                                    WorkSync.kickOutbox(app)
                                },
                                onFailure = { e -> error = e.message ?: "No se pudo guardar" },
                            )
                        }
                    },
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Guardar cambios")
                }
            }
        }

        // ── SAS: verificar identidad por voz ──
        if (room.sas.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Número de seguridad", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (room.sas.length == 6)
                            "${room.sas.substring(0, 3)} ${room.sas.substring(3)}"
                        else room.sas,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Si en el otro teléfono aparece EL MISMO número, la sala es solo de ustedes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── código de invitación ──
        if (inviteCode != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Código de invitación", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Quien tenga este código entra a la sala (es el secreto: compártelo " +
                            "solo por un canal que controles). Sirve también para recuperar el " +
                            "acceso en otro teléfono.",
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
                                inviteCode,
                                modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(inviteCode)) }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copiar")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, inviteCode)
                            }, "Enviar código de invitación"))
                        }) {
                            Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Compartir")
                        }
                    }
                }
            }
        }

        // ── miembros ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Miembros", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                members.forEach { (uid, p) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarView(
                            photoUid = uid,
                            photo = p?.photo == true,
                            avatarJson = p?.avatarJson,
                            fallbackInitial = p?.displayName ?: "?",
                            size = 36.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                p?.displayName ?: "Miembro sin conocer",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (uid == meUid) {
                                Text("Tú", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // ── zona peligrosa ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Historial", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "«Vaciar» borra los mensajes de esta sala SOLO en este teléfono; " +
                        "el historial del servidor y del otro miembro no se tocan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { showClear = true }) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Vaciar historial local")
                }
                Spacer(Modifier.height(16.dp))
                Text("Eliminar la sala", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sales de la sala y se borra todo lo suyo en este teléfono. La otra " +
                        "persona conserva su copia. Si era el último miembro, la sala se " +
                        "borra del servidor por completo (y el código de invitación muere).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Eliminar y salir de la sala")
                }
            }
        }
    }

    // ── confirmación: vaciar historial ──
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("¿Vaciar el historial?") },
            text = { Text("Se borran los mensajes de esta sala en este teléfono. " +
                "Los del otro teléfono y los del servidor no se tocan.") },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    scope.launch { app.engine.clearHistory(roomId) }
                }) { Text("Vaciar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showClear = false }) { Text("Cancelar") } },
        )
    }

    // ── confirmación: eliminar sala ──
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { if (!saving) showDelete = false },
            title = { Text("¿Eliminar la sala?") },
            text = {
                Text("Sales de la sala «${room.name}» y se borran sus mensajes y su clave " +
                    "de este teléfono. La otra persona conserva su copia. Necesitas " +
                    "conexión: si no hay, inténtalo más tarde. Para volver a entrar " +
                    "necesitarás el código de invitación.")
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        saving = true; error = null
                        scope.launch {
                            val r = app.engine.deleteRoom(roomId)
                            saving = false
                            r.fold(
                                onSuccess = { showDelete = false; onDeleted() },
                                onFailure = { e ->
                                    error = (e as? cu.alain.chat.net.ApiException)?.userMsg
                                        ?: "Sin conexión: la sala no se puede eliminar ahora"
                                },
                            )
                        }
                    },
                ) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showDelete = false }) { Text("Cancelar") } },
        )
    }
}
