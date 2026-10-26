package cu.alain.chat.ui.screens

import android.net.Uri
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
import androidx.compose.material.icons.filled.Lock
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
                    "Exporta todo el historial a un archivo cifrado con una frase que elijas. " +
                        "Guárdalo fuera del teléfono (USB, tarjeta). El archivo no se puede leer sin la frase.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { showExport = true; exportError = null }) { Text("Exportar historial cifrado") }
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
                    "Bloquear borra la clave de la memoria: para leer de nuevo necesitas la frase. " +
                        "Cerrar sesión borra además los mensajes de este teléfono.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(onClick = { app.engine.lock() }) {
                        Icon(Icons.Default.Lock, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Bloquear ahora")
                    }
                    Spacer(Modifier.width(12.dp))
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
            "v0.1.0 · cifrado Argon2id + ChaCha20-Poly1305 · el servidor nunca ve tus mensajes",
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
                        "Elige una frase para cifrar el archivo. Guárdala aparte: sin ella el archivo es irrecuperable.",
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
                                    ctx, app.db,
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

    // ── confirmar logout ──
    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("¿Cerrar sesión?") },
            text = {
                Text(
                    "Se cierra la sesión y se BORRAN de este teléfono los mensajes descifrados " +
                        "y la frase guardada. La otra persona conserva su copia.",
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
