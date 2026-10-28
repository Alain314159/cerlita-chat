package cu.alain.chat.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cu.alain.chat.ChatApp
import cu.alain.chat.core.C
import cu.alain.chat.db.Message
import cu.alain.chat.db.Profile
import cu.alain.chat.notify.Notifier
import cu.alain.chat.profile.AttachmentMsg
import cu.alain.chat.ui.AvatarView
import cu.alain.chat.ui.theme.LocalChatPalette
import cu.alain.chat.ui.attachments.AudioBubble
import cu.alain.chat.ui.attachments.Attach
import cu.alain.chat.ui.attachments.DocBubble
import cu.alain.chat.ui.attachments.ImageBubble
import cu.alain.chat.ui.attachments.VoiceRecorder
import cu.alain.chat.work.WorkSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla de conversación (v0.7.0): burbujas de texto + ADJUNTOS E2EE
 * (fotos, notas de voz, archivos) + composer con grabadora. Offline-first.
 */
@Composable
fun ChatScreen(app: ChatApp, roomId: String) {

    val ctx = LocalContext.current
    val messages by app.db.msgDao().observeChat(roomId).collectAsState(initial = emptyList())
    val profiles by app.db.profileDao().observeAll().collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // progreso de subida por msgId (la burbuja lo pinta, D76)
    val uploadProgress = remember { mutableStateMapOf<String, Float>() }

    // ── grabadora de notas de voz ──
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableStateOf(0L) }
    val recAmps = remember { mutableStateListOf<Int>() }
    var recError by remember { mutableStateOf<String?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var viewerBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saveDoc by remember { mutableStateOf<Pair<AttachmentMsg.Parsed, java.io.File>?>(null) }

    val recPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) {
            if (recorder.start()) { recording = true; recMs = 0; recAmps.clear(); recError = null }
            else recError = "No se pudo empezar a grabar"
        } else recError = "Sin permiso del micrófono no hay notas de voz"
    }

    // muestreo de amplitud + reloj mientras graba
    LaunchedEffect(recording) {
        while (recording) {
            recMs = recorder.durationMs
            if (recAmps.size < 3000) recAmps.add(recorder.amplitude())
            if (recMs >= C.MAX_RECORD_MS) { // tope 5 min: enviar tal cual
                recording = false
            }
            delay(100)
        }
    }
    DisposableEffect(roomId) {
        onDispose {
            if (recorder.isRecording) recorder.cancel()
        }
    }

    fun sendAudioNote() {
        val done = recorder.stop() ?: run {
            recError = "Mantén pulsado un momento más: la nota salió demasiado corta"
            return
        }
        recording = false
        val (file, durMs) = done
        if (durMs < 900) { file.delete(); recError = "La nota es demasiado corta"; return }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            app.engine.sendAttachment(
                roomId = roomId,
                type = AttachmentMsg.TYPE_AUDIO,
                name = "Nota de voz",
                mime = "audio/mp4",
                ext = "m4a",
                originalSize = bytes.size.toLong(),
                bytes = bytes,
                durMs = durMs,
                waveformB64 = Attach.waveformToB64(recAmps.toList()),
                onProgress = { id, f -> uploadProgress[id] = f },
            ).onSuccess {
                uploadProgress.clear()
                WorkSync.kickOutbox(app); app.engine.poke()
            }.onFailure { e ->
                sendError = e.message ?: "No se pudo enviar la nota de voz"
            }
            file.delete()
        }
    }

    // ── pickers de adjuntos ──
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val processed = withContext(Dispatchers.IO) { Attach.processImage(ctx, uri) }
                if (processed == null) { sendError = "No se pudo leer esa imagen"; return@launch }
                val (bytes, w, h) = processed
                app.engine.sendAttachment(
                    roomId = roomId,
                    type = AttachmentMsg.TYPE_IMAGE,
                    name = "Foto",
                    mime = "image/jpeg",
                    ext = "jpg",
                    originalSize = bytes.size.toLong(),
                    bytes = bytes,
                    width = w, height = h,
                    onProgress = { id, f -> uploadProgress[id] = f },
                ).onSuccess { WorkSync.kickOutbox(app); app.engine.poke(); uploadProgress.clear() }
                    .onFailure { e -> sendError = e.message ?: "No se pudo enviar la foto" }
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { readPickedFile(ctx, uri) }
                if (result == null) { sendError = "No se pudo leer ese archivo (¿más de 20 MB?)"; return@launch }
                val (name, mime, ext, bytes) = result
                app.engine.sendAttachment(
                    roomId = roomId,
                    type = AttachmentMsg.TYPE_DOC,
                    name = name,
                    mime = mime,
                    ext = ext,
                    originalSize = bytes.size.toLong(),
                    bytes = bytes,
                    onProgress = { id, f -> uploadProgress[id] = f },
                ).onSuccess { WorkSync.kickOutbox(app); app.engine.poke(); uploadProgress.clear() }
                    .onFailure { e -> sendError = e.message ?: "No se pudo enviar el archivo" }
            }
        }
    }
    // guardar archivo descargado (SAF — solo API <29; en 29+ guarda directo)
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pair = saveDoc
        saveDoc = null
        if (uri != null && pair != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { it.write(pair.second.readBytes()) }
                        true
                    } catch (_: Exception) { false }
                }
                sendError = if (ok) null else "No se pudo guardar el archivo"
            }
        }
    }

    // guardar FOTO en la galería (D80): directo en API 29+, SAF en <29
    val saveImageSaf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg"),
    ) { uri ->
        val bmp = viewerBitmap
        if (uri != null && bmp != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use {
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
                        }
                        true
                    } catch (_: Exception) { false }
                }
                if (ok) android.widget.Toast.makeText(
                    ctx, "Foto guardada ✓", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // Sala abierta: suprime SU notificación (D63), limpia la ya publicada,
    // pone el badge de no leídos a cero y acusa lectura (✓✓ azul, D68)
    DisposableEffect(roomId) {
        app.currentRoomId = roomId
        Notifier.cancelRoom(ctx, roomId)
        scope.launch {
            app.db.roomDao().resetUnread(roomId)
            app.engine.maybeSendReadReceipt(roomId)
        }
        onDispose { if (app.currentRoomId == roomId) app.currentRoomId = null }
    }

    // auto-scroll + acusar lectura + badge (B5/U2 auditoría):
    //  · solo salta al final si YA estabas cerca del final — si estás leyendo
    //    historia, la posición se respeta y aparece un chip «↓ N nuevos»
    //  · el badge de no leídos baja AL INSTANTE con la sala abierta (antes
    //    solo al entrar: el contador subía mientras leías)
    var newBelow by remember(roomId) { mutableStateOf(0) }
    var initialScroll by remember(roomId) { mutableStateOf(true) }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (initialScroll || lastVisible >= messages.size - 3) {
            initialScroll = false
            newBelow = 0
            listState.animateScrollToItem(messages.lastIndex)
        } else {
            newBelow = messages.size - 1 - lastVisible
        }
        app.db.roomDao().resetUnread(roomId) // B5: baja el badge ya, no al re-entrar
        app.engine.maybeSendReadReceipt(roomId)
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            itemsIndexed(messages, key = { _, m -> m.msgId }) { idx, m ->
                val prev = messages.getOrNull(idx - 1) // U3: O(1), era O(n²) con indexOfFirst
                MessageBubble(app, m, prev, profiles, uploadProgress, onRetry = { msgId ->
                    scope.launch {
                        app.engine.retryAttachment(msgId) { }
                            .onSuccess { WorkSync.kickOutbox(app); app.engine.poke() }
                            .onFailure { e -> sendError = e.message }
                    }
                }, onOpenImage = { viewerBitmap = it },
                    onSaveDoc = { att, file -> saveDoc = att to file; saveLauncher.launch(att.name) })
            }
        }

        // U2: chip discreto cuando llegan mensajes y NO estabas al final —
        // un toque y bajas; la vista nunca se mueve sola por debajo de tus ojos
        if (newBelow > 0) {
            Surface(
                onClick = {
                    newBelow = 0
                    scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            ) {
                Text(
                    if (newBelow == 1) "↓ 1 mensaje nuevo" else "↓ $newBelow mensajes nuevos",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        }
        sendError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (recording) {
            // ── barra de grabación: timer + waveform en vivo + cancelar/enviar ──
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { recorder.cancel(); recording = false; recError = null }) {
                        Icon(Icons.Default.Close, "Cancelar", tint = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        Attach.formatDuration(recMs),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Grabando…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (recMs >= C.MAX_RECORD_MS) "límite" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    IconButton(onClick = { sendAudioNote() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, "Enviar nota",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            // ── composer normal: adjuntar + texto + micro/enviar ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = { showAttachMenu = true }) {
                    Icon(Icons.Default.AttachFile, "Adjuntar", tint = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = draft,
                    onValueChange = { if (it.length <= 7500) { draft = it; sendError = null } },
                    placeholder = { Text("Mensaje…") },
                    maxLines = 5,
                    visualTransformation = VisualTransformation.None,
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.width(4.dp))
                if (draft.isNotBlank()) {
                    IconButton(
                        onClick = {
                            val text = draft.trim()
                            if (text.isEmpty()) return@IconButton
                            draft = ""
                            scope.launch {
                                app.engine.send(roomId, text)
                                    .onSuccess { WorkSync.kickOutbox(app); app.engine.poke() }
                                    .onFailure { e ->
                                        sendError = e.message ?: "No se pudo encolar el mensaje"
                                        draft = text
                                    }
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, "Enviar",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    IconButton(onClick = { recPermission.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Icon(Icons.Default.Mic, "Nota de voz", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    // ── menú adjuntar ──
    DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
        DropdownMenuItem(
            text = { Text("Foto") },
            leadingIcon = { Icon(Icons.Default.Image, null) },
            onClick = {
                showAttachMenu = false
                pickImage.launch(androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        DropdownMenuItem(
            text = { Text("Archivo (hasta 20 MB)") },
            leadingIcon = { Icon(Icons.Default.AttachFile, null) },
            onClick = { showAttachMenu = false; pickFile.launch(arrayOf("*/*")) },
        )
    }

    // ── visor de imagen a pantalla completa ──
    viewerBitmap?.let { bmp ->
        Dialog(
            onDismissRequest = { viewerBitmap = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .combinedClickable(onClick = {}, onLongClick = {}),
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Foto",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = { viewerBitmap = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) { Icon(Icons.Default.Close, "Cerrar", tint = androidx.compose.ui.graphics.Color.White) }
                // guardar en la galería (D80)
                IconButton(
                    onClick = {
                        val b = bmp
                        scope.launch {
                            val direct = withContext(Dispatchers.IO) {
                                cu.alain.chat.ui.attachments.AttachSaver.saveImageToGallery(ctx, b)
                            }
                            if (direct) {
                                android.widget.Toast.makeText(
                                    ctx, "Guardada en Imágenes/Cerlita ✓", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                saveImageSaf.launch(
                                    "cerlita_" + System.currentTimeMillis() + ".jpg",
                                )
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                ) { Icon(Icons.Default.Download, "Guardar foto", tint = androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}

/** Archivo escogido con SAF: nombre, mime, extensión y bytes claros. */
private data class PickedFile(val name: String, val mime: String, val ext: String, val bytes: ByteArray)

/** Lee el archivo escogido (SAF) con su nombre y mime; null si >20 MB o ilegible. */
private suspend fun readPickedFile(
    ctx: android.content.Context,
    uri: android.net.Uri,
): PickedFile? {
    return withContext(Dispatchers.IO) {
        try {
            val name = queryName(ctx, uri)
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            if (bytes.isEmpty() || bytes.size > C.MAX_FILE) return@withContext null
            val ext = Attach.extOf(name).ifEmpty { "bin" }
            PickedFile(name.take(80), mime, ext, bytes)
        } catch (_: Exception) {
            null
        }
    }
}

private fun queryName(ctx: android.content.Context, uri: android.net.Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: "archivo"
    }
    return uri.lastPathSegment ?: "archivo"
}

@Composable
private fun MessageBubble(
    app: ChatApp,
    m: Message,
    prev: Message?,
    profiles: List<Profile>,
    uploadProgress: Map<String, Float>,
    onRetry: (String) -> Unit,
    onOpenImage: (Bitmap) -> Unit,
    onSaveDoc: (AttachmentMsg.Parsed, java.io.File) -> Unit,
) {
    val time = cu.alain.chat.core.Fmt.time(m.ts) // U4: formatter cacheado
    val mine = m.mine
    val haptic = LocalHapticFeedback.current
    val att = remember(m.msgId) { AttachmentMsg.parse(m.body) }
    var menu by remember { mutableStateOf(false) }
    val palette = LocalChatPalette.current // D77: burbujas pastel + texto oscuro en Cerlita

    // perfil del emisor (mensajes viejos pre-v3 no traen uid → fallback a inicial)
    val profile = profiles.firstOrNull { it.uid.equals(m.senderUid, ignoreCase = true) }
    val senderName = profile?.displayName ?: m.senderUname
    val showHeader = !mine && (prev == null || prev.mine ||
        !prev.senderUid.equals(m.senderUid, ignoreCase = true) ||
        (prev.senderUid.isEmpty() && prev.senderUname != m.senderUname))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            AvatarView(
                photoUid = if (m.senderUid.isEmpty()) null else m.senderUid,
                photo = profile?.photo == true,
                avatarJson = profile?.avatarJson,
                fallbackInitial = senderName,
                size = 32.dp,
                photoVersion = profile?.updatedAt ?: 0L,
            )
            Spacer(Modifier.width(8.dp))
        }
        Box {
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (showHeader) {
                    Text(
                        senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                Surface(
                    color = when {
                        mine -> palette.sentBubble
                        m.state == C.ST_UNREADABLE -> MaterialTheme.colorScheme.errorContainer
                        else -> palette.recvBubble
                    },
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (mine) 16.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 16.dp,
                    ),
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (m.state == C.ST_FAILED && att != null) menu = true
                            },
                        ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        when {
                            att?.type == AttachmentMsg.TYPE_IMAGE ->
                                ImageBubble(app, m.msgId, att, onOpenImage)
                            att?.type == AttachmentMsg.TYPE_AUDIO ->
                                AudioBubble(app, m.msgId, att, mine, onSaveDoc)
                            att?.type == AttachmentMsg.TYPE_DOC ->
                                DocBubble(app, m.msgId, att, onSaveDoc)
                            else -> Text(
                                m.body,
                                style = MaterialTheme.typography.bodyLarge,
                                color = when {
                                    mine -> palette.onSentBubble
                                    m.state == C.ST_UNREADABLE -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> palette.onRecvBubble
                                },
                            )
                        }
                        // progreso de subida del adjunto propio (D76)
                        if (mine && m.state == C.ST_SENDING && att != null) {
                            val p = uploadProgress[m.msgId]
                            Spacer(Modifier.height(4.dp))
                            if (p != null) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { p },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                )
                                Text(
                                    "Subiendo… ${(p * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.onSentBubble.copy(alpha = 0.8f),
                                )
                            } else {
                                Text(
                                    "Cifrando…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.onSentBubble.copy(alpha = 0.8f),
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                time,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mine) palette.onSentBubble.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (mine) {
                                Spacer(Modifier.width(4.dp))
                                when (m.state) {
                                    C.ST_SENDING -> Icon(
                                        Icons.Default.Schedule, "Enviando",
                                        Modifier.width(13.dp), tint = palette.onSentBubble.copy(alpha = 0.7f),
                                    )
                                    C.ST_FAILED -> Icon(
                                        Icons.Default.ErrorOutline, "Falló",
                                        Modifier.width(13.dp), tint = MaterialTheme.colorScheme.error,
                                    )
                                    C.ST_SENT -> Icon(
                                        Icons.Default.Check, "Enviado",
                                        Modifier.width(14.dp), tint = palette.onSentBubble.copy(alpha = 0.7f),
                                    )
                                    C.ST_DELIVERED -> Icon(
                                        Icons.Default.DoneAll, "Entregado",
                                        Modifier.width(16.dp), tint = palette.onSentBubble.copy(alpha = 0.7f),
                                    )
                                    C.ST_READ -> Icon(
                                        Icons.Default.DoneAll, "Leído",
                                        Modifier.width(16.dp), tint = palette.readTick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Reintentar envío") },
                    leadingIcon = { Icon(Icons.Default.Schedule, null) },
                    onClick = { menu = false; onRetry(m.msgId) },
                )
            }
        }
        // D94: como en el mockup — el avatar propio acompaña a los mensajes
        // ENVIADOS (a la derecha de la burbuja), igual que el del par a la
        // izquierda en los recibidos.
        if (mine) {
            Spacer(Modifier.width(8.dp))
            AvatarView(
                photoUid = if (m.senderUid.isEmpty()) null else m.senderUid,
                photo = profile?.photo == true,
                avatarJson = profile?.avatarJson,
                fallbackInitial = senderName,
                size = 32.dp,
                photoVersion = profile?.updatedAt ?: 0L,
            )
        }
    }
}
