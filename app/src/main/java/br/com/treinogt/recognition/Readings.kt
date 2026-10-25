package br.com.treinogt.recognition

enum class Validity { VALID, LOW_CONFIDENCE, OUT_OF_RANGE, IMPLAUSIBLE_JUMP, NON_NUMERIC, NOT_ALLOWED, EMPTY, ERROR }

/** value pode estar preenchido mesmo quando inválido: o dado bruto não é apagado, só sinalizado. */
data class SpeedReading(val value: Int?, val raw: String?, val confidence: Float?, val validity: Validity) {
    val isValid: Boolean get() = validity == Validity.VALID && value != null
}

data class GearReading(val value: String?, val raw: String?, val confidence: Float?, val validity: Validity) {
    val isValid: Boolean get() = validity == Validity.VALID && value != null
}

enum class PedalState { RELEASED, PRESSED, UNKNOWN }

/**
 * changedRatio  = valor OBSERVADO (fração de pixels diferentes da base "pedal solto").
 * state         = classificação binária derivada (heurística, NÃO validada contra o jogo).
 * intensityEstimate = sempre null na v0.1: a interface do jogo ainda não foi validada como proporcional.
 */
data class PedalReading(
    val state: PedalState,
    val changedRatio: Float?,
    val confidence: Float?,
    val intensityEstimate: Float? = null,
) {
    companion object {
        val UNKNOWN = PedalReading(PedalState.UNKNOWN, null, null)
    }
}

const val SOURCE_SCREEN_OCR = "SCREEN_OCR_PIXELDIFF"

data class SampleInput(
    val monoNanos: Long,
    val speed: SpeedReading,
    val gear: GearReading,
    val throttle: PedalReading,
    val brake: PedalReading,
    val source: String = SOURCE_SCREEN_OCR,
)
