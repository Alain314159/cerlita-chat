package cu.alain.chat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.VisualTransformation
import cu.alain.chat.ChatApp
import cu.alain.chat.core.C
import cu.alain.chat.work.WorkSync
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla de conversación: burbujas + composer. Offline-first: escribir siempre funciona. */
@Composable
fun ChatScreen(app: ChatApp, peer: String) {

    val messages by app.db.msgDao().observeChat(peer).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // auto-scroll al último mensaje
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            items(messages, key = { it.msgId }) { m ->
                MessageBubble(m)
            }
        }

        sendError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = draft,
                onValueChange = { if (it.length <= 7500) { draft = it; sendError = null } },
                placeholder = { Text("Mensaje…") },
                maxLines = 5,
                visualTransformation = VisualTransformation.None,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val text = draft.trim()
                    if (text.isEmpty()) return@IconButton
                    draft = ""
                    scope.launch {
                        app.engine.send(peer, text)
                            .onSuccess { WorkSync.kickOutbox(app); app.engine.poke() }
                            .onFailure { e ->
                                sendError = e.message ?: "No se pudo encolar el mensaje"
                                draft = text // restaura el texto
                            }
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, "Enviar",
                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(m: cu.alain.chat.db.Message) {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts))
    val mine = m.mine

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Surface(
                color = when {
                    mine -> MaterialTheme.colorScheme.primary
                    m.state == C.ST_UNREADABLE -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (mine) 16.dp else 4.dp,
                    bottomEnd = if (mine) 4.dp else 16.dp,
                ),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        m.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            mine -> MaterialTheme.colorScheme.onPrimary
                            m.state == C.ST_UNREADABLE -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            time,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (mine) {
                            Spacer(Modifier.width(4.dp))
                            when (m.state) {
                                C.ST_SENDING -> Icon(
                                    Icons.Default.Schedule, "Enviando",
                                    Modifier.width(13.dp), tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                )
                                C.ST_FAILED -> Icon(
                                    Icons.Default.ErrorOutline, "Falló",
                                    Modifier.width(13.dp), tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
