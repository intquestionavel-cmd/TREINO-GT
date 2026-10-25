package br.com.treinogt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.treinogt.core.AndroidTimeSource
import br.com.treinogt.data.*
import br.com.treinogt.engine.SessionEngine
import br.com.treinogt.recognition.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Dados abaixo são SINTÉTICOS de teste, não representam uma corrida. */
@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        repo = SessionRepository(db.dao())
    }
    @After fun tearDown() = db.close()

    private fun sample(sid: Long, mono: Long, rel: Long) = TelemetrySampleEntity(sessionId = sid, monoNanos = mono, relNanos = rel,
        speedKmh = 50, speedRaw = "50", speedConfidence = 0.9f, speedValidity = "VALID", gear = "2", gearRaw = "2", gearConfidence = 0.9f,
        gearValidity = "VALID", throttleState = "UNKNOWN", throttleChangedRatio = null, throttleConfidence = null, throttleIntensityEst = null,
        brakeState = "UNKNOWN", brakeChangedRatio = null, brakeConfidence = null, brakeIntensityEst = null, source = "TEST_SYNTHETIC")

    @Test fun batchInsertQueryOrderAndCascadeDelete() = runBlocking {
        val id = repo.createSession(SessionEntity(name = "t", startedAtEpochMs = 1, zeroMonoNanos = 0, state = "RUNNING", appVersion = "t"))
        repo.insertSamples((1..500).map { sample(id, it * 1000L, it * 1000L) }.shuffled())
        repo.insertLap(LapMarkerEntity(sessionId = id, monoNanos = 5, relNanos = 5, sequence = 1, method = "MANUAL", isReference = true))
        val d = repo.load(id)!!
        assertEquals(500, d.samples.size)
        assertEquals(d.samples.map { it.monoNanos }.sorted(), d.samples.map { it.monoNanos })   // ordenado pelo índice (sessionId, monoNanos)
        repo.delete(id)
        assertNull(repo.load(id)); assertEquals(0, db.dao().sampleCount(id))
        assertTrue(db.dao().laps(id).isEmpty())
    }

    @Test fun newSessionDoesNotDiscardPreviousData() = runBlocking {
        val a = repo.createSession(SessionEntity(name = "a", startedAtEpochMs = 1, zeroMonoNanos = 0, state = "ENDED", appVersion = "t"))
        repo.insertSamples(listOf(sample(a, 1, 1)))
        repo.createSession(SessionEntity(name = "b", startedAtEpochMs = 2, zeroMonoNanos = 0, state = "RUNNING", appVersion = "t"))
        assertEquals(1, db.dao().sampleCount(a))
    }

    @Test fun profileRoundTripThroughDatabase() = runBlocking {
        val p = repo.loadProfile()
        assertFalse(p.roi.calibrated)
        val saved = repo.saveProfile(p.copy(roi = p.roi.copy(calibrated = true)))
        assertTrue(repo.loadProfile().roi.calibrated); assertEquals(saved.id, repo.loadProfile().id)
    }

    @Test fun engineRecoveryAfterSimulatedProcessDeathAgainstRealDatabase() = runBlocking {
        val e1 = SessionEngine(repo, AndroidTimeSource, Dispatchers.IO, "t", flushSize = 1)
        e1.start("sobrevivente")
        Thread.sleep(50)
        e1.submitSample(SampleInput(AndroidTimeSource.nanos(),
            SpeedReading(80, "80", 0.9f, Validity.VALID), GearReading("2", "2", 0.9f, Validity.VALID),
            PedalReading.UNKNOWN, PedalReading.UNKNOWN))
        Thread.sleep(300)                                   // "processo morre" aqui: sem end()
        val e2 = SessionEngine(repo, AndroidTimeSource, Dispatchers.IO, "t")
        assertEquals(1, e2.recoverInterrupted())
        val s = db.dao().sessionsInStates(listOf("INTERRUPTED")).single()
        assertTrue(s.totalNanos > 0); assertEquals(1, db.dao().sampleCount(s.id))
    }
}
