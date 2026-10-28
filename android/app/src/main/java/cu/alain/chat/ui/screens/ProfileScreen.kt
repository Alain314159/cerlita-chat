package cu.alain.chat.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.profile.AvatarConfig
import cu.alain.chat.profile.Photos
import cu.alain.chat.profile.ProfileMsg
import cu.alain.chat.ui.AvatarCatalog
import cu.alain.chat.ui.Avatar3DPreview
import cu.alain.chat.ui.AvatarView
import cu.alain.chat.ui.LayeredAvatar
import cu.alain.chat.ui.capture3DAvatar
import cu.alain.chat.work.WorkSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.roundToInt

/**
 * Editor de perfil (D61): nombre + FOTO o AVATAR por capas (base, ropa,
 * calzado, accesorios). firstRun=true → onboarding tras crear cuenta.
 *
 * v0.11 (D95-D98): ropa/accesorios raster 3D, pestañas Cerdita | Koala
 * (para no pasar por todas las cerditas buscando un koala), calzado propio
 * y vista previa VIVA: reacciona al vestirla, saluda al tocarla y gira
 * al arrastrarla.
 */
@Composable
fun ProfileScreen(app: ChatApp, firstRun: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val rootView = LocalView.current          // (D101) para el snapshot PixelCopy del 3D
    val scope = rememberCoroutineScope()
    val session = app.engine.session

    // estado editado en pantalla (arranca del perfil guardado o de defaults).
    var saved by remember { mutableStateOf<cu.alain.chat.db.Profile?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(session?.uid) {
        saved = app.db.profileDao().get(session?.uid ?: "")
        loaded = true
    }
    var name by remember { mutableStateOf(session?.uname ?: "") }
    var cfg by remember { mutableStateOf(AvatarConfig("pig")) }
    var hasPhoto by remember { mutableStateOf(false) }
    var newPhotoBytes by remember { mutableStateOf<ByteArray?>(null) } // foto recién elegida, sin guardar

    // aplica el perfil guardado una vez cargado (defaults si no existe)
    LaunchedEffect(loaded) {
        if (loaded && saved != null) {
            name = saved!!.displayName
            cfg = AvatarConfig.parse(saved!!.avatarJson) ?: AvatarConfig("pig")
            hasPhoto = saved!!.photo
        }
    }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (firstRun) {
            Spacer(Modifier.height(8.dp))
            Text("Tu perfil", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Elige cómo te verá la otra persona. Puedes cambiarlo cuando quieras.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── (D101) vista previa: SIEMPRE el editor 3D vivo — geometría real,
        //    ropa con volumen, órbita con el dedo. La foto de perfil (si la hay,
        //    o el snapshot 3D al guardar) es lo que ven los chats ──
        var saludo by remember { mutableStateOf(false) }
        LaunchedEffect(saludo) {
            if (saludo) {
                delay(1250)
                saludo = false
            }
        }
        Box(Modifier.size(230.dp)) {
            Avatar3DPreview(
                cfg = cfg,
                modifier = Modifier.fillMaxSize(),
                onWave = { saludo = true },
            )
            HolaBubble(saludo, Modifier.align(Alignment.TopEnd))
        }
        Text(
            "Tócalo para saludar · arrástralo para girarlo de verdad",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hasPhoto) {
            TextButton(onClick = { hasPhoto = false; newPhotoBytes = null }) {
                Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Quitar foto y usar avatar")
            }
        } else {
            OutlinedButton(onClick = { pickPhoto.launch("image/*") }) {
                Icon(Icons.Default.AddAPhoto, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ponerme una foto")
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = name,
            onValueChange = { if (it.length <= ProfileMsg.MAX_NAME) { name = it; error = null } },
            label = { Text("Tu nombre") },
            singleLine = true,
            isError = error != null,
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))

        // ── avatar: pestañas Cerdita | Koala (D96: buscar un koala ya no
        //    requiere pasar por todas las cerditas) ──
        SectionTitle("Avatar")
        var famTab by remember { mutableStateOf(AvatarCatalog.familyOf(cfg.base)) }
        LaunchedEffect(cfg.base) { famTab = AvatarCatalog.familyOf(cfg.base) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = famTab == AvatarCatalog.FAM_PIG,
                onClick = { famTab = AvatarCatalog.FAM_PIG },
                label = { Text("Cerdita") },
            )
            FilterChip(
                selected = famTab == AvatarCatalog.FAM_KOALA,
                onClick = { famTab = AvatarCatalog.FAM_KOALA },
                label = { Text("Koala") },
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvatarCatalog.bases.filter { it.family == famTab }.forEach { b ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { cfg = cfg.copy(base = b.id) }
                        .padding(4.dp),
                ) {
                    Box(
                        Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (cfg.base == b.id) 3.dp else 1.dp,
                                color = if (cfg.base == b.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .padding(4.dp),
                    ) {
                        AvatarView(
                            photoUid = null, photo = false,
                            avatarJson = AvatarConfig(b.id).encode(),
                            fallbackInitial = b.label, size = 72.dp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Text(
                        b.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (cfg.base == b.id) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── ropa (D95: vestido solo cerdita, traje solo koala) ──
        SectionTitle("Ropa")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvatarPreviewTile(
                label = "Ninguna",
                selected = cfg.clothes == null,
                avatarJson = cfg.copy(clothes = null).encode(),
                onClick = { cfg = cfg.copy(clothes = null) },
            )
            AvatarCatalog.clothesFor(famTab).forEach { c ->
                AvatarPreviewTile(
                    label = c.label,
                    selected = cfg.clothes == c.id,
                    avatarJson = cfg.copy(clothes = c.id).encode(),
                    onClick = { cfg = cfg.copy(clothes = c.id) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── calzado (D95: slot propio, se excluyen entre sí) ──
        SectionTitle("Calzado")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvatarPreviewTile(
                label = "Descalzo",
                selected = cfg.acc[AvatarCatalog.SLOT_FEET] == null,
                avatarJson = cfg.copy(acc = cfg.acc - AvatarCatalog.SLOT_FEET).encode(),
                onClick = { cfg = cfg.copy(acc = cfg.acc - AvatarCatalog.SLOT_FEET) },
            )
            AvatarCatalog.accessoriesFor(famTab).filter { it.slot == AvatarCatalog.SLOT_FEET }.forEach { a ->
                val selected = cfg.acc[a.slot] == a.id
                AvatarPreviewTile(
                    label = a.label,
                    selected = selected,
                    avatarJson = if (selected) cfg.encode()
                    else cfg.copy(acc = cfg.acc + (a.slot to a.id)).encode(),
                    onClick = { cfg = cfg.copy(acc = cfg.acc + (a.slot to a.id)) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── accesorios (cada tile alterna; los del mismo slot se excluyen) ──
        SectionTitle("Accesorios")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvatarCatalog.accessoriesFor(famTab).filter { it.slot != AvatarCatalog.SLOT_FEET }.forEach { a ->
                val selected = cfg.acc[a.slot] == a.id
                AvatarPreviewTile(
                    label = a.label,
                    selected = selected,
                    avatarJson = if (selected) cfg.encode() else {
                        cfg.copy(acc = cfg.acc + (a.slot to a.id)).encode()
                    },
                    onClick = {
                        val acc = if (selected) cfg.acc - a.slot else cfg.acc + (a.slot to a.id)
                        cfg = cfg.copy(acc = acc)
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            enabled = !saving && name.isNotBlank(),
            onClick = {
                saving = true; error = null
                // (D101) sin foto elegida esta sesión → snapshot del 3D como foto:
                // el otro teléfono y las notificaciones ven el muñeco REAL que
                // vestiste (la foto viaja cifrada como siempre)
                if (newPhotoBytes == null) {
                    newPhotoBytes = snapshot3D(rootView)
                }
                val keepExisting = hasPhoto && newPhotoBytes == null
                scope.launch {
                    // foto: nueva si se eligió (o snapshot 3D) · mantener si no se tocó
                    val r = app.engine.saveProfile(
                        displayName = name,
                        avatarJson = cfg.encode(),
                        photoBytes = newPhotoBytes,
                        keepPhoto = keepExisting,
                    )
                    saving = false
                    r.fold(
                        onSuccess = {
                            WorkSync.kickOutbox(app)
                            onDone()
                        },
                        onFailure = { e -> error = e.message ?: "No se pudo guardar" },
                    )
                }
            },
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
            }
            Text(if (firstRun) "Guardar y empezar" else "Guardar cambios")
        }

        if (firstRun) {
            TextButton(onClick = {
                // "ahora no": perfil por defecto (nombre de usuario, cerdita) —
                // sin perfil la lista de chats no tendría nada que anunciar (D61)
                scope.launch {
                    saving = true
                    app.engine.saveProfile(
                        displayName = session?.uname ?: "Yo",
                        avatarJson = AvatarConfig("pig").encode(),
                        photoBytes = null,
                    )
                    saving = false
                    onDone()
                }
            }) { Text("Ahora no") }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Elige un personaje y compónlo a tu gusto.\n" +
                "Tu perfil viaja cifrado igual que los mensajes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Burbuja "¡Hola!" del saludo (D98) — composable aparte para no heredar el
 *  ColumnScope de la pantalla (conflicto de AnimatedVisibility). */
@Composable
private fun HolaBubble(visible: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 2 } + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier.graphicsLayer { rotationZ = 8f },
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 3.dp,
            modifier = Modifier.padding(end = 2.dp, top = 30.dp),
        ) {
            Text(
                "  ¡Hola!  ",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * (D101) Snapshot del editor 3D → bytes JPEG (el pipeline de fotos existente
 * lo sube cifrado). Si PixelCopy no está disponible (API <26) o falla,
 * devuelve null y los chats caen al render 2D de siempre.
 */
private fun snapshot3D(root: android.view.View): ByteArray? = try {
    val bmp = capture3DAvatar(root) ?: return null
    val bos = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos)
    bmp.recycle()
    bos.toByteArray()
} catch (_: Exception) {
    null
}

/**
 * (D83) Ficha del selector de avatar con VISTA PREVIA real: se compone la
 * configuración completa (base + ropa + accesorios) sobre el personaje
 * elegido. Antes eran chips de texto y había que imaginarse el resultado.
 */
@Composable
private fun AvatarPreviewTile(
    label: String,
    selected: Boolean,
    avatarJson: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
                .padding(3.dp),
        ) {
            AvatarView(
                photoUid = null, photo = false,
                avatarJson = avatarJson,
                fallbackInitial = label, size = 68.dp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}
