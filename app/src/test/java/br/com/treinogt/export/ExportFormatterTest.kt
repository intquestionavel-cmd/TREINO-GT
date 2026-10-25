package br.com.treinogt.export

import br.com.treinogt.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExportFormatterTest {
    private val s = SessionEntity(id = 7, name = "Sessão, \"A\"", startedAtEpochMs = 1000, zeroMonoNanos = 10, totalNanos = 90_000_000_000L,
        activeNanos = 80_000_000_000L, state = "ENDED", appVersion = "0.1.0", notes = "linha1\nlinha2")
    private val sample = TelemetrySampleEntity(id = 1, sessionId = 7, monoNanos = 1_500_000_010L, relNanos = 1_500_000_000L,
        speedKmh = 123, speedRaw = "123", speedConfidence = 0.91f, speedValidity = "VALID", gear = null, gearRaw = "x", gearConfidence = null,
        gearValidity = "NOT_ALLOWED", throttleState = "UNKNOWN", throttleChangedRatio = null, throttleConfidence = null, throttleIntensityEst = null,
        brakeState = "PRESSED", brakeChangedRatio = 0.4f, brakeConfidence = 1f, brakeIntensityEst = null, source = "T")
    private val laps = listOf(
        LapMarkerEntity(sessionId = 7, monoNanos = 20_000_000_010L, relNanos = 20_000_000_000L, sequence = 1, method = "MANUAL", isReference = true),
        LapMarkerEntity(sessionId = 7, monoNanos = 80_000_000_010L, relNanos = 80_000_000_000L, sequence = 2, method = "MANUAL", notes = "a,b"))
    private val data = SessionData(s, listOf(sample), laps, emptyList())

    @Test fun samplesCsvHasUnitsInHeaderAndPreservesRawAndNulls() {
        val lines = ExportFormatter.samplesCsv(data).trim().split("\n")
        assertTrue(lines[0].contains("rel_time_s") && lines[0].contains("speed_kmh") && lines[0].contains("mono_time_ns"))
        val cols = lines[1].split(",")
        assertEquals(ExportFormatter.SAMPLES_HEADER.size, cols.size)
        assertEquals("1.500000", cols[3]); assertEquals("123", cols[4]); assertEquals("0.9100", cols[6])
        assertEquals("", cols[8])                                  // marcha ausente = vazio, não 0
        assertEquals("NOT_ALLOWED", cols[11])
    }

    @Test fun lapsCsvEscapesAndComputesIntervals() {
        val out = ExportFormatter.lapsCsv(data).trim().split("\n")
        assertEquals(3, out.size)
        assertTrue(out[2].contains("60.000000") && out[2].contains("VALID") && out[2].contains("\"a,b\""))
        assertTrue(out[1].contains("true"))                         // referência
    }

    @Test fun jsonIsValidWithUnitsMetadataAndNulls() {
        val j = JSONObject(ExportFormatter.sessionJson(data, 123L))
        assertEquals("treino-gt-session", j.getString("format"))
        assertTrue(j.getJSONObject("units").getString("mono_time_ns").contains("elapsedRealtimeNanos"))
        val ses = j.getJSONObject("session")
        assertEquals("Sessão, \"A\"", ses.getString("name")); assertEquals("linha1\nlinha2", ses.getString("notes"))
        assertEquals(10_000_000_000L, ses.getLong("pausedNanos"))
        val smp = j.getJSONArray("samples").getJSONObject(0)
        assertTrue(smp.getJSONObject("gear").isNull("value"))
        assertEquals(123, smp.getJSONObject("speed").getInt("kmh"))
        assertEquals(2, j.getJSONArray("lapMarkers").length())
    }
}
