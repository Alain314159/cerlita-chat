package cu.alain.chat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cu.alain.chat.ChatApp
import cu.alain.chat.db.Message
import cu.alain.chat.db.Room
import cu.alain.chat.profile.AttachmentMsg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Búsqueda global de mensajes (S2/D110, feature F4 de la auditoría).
 *
 * FTS4 sobre el cuerpo de los mensajes (tabla virtual messages_fts mantenida
 * por triggers — ver Db.kt): índice invertido en el propio SQLite, gratis y
 * offline. Con 5 000+ mensajes, buscar es la función que más se echaba de
 * menos. El input se escapa entrecomillando cada término (AND implícito) —
 * sin sorpresas de sintaxis FTS.
 */
@Composable
fun SearchScreen(app: ChatApp, onOpenRoom: (Room) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Message>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    // debounce corto: no buscar por cada tecla
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList(); searched = false; return@LaunchedEffect
        }
        delay(250)
        val q = ftsEscape(query.trim())
        if (q != null) {
            results = withContext(Dispatchers.IO) {
                try { app.db.searchRaw(q) } catch (_: Exception) { emptyList() }
            }
            searched = true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { if (it.length <= 120) query = it },
            placeholder = { Text("Buscar en todos los chats…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        if (!searched && query.trim().length < 2) {
            Text(
                "Busca por palabras: «playa», «feliz cumple», «dirección»…\n" +
                    "Los resultados salen del historial de este teléfono (E2EE: nadie más puede buscarlos por ti).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else if (searched && results.isEmpty()) {
            Text(
                "Sin resultados para «$query»",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results, key = { it.msgId }) { m ->
                SearchResultRow(app, m, onOpenRoom)
            }
        }
    }
}

@Composable
private fun SearchResultRow(app: ChatApp, m: Message, onOpenRoom: (Room) -> Unit) {
    var roomName by remember(m.roomId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(m.roomId) {
        roomName = withContext(Dispatchers.IO) {
            app.db.roomDao().get(m.roomId)?.name ?: "Sala"
        }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                scope.launch {
                    val room = withContext(Dispatchers.IO) { app.db.roomDao().get(m.roomId) }
                    room?.let(onOpenRoom)
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    roomName ?: "…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    cu.alain.chat.core.Fmt.list(m.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (m.mine) "Yo" else m.senderUname,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                AttachmentMsg.preview(m.body) ?: m.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Escapa el input de usuario para FTS4: cada término entre comillas dobles
 * (AND implícito). null si no queda nada buscable.
 */
internal fun ftsEscape(raw: String): String? {
    val terms = raw.split(Regex("\\s+"))
        .map { it.replace("\"", "") }
        .filter { it.isNotBlank() }
    if (terms.isEmpty()) return null
    val sb = StringBuilder()
    terms.forEachIndexed { i, t ->
        if (i > 0) sb.append(' ')
        sb.append('"').append(t).append('"')
    }
    return sb.toString()
}
