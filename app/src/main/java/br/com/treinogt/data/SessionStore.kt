package br.com.treinogt.data

/** Contrato de persistência usado pelo SessionEngine (permite fake em memória nos testes JVM). */
interface SessionStore {
    suspend fun createSession(s: SessionEntity): Long
    suspend fun checkpoint(id: Long, relNanos: Long)
    suspend fun finalizeSession(id: Long, state: String, endedAtEpochMs: Long, totalNanos: Long, activeNanos: Long, perfJson: String?)
    suspend fun insertSamples(list: List<TelemetrySampleEntity>)
    suspend fun insertLap(l: LapMarkerEntity)
    suspend fun insertPause(p: PauseEntity)
    suspend fun endPause(sessionId: Long, startMonoNanos: Long, endMonoNanos: Long)
    suspend fun sessionsInStates(states: List<String>): List<SessionEntity>
    suspend fun lastSampleRel(sessionId: Long): Long?
    suspend fun lastLapRel(sessionId: Long): Long?
    suspend fun pauses(sessionId: Long): List<PauseEntity>
}
