package br.com.treinogt.core

import org.junit.Assert.*
import org.junit.Test

class LapCalculatorTest {
    private fun p(seq: Int, sec: Double, ref: Boolean = false) = LapPoint(seq, (sec * 1e9).toLong(), ref)
    private fun pause(a: Double, b: Double) = PauseSpan((a * 1e9).toLong(), (b * 1e9).toLong(), "USER")

    @Test fun intervalsStartFromReferenceAndIgnoreInitialPartialStretch() {
        val r = LapCalculator.compute(listOf(p(1, 3.0), p(2, 40.0, true), p(3, 100.0), p(4, 158.0)), emptyList())
        assertEquals(2, r.referenceSequence); assertEquals(1, r.ignoredBeforeReference)
        assertEquals(listOf(60.0, 58.0), r.intervals.map { it.durationNanos / 1e9 })
        assertEquals(58.0, r.best!!.durationNanos / 1e9, 1e-9)
    }

    @Test fun noReferenceMeansNoIntervals() {
        val r = LapCalculator.compute(listOf(p(1, 1.0), p(2, 70.0)), emptyList())
        assertNull(r.referenceSequence); assertTrue(r.intervals.isEmpty()); assertNull(r.best)
    }

    @Test fun intervalCrossingPauseIsInvalidAndNotBest() {
        val r = LapCalculator.compute(listOf(p(1, 0.0, true), p(2, 60.0), p(3, 100.0)), listOf(pause(70.0, 80.0)))
        assertEquals(IntervalValidity.VALID, r.intervals[0].validity)
        assertEquals(IntervalValidity.PAUSE_OVERLAP, r.intervals[1].validity)
        assertEquals(1, r.best!!.number)
    }

    @Test fun veryShortIntervalIsInvalid() {
        val r = LapCalculator.compute(listOf(p(1, 0.0, true), p(2, 1.2), p(3, 61.0)), emptyList())
        assertEquals(IntervalValidity.TOO_SHORT, r.intervals[0].validity)
        assertEquals(2, r.best!!.number)
    }

    @Test fun unsortedInputIsHandled() {
        val r = LapCalculator.compute(listOf(p(2, 70.0), p(1, 0.0, true)), emptyList())
        assertEquals(70.0, r.intervals.single().durationNanos / 1e9, 1e-9)
    }
}
