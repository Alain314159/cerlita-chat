package cu.alain.chat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.calls.CallDispatcher
import cu.alain.chat.core.C
import cu.alain.chat.db.CallLog
import cu.alain.chat.db.Room
import cu.alain.chat.net.ApiException
import cu.alain.chat.net.FoundUser
import cu.alain.chat.ui.AvatarView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pestaña LLAMADAS (v0.7.0, D75): registro local + arrancar llamadas.
 * Las llamadas solo existen entre chats directos: al elegir a alguien se
 * busca/crea el chat directo y se llama desde ahí.
 */
@Composable
fun CallsScreen(app: ChatApp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val calls by app.db.callLogDao().observeAll().collectAsState(initial = emptyList())
    val nicknames by app.nicknames.collectAsState() // D78: apodos personales

    // ── nueva llamada: búsqueda de usuario ──
    var showSearch by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoundUser>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var calling by remember { mutableStateOf<String?>(null) }
    var callError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(searchQ) {
        searchError = null
        val q = searchQ.trim().lowercase().removePrefix("@")
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        searching = true
        delay(350) // debounce
        if (q != searchQ.trim().lowercase().removePrefix("@")) return@LaunchedEffect
        val r = try { app.api.searchUsers(app.engine.session!!.token, q) } catch (e: Exception) { null }
        searching = false
        if (r == null) searchError = "Sin conexión o el servidor no respondió"
        else results = r
    }

    /** Busca/crea el chat directo con ese usuario y arranca la llamada. */
    fun startCallTo(uname: String, video: Boolean) {
        if (calling != null) return
        calling = uname
        callError = null
        scope.launch {
            val dm = app.engine.createDm(uname)
            calling = null
            dm.fold(
                onSuccess = { room -> showSearch = false; CallDispatcher.startOutgoing(app, room, "@${uname}", video) },
                onFailure = { e -> callError = (e as? ApiException)?.userMsg ?: (e.message ?: "No se pudo llamar") },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (calls.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.Call, null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primaryContainer,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Aún no hay llamadas",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Toca el botón para llamar a alguien de tus chats directos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(calls, key = { it.id }) { call ->
                    CallRow(
                        app, call,
                        nickname = call.peerUid.takeIf { it.isNotEmpty() }
                            ?.let { nicknames[it.lowercase()] },
                        onCallAgain = {
                            scope.launch {
                                // el @usuario real sale del nombre de la sala DM;
                                // peerName puede ser un perfil/apodo (D78)
                                val uname = app.db.roomDao().get(call.roomId)?.name
                                    ?.removePrefix("@")?.takeIf { it.isNotBlank() }
                                    ?: call.peerName.removePrefix("@")
                                startCallTo(uname, call.kind == C.CALL_VIDEO)
                            }
                        },
                    )
                }
            }
        }

        androidx.compose.material3.FloatingActionButton(
            onClick = {
                showSearch = true; searchQ = ""; results = null; searchError = null; callError = null
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Icon(Icons.Default.Call, "Nueva llamada") }
    }

    // ── diálogo: buscar a quién llamar ──
    if (showSearch) {
        AlertDialog(
            onDismissRequest = { if (calling == null) showSearch = false },
            title = { Text("Llamar a…") },
            text = {
                Column {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(), value = searchQ,
                        onValueChange = { searchQ = it.lowercase() },
                        label = { Text("@usuario") }, singleLine = true,
                    )
                    when {
                        searching -> {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Buscando…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        searchError != null -> {
                            Spacer(Modifier.height(6.dp))
                            Text(searchError!!, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        results?.isEmpty() == true -> {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Nadie con ese nombre todavía.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        results != null -> {
                            Spacer(Modifier.height(6.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                results!!.forEach { u ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AvatarView(
                                            photoUid = null, photo = false, avatarJson = null,
                                            fallbackInitial = u.uname, size = 36.dp,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text("@${u.uname}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                        if (calling == u.uname) {
                                            CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                                        } else if (u.pubB64 != null) {
                                            IconButton(onClick = { startCallTo(u.uname, false) }) {
                                                Icon(Icons.Default.Call, "Llamada de voz",
                                                    tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(onClick = { startCallTo(u.uname, true) }) {
                                                Icon(Icons.Default.Videocam, "Videollamada",
                                                    tint = MaterialTheme.colorScheme.primary)
                                            }
                                        } else {
                                            Text("sin nueva versión", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    callError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSearch = false }) { Text("Cerrar") }
            },
        )
    }
}

@Composable
private fun CallRow(app: ChatApp, call: CallLog, nickname: String?, onCallAgain: () -> Unit) {
    val missed = call.status == C.CALL_MISSED || call.status == C.CALL_DECLINED
    val shownName = nickname ?: call.peerName // D78: apodo personal primero
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(
            photoUid = call.peerUid, photo = false, avatarJson = null,
            fallbackInitial = shownName, size = 46.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                shownName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (missed) FontWeight.Bold else FontWeight.SemiBold,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        call.status == C.CALL_MISSED -> Icons.Default.CallMissed
                        call.dir == C.CALL_OUT -> Icons.Default.CallMade
                        else -> Icons.Default.CallReceived
                    },
                    null, Modifier.size(13.dp),
                    tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    buildString {
                        append(if (call.status == C.CALL_MISSED) "Perdida"
                        else if (call.status == C.CALL_DECLINED) "Rechazada"
                        else if (call.status == C.CALL_CANCELLED) "Cancelada"
                        else if (call.duration > 0) "Llamada · ${call.duration / 60}:${"%02d".format(call.duration % 60)}"
                        else "Llamada")
                        append(if (call.kind == C.CALL_VIDEO) " · vídeo" else "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                cu.alain.chat.core.Fmt.call(call.startedAt), // U4: formatter cacheado
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCallAgain) {
            Icon(
                if (call.kind == C.CALL_VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                "Volver a llamar",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
