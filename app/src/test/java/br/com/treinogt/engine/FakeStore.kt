package br.com.treinogt.engine

import br.com.treinogt.data.*

/** Store em memória só para testes. */
class FakeStore : SessionStore {
    val sessions = LinkedHashMap<Long, SessionEntity>()
    val samples = ArrayList<TelemetrySampleEntity>()
    val laps = ArrayList<LapMarkerEntity>()
    val pauseList = ArrayList<PauseEntity>()
    var nextId = 1L
    var failSamples = false

    override suspend fun createSession(s: SessionEntity): Long { val id = nextId++; sessions[id] = s.copy(id = id); return id }
    override suspend fun checkpoint(id: Long, relNanos: Long) {
        val s = sessions[id]!!; if (relNanos > s.lastCheckpointRelNanos) sessions[id] = s.copy(lastCheckpointRelNanos = relNanos)
    }
    override suspend fun finalizeSession(id: Long, state: String, endedAtEpochMs: Long, totalNanos: Long, activeNanos: Long, perfJson: String?) {
        sessions[id] = sessions[id]!!.copy(state = state, endedAtEpochMs = endedAtEpochMs, totalNanos = totalNanos,
            activeNanos = activeNanos, lastCheckpointRelNanos = totalNanos, perfJson = perfJson)
    }
    override suspend fun insertSamples(list: List<TelemetrySampleEntity>) {
        if (failSamples) throw IllegalStateException("disco cheio")
        samples += list
    }
    override suspend fun insertLap(l: LapMarkerEntity) { laps += l }
    override suspend fun insertPause(p: PauseEntity) { pauseList += p }
    override suspend fun endPause(sessionId: Long, startMonoNanos: Long, endMonoNanos: Long) {
        val i = pauseList.indexOfFirst { it.sessionId == sessionId && it.startMonoNanos == startMonoNanos }
        if (i >= 0) pauseList[i] = pauseList[i].copy(endMonoNanos = endMonoNanos)
    }
    override suspend fun sessionsInStates(states: List<String>) = sessions.values.filter { it.state in states }
    override suspend fun lastSampleRel(sessionId: Long) = samples.filter { it.sessionId == sessionId }.maxOfOrNull { it.relNanos }
    override suspend fun lastLapRel(sessionId: Long) = laps.filter { it.sessionId == sessionId }.maxOfOrNull { it.relNanos }
    override suspend fun pauses(sessionId: Long) = pauseList.filter { it.sessionId == sessionId }
}
