package cu.alain.chat.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.core.C
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista de conversaciones + alta del contacto (MVP: contacto único). */
@Composable
fun ChatsScreen(app: ChatApp, onOpenChat: (String) -> Unit) {

    val db = app.db
    val chats by remember {
        db.msgDao().observeChats().map { list -> list.map { it.peerUname to it } }
    }.collectAsState(initial = emptyList())
    val savedPeer = app.prefs.peerUname

    var showAdd by remember { mutableStateOf(false) }
    var newPeer by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    val peers = remember(chats, savedPeer) {
        (chats.map { it.first } + listOfNotNull(savedPeer)).distinct()
    }

    Box(Modifier.fillMaxSize()) {
        if (peers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Aún no tienes conversaciones",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Añade el usuario de tu contacto con el botón +",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(peers, key = { it }) { peer ->
                    val last = chats.firstOrNull { it.first == peer }?.second
                    ChatRow(
                        peer = peer,
                        lastBody = last?.body ?: "",
                        lastTs = last?.ts,
                        unread = last?.state == C.ST_RECEIVED,
                    ) { onOpenChat(peer) }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true; addError = null; newPeer = savedPeer ?: "" },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Icon(Icons.Default.PersonAdd, "Añadir contacto") }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Tu contacto") },
            text = {
                Column {
                    Text(
                        "Escribe el nombre de usuario de la otra persona. " +
                            "Ambos deben compartir la misma frase de cifrado (en persona).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(), value = newPeer,
                        onValueChange = { newPeer = it.trim().lowercase() },
                        label = { Text("Usuario (a-z, 0-9, _)") }, singleLine = true,
                    )
                    addError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = newPeer.isNotEmpty(),
                    onClick = {
                        if (!C.validUsername(newPeer)) {
                            addError = "Usuario inválido: 3-24 letras minúsculas, números o _"
                        } else if (app.store.session?.uname == newPeer) {
                            addError = "Ese usuario eres tú"
                        } else {
                            app.prefs.peerUname = newPeer
                            showAdd = false
                            onOpenChat(newPeer)
                        }
                    },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton({ showAdd = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ChatRow(peer: String, lastBody: String, lastTs: Long?, unread: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text(
                    peer.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer, style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
            )
            if (lastBody.isNotEmpty()) {
                Text(
                    lastBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        lastTs?.let {
            Text(
                SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
