package br.com.treinogt.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class MetricsSnapshot(
    val ticks: Long = 0,
    val framesAcquired: Long = 0,
    val noFrame: Long = 0,
    val skippedUncalibrated: Long = 0,
    val samplesProduced: Long = 0,
    val speedValid: Long = 0,
    val ocrCalls: Long = 0,
    val ocrTotalMs: Long = 0,
    val frameProcessingTotalMs: Long = 0,
    val errors: Long = 0,
    val lastError: String? = null,
    val firstTickNanos: Long = 0,
    val lastTickNanos: Long = 0,
) {
    val ocrAvgMs: Double? get() = if (ocrCalls == 0L) null else ocrTotalMs.toDouble() / ocrCalls
    val processingAvgMs: Double? get() = if (samplesProduced == 0L) null else frameProcessingTotalMs.toDouble() / samplesProduced
    val effectiveHz: Double? get() =
        if (framesAcquired < 2 || lastTickNanos <= firstTickNanos) null
        else (framesAcquired - 1) / ((lastTickNanos - firstTickNanos) / 1e9)
    val speedRecognitionPct: Double? get() = if (samplesProduced == 0L) null else 100.0 * speedValid / samplesProduced

    fun toJson(): String = JSONObject()
        .put("framesAcquired", framesAcquired).put("noFrame", noFrame)
        .put("skippedUncalibrated", skippedUncalibrated).put("samplesProduced", samplesProduced)
        .put("speedValid", speedValid).put("ocrCalls", ocrCalls)
        .put("ocrAvgMs", ocrAvgMs ?: JSONObject.NULL).put("processingAvgMs", processingAvgMs ?: JSONObject.NULL)
        .put("effectiveHz", effectiveHz ?: JSONObject.NULL).put("errors", errors)
        .put("lastError", lastError ?: JSONObject.NULL).toString()
}

/** Métricas internas do pipeline (CPU de OCR, taxa efetiva, erros). Não mede FPS do jogo nem temperatura. */
class PipelineMetrics {
    private val _state = MutableStateFlow(MetricsSnapshot())
    val snapshot: StateFlow<MetricsSnapshot> = _state.asStateFlow()

    @Synchronized private fun upd(f: (MetricsSnapshot) -> MetricsSnapshot) { _state.value = f(_state.value) }

    fun reset() = upd { MetricsSnapshot() }
    fun frame(nowNanos: Long) = upd {
        it.copy(framesAcquired = it.framesAcquired + 1,
            firstTickNanos = if (it.framesAcquired == 0L) nowNanos else it.firstTickNanos, lastTickNanos = nowNanos)
    }
    fun noFrame() = upd { it.copy(noFrame = it.noFrame + 1) }
    fun uncalibrated() = upd { it.copy(skippedUncalibrated = it.skippedUncalibrated + 1) }
    fun ocr(ms: Long) = upd { it.copy(ocrCalls = it.ocrCalls + 1, ocrTotalMs = it.ocrTotalMs + ms) }
    fun sample(speedValid: Boolean, processingMs: Long) = upd {
        it.copy(samplesProduced = it.samplesProduced + 1, speedValid = it.speedValid + if (speedValid) 1 else 0,
            frameProcessingTotalMs = it.frameProcessingTotalMs + processingMs)
    }
    fun error(msg: String) = upd { it.copy(errors = it.errors + 1, lastError = msg) }
}
