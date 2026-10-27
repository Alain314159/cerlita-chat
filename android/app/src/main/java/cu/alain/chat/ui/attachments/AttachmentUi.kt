package cu.alain.chat.ui.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.profile.AttachmentMsg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Caché en RAM de miniaturas descifradas (msgId → bitmap). */
private val thumbCache = object : LruCache<String, Bitmap>(24) {}

/**
 * Descarga (una vez) y descifra la imagen de un adjunto; cachea el bitmap.
 * Devuelve null mientras trabaja (la UI muestra un spinner) — el estado
 * `error` distingue "sin red" de "cargando".
 */
@Composable
fun rememberAttachmentImage(app: ChatApp, msgId: String, att: AttachmentMsg.Parsed): Bitmap? {
    var bmp by remember(msgId) { mutableStateOf(thumbCache.get(msgId)) }
    var tried by remember(msgId) { mutableStateOf(false) }
    LaunchedEffect(msgId) {
        if (bmp == null && !tried) {
            tried = true
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val f = app.engine.downloadAttachment(msgId, att)
                    BitmapFactory.decodeFile(f.path)
                } catch (_: Exception) {
                    null
                }
            }
            if (loaded != null) {
                thumbCache.put(msgId, loaded)
                bmp = loaded
            }
        }
    }
    return bmp
}

/** Reproductor de notas de voz (MediaPlayer sobre el m4a descifrado). */
@Composable
fun rememberAudioPlayer(app: ChatApp, msgId: String, att: AttachmentMsg.Parsed): AudioPlayerState {
    val state = remember(msgId) { AudioPlayerState() }
    DisposableEffect(msgId) {
        onDispose { state.release() }
    }
    LaunchedEffect(msgId) {
        if (!state.ready && !state.failed) {
            withContext(Dispatchers.IO) {
                try {
                    val f = app.engine.downloadAttachment(msgId, att)
                    val p = MediaPlayer()
                    p.setDataSource(f.path)
                    p.prepare()
                    state.attach(p)
                } catch (_: Exception) {
                    state.fail()
                }
            }
        }
    }
    return state
}

class AudioPlayerState {
    var player by mutableStateOf<MediaPlayer?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0)
        private set
    var durationMs by mutableStateOf(0)
        private set
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun attach(p: MediaPlayer) {
        player = p
        durationMs = p.duration
        ready = true
        p.setOnCompletionListener { playing = false; positionMs = 0 }
    }

    fun fail() { failed = true }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); playing = false }
        else { p.start(); playing = true }
    }

    fun seekTo(fraction: Float) {
        val p = player ?: return
        p.seekTo((durationMs * fraction).toInt().coerceIn(0, durationMs))
        positionMs = p.currentPosition
    }

    fun tick() {
        val p = player ?: return
        if (p.isPlaying) positionMs = p.currentPosition
    }

    fun release() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playing = false
    }
}

/** Burbuja de NOTA DE VOZ: play/pausa + waveform con progreso + duración. */
@Composable
fun AudioBubble(app: ChatApp, msgId: String, att: AttachmentMsg.Parsed, mine: Boolean) {
    val st = rememberAudioPlayer(app, msgId, att)
    LaunchedEffect(st.playing) { while (st.playing) { st.tick(); delay(100) } }
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val bg = if (mine) Color.White.copy(alpha = 0.35f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = bg, shape = androidx.compose.foundation.shape.CircleShape) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                when {
                    st.failed -> Icon(Icons.Default.ErrorOutline, "Error", tint = MaterialTheme.colorScheme.error)
                    !st.ready -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else -> IconButton(onClick = { st.toggle() }, Modifier.size(36.dp)) {
                        Icon(
                            if (st.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (st.playing) "Pausar" else "Reproducir",
                            tint = fg,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(170.dp)) {
            val wf = remember(msgId) { Attach.b64ToWaveform(att.waveformB64 ?: "") }
            val fraction = if (st.durationMs > 0) st.positionMs.toFloat() / st.durationMs else 0f
            if (wf.isNotEmpty()) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clickable { st.seekTo(fraction) },
                ) {
                    val n = wf.size
                    val step = size.width / n
                    val played = Color(0xFFE56B8C) // rosa Cerlita para lo reproducido
                    val pending = fg.copy(alpha = 0.35f)
                    wf.forEachIndexed { i, a ->
                        val h = (a / 255f) * size.height * 0.9f + 2f
                        drawLine(
                            color = if (i.toFloat() / n <= fraction) played else pending,
                            start = androidx.compose.ui.geometry.Offset(i * step + step / 2, size.height / 2 - h / 2),
                            end = androidx.compose.ui.geometry.Offset(i * step + step / 2, size.height / 2 + h / 2),
                            strokeWidth = step * 0.6f,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                }
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
            Text(
                Attach.formatDuration(if (st.ready) st.durationMs.toLong() else att.durMs),
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.8f),
            )
        }
    }
}

/** Burbuja de IMAGEN: miniatura descifrada; click → visor a pantalla completa. */
@Composable
fun ImageBubble(
    app: ChatApp,
    msgId: String,
    att: AttachmentMsg.Parsed,
    onOpenViewer: (Bitmap) -> Unit,
) {
    val bmp = rememberAttachmentImage(app, msgId, att)
    if (bmp == null) {
        Box(
            Modifier
                .size(190.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
    } else {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Foto",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(190.dp)
                .clickable { onOpenViewer(bmp) },
        )
    }
}

/** Burbuja de ARCHIVO: chip con icono + nombre + tamaño + botón guardar. */
@Composable
fun DocBubble(
    app: ChatApp,
    msgId: String,
    att: AttachmentMsg.Parsed,
    onSave: (AttachmentMsg.Parsed, File) -> Unit,
) {
    var downloading by remember(msgId) { mutableStateOf(false) }
    var error by remember(msgId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(170.dp)) {
            Text(att.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                Attach.humanSize(att.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = {
            if (!downloading) {
                downloading = true; error = null
                scope.launch {
                    try {
                        val f = app.engine.downloadAttachment(msgId, att)
                        onSave(att, f)
                    } catch (e: Exception) {
                        error = "Sin conexión o el archivo ya no está"
                    }
                    downloading = false
                }
            }
        }) {
            if (downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Download, "Guardar")
        }
    }
}
