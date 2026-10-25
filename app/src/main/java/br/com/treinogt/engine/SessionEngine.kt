package br.com.treinogt.engine

import br.com.treinogt.core.PauseMath
import br.com.treinogt.core.PauseSpan
import br.com.treinogt.core.SessionClock
import br.com.treinogt.core.TimeSource
import br.com.treinogt.data.LapMarkerEntity
import br.com.treinogt.data.PauseEntity
import br.com.treinogt.data.SessionEntity
import br.com.treinogt.data.SessionState
import br.com.treinogt.data.SessionStore
import br.com.treinogt.data.TelemetrySampleEntity
import br.com.treinogt.recognition.SampleInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { IDLE, RUNNING, PAUSED }
enum class CaptureStatus { NOT_REQUESTED, ACTIVE, DENIED, REVOKED, FAILED }

object PauseReason {
    const val USER = "USER"
    const val SERVICE_STOPPED = "SERVICE_STOPPED"
}

data class LapResult(val sequence: Int, val relNanos: Long, val isReference: Boolean)
data class Elapsed(val relativeNanos: Long, val activeNanos: Long)

/**
 * Orquestra uma sessão: marco zero, passagens, pausas, amostras e encerramento.
 *
 * Garantias de projeto:
 *  - O timestamp do marco zero e das passagens é lido de forma SÍNCRONA no instante da chamada,
 *    antes de qualquer acesso a banco (latência de IO não contamina o tempo).
 *  - A persistência roda num dispatcher SERIAL (ordem preservada) e nunca na thread principal.
 *  - Amostras são gravadas em lote; cada lote atualiza um checkpoint usado na recuperação após falha.
 */
class SessionEngine(
    private val store: SessionStore,
    private val time: TimeSource,
    ioDispatcher: CoroutineDispatcher,
    private val appVersion: String,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val flushSize: Int = 40,
    private val flushIntervalNanos: Long = 2_000_000_000L,
) {
    private val _persistenceError = MutableStateFlow<String?>(null)
    /** Última falha de gravação (null = nenhuma). A UI deve exibir isto: falha silenciosa = dado perdido. */
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + ioDispatcher +
            CoroutineExceptionHandler { _, e -> _persistenceError.value = e.message ?: e.javaClass.simpleName }
    )
    private val lock = Any()

    private var clock = SessionClock(time)
    private var idDeferred: CompletableDeferred<Long>? = null
    private var lapSeq = 0
    private var openPauseStart: Long? = null
    private val buffer = ArrayList<TelemetrySampleEntity>()
    private var lastFlushNanos = 0L
    @Volatile private var perfProvider: () -> String? = { null }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _captureStatus = MutableStateFlow(CaptureStatus.NOT_REQUESTED)
    val captureStatus: StateFlow<CaptureStatus> = _captureStatus.asStateFlow()
    private val _sessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _sessionId.asStateFlow()
    private val _lastEndedId = MutableStateFlow<Long?>(null)
    val lastEndedSessionId: StateFlow<Long?> = _lastEndedId.asStateFlow()

    fun setPerfProvider(p: () -> String?) { perfProvider = p }
    fun setCaptureStatus(s: CaptureStatus) { _captureStatus.value = s }

    /** MARCO ZERO. Retorna false se já houver sessão em andamento. */
    fun start(name: String? = null, captureConfigJson: String = "{}"): Boolean = synchronized(lock) {
        if (_phase.value != Phase.IDLE) return@synchronized false
        val c = SessionClock(time)
        val zero = c.start()                       // <- timestamp do toque
        clock = c
        val wall = wallClockMs()
        val entity = SessionEntity(
            name = name?.takeIf { it.isNotBlank() } ?: defaultName(wall),
            startedAtEpochMs = wall,
            zeroMonoNanos = zero,
            state = SessionState.RUNNING,
            appVersion = appVersion,
            captureConfigJson = captureConfigJson,
        )
        val d = CompletableDeferred<Long>()
        idDeferred = d
        lapSeq = 0; openPauseStart = null; buffer.clear(); lastFlushNanos = zero
        _sessionId.value = null
        _phase.value = Phase.RUNNING
        scope.launch {
            try {
                val id = store.createSession(entity)
                d.complete(id)
                _sessionId.value = id
            } catch (e: Throwable) {
                d.completeExceptionally(e)
                throw e
            }
        }
        true
    }

    private data class LapTicket(val d: CompletableDeferred<Long>, val seq: Int, val rel: Long, val isRef: Boolean)

    /** Registra passagem MANUAL com o timestamp exato da chamada. A 1ª passagem vira referência por padrão. */
    fun markLap(): LapResult? {
        val ts = time.nanos()                      // <- timestamp exato do toque
        val t = synchronized(lock) {
            if (_phase.value != Phase.RUNNING) return@synchronized null
            val seq = ++lapSeq
            LapTicket(idDeferred!!, seq, ts - clock.zeroNanos, seq == 1)
        } ?: return null
        scope.launch {
            store.insertLap(
                LapMarkerEntity(
                    sessionId = t.d.await(), monoNanos = ts, relNanos = t.rel,
                    sequence = t.seq, method = "MANUAL", isReference = t.isRef,
                )
            )
        }
        return LapResult(t.seq, t.rel, t.isRef)
    }

    fun pause(reason: String = PauseReason.USER): Boolean = synchronized(lock) {
        if (_phase.value != Phase.RUNNING) return@synchronized false
        val ts = clock.pause(reason)
        openPauseStart = ts
        flushLocked()
        _phase.value = Phase.PAUSED
        val d = idDeferred!!
        scope.launch {
            store.insertPause(PauseEntity(sessionId = d.await(), startMonoNanos = ts, endMonoNanos = null, reason = reason))
        }
        true
    }

    /** O período pausado NÃO conta como pilotagem ativa. */
    fun resume(): Boolean = synchronized(lock) {
        if (_phase.value != Phase.PAUSED) return@synchronized false
        val start = openPauseStart!!
        val ts = clock.resume()
        openPauseStart = null
        _phase.value = Phase.RUNNING
        val d = idDeferred!!
        scope.launch { store.endPause(d.await(), start, ts) }
        true
    }

    private data class EndTicket(
        val d: CompletableDeferred<Long>, val endTs: Long, val total: Long, val active: Long,
        val wallEnd: Long, val pauseStart: Long?,
    )

    /** Encerra e finaliza a sessão no banco. Suspende até a gravação terminar. */
    suspend fun end(): Boolean {
        val t = synchronized(lock) {
            if (_phase.value == Phase.IDLE) return@synchronized null
            val wasPaused = _phase.value == Phase.PAUSED
            val pStart = openPauseStart
            val endTs = clock.end()
            flushLocked()
            openPauseStart = null
            _phase.value = Phase.IDLE
            EndTicket(idDeferred!!, endTs, clock.relativeNanos(endTs), clock.activeNanos(endTs),
                wallClockMs(), if (wasPaused) pStart else null)
        } ?: return false
        scope.launch {
            val id = t.d.await()
            if (t.pauseStart != null) store.endPause(id, t.pauseStart, t.endTs)
            store.finalizeSession(id, SessionState.ENDED, t.wallEnd, t.total, t.active, perfProvider())
        }.join()
        _lastEndedId.value = runCatching { t.d.await() }.getOrNull()
        _sessionId.value = null
        return true
    }

    /** Chamado pelo pipeline. Amostras fora da coleta ativa (pausa, antes do zero, depois do fim) são descartadas. */
    fun submitSample(i: SampleInput) {
        synchronized(lock) {
            if (_phase.value != Phase.RUNNING) return
            if (!clock.isCollectingAt(i.monoNanos)) return
            buffer += i.toEntity(i.monoNanos - clock.zeroNanos)
            if (buffer.size >= flushSize || time.nanos() - lastFlushNanos >= flushIntervalNanos) flushLocked()
        }
    }

    private fun flushLocked() {
        if (buffer.isEmpty()) return
        val batch = ArrayList(buffer)
        buffer.clear()
        lastFlushNanos = time.nanos()
        val d = idDeferred!!
        val lastRel = batch.last().relNanos
        scope.launch {
            val id = d.await()
            store.insertSamples(batch.map { it.copy(sessionId = id) })   // um INSERT em lote = uma transação
            store.checkpoint(id, lastRel)
        }
    }

    /** Captura negada/revogada/falha: registra o motivo e pausa a coleta (a marcação manual segue disponível ao retomar). */
    fun onCaptureLost(status: CaptureStatus) {
        require(status == CaptureStatus.DENIED || status == CaptureStatus.REVOKED || status == CaptureStatus.FAILED)
        _captureStatus.value = status
        pause("CAPTURE_" + status.name)
    }

    fun elapsed(): Elapsed? = synchronized(lock) {
        if (_phase.value == Phase.IDLE) null else Elapsed(clock.relativeNanos(), clock.activeNanos())
    }

    fun bufferedSamples(): Int = synchronized(lock) { buffer.size }

    /**
     * Chamar na inicialização do processo. Sessões RUNNING/PAUSED que sobraram no banco pertencem a um
     * processo que morreu: são marcadas INTERRUPTED, com totais calculados até o último dado persistido.
     */
    suspend fun recoverInterrupted(): Int {
        if (_phase.value != Phase.IDLE) return 0
        val stale = store.sessionsInStates(listOf(SessionState.RUNNING, SessionState.PAUSED))
        for (s in stale) {
            val total = maxOf(s.lastCheckpointRelNanos, store.lastSampleRel(s.id) ?: 0L, store.lastLapRel(s.id) ?: 0L)
            val spans = store.pauses(s.id).map { PauseSpan(it.startMonoNanos, it.endMonoNanos, it.reason) }
            val paused = PauseMath.overlap(spans, s.zeroMonoNanos, s.zeroMonoNanos + total)
            // Hora de término aproximada: o relógio de parede do último dado não foi gravado.
            store.finalizeSession(s.id, SessionState.INTERRUPTED, s.startedAtEpochMs + total / 1_000_000L,
                total, total - paused, s.perfJson)
        }
        return stale.size
    }

    private fun defaultName(wall: Long): String =
        "Treino " + SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(wall))
}

private fun SampleInput.toEntity(rel: Long) = TelemetrySampleEntity(
    sessionId = 0, monoNanos = monoNanos, relNanos = rel,
    speedKmh = speed.value, speedRaw = speed.raw, speedConfidence = speed.confidence, speedValidity = speed.validity.name,
    gear = gear.value, gearRaw = gear.raw, gearConfidence = gear.confidence, gearValidity = gear.validity.name,
    throttleState = throttle.state.name, throttleChangedRatio = throttle.changedRatio,
    throttleConfidence = throttle.confidence, throttleIntensityEst = throttle.intensityEstimate,
    brakeState = brake.state.name, brakeChangedRatio = brake.changedRatio,
    brakeConfidence = brake.confidence, brakeIntensityEst = brake.intensityEstimate,
    source = source,
)
