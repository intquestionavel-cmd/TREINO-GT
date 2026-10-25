package br.com.treinogt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.treinogt.core.Formatters
import br.com.treinogt.data.SessionEntity

@Composable
fun HistoryScreen(onNew: () -> Unit, onOpen: (Long) -> Unit, onExport: (Long) -> Unit) {
    val c = rememberContainer()
    val vm: HistoryViewModel = viewModel(factory = viewModelFactory { initializer { HistoryViewModel(c.repository) } })
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var noting by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Treino GT", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onNew) { Text("Nova coleta") }
        }
        Text("Sessões anteriores", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        if (sessions.isEmpty()) {
            Text("Nenhuma sessão ainda. Toque em \"Nova coleta\" para começar.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.name, fontWeight = FontWeight.Bold)
                        Text("${formatDate(s.startedAtEpochMs)}  •  ${Formatters.duration(s.totalNanos)}  •  ${stateLabel(s.state)}",
                            style = MaterialTheme.typography.bodySmall)
                        if (s.notes.isNotBlank()) Text(s.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Row {
                            TextButton(onClick = { onOpen(s.id) }) { Text("Relatório") }
                            TextButton(onClick = { onExport(s.id) }) { Text("Exportar") }
                            var menu by remember { mutableStateOf(false) }
                            TextButton(onClick = { menu = true }) { Text("Mais ▾") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; renaming = s })
                                DropdownMenuItem(text = { Text("Observações") }, onClick = { menu = false; noting = s })
                                DropdownMenuItem(text = { Text("Excluir") }, onClick = { menu = false; deleting = s })
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { s -> TextInputDialog("Renomear sessão", s.name, true, { vm.rename(s.id, it); renaming = null }, { renaming = null }) }
    noting?.let { s -> TextInputDialog("Observações", s.notes, false, { vm.setNotes(s.id, it); noting = null }, { noting = null }) }
    deleting?.let { s ->
        ConfirmDialog("Excluir sessão?", "\"${s.name}\" e todas as suas amostras e passagens serão apagadas permanentemente deste aparelho.",
            "Excluir", { vm.delete(s.id); deleting = null }, { deleting = null })
    }
}
