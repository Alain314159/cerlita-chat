package cu.alain.chat.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.profile.AvatarConfig
import cu.alain.chat.profile.Photos
import cu.alain.chat.profile.ProfileMsg
import cu.alain.chat.ui.AvatarCatalog
import cu.alain.chat.ui.AvatarView
import cu.alain.chat.work.WorkSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor de perfil (D61): nombre + FOTO o AVATAR por capas (base, ropa,
 * accesorios). firstRun=true → onboarding tras crear cuenta/entrar sin perfil.
 *
 * Todo lo que se ve aquí son PLACEHOLDERS (2 bases: Cerdita y Koalita; ropa y
 * accesorios dibujados): la estructura (AvatarConfig JSON por capas) ya está
 * lista para los avatares personalizables del futuro.
 */
@Composable
fun ProfileScreen(app: ChatApp, firstRun: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = app.engine.session

    // estado editado en pantalla (arranca del perfil guardado o de defaults)
    val saved = remember { kotlinx.coroutines.runBlocking { app.db.profileDao().get(session?.uid ?: "") } }
    var name by remember { mutableStateOf(saved?.displayName ?: session?.uname ?: "") }
    var cfg by remember { mutableStateOf(AvatarConfig.parse(saved?.avatarJson) ?: AvatarConfig("pig")) }
    var hasPhoto by remember { mutableStateOf(saved?.photo == true) }
    var newPhotoBytes by remember { mutableStateOf<ByteArray?>(null) } // foto recién elegida, sin guardar

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

        // ── vista previa grande: foto (nueva o guardada) si hay, si no avatar por capas ──
        AvatarView(
            photoUid = session?.uid,
            photo = hasPhoto,
            avatarJson = cfg.encode(),
            fallbackInitial = name,
            size = 128.dp,
            pendingPhotoBytes = newPhotoBytes,
            photoVersion = saved?.updatedAt ?: 0L,
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

        // ── avatar: base (solo aplica si no hay foto) ──
        SectionTitle("Avatar${if (hasPhoto) " (ahora tienes foto, el avatar no se ve)" else ""}")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvatarCatalog.bases.forEach { b ->
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

        // ── ropa ──
        SectionTitle("Ropa")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = cfg.clothes == null,
                onClick = { cfg = cfg.copy(clothes = null) },
                label = { Text("Ninguna") },
            )
            AvatarCatalog.clothes.forEach { c ->
                FilterChip(
                    selected = cfg.clothes == c.id,
                    onClick = { cfg = cfg.copy(clothes = c.id) },
                    label = { Text(c.label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── accesorios (por slot: moño O gorro O flor; gafas y corazón aparte) ──
        SectionTitle("Accesorios")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AvatarCatalog.accessories.forEach { a ->
                val selected = cfg.acc[a.slot] == a.id
                FilterChip(
                    selected = selected,
                    onClick = {
                        val acc = cfg.acc.toMutableMap()
                        if (selected) acc.remove(a.slot) else acc[a.slot] = a.id
                        cfg = cfg.copy(acc = acc)
                    },
                    label = { Text(a.label) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            enabled = !saving && name.isNotBlank(),
            onClick = {
                saving = true; error = null
                scope.launch {
                    // foto: nueva si se eligió · mantener si no se tocó · borrar si la quitó
                    val keepExisting = hasPhoto && newPhotoBytes == null
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
            "Los avatares son provisionales: pronto podrás crear el tuyo.\n" +
                "Tu perfil viaja cifrado igual que los mensajes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
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
