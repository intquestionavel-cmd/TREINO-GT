package br.com.treinogt.data

import br.com.treinogt.recognition.ProfileCodec
import br.com.treinogt.recognition.ProfileConfig
import kotlinx.coroutines.flow.Flow

data class SessionData(
    val session: SessionEntity,
    val samples: List<TelemetrySampleEntity>,
    val laps: List<LapMarkerEntity>,
    val pauses: List<PauseEntity>,
)

class SessionRepository(private val dao: TelemetryDao) : SessionStore {

    // ---- SessionStore
    override suspend fun createSession(s: SessionEntity): Long = dao.insertSession(s)
    override suspend fun checkpoint(id: Long, relNanos: Long) = dao.checkpoint(id, relNanos)
    override suspend fun finalizeSession(id: Long, state: String, endedAtEpochMs: Long, totalNanos: Long, activeNanos: Long, perfJson: String?) =
        dao.finalizeSession(id, state, endedAtEpochMs, totalNanos, activeNanos, perfJson)
    override suspend fun insertSamples(list: List<TelemetrySampleEntity>) = dao.insertSamples(list)
    override suspend fun insertLap(l: LapMarkerEntity) { dao.insertLap(l) }
    override suspend fun insertPause(p: PauseEntity) { dao.insertPause(p) }
    override suspend fun endPause(sessionId: Long, startMonoNanos: Long, endMonoNanos: Long) =
        dao.endPause(sessionId, startMonoNanos, endMonoNanos)
    override suspend fun sessionsInStates(states: List<String>) = dao.sessionsInStates(states)
    override suspend fun lastSampleRel(sessionId: Long) = dao.lastSampleRel(sessionId)
    override suspend fun lastLapRel(sessionId: Long) = dao.lastLapRel(sessionId)
    override suspend fun pauses(sessionId: Long) = dao.pauses(sessionId)

    // ---- Consulta / edição (UI)
    fun observeSessions(): Flow<List<SessionEntity>> = dao.observeSessions()

    suspend fun load(id: Long): SessionData? {
        val s = dao.session(id) ?: return null
        return SessionData(s, dao.samples(id), dao.laps(id), dao.pauses(id))
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim().ifEmpty { "Sem nome" })
    suspend fun setNotes(id: Long, notes: String) = dao.setNotes(id, notes)
    suspend fun delete(id: Long) = dao.deleteSession(id)

    /** Define qual passagem é a referência (as anteriores deixam de contar como volta). */
    suspend fun setReferenceLap(sessionId: Long, sequence: Int) {
        dao.clearReference(sessionId)
        dao.setReference(sessionId, sequence)
    }

    // ---- Perfil de pista / ROIs
    suspend fun loadProfile(): ProfileConfig {
        val e = dao.firstProfile()
        if (e == null) {
            val p = ProfileConfig(name = "Padrão")
            return p.copy(id = dao.upsertProfile(toEntity(p)))
        }
        return ProfileConfig(
            id = e.id, name = e.name,
            roi = ProfileCodec.roiFromJson(e.roiJson),
            params = ProfileCodec.paramsFromJson(e.paramsJson),
            version = e.configVersion,
        )
    }

    suspend fun saveProfile(p: ProfileConfig): ProfileConfig = p.copy(id = dao.upsertProfile(toEntity(p)))

    private fun toEntity(p: ProfileConfig) = TrackProfileEntity(
        id = p.id, name = p.name,
        roiJson = ProfileCodec.roiToJson(p.roi),
        refWidth = p.roi.refWidth, refHeight = p.roi.refHeight,
        paramsJson = ProfileCodec.paramsToJson(p.params),
        configVersion = p.version,
    )
}
