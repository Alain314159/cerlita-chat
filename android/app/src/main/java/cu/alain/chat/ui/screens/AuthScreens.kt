package cu.alain.chat.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cu.alain.chat.ChatApp
import cu.alain.chat.core.C
import cu.alain.chat.engine.AppState
import cu.alain.chat.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────────── ViewModel ─────────────────────────────

class AuthViewModel(private val app: ChatApp) : AndroidViewModel(app) {

    /** null = inactivo; "" = trabajando; texto = error a mostrar */
    val busy = androidx.compose.runtime.mutableStateOf<String?>(null)

    fun login(u: String, p: String, onDone: () -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            try {
                val r = app.api.login(u, p, app.prefs.deviceId)
                app.engine.onAuthed(r.uid, r.uname, r.token, app.prefs.deviceId)
                busy.value = null; onDone()
            } catch (e: Exception) {
                busy.value = (e as? ApiException)?.userMsg ?: "Sin conexión o servidor caído"
            }
        }
    }

    fun register(u: String, p: String, invite: String, onDone: () -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            try {
                val r = app.api.register(u, p, app.prefs.deviceId, invite)
                app.engine.onAuthed(r.uid, r.uname, r.token, app.prefs.deviceId)
                busy.value = null; onDone()
            } catch (e: Exception) {
                busy.value = (e as? ApiException)?.userMsg ?: "Sin conexión o servidor caído"
            }
        }
    }

    /** Argon2id local 64 MiB: fuera del hilo principal (regla de los 16 ms). */
    fun setup(passphrase: CharArray, onDone: (Boolean) -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) {
                try { app.engine.setupE2ee(passphrase); true } catch (_: Exception) { false }
            }
            busy.value = null; onDone(ok)
        }
    }

    fun unlock(passphrase: CharArray, onDone: (Boolean) -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) {
                app.engine.unlock(passphrase)
            }
            busy.value = null; onDone(ok)
        }
    }
}

// ───────────────────────────── Pantallas ─────────────────────────────

@Composable
fun AuthScreens(state: AppState, app: ChatApp) {
    val vm: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    when (state) {
        AppState.Login -> LoginScreen(vm)
        AppState.Setup -> SetupScreen(vm)
        AppState.Unlock -> UnlockScreen(vm)
        else -> {}
    }
}

@Composable
private fun LoginScreen(vm: AuthViewModel) {
    var mode by rememberSaveable { mutableStateOf(0) } // 0=entrar 1=crear cuenta
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val busy = vm.busy.value
    val fieldModifier = Modifier.fillMaxWidth()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Chat privado", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Cifrado de extremo a extremo",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        SingleChoiceSegmentedButtonRow(fieldModifier) {
            SegmentedButton(
                selected = mode == 0, onClick = { mode = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Entrar") }
            SegmentedButton(
                selected = mode == 1, onClick = { mode = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Crear cuenta") }
        }
        Spacer(Modifier.height(20.dp))

        Field(Icons.Default.Person, "Usuario (a-z, 0-9, _)", user, { user = it.trim().lowercase() },
            KeyboardOptions(keyboardType = KeyboardType.Ascii))
        Spacer(Modifier.height(12.dp))
        Field(Icons.Default.VpnKey, "Contraseña (mínimo 8)", pass, { pass = it },
            KeyboardOptions(keyboardType = KeyboardType.Password), secret = true)
        if (mode == 1) {
            Spacer(Modifier.height(12.dp))
            Field(Icons.Default.Lock, "Código de invitación", invite, { invite = it.trim().uppercase() },
                KeyboardOptions(keyboardType = KeyboardType.Ascii))
        }

        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(error != null) {
            Text(
                error ?: "", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                error = null
                if (!C.validUsername(user)) {
                    error = "Usuario inválido: 3-24 letras minúsculas, números o _"
                } else if (mode == 0) {
                    vm.login(user, pass) { }
                } else {
                    vm.register(user, pass, invite) { }
                }
            },
            modifier = fieldModifier,
            enabled = busy == null && user.isNotEmpty() && pass.length >= 8 &&
                (mode == 0 || invite.isNotEmpty()),
        ) {
            if (busy == "") {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Verificando…")
            } else {
                Text(if (mode == 0) "Entrar" else "Crear cuenta")
            }
        }

        Text(
            "El login tarda unos segundos: el servidor cifra tu contraseña con Argon2id.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = fieldModifier.padding(top = 20.dp),
        )
    }
}

@Composable
private fun SetupScreen(vm: AuthViewModel) {
    var p1 by rememberSaveable { mutableStateOf("") }
    var p2 by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = vm.busy.value
    val fieldModifier = Modifier.fillMaxWidth()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Frase de cifrado", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Card(
            fieldModifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Esta frase NO es tu contraseña: es la clave que cifra tus mensajes de extremo a extremo.\n\n" +
                        "• Nadie puede recuperarla: ni tú ni el servidor.\n" +
                        "• Compártela SOLO en persona con tu contacto.\n" +
                        "• Si la olvidas, los mensajes serán ilegibles para siempre.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            modifier = fieldModifier, value = p1, onValueChange = { p1 = it },
            label = { Text("Frase de cifrado") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            modifier = fieldModifier, value = p2, onValueChange = { p2 = it },
            label = { Text("Repite la frase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (p1.length >= 16) "Frase larga: bien" else "Consejo: 4-6 palabras al azar, 16+ caracteres",
            style = MaterialTheme.typography.bodySmall,
            color = if (p1.length >= 16) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(error != null) {
            Text(error ?: "", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                error = null
                vm.setup(p1.toCharArray()) { ok -> if (!ok) error = "No se pudo guardar la frase" }
            },
            modifier = fieldModifier,
            enabled = busy == null && p1.length >= 8 && p1 == p2,
        ) {
            if (busy == "") {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Derivando clave…")
            } else Text("Guardar frase y entrar")
        }
        Text(
            "Argon2id con 64 MiB de memoria: tarda 1-3 segundos, solo esta vez.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnlockScreen(vm: AuthViewModel) {
    var p by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = vm.busy.value
    val fieldModifier = Modifier.fillMaxWidth()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Bloqueado", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Introduce tu frase de cifrado",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            modifier = fieldModifier, value = p, onValueChange = { p = it },
            label = { Text("Frase de cifrado") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        AnimatedVisibility(error != null) {
            Text(error ?: "", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                error = null
                vm.unlock(p.toCharArray()) { ok ->
                    if (!ok) error = "Frase incorrecta"
                }
            },
            modifier = fieldModifier,
            enabled = busy == null && p.isNotEmpty(),
        ) {
            if (busy == "") {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Descifrando…")
            } else Text("Desbloquear")
        }
    }
}

@Composable
private fun Field(
    icon: ImageVector, label: String, value: String, onChange: (String) -> Unit,
    keyboard: KeyboardOptions, secret: Boolean = false,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(), value = value, onValueChange = onChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        visualTransformation = if (secret) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = keyboard,
        singleLine = true,
    )
}
