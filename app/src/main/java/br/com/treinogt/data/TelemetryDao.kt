package br.com.treinogt.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {
    // ---- sessões
    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Update suspend fun updateSession(s: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: Long): SessionEntity?
    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC") fun observeSessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE state IN (:states)") suspend fun sessionsInStates(states: List<String>): List<SessionEntity>
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("UPDATE sessions SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("UPDATE sessions SET notes = :notes WHERE id = :id") suspend fun setNotes(id: Long, notes: String)
    @Query("UPDATE sessions SET lastCheckpointRelNanos = :rel WHERE id = :id AND lastCheckpointRelNanos < :rel")
    suspend fun checkpoint(id: Long, rel: Long)
    @Query(
        "UPDATE sessions SET state = :state, endedAtEpochMs = :endedAt, totalNanos = :total, " +
            "activeNanos = :active, lastCheckpointRelNanos = :total, perfJson = :perf WHERE id = :id"
    )
    suspend fun finalizeSession(id: Long, state: String, endedAt: Long, total: Long, active: Long, perf: String?)

    // ---- amostras (inserção em lote = uma transação do Room)
    @Insert suspend fun insertSamples(list: List<TelemetrySampleEntity>)
    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY monoNanos") suspend fun samples(id: Long): List<TelemetrySampleEntity>
    @Query("SELECT MAX(relNanos) FROM samples WHERE sessionId = :id") suspend fun lastSampleRel(id: Long): Long?
    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :id") suspend fun sampleCount(id: Long): Int

    // ---- passagens
    @Insert suspend fun insertLap(l: LapMarkerEntity): Long
    @Query("SELECT * FROM lap_markers WHERE sessionId = :id ORDER BY sequence") suspend fun laps(id: Long): List<LapMarkerEntity>
    @Query("SELECT MAX(relNanos) FROM lap_markers WHERE sessionId = :id") suspend fun lastLapRel(id: Long): Long?
    @Query("UPDATE lap_markers SET isReference = 0 WHERE sessionId = :id") suspend fun clearReference(id: Long)
    @Query("UPDATE lap_markers SET isReference = 1 WHERE sessionId = :id AND sequence = :seq")
    suspend fun setReference(id: Long, seq: Int)

    // ---- pausas
    @Insert suspend fun insertPause(p: PauseEntity): Long
    @Query("UPDATE pauses SET endMonoNanos = :end WHERE sessionId = :id AND startMonoNanos = :start")
    suspend fun endPause(id: Long, start: Long, end: Long)
    @Query("SELECT * FROM pauses WHERE sessionId = :id ORDER BY startMonoNanos") suspend fun pauses(id: Long): List<PauseEntity>

    // ---- perfis de pista / ROIs
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProfile(p: TrackProfileEntity): Long
    @Query("SELECT * FROM track_profiles ORDER BY id LIMIT 1") suspend fun firstProfile(): TrackProfileEntity?
}
