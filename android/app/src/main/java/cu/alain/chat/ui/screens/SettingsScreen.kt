package cu.alain.chat.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.export.HistoryExport
import cu.alain.chat.export.HistoryImport
import cu.alain.chat.notify.Doorbell
import cu.alain.chat.notify.DoorbellService
import cu.alain.chat.notify.Notifier
import cu.alain.chat.ui.AvatarView
import cu.alain.chat.ui.theme.Themes
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: ChatApp, onEditProfile: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = app.engine.session

    // D93: días que le quedan a la sesión (null = aún sin dato del servidor)
    val sessionDaysLeft = session?.exp?.let { exp ->
        val ms = exp - System.currentTimeMillis()
        if (ms <= 0) 0 else ((ms + 86_399_999L) / 86_400_000L).toInt()
    }

    var showExport by remember { mutableStateOf(false) }
    var exportPass by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var exportUri by remember { mutableStateOf<Uri?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var showLogout by remember { mutableStateOf(false) }

    // ── import del respaldo ──
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importPass by remember { mutableStateOf("") }
    var showImportPass by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<HistoryImport.ImportResult?>(null) }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri
            importPass = ""
            importError = null
            showImportPass = true
        }
    }

    var serverUrl by remember { mutableStateOf(app.prefs.baseUrl) }
    var ping by remember { mutableStateOf<String?>(null) }
    var pinging by remember { mutableStateOf(false) }

    // ── estado notificaciones/batería (se refresca al recomponer) ──
    var permTick by remember { mutableStateOf(0) }
    val notifOk = remember(permTick) { Notifier.canNotify(ctx) }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val batteryOk = remember(permTick) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }

    // perfil propio (D61): tarjeta con avatar + nombre elegido
    val myProfile by app.db.profileDao().observe(app.engine.session?.uid ?: "")
        .collectAsState(initial = null)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── perfil ──
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarView(
                        photoUid = app.engine.session?.uid,
                        photo = myProfile?.photo == true,
                        avatarJson = myProfile?.avatarJson,
                        fallbackInitial = myProfile?.displayName ?: session?.uname ?: "?",
                        size = 56.dp,
                        photoVersion = myProfile?.updatedAt ?: 0L,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            myProfile?.displayName ?: session?.uname ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "@${session?.uname ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when (sessionDaysLeft) {
                                null -> "Sesión válida 90 días; luego pide entrar de nuevo."
                                0 -> "Tu sesión expiró: entra de nuevo (todo lo tuyo queda guardado)."
                                1 -> "Tu sesión expira mañana — entra de nuevo para renovarla."
                                else -> "Sesión: quedan $sessionDaysLeft días (luego pide entrar de nuevo)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sessionDaysLeft != null && sessionDaysLeft <= 7)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onEditProfile) {
                    Text("Editar perfil (nombre, foto, avatar)")
                }
            }
        }

        // ── apariencia (D73): tema claro / oscuro / cerlita ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Apariencia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Elige el tema de la app. «Cerlita» es el tema de ustedes dos 💗",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Themes.OPTIONS.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = app.prefs.theme == mode,
                            onClick = {
                                app.prefs.theme = mode
                                app.themeFlow.value = mode
                            },
                        )
                        Text(Themes.label(mode), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // ── export cifrado (P4) ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.UploadFile, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Copia de seguridad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Exporta el historial Y las claves de tus salas a un archivo cifrado con una " +
                        "frase que elijas. Guárdalo fuera del teléfono (USB, tarjeta): con él " +
                        "recuperas todo en un teléfono nuevo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { showExport = true; exportError = null }) { Text("Exportar historial cifrado") }
                    OutlinedButton(
                        enabled = !importing,
                        onClick = { pickImport.launch(arrayOf("*/*")) },
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Importar respaldo")
                    }
                }
                if (importing) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Descifrando respaldo… (tarda unos segundos)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── notificaciones y batería (D63/D67/D72) ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Notificaciones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Con la app abierta los mensajes llegan SIEMPRE al instante. " +
                        "Con la app cerrada puedes elegir (recomendado: activado):",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = app.prefs.instantBg,
                        onCheckedChange = {
                            app.prefs.instantBg = it
                            app.syncDoorbell()
                            permTick++ // refresca el texto de estado
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Avisos instantáneos con la app cerrada", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (app.prefs.instantBg)
                                "Tiempo real también con la app cerrada. Android exige una " +
                                    "notificación permanente mientras esté activo (sin sonido ni " +
                                    "icono; en Android 13+ puedes deslizarla y el aviso sigue)."
                            else
                                "Sin notificación permanente. Los mensajes se buscan " +
                                    "cada ~15 minutos (o al abrir la app) y avisan igual.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        DoorbellService.running && DoorbellService.connected ->
                            "● Tiempo real también con la app cerrada (modo instantáneo)"
                        DoorbellService.running -> "● Conectando el timbre permanente…"
                        app.doorbellConnected -> "● Tiempo real con la app abierta"
                        else -> "● Timbre sin conexión ahora"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (DoorbellService.running || app.doorbellConnected)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!notifOk) {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                try {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                                    )
                                } catch (_: Exception) {}
                            }
                        }) { Text("Permitir avisos") }
                    }
                    if (!batteryOk) {
                        OutlinedButton(onClick = {
                            try {
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + ctx.packageName),
                                    ),
                                )
                            } catch (_: Exception) {}
                        }) { Text("Quitar ahorro de batería") }
                    }
                    if (notifOk && batteryOk) {
                        Text(
                            "✓ Todo en orden",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (!batteryOk) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "El ahorro de batería puede retrasar los avisos. En Xiaomi/Redmi activa " +
                            "también Autoinicio y batería «Sin restricciones» para Cerlita Chat " +
                            "(Ajustes del teléfono → Aplicaciones) — imprescindible si usas el modo instantáneo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── red ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Servidor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(), value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("URL base") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = serverUrl.isNotBlank() && serverUrl != app.prefs.baseUrl,
                        onClick = { app.prefs.baseUrl = serverUrl.trimEnd('/') },
                    ) { Text("Guardar URL") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        enabled = !pinging,
                        onClick = {
                            pinging = true; ping = null
                            scope.launch {
                                ping = try {
                                    "Latencia: ${app.api.heartbeat()} ms"
                                } catch (e: Exception) {
                                    "Sin respuesta del servidor"
                                }
                                pinging = false
                            }
                        },
                    ) {
                        if (pinging) CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Speed, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Probar")
                    }
                }
                ping?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = if (it.startsWith("Latencia")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
                }
            }
        }

        // ── seguridad ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Seguridad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Las claves de las salas están cifradas en este teléfono con el almacén de " +
                        "claves de Android: no se pueden copiar a otro dispositivo. Cierra sesión para " +
                        "borrarlas junto con los mensajes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(
                        onClick = { showLogout = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Logout, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Cerrar sesión")
                    }
                }
            }
        }

        Text(
            "v0.7.0 · Cerlita Chat · salas con ChaCha20-Poly1305 · el servidor nunca ve tus mensajes ni tu perfil",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    // ── diálogo export ──
    if (showExport) {
        AlertDialog(
            onDismissRequest = { if (!exporting) showExport = false },
            title = { Text("Frase del export") },
            text = {
                Column {
                    Text(
                        "Elige una frase para cifrar el archivo (no uses la de tu cuenta). " +
                            "Incluye historial y claves de salas: quien tenga el archivo Y la frase " +
                            "lee todo. Guárdala aparte.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(), value = exportPass,
                        onValueChange = { exportPass = it },
                        label = { Text("Frase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    exportError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = exportPass.length >= 8 && !exporting,
                    onClick = {
                        exporting = true; exportError = null
                        scope.launch {
                            val uri = try {
                                HistoryExport.export(
                                    ctx, app.db, app.store,
                                    app.engine.session?.uname ?: "?",
                                    exportPass.toCharArray(),
                                )
                            } catch (e: Exception) { null }
                            exporting = false
                            if (uri != null) {
                                exportUri = uri
                                showExport = false
                            } else {
                                exportError = "No hay mensajes o falló el export"
                            }
                        }
                    },
                ) {
                    if (exporting) {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Exportar")
                }
            },
            dismissButton = { TextButton({ showExport = false }) { Text("Cancelar") } },
        )
    }

    // ── compartir tras export ──
    exportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { exportUri = null },
            title = { Text("Export listo") },
            text = { Text("El archivo cifrado se guardó en Descargas. ¿Quieres compartirlo ahora?") },
            confirmButton = {
                TextButton({
                    ctx.startActivity(HistoryExport.shareIntent(ctx, uri))
                    exportUri = null
                }) { Text("Compartir") }
            },
            dismissButton = { TextButton({ exportUri = null }) { Text("Listo") } },
        )
    }

    // ── diálogo frase del import ──
    if (showImportPass && importUri != null) {
        AlertDialog(
            onDismissRequest = { if (!importing) showImportPass = false },
            title = { Text("Frase del respaldo") },
            text = {
                Column {
                    Text(
                        "Introduce la frase con la que se cifró este respaldo " +
                            "(la elegiste al exportarlo).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(), value = importPass,
                        onValueChange = { importPass = it },
                        label = { Text("Frase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    importError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = importPass.isNotEmpty() && !importing,
                    onClick = {
                        importing = true; importError = null
                        val uri = importUri ?: return@Button
                        scope.launch {
                            val res = HistoryImport.import(ctx, app.db, app.store, uri, importPass.toCharArray())
                            importing = false
                            res.fold(
                                onSuccess = { r ->
                                    importResult = r
                                    showImportPass = false
                                    // claves nuevas a RAM + drenar pendientes + salas del servidor
                                    app.engine.onProcessStart()
                                },
                                onFailure = { e ->
                                    importError = e.message ?: "No se pudo importar"
                                },
                            )
                        }
                    },
                ) {
                    if (importing) {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Importar")
                }
            },
            dismissButton = { TextButton({ if (!importing) showImportPass = false }) { Text("Cancelar") } },
        )
    }

    // ── resultado del import ──
    importResult?.let { r ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("Respaldo importado") },
            text = {
                Text(
                    buildString {
                        append("Salas nuevas: ${r.roomsAdded}")
                        append("\nClaves restauradas: ${r.keysRestored}")
                        append("\nMensajes: ${r.messagesRestored}")
                        if (r.me != (session?.uname ?: "?")) append("\n\nOJO: el respaldo era de «${r.me}».")
                    },
                )
            },
            confirmButton = { TextButton({ importResult = null }) { Text("Listo") } },
        )
    }

    // ── confirmar logout ──
    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("¿Cerrar sesión?") },
            text = {
                Text(
                    "Se cierra la sesión y se BORRAN de este teléfono los mensajes, las salas y " +
                        "sus claves. La otra persona conserva su copia; tú puedes volver a entrar " +
                        "con tu usuario y recuperar las salas con los códigos de invitación " +
                        "o el archivo de respaldo.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogout = false
                        Doorbell.stop(ctx)            // fuera el timbre y su notificación
                        Notifier.cancelAllMessages(ctx) // fuera los avisos de mensajes
                        app.engine.logout(apiLogout = true)
                    },
                ) { Text("Cerrar y borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showLogout = false }) { Text("Cancelar") } },
        )
    }
}
