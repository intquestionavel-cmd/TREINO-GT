package br.com.treinogt.export

import br.com.treinogt.core.LapCalculator
import br.com.treinogt.core.LapPoint
import br.com.treinogt.core.PauseSpan
import br.com.treinogt.data.SessionData
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Formatação pura (sem Android) de CSV/JSON — testável em JVM. */
object ExportFormatter {
    private fun csv(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun sec(n: Long) = String.format(Locale.ROOT, "%.6f", n / 1e9)
    private fun f(v: Float?) = v?.let { String.format(Locale.ROOT, "%.4f", it) } ?: ""

    val SAMPLES_HEADER = listOf(
        "sample_id", "session_id", "mono_time_ns", "rel_time_s",
        "speed_kmh", "speed_raw", "speed_confidence_0_1", "speed_validity",
        "gear", "gear_raw", "gear_confidence_0_1", "gear_validity",
        "throttle_state", "throttle_changed_pixel_ratio_0_1", "throttle_confidence_heuristic_0_1", "throttle_intensity_estimate",
        "brake_state", "brake_changed_pixel_ratio_0_1", "brake_confidence_heuristic_0_1", "brake_intensity_estimate",
        "source",
    )

    fun samplesCsv(d: SessionData): String {
        val sb = StringBuilder(SAMPLES_HEADER.joinToString(",")).append('\n')
        for (e in d.samples) {
            sb.append(listOf(
                e.id, e.sessionId, e.monoNanos, sec(e.relNanos),
                e.speedKmh, e.speedRaw, f(e.speedConfidence), e.speedValidity,
                e.gear, e.gearRaw, f(e.gearConfidence), e.gearValidity,
                e.throttleState, f(e.throttleChangedRatio), f(e.throttleConfidence), f(e.throttleIntensityEst),
                e.brakeState, f(e.brakeChangedRatio), f(e.brakeConfidence), f(e.brakeIntensityEst),
                e.source,
            ).joinToString(",") { csv(it) }).append('\n')
        }
        return sb.toString()
    }

    fun lapsCsv(d: SessionData): String {
        val spans = d.pauses.map { PauseSpan(it.startMonoNanos, it.endMonoNanos ?: (d.session.zeroMonoNanos + d.session.totalNanos), it.reason) }
        val summary = LapCalculator.compute(d.laps.map { LapPoint(it.sequence, it.monoNanos, it.isReference) }, spans)
        val byEnd = summary.intervals.associateBy { it.toSequence }
        val sb = StringBuilder("sequence,mono_time_ns,rel_time_s,method,is_reference,confidence_0_1,interval_from_previous_s,interval_validity,notes\n")
        for (l in d.laps.sortedBy { it.sequence }) {
            val iv = byEnd[l.sequence]
            sb.append(listOf(
                l.sequence, l.monoNanos, sec(l.relNanos), l.method, l.isReference, f(l.confidence),
                iv?.let { sec(it.durationNanos) } ?: "", iv?.validity?.name ?: "", l.notes,
            ).joinToString(",") { csv(it) }).append('\n')
        }
        return sb.toString()
    }

    fun sessionJson(d: SessionData, exportedAtEpochMs: Long): String {
        val s = d.session
        val root = JSONObject()
        root.put("format", "treino-gt-session").put("formatVersion", 1).put("exportedAtEpochMs", exportedAtEpochMs)
        root.put("units", JSONObject()
            .put("mono_time_ns", "nanoseconds, SystemClock.elapsedRealtimeNanos() (monotonic since boot, includes deep sleep)")
            .put("rel_time_s", "seconds since marco zero (includes pauses)")
            .put("speed_kmh", "km/h").put("ratios_and_confidence", "0..1")
            .put("durations_ns", "nanoseconds"))
        root.put("session", JSONObject()
            .put("id", s.id).put("name", s.name).put("state", s.state)
            .put("startedAtEpochMs", s.startedAtEpochMs).put("endedAtEpochMs", s.endedAtEpochMs ?: JSONObject.NULL)
            .put("zeroMonoNanos", s.zeroMonoNanos).put("totalNanos", s.totalNanos).put("activeNanos", s.activeNanos)
            .put("pausedNanos", (s.totalNanos - s.activeNanos).coerceAtLeast(0L))
            .put("appVersion", s.appVersion).put("notes", s.notes)
            .put("captureConfig", runCatching { JSONObject(s.captureConfigJson) }.getOrElse { JSONObject() })
            .put("performance", s.perfJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL))
        root.put("pauses", JSONArray().also { a ->
            d.pauses.forEach { a.put(JSONObject().put("startMonoNanos", it.startMonoNanos)
                .put("endMonoNanos", it.endMonoNanos ?: JSONObject.NULL).put("reason", it.reason)) }
        })
        root.put("lapMarkers", JSONArray().also { a ->
            d.laps.sortedBy { it.sequence }.forEach { a.put(JSONObject()
                .put("sequence", it.sequence).put("monoNanos", it.monoNanos).put("relNanos", it.relNanos)
                .put("method", it.method).put("isReference", it.isReference)
                .put("confidence", it.confidence?.toDouble() ?: JSONObject.NULL).put("notes", it.notes)) }
        })
        root.put("samples", JSONArray().also { a ->
            d.samples.forEach { e ->
                a.put(JSONObject()
                    .put("monoNanos", e.monoNanos).put("relNanos", e.relNanos)
                    .put("speed", JSONObject().put("kmh", e.speedKmh ?: JSONObject.NULL).put("raw", e.speedRaw ?: JSONObject.NULL)
                        .put("confidence", e.speedConfidence?.toDouble() ?: JSONObject.NULL).put("validity", e.speedValidity))
                    .put("gear", JSONObject().put("value", e.gear ?: JSONObject.NULL).put("raw", e.gearRaw ?: JSONObject.NULL)
                        .put("confidence", e.gearConfidence?.toDouble() ?: JSONObject.NULL).put("validity", e.gearValidity))
                    .put("throttle", JSONObject().put("state", e.throttleState)
                        .put("changedPixelRatio", e.throttleChangedRatio?.toDouble() ?: JSONObject.NULL)
                        .put("confidenceHeuristic", e.throttleConfidence?.toDouble() ?: JSONObject.NULL)
                        .put("intensityEstimate", e.throttleIntensityEst?.toDouble() ?: JSONObject.NULL))
                    .put("brake", JSONObject().put("state", e.brakeState)
                        .put("changedPixelRatio", e.brakeChangedRatio?.toDouble() ?: JSONObject.NULL)
                        .put("confidenceHeuristic", e.brakeConfidence?.toDouble() ?: JSONObject.NULL)
                        .put("intensityEstimate", e.brakeIntensityEst?.toDouble() ?: JSONObject.NULL))
                    .put("source", e.source))
            }
        })
        return root.toString(2)
    }
}
