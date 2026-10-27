package cu.alain.chat.ui.screens

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cu.alain.chat.ChatApp
import cu.alain.chat.core.C
import cu.alain.chat.engine.AppState
import cu.alain.chat.net.ApiException
import kotlinx.coroutines.launch

// ───────────────────────────── ViewModel ─────────────────────────────

/**
 * D62: el constructor SIEMPRE (Application) — AndroidViewModelFactory instancia por
 * reflexión buscando exactamente esa firma; (ChatApp) lanzaba NoSuchMethodException
 * y mataba la app al abrir (crash del login en teléfonos reales, v0.1.0–v0.3.0).
 */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val app get() = getApplication<ChatApp>()

    /** null = inactivo; "" = trabajando; texto = error a mostrar */
    val busy = androidx.compose.runtime.mutableStateOf<String?>(null)

    fun login(u: String, p: String, onDone: () -> Unit) {
        busy.value = ""
        viewModelScope.launch {
            try {
                val pub = app.store.ensureDeviceKeyPair().first.pubB64 // D74: pública del teléfono
                val r = app.api.login(u, p, app.prefs.deviceId, pub)
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
                val pub = app.store.ensureDeviceKeyPair().first.pubB64 // D74: pública del teléfono
                val r = app.api.register(u, p, app.prefs.deviceId, invite, pub)
                app.engine.onAuthed(r.uid, r.uname, r.token, app.prefs.deviceId)
                busy.value = null; onDone()
            } catch (e: Exception) {
                busy.value = (e as? ApiException)?.userMsg ?: "Sin conexión o servidor caído"
            }
        }
    }
}

// ───────────────────────────── Pantallas ─────────────────────────────

/**
 * Login + registro. La frase E2EE desapareció con el modelo rooms (D54):
 * el emparejamiento es por código de invitación dentro de la app, y las
 * claves se guardan cifradas por hardware — nada que teclear aquí.
 */
@Composable
fun AuthScreens(state: AppState, app: ChatApp) {
    val vm: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    if (state is AppState.Login) LoginScreen(vm)
}

@Composable
private fun LoginScreen(vm: AuthViewModel) {
    var mode by rememberSaveable { mutableStateOf(0) } // 0=entrar 1=crear cuenta
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var pass2 by rememberSaveable { mutableStateOf("") }   // v0.6.0: repetir contraseña
    var showPass by rememberSaveable { mutableStateOf(false) } // ojo ver/ocultar
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
        Text("Cerlita Chat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
        Field(Icons.Default.VpnKey, "Contraseña (mínimo 8)", pass, { pass = it; error = null },
            KeyboardOptions(keyboardType = KeyboardType.Password), secret = true,
            show = showPass, onToggleShow = { showPass = !showPass })
        if (mode == 1) {
            Spacer(Modifier.height(12.dp))
            Field(Icons.Default.Lock, "Repetir la contraseña", pass2, { pass2 = it; error = null },
                KeyboardOptions(keyboardType = KeyboardType.Password), secret = true,
                show = showPass, onToggleShow = { showPass = !showPass })
            Spacer(Modifier.height(12.dp))
            Field(Icons.Default.Lock, "Código de invitación de la app", invite, { invite = it.trim().uppercase() },
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
                when {
                    !C.validUsername(user) ->
                        error = "Usuario inválido: 3-24 letras minúsculas, números o _"
                    mode == 1 && pass != pass2 ->
                        error = "Las contraseñas no coinciden"
                    mode == 0 -> vm.login(user, pass) { }
                    else -> vm.register(user, pass, invite) { }
                }
            },
            modifier = fieldModifier,
            enabled = busy == null && user.isNotEmpty() && pass.length >= 8 &&
                (mode == 0 || (invite.isNotEmpty() && pass2.length >= 8 && pass == pass2)),
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
private fun Field(
    icon: ImageVector, label: String, value: String, onChange: (String) -> Unit,
    keyboard: KeyboardOptions, secret: Boolean = false,
    show: Boolean = false, onToggleShow: (() -> Unit)? = null,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(), value = value, onValueChange = onChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        // v0.6.0: ojo para ver/ocultar la contraseña (login Y repetir)
        trailingIcon = if (secret && onToggleShow != null) {
            {
                IconButton(onClick = onToggleShow) {
                    Icon(
                        if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (show) "Ocultar contraseña" else "Ver contraseña",
                    )
                }
            }
        } else null,
        visualTransformation = if (secret && !show) PasswordVisualTransformation()
        else VisualTransformation.None,
        keyboardOptions = keyboard,
        singleLine = true,
    )
}
