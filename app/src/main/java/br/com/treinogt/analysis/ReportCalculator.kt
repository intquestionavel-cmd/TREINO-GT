package br.com.treinogt.analysis

import br.com.treinogt.core.LapCalculator
import br.com.treinogt.core.LapPoint
import br.com.treinogt.core.LapSummary
import br.com.treinogt.core.PauseMath
import br.com.treinogt.core.PauseSpan
import br.com.treinogt.data.LapMarkerEntity
import br.com.treinogt.data.PauseEntity
import br.com.treinogt.data.SessionEntity
import br.com.treinogt.data.TelemetrySampleEntity
import br.com.treinogt.recognition.PedalState
import br.com.treinogt.recognition.Validity

data class GapSpan(val startRelNanos: Long, val endRelNanos: Long)

/** Campos nulos = "Não disponível / Dados insuficientes". Nunca são preenchidos com valores fictícios. */
data class ReportModel(
    val totalNanos: Long,
    val activeNanos: Long,
    val pausedNanos: Long,
    val lapMarkerCount: Int,
    val lapSummary: LapSummary,
    val maxSpeedKmh: Int?,
    /** Média ponderada no tempo (retenção de ordem zero) — ver [ReportCalculator.compute]. */
    val avgSpeedKmh: Double?,
    val totalSamples: Int,
    val validSpeedSamples: Int,
    val speedRecognitionPct: Double?,
    val gearRecognitionPct: Double?,
    val gaps: List<GapSpan>,
    val hasPedalData: Boolean,
    val hasGearData: Boolean,
    val notes: List<String>,
)

object ReportCalculator {
    const val DEFAULT_GAP_THRESHOLD_NANOS = 1_500_000_000L

    /**
     * Critérios documentados:
     *  - Velocidade máxima: maior valor entre leituras VÁLIDAS (validity == VALID).
     *  - Velocidade média: média ponderada pelo tempo. Entre duas leituras válidas consecutivas a e b,
     *    o valor de `a` vale durante (b - a), descontando pausas; pares separados por mais que o limite de
     *    lacuna são ignorados (não se interpola através de lacunas). Sem peso total => null.
     *  - % reconhecimento: leituras válidas / amostras produzidas pelo pipeline (cada amostra = 1 tentativa).
     *  - Lacuna: intervalo sem amostras maior que o limite, descontadas as pausas, incluindo início e fim.
     */
    fun compute(
        session: SessionEntity,
        samples: List<TelemetrySampleEntity>,
        laps: List<LapMarkerEntity>,
        pauses: List<PauseEntity>,
        gapThresholdNanos: Long = DEFAULT_GAP_THRESHOLD_NANOS,
    ): ReportModel {
        val zero = session.zeroMonoNanos
        val endAbs = zero + session.totalNanos
        val spans = pauses.map { PauseSpan(it.startMonoNanos, it.endMonoNanos ?: endAbs, it.reason) }
        val sorted = samples.sortedBy { it.monoNanos }

        val lapSummary = LapCalculator.compute(
            laps.map { LapPoint(it.sequence, it.monoNanos, it.isReference) }, spans)

        val validSpeed = sorted.filter { it.speedValidity == Validity.VALID.name && it.speedKmh != null }
        val validGear = sorted.count { it.gearValidity == Validity.VALID.name && it.gear != null }

        // ---- média ponderada
        var wSum = 0.0; var vSum = 0.0
        for (i in 1 until validSpeed.size) {
            val a = validSpeed[i - 1]; val b = validSpeed[i]
            val dt = (b.monoNanos - a.monoNanos) - PauseMath.overlap(spans, a.monoNanos, b.monoNanos)
            if (dt in 1..gapThresholdNanos) { wSum += dt; vSum += dt * a.speedKmh!! }
        }

        // ---- lacunas
        val gaps = ArrayList<GapSpan>()
        if (sorted.isNotEmpty()) {
            fun check(fromAbs: Long, toAbs: Long) {
                val eff = (toAbs - fromAbs) - PauseMath.overlap(spans, fromAbs, toAbs)
                if (eff > gapThresholdNanos) gaps += GapSpan(fromAbs - zero, toAbs - zero)
            }
            check(zero, sorted.first().monoNanos)
            for (i in 1 until sorted.size) check(sorted[i - 1].monoNanos, sorted[i].monoNanos)
            check(sorted.last().monoNanos, endAbs)
        }

        val hasPedals = sorted.any { it.throttleState != PedalState.UNKNOWN.name || it.brakeState != PedalState.UNKNOWN.name }
        val notes = ArrayList<String>()
        notes += "Passagens são marcações MANUAIS; o tempo entre elas inclui o erro de reação do toque."
        if (sorted.isEmpty()) notes += "Nenhuma amostra de telemetria nesta sessão: captura não usada ou sem leituras."
        if (hasPedals) notes += "Pedais: estados binários estimados por diferença de pixels contra a base 'pedal solto'. " +
            "Heurística não validada; sem escala percentual."
        notes += "Comparações entre trechos usam tempo relativo, não posição na pista (sem referência espacial confiável)."

        return ReportModel(
            totalNanos = session.totalNanos,
            activeNanos = session.activeNanos,
            pausedNanos = (session.totalNanos - session.activeNanos).coerceAtLeast(0L),
            lapMarkerCount = laps.size,
            lapSummary = lapSummary,
            maxSpeedKmh = validSpeed.maxOfOrNull { it.speedKmh!! },
            avgSpeedKmh = if (wSum > 0.0) vSum / wSum else null,
            totalSamples = sorted.size,
            validSpeedSamples = validSpeed.size,
            speedRecognitionPct = if (sorted.isEmpty()) null else 100.0 * validSpeed.size / sorted.size,
            gearRecognitionPct = if (sorted.isEmpty()) null else 100.0 * validGear / sorted.size,
            gaps = gaps,
            hasPedalData = hasPedals,
            hasGearData = validGear > 0,
            notes = notes,
        )
    }
}
