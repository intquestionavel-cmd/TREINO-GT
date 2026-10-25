package br.com.treinogt.capture

import android.graphics.Bitmap
import br.com.treinogt.core.TimeSource
import br.com.treinogt.engine.Phase
import br.com.treinogt.engine.SessionEngine
import br.com.treinogt.recognition.GearParser
import br.com.treinogt.recognition.GearReading
import br.com.treinogt.recognition.LumaGrid
import br.com.treinogt.recognition.PedalMath
import br.com.treinogt.recognition.PedalReading
import br.com.treinogt.recognition.PedalTracker
import br.com.treinogt.recognition.ProfileConfig
import br.com.treinogt.recognition.RoiConfig
import br.com.treinogt.recognition.RoiKind
import br.com.treinogt.recognition.SampleInput
import br.com.treinogt.recognition.SpeedParser
import br.com.treinogt.recognition.SpeedPlausibilityValidator
import br.com.treinogt.recognition.SpeedReading
import br.com.treinogt.recognition.Validity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Resultado de uma leitura única, para a tela de calibração. */
data class Readout(
    val frameWidth: Int, val frameHeight: Int,
    val speed: SpeedReading, val gear: GearReading,
    val throttleRatio: Float?, val brakeRatio: Float?, val ocrMs: Long,
)

/**
 * Pipeline: captura periódica (frequência ajustável) -> OCR/pixel-diff SOMENTE nas ROIs -> amostra -> engine.
 * Roda em Dispatchers.Default (nunca na thread principal). Processa no máximo 1 frame por vez: se o OCR
 * demorar mais que o intervalo, o próximo ciclo começa imediatamente e frames intermediários são descartados.
 * Só processa enquanto a sessão estiver RUNNING.
 */
class RecognitionPipeline(
    private val frames: FrameSource,
    private val ocr: OcrEngine,
    private val engine: SessionEngine,
    private val time: TimeSource,
    val metrics: PipelineMetrics,
) {
    @Volatile var profile: ProfileConfig = ProfileConfig()

    private val speedValidator = SpeedPlausibilityValidator()
    private val throttleTracker = PedalTracker()
    private val brakeTracker = PedalTracker()

    suspend fun run(intervalMs: () -> Long) {
        while (currentCoroutineContext().isActive) {
            val t0 = time.nanos()
            if (engine.phase.value == Phase.RUNNING) {
                try { tick() } catch (e: Exception) { metrics.error(e.message ?: e.javaClass.simpleName) }
            }
            val spentMs = (time.nanos() - t0) / 1_000_000L
            delay((intervalMs() - spentMs).coerceAtLeast(1L))
        }
    }

    private suspend fun tick() {
        val frame = frames.acquire()
        if (frame == null) { metrics.noFrame(); return }
        val ts = time.nanos()  // instante da aquisição (a imagem pode ter ~1 frame de latência)
        metrics.frame(ts)
        val cfg = profile
        if (!cfg.roi.calibrated || !cfg.roi.isCompatible(frame.width, frame.height)) { metrics.uncalibrated(); return }
        val sample = analyze(frame, ts, cfg)
        metrics.sample(sample.speed.isValid, (time.nanos() - ts) / 1_000_000L)
        engine.submitSample(sample)
    }

    private suspend fun analyze(frame: Frame, ts: Long, cfg: ProfileConfig): SampleInput {
        val p = cfg.params
        val speedOut = readText(frame, cfg.roi, RoiKind.SPEED)
        val speed = if (speedOut == null) SpeedReading(null, null, null, Validity.ERROR)
        else speedValidator.validate(
            SpeedParser.parse(speedOut.text, speedOut.confidence, p.maxSpeedKmh, p.minConfidence), ts)
        val gearOut = readText(frame, cfg.roi, RoiKind.GEAR)
        val gear = if (gearOut == null) GearReading(null, null, null, Validity.ERROR)
        else GearParser.parse(gearOut.text, gearOut.confidence, p.allowedGears, p.minConfidence)
        return SampleInput(ts, speed, gear,
            pedal(frame, cfg.roi, RoiKind.THROTTLE, p.throttleBaseline, throttleTracker),
            pedal(frame, cfg.roi, RoiKind.BRAKE, p.brakeBaseline, brakeTracker))
    }

    private suspend fun readText(frame: Frame, roi: RoiConfig, kind: RoiKind): OcrResult? {
        val r = roi.roi(kind)?.toPixelRect(frame.width, frame.height) ?: return null
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(frame.bitmap, r.left, r.top, r.width, r.height)
            val k = if (crop.height >= 64) 1 else minOf(4, (64 + crop.height - 1) / crop.height)
            scaled = if (k > 1) Bitmap.createScaledBitmap(crop, crop.width * k, crop.height * k, true) else crop
            val t0 = time.nanos()
            val out = ocr.read(scaled)
            metrics.ocr((time.nanos() - t0) / 1_000_000L)
            out
        } catch (e: Exception) {
            metrics.error("OCR ${kind.name}: ${e.message}")
            null
        } finally {
            if (scaled != null && scaled !== crop && scaled !== frame.bitmap) scaled.recycle()
            if (crop != null && crop !== frame.bitmap) crop.recycle()
        }
    }

    private fun pedal(frame: Frame, roi: RoiConfig, kind: RoiKind, baseline: LumaGrid?, tracker: PedalTracker): PedalReading {
        val r = roi.roi(kind)?.toPixelRect(frame.width, frame.height) ?: return PedalReading.UNKNOWN
        if (baseline == null) return tracker.update(null)
        return tracker.update(PedalMath.changedRatio(LumaGridBuilder.build(frame.bitmap, r), baseline))
    }

    // ---------------- Calibração ----------------

    /** Lê o frame atual com as ROIs fornecidas, sem gravar nada. Usado para validar a calibração. */
    suspend fun readOnce(roi: RoiConfig): Readout? {
        val frame = frames.acquire() ?: return null
        val p = profile.params
        val t0 = time.nanos()
        val s = readText(frame, roi, RoiKind.SPEED)
        val g = readText(frame, roi, RoiKind.GEAR)
        val ocrMs = (time.nanos() - t0) / 1_000_000L
        fun ratio(kind: RoiKind, base: LumaGrid?): Float? {
            val r = roi.roi(kind)?.toPixelRect(frame.width, frame.height) ?: return null
            return PedalMath.changedRatio(LumaGridBuilder.build(frame.bitmap, r), base)
        }
        return Readout(frame.width, frame.height,
            SpeedParser.parse(s?.text, s?.confidence, p.maxSpeedKmh, p.minConfidence),
            GearParser.parse(g?.text, g?.confidence, p.allowedGears, p.minConfidence),
            ratio(RoiKind.THROTTLE, p.throttleBaseline), ratio(RoiKind.BRAKE, p.brakeBaseline), ocrMs)
    }

    /** Captura a base "pedais soltos" (acelerador, freio) a partir do frame atual. */
    fun captureBaselines(roi: RoiConfig): Pair<LumaGrid, LumaGrid>? {
        val frame = frames.acquire() ?: return null
        val t = roi.roi(RoiKind.THROTTLE)?.toPixelRect(frame.width, frame.height) ?: return null
        val b = roi.roi(RoiKind.BRAKE)?.toPixelRect(frame.width, frame.height) ?: return null
        return LumaGridBuilder.build(frame.bitmap, t) to LumaGridBuilder.build(frame.bitmap, b)
    }

    fun resetTrackers() {
        speedValidator.reset(); throttleTracker.reset(); brakeTracker.reset()
    }
}
