package br.com.treinogt.recognition

object SpeedParser {
    private val unitRegex = Regex("(?i)km\\s*/?\\s*h")
    // Confusões típicas de OCR em dígitos. Só aplicadas dentro de um texto que de resto é numérico.
    private val confusions = mapOf('O' to '0', 'o' to '0', 'l' to '1', 'I' to '1', '|' to '1')

    fun parse(raw: String?, confidence: Float?, maxKmh: Int = 450, minConfidence: Float = 0.5f): SpeedReading {
        if (raw == null || raw.isBlank()) return SpeedReading(null, raw, confidence, Validity.EMPTY)
        val cleaned = raw.replace(unitRegex, "")
            .filter { !it.isWhitespace() }
            .map { confusions[it] ?: it }
            .joinToString("")
        if (cleaned.isEmpty() || cleaned.any { it !in '0'..'9' }) {
            return SpeedReading(null, raw, confidence, Validity.NON_NUMERIC)
        }
        val v = cleaned.toIntOrNull() ?: return SpeedReading(null, raw, confidence, Validity.NON_NUMERIC)
        val validity = when {
            v > maxKmh -> Validity.OUT_OF_RANGE
            confidence != null && confidence < minConfidence -> Validity.LOW_CONFIDENCE
            else -> Validity.VALID
        }
        return SpeedReading(v, raw, confidence, validity)
    }
}

object GearParser {
    val DIGIT_GEARS: Set<String> = (1..9).map { it.toString() }.toSet()

    /**
     * `allowed` define os estados REALMENTE exibidos pelo jogo. Padrão: apenas dígitos 1..9.
     * "N" e "R" só devem ser adicionados ao perfil depois de confirmados na interface do GT Rivals.
     */
    fun parse(
        raw: String?,
        confidence: Float?,
        allowed: Set<String> = DIGIT_GEARS,
        minConfidence: Float = 0.5f,
    ): GearReading {
        if (raw == null || raw.isBlank()) return GearReading(null, raw, confidence, Validity.EMPTY)
        var t = raw.trim().uppercase()
        if (t in setOf("I", "L", "|") && "1" in allowed) t = "1"
        if (t !in allowed) return GearReading(null, raw, confidence, Validity.NOT_ALLOWED)
        val validity = if (confidence != null && confidence < minConfidence) Validity.LOW_CONFIDENCE else Validity.VALID
        return GearReading(t, raw, confidence, validity)
    }
}

/**
 * Rejeita saltos de velocidade fisicamente implausíveis entre leituras VÁLIDAS consecutivas.
 * Não apaga nada: devolve a leitura com validity=IMPLAUSIBLE_JUMP (o valor bruto continua gravado).
 * Re-sincroniza se duas leituras rejeitadas seguidas concordam entre si (evita travar após 1ª leitura errada).
 */
class SpeedPlausibilityValidator(
    private val maxDeltaKmhPerSec: Float = 300f,
    private val toleranceKmh: Float = 10f,
    private val resyncKmh: Int = 5,
) {
    private var lastValue: Int? = null
    private var lastNanos = 0L
    private var candidate: Int? = null

    fun reset() {
        lastValue = null; candidate = null; lastNanos = 0L
    }

    fun validate(r: SpeedReading, monoNanos: Long): SpeedReading {
        val v = r.value
        if (!r.isValid || v == null) return r
        val last = lastValue
        if (last == null) {
            accept(v, monoNanos); return r
        }
        val dtSec = (monoNanos - lastNanos).coerceAtLeast(0L) / 1e9
        val allowed = maxDeltaKmhPerSec * dtSec + toleranceKmh
        if (Math.abs(v - last) <= allowed) {
            accept(v, monoNanos); return r
        }
        val c = candidate
        if (c != null && Math.abs(v - c) <= resyncKmh) {
            accept(v, monoNanos); return r
        }
        candidate = v
        return r.copy(validity = Validity.IMPLAUSIBLE_JUMP)
    }

    private fun accept(v: Int, nanos: Long) {
        lastValue = v; lastNanos = nanos; candidate = null
    }
}
