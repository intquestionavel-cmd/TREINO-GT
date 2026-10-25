package br.com.treinogt.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.treinogt.core.Formatters
import br.com.treinogt.core.IntervalValidity
import java.util.Locale

private const val NA = "Não disponível"
private const val INSUFFICIENT = "Dados insuficientes"

@Composable
fun ReportScreen(id: Long, onBack: () -> Unit, onExport: (Long) -> Unit) {
    val c = rememberContainer()
    val vm: ReportViewModel = viewModel(key = "report$id", factory = viewModelFactory { initializer { ReportViewModel(c.repository, id) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val notFound by vm.notFound.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val u = ui
    if (u == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = onBack) { Text("Voltar") }
            Text(if (notFound) "Sessão não encontrada." else "Carregando…")
        }
        return
    }
    val s = u.data.session
    val m = u.model

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Voltar") }
        }
        if (s.state == "INTERRUPTED") {
            Text("⚠ Sessão interrompida (o app foi encerrado durante a coleta). Os dados vão até o último ponto gravado; " +
                "a hora de término é aproximada.", color = MaterialTheme.colorScheme.error)
        }

        SummaryCard(u)
        Card(Modifier.fillMaxWidth()) {
            val base = LocalConfiguration.current.screenWidthDp.dp - 32.dp
            var zoom by remember { mutableStateOf(1f) }
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gráficos (mesmo eixo de tempo; role para os lados)", fontWeight = FontWeight.Bold)
                Text("Zoom horizontal: ${String.format(Locale.ROOT, "%.1f", zoom)}x", style = MaterialTheme.typography.bodySmall)
                Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..12f)
                Text("Cinza = pausa • vermelho = lacuna de captura • linha azul tracejada = passagem manual (Pn)", style = MaterialTheme.typography.bodySmall)
                val w = base * zoom
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Text("Velocidade (km/h)", style = MaterialTheme.typography.labelLarge)
                    if (m.validSpeedSamples == 0) Text(INSUFFICIENT + ": nenhuma leitura de velocidade válida.")
                    else SpeedChart(u.chart, w, textColor)
                    Text("Acelerador e freio (estado binário observado por diferença de pixels)", style = MaterialTheme.typography.labelLarge)
                    if (!m.hasPedalData) Text(INSUFFICIENT + ": pedais sem base calibrada ou sem leituras.")
                    else PedalChart(u.chart, w, textColor)
                    Text("Marcha", style = MaterialTheme.typography.labelLarge)
                    if (!m.hasGearData) Text(INSUFFICIENT + ": nenhuma marcha reconhecida.")
                    else GearChart(u.chart, w, textColor)
                }
            }
        }
        LapsCard(u, onSetRef = vm::setReference)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Limitações desta sessão", fontWeight = FontWeight.Bold)
                m.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (s.notes.isNotBlank()) Text("Observações do usuário: ${s.notes}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExport(id) }) { Text("Exportar") }
            OutlinedButton(onClick = { renaming = true }) { Text("Renomear") }
            OutlinedButton(onClick = { noting = true }) { Text("Notas") }
            OutlinedButton(onClick = { deleting = true }) { Text("Excluir") }
        }
    }

    if (renaming) TextInputDialog("Renomear sessão", s.name, true, { vm.rename(it); renaming = false }, { renaming = false })
    if (noting) TextInputDialog("Observações", s.notes, false, { vm.setNotes(it); noting = false }, { noting = false })
    if (deleting) ConfirmDialog("Excluir sessão?", "Todos os dados desta sessão serão apagados permanentemente.", "Excluir",
        { deleting = false; vm.delete(onBack) }, { deleting = false })
}

@Composable
private fun SummaryCard(u: ReportUi) {
    val s = u.data.session; val m = u.model
    fun pct(v: Double?) = v?.let { String.format(Locale.ROOT, "%.1f %%", it) } ?: INSUFFICIENT
    val pauseSummary = u.data.pauses.groupingBy { it.reason }.eachCount()
        .entries.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "nenhuma" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Resumo", fontWeight = FontWeight.Bold)
            KeyValue("Data", formatDate(s.startedAtEpochMs))
            KeyValue("Duração total da sessão", Formatters.duration(m.totalNanos))
            KeyValue("Tempo de coleta ativa", Formatters.duration(m.activeNanos))
            KeyValue("Tempo em pausa", Formatters.duration(m.pausedNanos))
            KeyValue("Passagens registradas", m.lapMarkerCount.toString())
            KeyValue("Melhor intervalo válido", m.lapSummary.best?.let { "${Formatters.duration(it.durationNanos)} (#${it.number})" } ?: INSUFFICIENT)
            KeyValue("Velocidade máxima observada", m.maxSpeedKmh?.let { "$it km/h" } ?: NA)
            KeyValue("Velocidade média*", m.avgSpeedKmh?.let { String.format(Locale.ROOT, "%.1f km/h", it) } ?: NA)
            KeyValue("Amostras (total / vel. válidas)", "${m.totalSamples} / ${m.validSpeedSamples}")
            KeyValue("Velocidade reconhecida", pct(m.speedRecognitionPct))
            KeyValue("Marcha reconhecida", pct(m.gearRecognitionPct))
            KeyValue("Lacunas de captura", if (m.totalSamples == 0) NA else if (m.gaps.isEmpty()) "nenhuma" else
                "${m.gaps.size} (soma ${Formatters.duration(m.gaps.sumOf { it.endRelNanos - it.startRelNanos })})")
            KeyValue("Interrupções/pausas", pauseSummary)
            Text("*Média ponderada no tempo, só leituras válidas; não interpola lacunas nem pausas.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LapsCard(u: ReportUi, onSetRef: (Int) -> Unit) {
    val laps = u.data.laps.sortedBy { it.sequence }
    val sum = u.model.lapSummary
    val byEnd = sum.intervals.associateBy { it.toSequence }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Histórico de passagens", fontWeight = FontWeight.Bold)
            if (laps.isEmpty()) { Text(INSUFFICIENT + ": nenhuma passagem registrada."); return@Column }
            Text("A referência é o início da 1ª volta; marcações antes dela não geram intervalo.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                listOf("#", "Acumulado", "Intervalo", "Validade", "Método").forEachIndexed { i, h ->
                    Text(h, Modifier.weight(if (i == 3) 1.4f else 1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Text("", Modifier.weight(0.8f))
            }
            for (l in laps) {
                val iv = byEnd[l.sequence]
                val validity = when {
                    l.sequence == sum.referenceSequence -> "REFERÊNCIA"
                    iv == null -> "antes da ref."
                    iv.validity == IntervalValidity.VALID -> if (iv == sum.best) "válido ★melhor" else "válido"
                    iv.validity == IntervalValidity.PAUSE_OVERLAP -> "cruza pausa"
                    else -> "curto demais"
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("${l.sequence}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(Formatters.duration(l.relNanos), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(iv?.let { Formatters.duration(it.durationNanos) } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(validity, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
                    Text(if (l.method == "MANUAL") "manual" else "auto", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onSetRef(l.sequence) }, modifier = Modifier.weight(0.8f)) { Text("Ref.", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
