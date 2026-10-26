package cu.alain.chat.ui.screens

import android.net.Uri
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: ChatApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = app.engine.session

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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── cuenta ──
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Cuenta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Usuario: ${session?.uname ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Sesión válida 90 días; luego pide entrar de nuevo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            "v0.3.0 · Cerlita Chat · salas con ChaCha20-Poly1305 · el servidor nunca ve tus mensajes",
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
                        app.engine.logout(apiLogout = true)
                    },
                ) { Text("Cerrar y borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showLogout = false }) { Text("Cancelar") } },
        )
    }
}
