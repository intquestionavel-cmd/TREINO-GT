package br.com.treinogt.core

data class LapPoint(val sequence: Int, val monoNanos: Long, val isReference: Boolean)

enum class IntervalValidity { VALID, PAUSE_OVERLAP, TOO_SHORT }

data class LapInterval(
    val number: Int,
    val fromSequence: Int,
    val toSequence: Int,
    val startNanos: Long,
    val endNanos: Long,
    val durationNanos: Long,
    val validity: IntervalValidity,
) {
    val isValid: Boolean get() = validity == IntervalValidity.VALID
}

data class LapSummary(
    val referenceSequence: Int?,
    val intervals: List<LapInterval>,
    val best: LapInterval?,
    /** Marcações anteriores à referência: ignoradas (trecho inicial incompleto). */
    val ignoredBeforeReference: Int,
)

/**
 * Calcula intervalos entre passagens consecutivas a partir da PRIMEIRA marcação marcada como referência.
 * Marcações anteriores à referência não geram intervalo (evita contar trecho inicial incompleto como volta).
 * Intervalo é inválido se cruza uma pausa, ou se for menor que [minValidNanos] (toque duplo/acidental).
 */
object LapCalculator {
    const val DEFAULT_MIN_VALID_NANOS = 5_000_000_000L

    fun compute(
        points: List<LapPoint>,
        pauses: List<PauseSpan>,
        minValidNanos: Long = DEFAULT_MIN_VALID_NANOS,
    ): LapSummary {
        val sorted = points.sortedBy { it.monoNanos }
        val refIdx = sorted.indexOfFirst { it.isReference }
        if (refIdx < 0) return LapSummary(null, emptyList(), null, sorted.size)

        val usable = sorted.subList(refIdx, sorted.size)
        val intervals = ArrayList<LapInterval>()
        for (i in 1 until usable.size) {
            val a = usable[i - 1]
            val b = usable[i]
            val dur = b.monoNanos - a.monoNanos
            val paused = PauseMath.overlap(pauses, a.monoNanos, b.monoNanos)
            val validity = when {
                paused > 0L -> IntervalValidity.PAUSE_OVERLAP
                dur < minValidNanos -> IntervalValidity.TOO_SHORT
                else -> IntervalValidity.VALID
            }
            intervals += LapInterval(i, a.sequence, b.sequence, a.monoNanos, b.monoNanos, dur, validity)
        }
        val best = intervals.filter { it.isValid }.minByOrNull { it.durationNanos }
        return LapSummary(usable.first().sequence, intervals, best, refIdx)
    }
}
