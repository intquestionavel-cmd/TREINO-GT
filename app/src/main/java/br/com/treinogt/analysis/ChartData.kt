package br.com.treinogt.analysis

import br.com.treinogt.data.LapMarkerEntity
import br.com.treinogt.data.PauseEntity
import br.com.treinogt.data.SessionEntity
import br.com.treinogt.data.TelemetrySampleEntity
import br.com.treinogt.recognition.PedalState
import br.com.treinogt.recognition.Validity

/** Séries prontas para desenhar. -1 = sem leitura válida (a linha é interrompida, nunca interpolada). */
class ChartData(
    val totalSec: Float,
    val tSec: FloatArray,
    val speed: IntArray,
    val gear: IntArray,
    /** 1 = acionado, 0 = solto, -1 = desconhecido */
    val throttle: ByteArray,
    val brake: ByteArray,
    val gapsSec: List<Pair<Float, Float>>,
    val lapsSec: List<Pair<Float, Int>>,
    val pausesSec: List<Pair<Float, Float>>,
    val maxSpeed: Int,
)

object ChartDataBuilder {
    fun build(
        session: SessionEntity, samples: List<TelemetrySampleEntity>, laps: List<LapMarkerEntity>,
        pauses: List<PauseEntity>, gaps: List<GapSpan>,
    ): ChartData {
        val s = samples.sortedBy { it.monoNanos }
        fun sec(n: Long) = n / 1e9f
        fun ped(state: String): Byte = when (state) {
            PedalState.PRESSED.name -> 1; PedalState.RELEASED.name -> 0; else -> -1
        }
        val speed = IntArray(s.size) { i ->
            val e = s[i]; if (e.speedValidity == Validity.VALID.name) e.speedKmh ?: -1 else -1
        }
        val gear = IntArray(s.size) { i ->
            val e = s[i]; if (e.gearValidity == Validity.VALID.name) e.gear?.toIntOrNull() ?: -1 else -1
        }
        val endAbs = session.zeroMonoNanos + session.totalNanos
        return ChartData(
            totalSec = sec(session.totalNanos).coerceAtLeast(1f),
            tSec = FloatArray(s.size) { sec(s[it].relNanos) },
            speed = speed, gear = gear,
            throttle = ByteArray(s.size) { ped(s[it].throttleState) },
            brake = ByteArray(s.size) { ped(s[it].brakeState) },
            gapsSec = gaps.map { sec(it.startRelNanos) to sec(it.endRelNanos) },
            lapsSec = laps.sortedBy { it.sequence }.map { sec(it.relNanos) to it.sequence },
            pausesSec = pauses.map { sec(it.startMonoNanos - session.zeroMonoNanos) to sec((it.endMonoNanos ?: endAbs) - session.zeroMonoNanos) },
            maxSpeed = (speed.maxOrNull() ?: 0).coerceAtLeast(50),
        )
    }
}
