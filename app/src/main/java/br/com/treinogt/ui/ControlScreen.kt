package br.com.treinogt.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.treinogt.SettingsStore
import br.com.treinogt.core.Formatters
import br.com.treinogt.engine.CaptureStatus
import br.com.treinogt.engine.Elapsed
import br.com.treinogt.engine.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ControlActions(
    val requestOverlayPermission: () -> Unit,
    val requestNotificationPermission: () -> Unit,
    val launchProjection: () -> Unit,
    val startManualService: () -> Unit,
    val stopService: () -> Unit,
    val exportSession: (Long) -> Unit,
)

@Composable
fun ControlScreen(actions: ControlActions, onBack: () -> Unit, onOpenReport: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val phase by c.engine.phase.collectAsStateWithLifecycle()
    val capStatus by c.engine.captureStatus.collectAsStateWithLifecycle()
    val persistErr by c.engine.persistenceError.collectAsStateWithLifecycle()
    val lastEnded by c.engine.lastEndedSessionId.collectAsStateWithLifecycle()
    val metrics by c.metrics.snapshot.collectAsStateWithLifecycle()
    val interval by c.settings.intervalMs.collectAsStateWithLifecycle(SettingsStore.DEFAULT_INTERVAL_MS)

    fun overlayGranted() = Settings.canDrawOverlays(ctx)
    fun notifGranted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var overlayOk by remember { mutableStateOf(overlayGranted()) }
    var notifOk by remember { mutableStateOf(notifGranted()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { overlayOk = overlayGranted(); notifOk = notifGranted() }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val elapsed by produceState<Elapsed?>(null, phase) { while (true) { value = c.engine.elapsed(); delay(200) } }
    var showPrivacy by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    fun startCaptureFlow() {
        if (!overlayOk) { actions.requestOverlayPermission(); return }
        if (!notifOk) { actions.requestNotificationPermission(); return }
        showPrivacy = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Nova coleta", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Voltar") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("1. Captura de tela + janela flutuante", fontWeight = FontWeight.Bold)
                Text("O app só captura a tela depois que você autorizar no diálogo do Android (escolha \"Tela inteira\"). " +
                    "São lidas apenas as regiões de velocidade, marcha e pedais. As imagens são processadas na hora, no aparelho, " +
                    "e descartadas — nada é enviado para fora nem guardado como imagem.", style = MaterialTheme.typography.bodySmall)
                KeyValue("Permissão de sobreposição", if (overlayOk) "concedida" else "pendente")
                KeyValue("Notificações", if (notifOk) "concedida" else "pendente")
                KeyValue("Captura", when (capStatus) {
                    CaptureStatus.ACTIVE -> "ATIVA"; CaptureStatus.NOT_REQUESTED -> "não iniciada"
                    CaptureStatus.DENIED -> "NEGADA"; CaptureStatus.REVOKED -> "REVOGADA"; CaptureStatus.FAILED -> "FALHOU"
                })
                Button(onClick = { startCaptureFlow() }, modifier = Modifier.fillMaxWidth()) { Text("Iniciar captura + janela flutuante") }
                OutlinedButton(onClick = {
                    if (!overlayOk) actions.requestOverlayPermission()
                    else if (!notifOk) actions.requestNotificationPermission()
                    else actions.startManualService()
                }, modifier = Modifier.fillMaxWidth()) { Text("Só janela flutuante (modo manual, sem captura)") }
                OutlinedButton(onClick = actions.stopService, modifier = Modifier.fillMaxWidth()) { Text("Parar captura / janela") }
                Text("Na 1ª vez, calibre as regiões pela janela flutuante (botão CALIBRAR) com o jogo aberto. " +
                    "Sem calibração, nenhuma leitura é gravada — só marco zero e passagens manuais.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("2. Controle da sessão (alternativa à janela flutuante)", fontWeight = FontWeight.Bold)
                KeyValue("Estado", when (phase) { Phase.IDLE -> "parado"; Phase.RUNNING -> "COLETANDO"; Phase.PAUSED -> "PAUSADO" })
                KeyValue("Tempo desde o marco zero", elapsed?.let { Formatters.duration(it.relativeNanos) } ?: "—")
                KeyValue("Tempo de coleta ativa", elapsed?.let { Formatters.duration(it.activeNanos) } ?: "—")
                if (phase == Phase.IDLE) {
                    Button(onClick = { c.engine.start() }, modifier = Modifier.fillMaxWidth()) { Text("INICIAR COLETA / MARCO ZERO") }
                } else {
                    Button(onClick = { c.engine.markLap() }, enabled = phase == Phase.RUNNING, modifier = Modifier.fillMaxWidth()) { Text("REGISTRAR PASSAGEM") }
                    OutlinedButton(onClick = { if (phase == Phase.RUNNING) c.engine.pause() else c.engine.resume() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (phase == Phase.RUNNING) "Pausar" else "Continuar")
                    }
                    OutlinedButton(onClick = { confirmEnd = true }, modifier = Modifier.fillMaxWidth()) { Text("Encerrar sessão") }
                }
                lastEnded?.let { id -> TextButton(onClick = { onOpenReport(id) }) { Text("Abrir relatório da última sessão") } }
                persistErr?.let { Text("⚠ Falha ao gravar no banco: $it", color = MaterialTheme.colorScheme.error) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("3. Frequência de análise", fontWeight = FontWeight.Bold)
                Text("Comece conservador. Aumente só depois de conferir precisão e temperatura no seu aparelho.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsStore.OPTIONS.forEach { ms ->
                        FilterChip(selected = interval == ms, onClick = { scope.launch { c.settings.setIntervalMs(ms) } }, label = { Text("$ms ms") })
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Diagnóstico do pipeline", fontWeight = FontWeight.Bold)
                fun d(v: Double?, f: String) = v?.let { String.format(java.util.Locale.ROOT, f, it) } ?: "n/d"
                KeyValue("Frames adquiridos", metrics.framesAcquired.toString())
                KeyValue("Taxa efetiva", d(metrics.effectiveHz, "%.2f Hz"))
                KeyValue("Ciclos sem frame novo", metrics.noFrame.toString())
                KeyValue("Pulados (sem calibração)", metrics.skippedUncalibrated.toString())
                KeyValue("Amostras produzidas", metrics.samplesProduced.toString())
                KeyValue("Velocidade reconhecida", d(metrics.speedRecognitionPct, "%.1f %%"))
                KeyValue("OCR médio", d(metrics.ocrAvgMs, "%.1f ms"))
                KeyValue("Processamento médio/amostra", d(metrics.processingAvgMs, "%.1f ms"))
                KeyValue("Erros", metrics.errors.toString())
                metrics.lastError?.let { Text("Último erro: $it", style = MaterialTheme.typography.bodySmall) }
                Text("Não mede FPS do jogo nem temperatura: avalie isso você mesmo numa sessão longa.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showPrivacy) {
        ConfirmDialog(
            "Autorizar captura de tela?",
            "O Android vai pedir permissão para gravar a tela. Escolha \"Tela inteira\" (se escolher \"um app\", o GT Rivals não será capturado). " +
                "O Treino GT lê só números e indicadores nas regiões configuradas, processa localmente e não armazena imagens. " +
                "Você pode parar a qualquer momento pela notificação ou pelo botão \"Parar\".",
            "Continuar", { showPrivacy = false; actions.launchProjection() }, { showPrivacy = false })
    }
    if (confirmEnd) {
        ConfirmDialog("Encerrar sessão?", "Os dados já estão gravados no aparelho. Encerrar fecha a sessão e libera o relatório.",
            "Encerrar", { confirmEnd = false; scope.launch { c.engine.end() } }, { confirmEnd = false })
    }
}
