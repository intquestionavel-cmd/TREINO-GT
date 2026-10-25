package br.com.treinogt.core

import org.junit.Assert.*
import org.junit.Test

class SessionClockTest {
    private val t = FakeTime()
    private val clock = SessionClock(t)

    @Test fun relativeTimeStartsAtZeroAtMarcoZero() {
        t.advanceSec(50.0)                      // tempo antes do marco zero não conta
        val zero = clock.start()
        assertEquals(0L, clock.relativeNanos())
        t.advanceSec(2.5)
        assertEquals(2_500_000_000L, clock.relativeNanos())
        assertEquals(zero, clock.zeroNanos)
    }

    @Test fun pauseIsExcludedFromActiveTimeButKeptInSessionTime() {
        clock.start(); t.advanceSec(10.0)
        clock.pause("USER"); t.advanceSec(30.0)
        clock.resume(); t.advanceSec(5.0)
        assertEquals(45_000_000_000L, clock.relativeNanos())
        assertEquals(15_000_000_000L, clock.activeNanos())
        assertEquals(30_000_000_000L, clock.pausedNanos())
    }

    @Test fun activeTimeFreezesDuringOpenPause() {
        clock.start(); t.advanceSec(4.0); clock.pause("USER")
        t.advanceSec(100.0)
        assertEquals(4_000_000_000L, clock.activeNanos())
    }

    @Test fun endFreezesClockAndClosesOpenPause() {
        clock.start(); t.advanceSec(3.0); clock.pause("USER"); t.advanceSec(2.0)
        clock.end(); t.advanceSec(999.0)
        assertEquals(ClockState.ENDED, clock.state)
        assertEquals(5_000_000_000L, clock.relativeNanos())
        assertEquals(3_000_000_000L, clock.activeNanos())
        assertNotNull(clock.pauses().single().endNanos)
    }

    @Test fun isCollectingAtRespectsZeroPausesAndEnd() {
        val zero = clock.start(); t.advanceSec(1.0)
        val p0 = clock.pause("USER"); t.advanceSec(1.0)
        val p1 = clock.resume(); t.advanceSec(1.0)
        assertFalse(clock.isCollectingAt(zero - 1))
        assertTrue(clock.isCollectingAt(zero))
        assertFalse(clock.isCollectingAt(p0 + 1))
        assertTrue(clock.isCollectingAt(p1))
    }

    @Test fun invalidTransitionsThrow() {
        assertThrows(IllegalStateException::class.java) { clock.pause("x") }
        clock.start()
        assertThrows(IllegalStateException::class.java) { clock.start() }
        assertThrows(IllegalStateException::class.java) { clock.resume() }
    }

    @Test fun usesOnlyMonotonicSourceSoWallClockChangesAreIrrelevant() {
        // O relógio só consulta TimeSource: nada depende de System.currentTimeMillis().
        clock.start(); t.advanceSec(1.0)
        assertEquals(1_000_000_000L, clock.relativeNanos())
    }
}
