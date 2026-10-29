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
import cu.alain.chat.ui.AvatarView
import cu.alain.chat.ui.LayeredAvatar
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
 * v0.17 (D113): el avatar es 2D para siempre — FUERA el 3D (SceneView, GLB,
 * Filament: −20 MB de APK). Pestañas Cerdita | Koala (D96), calzado propio
 * (D97) y vista previa VIVA 2D (D98, restaurada): reacciona al vestirla,
 * saluda al tocarla y gira al arrastrarla. La FOTO sigue siendo opción de
 * primera clase (D76): elige foto o avatar, lo que prefieras.
 */
@Composable
fun ProfileScreen(app: ChatApp, firstRun: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
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

        // ── vista previa: FOTO si la hay; si no, avatar VIVO 2D (D98, de
        //    vuelta en D113 — fuera el editor 3D, el avatar es 2D y basta) ──
        if (hasPhoto) {
            AvatarView(
                photoUid = session?.uid,
                photo = true,
                avatarJson = cfg.encode(),
                fallbackInitial = name,
                size = 128.dp,
                pendingPhotoBytes = newPhotoBytes,
                photoVersion = saved?.updatedAt ?: 0L,
            )
            TextButton(onClick = { hasPhoto = false; newPhotoBytes = null }) {
                Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Quitar foto y usar avatar")
            }
        } else {
            InteractiveAvatar(cfg, Modifier.size(196.dp))
            Text(
                "Tócalo para saludar · arrástralo para girarlo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
                val keepExisting = hasPhoto && newPhotoBytes == null
                scope.launch {
                    // foto: nueva si se eligió · mantener si no se tocó · borrar si la quitó
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

/**
 * (D98, restaurado en D113) El avatar VIVO de la vista previa — 2D:
 *  - RESPIRA en reposo (balanceo sutil infinito)
 *  - SABE que lo vistes: rebote feliz + meneo en cada cambio de ropa/accesorio
 *  - cambia de personaje → vuelta completa de 360°
 *  - TOCARLO → saluda (brinquitos + meneo + burbuja "¡Hola!")
 *  - ARRASTRARLO → gira (rotación Y con perspectiva; X inclina) y al soltar
 *    vuelve con muelle al frente más cercano
 */
@Composable
private fun InteractiveAvatar(cfg: AvatarConfig, modifier: Modifier = Modifier) {
    val base = AvatarCatalog.base(cfg.base) ?: return
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(0.55f) }
    val rotZ = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    val rotX = remember { Animatable(0f) }
    val ty = remember { Animatable(0f) }
    var saludo by remember { mutableStateOf(false) }
    // px reales según densidad: los saltos se ven iguales en cualquier móvil
    val hop = with(LocalDensity.current) { 26.dp.toPx() }
    val bob = with(LocalDensity.current) { 7.dp.toPx() }

    // respiración idle: sube/baja sutil y cabecea 1.4°
    val vivo = rememberInfiniteTransition(label = "vivo")
    val fase by vivo.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "respira",
    )

    // entrada con rebote
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
    }

    // reacción al vestirlo (salta la entrada inicial)
    LaunchedEffect(cfg.clothes, cfg.acc) {
        if (scale.isRunning && scale.value < 0.95f) return@LaunchedEffect
        if (scale.value >= 0.9f) {
            launch {
                scale.animateTo(1f, keyframes {
                    durationMillis = 560
                    1.10f at 90 using FastOutSlowInEasing
                    0.94f at 220
                    1.04f at 360
                    1f at 560
                })
            }
            launch {
                rotZ.animateTo(0f, keyframes {
                    durationMillis = 560
                    -5f at 110
                    4.5f at 260
                    -2f at 400
                    0f at 560
                })
            }
        }
    }

    // cambio de personaje → vuelta completa
    LaunchedEffect(cfg.base) {
        val objetivo = (rotY.value / 360f).toInt() * 360f + 360f
        rotY.animateTo(objetivo, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }

    // la burbuja de saludo se despide sola
    LaunchedEffect(saludo) {
        if (saludo) {
            delay(1250)
            saludo = false
        }
    }

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rotZ.value + (fase - 0.5f) * 1.4f
                rotationY = rotY.value
                rotationX = rotX.value
                translationY = ty.value + (0.5f - fase) * bob
                cameraDistance = size.maxDimension * 6f
            }
            .drawBehind {
                // sombra de suelo que ancla el personaje
                val w = size.width * 0.52f
                val cxS = size.width / 2
                val cyS = size.height * 0.955f
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x2E3A2E38), Color.Transparent),
                        center = Offset(cxS, cyS),
                        radius = w / 2,
                    ),
                    topLeft = Offset(cxS - w / 2, cyS - w * 0.085f),
                    size = Size(w, w * 0.17f),
                )
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    saludo = true
                    scope.launch {
                        ty.animateTo(0f, keyframes {
                            durationMillis = 950
                            -hop at 130 using FastOutSlowInEasing
                            0f at 250
                            -hop * 0.66f at 400
                            0f at 540
                            -hop * 0.33f at 660
                            0f at 950
                        })
                        rotZ.animateTo(0f, keyframes {
                            durationMillis = 950
                            -9f at 130
                            7f at 300
                            -5f at 470
                            3f at 640
                            0f at 950
                        })
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amt ->
                        change.consume()
                        scope.launch {
                            rotY.snapTo(rotY.value + amt.x * 0.55f)
                            rotX.snapTo((rotX.value - amt.y * 0.22f).coerceIn(-14f, 14f))
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            val frente = (rotY.value / 360f).roundToInt() * 360f
                            rotY.animateTo(frente.toFloat(), spring(dampingRatio = 0.62f))
                            rotX.animateTo(0f, spring(dampingRatio = 0.7f))
                        }
                    },
                )
            },
    ) {
        LayeredAvatar(base, cfg, Modifier.fillMaxSize())
        // burbuja "¡Hola!" del saludo (D98) — nada de corazones (D96)
        AnimatedVisibility(
            visible = saludo,
            enter = fadeIn() + slideInVertically { -it / 2 } + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer { rotationZ = 8f },
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
