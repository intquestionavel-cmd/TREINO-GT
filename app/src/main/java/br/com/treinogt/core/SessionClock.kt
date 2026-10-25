package br.com.treinogt.core

enum class ClockState { IDLE, RUNNING, PAUSED, ENDED }

/** Intervalo de pausa em tempo monotônico absoluto. endNanos == null => pausa ainda aberta. */
data class PauseSpan(val startNanos: Long, val endNanos: Long?, val reason: String)

object PauseMath {
    /** Soma da sobreposição entre as pausas e a janela [from, to]. Pausa aberta é tratada como terminando em `to`. */
    fun overlap(spans: List<PauseSpan>, from: Long, to: Long): Long {
        if (to <= from) return 0L
        var sum = 0L
        for (p in spans) {
            val s = maxOf(p.startNanos, from)
            val e = minOf(p.endNanos ?: to, to)
            if (e > s) sum += e - s
        }
        return sum
    }
}

/**
 * Relógio próprio da sessão (marco zero). Usa apenas [TimeSource] monotônico.
 * - tempo de sessão  = relativeNanos (inclui pausas)
 * - tempo ativo      = activeNanos   (exclui pausas)
 * - tempo de pausa   = pausedNanos
 */
class SessionClock(private val time: TimeSource) {
    var state: ClockState = ClockState.IDLE
        private set
    var zeroNanos: Long = 0L
        private set
    private var endNanos: Long? = null
    private val pauseList = ArrayList<PauseSpan>()

    @Synchronized
    fun start(): Long {
        check(state == ClockState.IDLE) { "Relógio já iniciado (estado=$state)" }
        zeroNanos = time.nanos()
        state = ClockState.RUNNING
        return zeroNanos
    }

    @Synchronized
    fun pause(reason: String): Long {
        check(state == ClockState.RUNNING) { "Só é possível pausar em RUNNING (estado=$state)" }
        val now = time.nanos()
        pauseList.add(PauseSpan(now, null, reason))
        state = ClockState.PAUSED
        return now
    }

    @Synchronized
    fun resume(): Long {
        check(state == ClockState.PAUSED) { "Só é possível continuar em PAUSED (estado=$state)" }
        val now = time.nanos()
        val last = pauseList.last()
        pauseList[pauseList.lastIndex] = last.copy(endNanos = now)
        state = ClockState.RUNNING
        return now
    }

    /** Encerra. Se estiver pausado, a pausa é fechada no instante do encerramento. */
    @Synchronized
    fun end(): Long {
        check(state == ClockState.RUNNING || state == ClockState.PAUSED) { "Nada a encerrar (estado=$state)" }
        val now = time.nanos()
        if (state == ClockState.PAUSED) {
            val last = pauseList.last()
            pauseList[pauseList.lastIndex] = last.copy(endNanos = now)
        }
        endNanos = now
        state = ClockState.ENDED
        return now
    }

    /** "Agora" efetivo: congelado após o encerramento. */
    @Synchronized
    fun now(): Long = endNanos ?: time.nanos()

    @Synchronized
    fun relativeNanos(at: Long = now()): Long = at - zeroNanos

    @Synchronized
    fun pausedNanos(upTo: Long = now()): Long = PauseMath.overlap(pauseList, zeroNanos, upTo)

    @Synchronized
    fun activeNanos(at: Long = now()): Long = (at - zeroNanos) - PauseMath.overlap(pauseList, zeroNanos, at)

    /** true se o instante [ts] cai dentro da coleta ativa (após o marco zero, fora de pausas, antes do fim). */
    @Synchronized
    fun isCollectingAt(ts: Long): Boolean {
        if (state == ClockState.IDLE || ts < zeroNanos) return false
        val end = endNanos
        if (end != null && ts > end) return false
        for (p in pauseList) {
            val pe = p.endNanos
            if (ts >= p.startNanos && (pe == null || ts < pe)) return false
        }
        return true
    }

    @Synchronized
    fun pauses(): List<PauseSpan> = pauseList.toList()
}
