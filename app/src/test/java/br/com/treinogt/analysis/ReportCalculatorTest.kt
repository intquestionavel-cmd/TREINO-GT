package br.com.treinogt.analysis

import br.com.treinogt.data.*
import org.junit.Assert.*
import org.junit.Test

class ReportCalculatorTest {
    private val zero = 1_000_000_000L
    private fun ns(s: Double) = (s * 1e9).toLong()
    private fun session(total: Double, active: Double = total) = SessionEntity(id = 1, name = "t", startedAtEpochMs = 0,
        zeroMonoNanos = zero, totalNanos = ns(total), activeNanos = ns(active), state = "ENDED", appVersion = "t")
    private fun smp(rel: Double, kmh: Int?, validity: String = "VALID", gear: String? = null, gv: String = "EMPTY") =
        TelemetrySampleEntity(sessionId = 1, monoNanos = zero + ns(rel), relNanos = ns(rel), speedKmh = kmh, speedRaw = kmh?.toString(),
            speedConfidence = 0.9f, speedValidity = validity, gear = gear, gearRaw = gear, gearConfidence = null, gearValidity = gv,
            throttleState = "UNKNOWN", throttleChangedRatio = null, throttleConfidence = null, throttleIntensityEst = null,
            brakeState = "UNKNOWN", brakeChangedRatio = null, brakeConfidence = null, brakeIntensityEst = null, source = "TEST")

    @Test fun emptySessionReportsUnavailableNotFakeNumbers() {
        val m = ReportCalculator.compute(session(60.0), emptyList(), emptyList(), emptyList())
        assertNull(m.maxSpeedKmh); assertNull(m.avgSpeedKmh); assertNull(m.speedRecognitionPct)
        assertTrue(m.gaps.isEmpty()); assertFalse(m.hasPedalData); assertNull(m.lapSummary.best)
    }

    @Test fun maxUsesOnlyValidReadings() {
        val m = ReportCalculator.compute(session(5.0),
            listOf(smp(0.0, 100), smp(1.0, 999, "OUT_OF_RANGE"), smp(2.0, 140)), emptyList(), emptyList())
        assertEquals(140, m.maxSpeedKmh)
        assertEquals(2, m.validSpeedSamples); assertEquals(3, m.totalSamples)
        assertEquals(100.0 * 2 / 3, m.speedRecognitionPct!!, 1e-9)
    }

    @Test fun averageIsTimeWeightedZeroOrderHold() {
        val m = ReportCalculator.compute(session(2.0),
            listOf(smp(0.0, 100), smp(1.0, 200), smp(2.0, 200)), emptyList(), emptyList())
        assertEquals(150.0, m.avgSpeedKmh!!, 1e-9)
    }

    @Test fun gapsAreDetectedAndPausesAreSubtracted() {
        val samples = listOf(smp(0.0, 100), smp(0.5, 100), smp(1.0, 100), smp(5.0, 100), smp(5.5, 100))
        val m = ReportCalculator.compute(session(6.0), samples, emptyList(), emptyList())
        assertEquals(1, m.gaps.size)
        assertEquals(1.0, m.gaps[0].startRelNanos / 1e9, 1e-9); assertEquals(5.0, m.gaps[0].endRelNanos / 1e9, 1e-9)
        val pause = PauseEntity(sessionId = 1, startMonoNanos = zero + ns(1.5), endMonoNanos = zero + ns(4.5), reason = "USER")
        val m2 = ReportCalculator.compute(session(6.0, 3.0), samples, emptyList(), listOf(pause))
        assertTrue(m2.gaps.isEmpty())                        // 4 s - 3 s de pausa = 1 s < limiar
        assertEquals(3.0, m2.pausedNanos / 1e9, 1e-9)
    }

    @Test fun gearRecognitionPercentage() {
        val m = ReportCalculator.compute(session(3.0),
            listOf(smp(0.0, 10, gear = "2", gv = "VALID"), smp(1.0, 10), smp(2.0, 10, gear = "3", gv = "VALID"), smp(2.5, 10)),
            emptyList(), emptyList())
        assertEquals(50.0, m.gearRecognitionPct!!, 1e-9); assertTrue(m.hasGearData)
    }

    @Test fun lapSummaryUsesReferenceAndBestValidInterval() {
        val laps = listOf(
            LapMarkerEntity(sessionId = 1, monoNanos = zero + ns(5.0), relNanos = ns(5.0), sequence = 1, method = "MANUAL", isReference = true),
            LapMarkerEntity(sessionId = 1, monoNanos = zero + ns(70.0), relNanos = ns(70.0), sequence = 2, method = "MANUAL"),
            LapMarkerEntity(sessionId = 1, monoNanos = zero + ns(127.0), relNanos = ns(127.0), sequence = 3, method = "MANUAL"))
        val m = ReportCalculator.compute(session(130.0), emptyList(), laps, emptyList())
        assertEquals(3, m.lapMarkerCount); assertEquals(57.0, m.lapSummary.best!!.durationNanos / 1e9, 1e-9)
    }
}
