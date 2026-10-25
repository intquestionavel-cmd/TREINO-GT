package br.com.treinogt.engine

import br.com.treinogt.core.FakeTime
import br.com.treinogt.data.SessionEntity
import br.com.treinogt.data.SessionState
import br.com.treinogt.recognition.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEngineTest {
    private val time = FakeTime()
    private val store = FakeStore()

    private fun TestScope.engine(flushSize: Int = 40) =
        SessionEngine(store, time, StandardTestDispatcher(testScheduler), "test", { 1_700_000_000_000L }, flushSize)

    private fun sample(mono: Long, kmh: Int = 100) = SampleInput(
        mono, SpeedReading(kmh, "$kmh", 0.9f, Validity.VALID), GearReading("3", "3", 0.9f, Validity.VALID),
        PedalReading.UNKNOWN, PedalReading.UNKNOWN)

    @Test fun marcoZeroLapsAndEndArePersistedWithExactTimestamps() = runTest {
        val e = engine()
        assertTrue(e.start("T"))
        val zero = time.now
        time.advanceSec(30.0); val l1 = e.markLap()!!
        time.advanceSec(60.0); val l2 = e.markLap()!!
        time.advanceSec(5.0)
        assertTrue(e.end())
        assertEquals(30_000_000_000L, l1.relNanos); assertTrue(l1.isReference)
        assertEquals(90_000_000_000L, l2.relNanos); assertFalse(l2.isReference)
        val s = store.sessions.values.single()
        assertEquals(SessionState.ENDED, s.state); assertEquals(zero, s.zeroMonoNanos)
        assertEquals(95_000_000_000L, s.totalNanos); assertEquals(95_000_000_000L, s.activeNanos)
        assertEquals(listOf(1, 2), store.laps.map { it.sequence })
        assertEquals(listOf("MANUAL", "MANUAL"), store.laps.map { it.method })
        assertEquals(Phase.IDLE, e.phase.value)
    }

    @Test fun lapTimestampIsTakenAtTapNotAtPersistenceTime() = runTest {
        val e = engine()
        e.start(); time.advanceSec(10.0)
        e.markLap()
        time.advanceSec(7.0)           // persistência só roda depois (dispatcher padrão adiado)
        advanceUntilIdle()
        assertEquals(10_000_000_000L, store.laps.single().relNanos)
    }

    @Test fun pausedTimeIsNotActiveTimeAndPauseIsRecorded() = runTest {
        val e = engine()
        e.start(); time.advanceSec(10.0)
        assertTrue(e.pause()); time.advanceSec(20.0)
        assertNull(e.markLap())        // não registra passagem pausado
        assertTrue(e.resume()); time.advanceSec(5.0)
        e.end()
        val s = store.sessions.values.single()
        assertEquals(35_000_000_000L, s.totalNanos); assertEquals(15_000_000_000L, s.activeNanos)
        val p = store.pauseList.single()
        assertEquals("USER", p.reason); assertEquals(20_000_000_000L, p.endMonoNanos!! - p.startMonoNanos)
    }

    @Test fun endWhilePausedClosesPause() = runTest {
        val e = engine(); e.start(); time.advanceSec(2.0); e.pause(); time.advanceSec(3.0); e.end()
        assertNotNull(store.pauseList.single().endMonoNanos)
        assertEquals(2_000_000_000L, store.sessions.values.single().activeNanos)
    }

    @Test fun samplesOutsideActiveCollectionAreDropped() = runTest {
        val e = engine(flushSize = 1)
        e.start(); val zero = time.now
        e.submitSample(sample(zero - 1))                     // antes do marco zero
        time.advanceSec(1.0); e.submitSample(sample(time.now)) // ok
        e.pause(); time.advanceSec(1.0)
        e.submitSample(sample(time.now))                     // pausado
        e.resume(); time.advanceSec(1.0); e.submitSample(sample(time.now)) // ok
        e.end()
        assertEquals(2, store.samples.size)
        assertTrue(store.samples.all { it.sessionId == 1L && it.relNanos >= 0 })
    }

    @Test fun batchesAreFlushedAndCheckpointed() = runTest {
        val e = engine(flushSize = 3)
        e.start()
        repeat(7) { time.advanceSec(0.3); e.submitSample(sample(time.now)) }
        advanceUntilIdle()
        assertEquals(6, store.samples.size)                  // 2 lotes de 3; 1 ainda em buffer
        assertEquals(1, e.bufferedSamples())
        assertTrue(store.sessions[1]!!.lastCheckpointRelNanos > 0)
        e.end(); assertEquals(7, store.samples.size)
    }

    @Test fun captureRevokedPausesWithReasonAndDeniedWhileIdleDoesNothingElse() = runTest {
        val e = engine()
        e.onCaptureLost(CaptureStatus.DENIED)
        assertEquals(Phase.IDLE, e.phase.value); assertEquals(CaptureStatus.DENIED, e.captureStatus.value)
        e.start(); time.advanceSec(5.0)
        e.onCaptureLost(CaptureStatus.REVOKED)
        assertEquals(Phase.PAUSED, e.phase.value)
        advanceUntilIdle()
        assertEquals("CAPTURE_REVOKED", store.pauseList.single().reason)
    }

    @Test fun cannotStartTwiceOrLapWhenIdle() = runTest {
        val e = engine()
        assertNull(e.markLap())
        assertTrue(e.start()); assertFalse(e.start())
        assertFalse(e.resume())
    }

    @Test fun persistenceFailureIsSurfacedNotSilent() = runTest {
        val e = engine(flushSize = 1)
        store.failSamples = true
        e.start(); time.advanceSec(1.0); e.submitSample(sample(time.now))
        advanceUntilIdle()
        assertEquals("disco cheio", e.persistenceError.value)
    }

    @Test fun recoveryMarksOrphanSessionsInterruptedWithTotalsFromLastPersistedData() = runTest {
        val zero = 5_000_000_000L
        val id = store.createSession(SessionEntity(name = "orf", startedAtEpochMs = 1_000_000, zeroMonoNanos = zero,
            state = SessionState.RUNNING, appVersion = "t", lastCheckpointRelNanos = 40_000_000_000L))
        store.insertPause(br.com.treinogt.data.PauseEntity(sessionId = id, startMonoNanos = zero + 10_000_000_000L,
            endMonoNanos = zero + 15_000_000_000L, reason = "USER"))
        assertEquals(1, engine().recoverInterrupted())
        val s = store.sessions[id]!!
        assertEquals(SessionState.INTERRUPTED, s.state)
        assertEquals(40_000_000_000L, s.totalNanos); assertEquals(35_000_000_000L, s.activeNanos)
        assertEquals(0, engine().recoverInterrupted())       // idempotente
    }
}
