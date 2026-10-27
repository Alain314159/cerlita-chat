package cu.alain.chat.calls

import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cu.alain.chat.ChatApp
import cu.alain.chat.ui.AvatarView
import cu.alain.chat.ui.theme.CallColors
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.Locale

/**
 * Pantalla de llamada (v0.7.0, D75) — overlay a pantalla completa, estilo del
 * mockup: entrante con aceptar (verde) / rechazar (rojo); activa con vídeo PIP
 * y controles; saliente con «Llamando…».
 */
@Composable
fun CallScreenOverlay(app: ChatApp) {
    val state by CallDispatcher.state.collectAsState()
    val ctx = LocalContext.current

    // vida del dispatcher ligada a la pantalla: sin overlay no hay llamadas
    DisposableEffect(Unit) {
        CallDispatcher.enabled = true
        onDispose { }
    }

    val s = state ?: return
    val peerProfile by app.db.profileDao().observe(s.peerUid).collectAsState(initial = null)
    val nicknames by app.nicknames.collectAsState() // D78
    val shownName = nicknames[s.peerUid.lowercase()] ?: s.peerName

    // permisos de llamada: se piden al ACEPTAR (mic siempre; cámara si vídeo)
    val callPerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[android.Manifest.permission.RECORD_AUDIO] == true) {
            CallDispatcher.accept(app)
        }
        // sin micrófono no hay llamada: se queda sonando (el par cuelga a los 40 s)
    }

    // cronómetro
    var elapsed by remember(s.callId) { mutableLongStateOf(0L) }
    LaunchedEffect(s.phase, s.connectedAt) {
        while (s.phase == CallDispatcher.Phase.ACTIVE && s.connectedAt > 0) {
            elapsed = (System.currentTimeMillis() - s.connectedAt) / 1000
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (s.video && s.phase == CallDispatcher.Phase.ACTIVE) Color(0xFF1A0F14) else MaterialTheme.colorScheme.background),
    ) {
        // ── vídeo remoto a pantalla completa + PIP local (solo videollamada activa) ──
        if (s.video && s.phase == CallDispatcher.Phase.ACTIVE) {
            VideoRenderer(
                trackProvider = { getSessionRemote(app) },
                modifier = Modifier.fillMaxSize(),
            )
            getSessionLocal(app)?.let { local ->
                VideoRenderer(
                    trackProvider = { local },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(width = 96.dp, height = 128.dp)
                        .clip(RoundedCorner8),
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (s.phase == CallDispatcher.Phase.ACTIVE && s.video) 12.dp else 90.dp))
            if (!(s.phase == CallDispatcher.Phase.ACTIVE && s.video)) {
                AvatarView(
                    photoUid = s.peerUid,
                    photo = peerProfile?.photo == true,
                    avatarJson = peerProfile?.avatarJson,
                    fallbackInitial = shownName,
                    size = 120.dp,
                    photoVersion = peerProfile?.updatedAt ?: 0L,
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                shownName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (s.phase == CallDispatcher.Phase.ACTIVE && s.video) Color.White else Color.Unspecified,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when (s.phase) {
                    CallDispatcher.Phase.OUTGOING ->
                        if (s.video) "Videollamando…" else "Llamando…"
                    CallDispatcher.Phase.INCOMING ->
                        if (s.video) "Videollamada entrante" else "Llamada entrante"
                    CallDispatcher.Phase.ACTIVE ->
                        "%d:%02d".format(elapsed / 60, elapsed % 60)
                    CallDispatcher.Phase.ENDED -> "Finalizada"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (s.phase == CallDispatcher.Phase.ACTIVE && s.video) Color.White.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── controles según fase ──
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (s.phase) {
                CallDispatcher.Phase.INCOMING -> {
                    // rechazar (rojo) — aceptar (verde): como el mockup
                    RoundCallButton(
                        icon = Icons.Default.CallEnd, tint = CallColors.reject, label = "Rechazar",
                        size = 68.dp,
                    ) { CallDispatcher.decline(app) }
                    RoundCallButton(
                        icon = if (s.video) Icons.Default.Videocam else Icons.Default.Call,
                        tint = CallColors.accept, label = "Aceptar", size = 68.dp,
                    ) {
                        callPerms.launch(
                            if (s.video) arrayOf(
                                android.Manifest.permission.RECORD_AUDIO,
                                android.Manifest.permission.CAMERA,
                            )
                            else arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        )
                    }
                }

                CallDispatcher.Phase.OUTGOING -> {
                    RoundCallButton(
                        icon = Icons.Default.CallEnd, tint = CallColors.reject, label = "Colgar",
                        size = 68.dp,
                    ) { CallDispatcher.hangup(app) }
                }

                CallDispatcher.Phase.ACTIVE -> {
                    RoundCallButton(
                        icon = if (s.micOn) Icons.Default.Mic else Icons.Default.MicOff,
                        tint = MaterialTheme.colorScheme.onSurface, label = if (s.micOn) "Silenciar" else "Micrófono",
                        active = !s.micOn,
                    ) { CallDispatcher.toggleMic() }
                    if (s.video) {
                        RoundCallButton(
                            icon = if (s.camOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            tint = MaterialTheme.colorScheme.onSurface, label = "Cámara",
                            active = !s.camOn,
                        ) { CallDispatcher.toggleCam(app) }
                        RoundCallButton(
                            icon = Icons.Default.FlipCameraAndroid,
                            tint = MaterialTheme.colorScheme.onSurface, label = "Cambiar",
                        ) { CallDispatcher.switchCam() }
                    } else {
                        RoundCallButton(
                            icon = Icons.Default.VolumeUp,
                            tint = MaterialTheme.colorScheme.onSurface, label = "Altavoz",
                            active = s.speakerOn,
                        ) { CallDispatcher.toggleSpeaker(app) }
                    }
                    RoundCallButton(
                        icon = Icons.Default.CallEnd, tint = CallColors.reject, label = "Colgar",
                        size = 68.dp,
                    ) { CallDispatcher.hangup(app) }
                }

                CallDispatcher.Phase.ENDED -> {}
            }
        }
    }
}

private val RoundedCorner8 = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

/** Render WebRTC de una pista (remote o local). trackProvider se re-poll-ea. */
@Composable
private fun VideoRenderer(
    trackProvider: () -> VideoTrack?,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    var bound by remember { mutableStateOf<VideoTrack?>(null) }
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        factory = { c ->
            SurfaceViewRenderer(c).apply {
                init(CallSession.egl().eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                renderer = this
            }
        },
        modifier = modifier,
    )

    // enlazar la pista cuando aparezca (y solo una vez)
    LaunchedEffect(Unit) {
        while (bound == null) {
            val t = trackProvider()
            if (t != null) {
                renderer?.let { r -> t.addSink(r); bound = t }
            }
            delay(120)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { bound?.removeSink(renderer) } catch (_: Exception) {}
            try { renderer?.release() } catch (_: Exception) {}
        }
    }
}

@Composable
private fun RoundCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primaryContainer
            else tint.copy(alpha = 0.16f),
            modifier = Modifier.size(size),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = tint, modifier = Modifier.size((size.value * 0.45f).dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── acceso a las pistas del dispatcher (sin exponer la sesión) ──
internal fun getSessionRemote(@Suppress("UNUSED_PARAMETER") app: ChatApp): VideoTrack? =
    CallDispatcher.currentRemoteTrack()
internal fun getSessionLocal(@Suppress("UNUSED_PARAMETER") app: ChatApp): VideoTrack? =
    CallDispatcher.currentLocalTrack()
