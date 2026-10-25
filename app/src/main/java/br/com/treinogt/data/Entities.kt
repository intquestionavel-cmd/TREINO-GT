package br.com.treinogt.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object SessionState {
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val ENDED = "ENDED"
    /** Processo morreu com a sessão aberta; dados até o último checkpoint foram preservados. */
    const val INTERRUPTED = "INTERRUPTED"
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    /** Referência monotônica (SystemClock.elapsedRealtimeNanos) do marco zero. */
    val zeroMonoNanos: Long,
    /** Tempo de sessão (marco zero -> fim), inclui pausas. */
    val totalNanos: Long = 0,
    /** Tempo de coleta ativa (exclui pausas). Tempo de pausa = total - ativo. */
    val activeNanos: Long = 0,
    /** Último instante relativo comprovadamente persistido (usado na recuperação após falha). */
    val lastCheckpointRelNanos: Long = 0,
    val state: String,
    val appVersion: String,
    val captureConfigJson: String = "{}",
    val perfJson: String? = null,
    val notes: String = "",
    val trackProfileId: Long? = null,
)

@Entity(
    tableName = "samples",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "monoNanos"])],
)
data class TelemetrySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val monoNanos: Long,
    val relNanos: Long,
    val speedKmh: Int?,
    val speedRaw: String?,
    val speedConfidence: Float?,
    val speedValidity: String,
    val gear: String?,
    val gearRaw: String?,
    val gearConfidence: Float?,
    val gearValidity: String,
    val throttleState: String,
    /** OBSERVADO: fração de pixels alterados em relação à base "pedal solto". */
    val throttleChangedRatio: Float?,
    val throttleConfidence: Float?,
    /** ESTIMADO: null enquanto a proporcionalidade do indicador não for validada. */
    val throttleIntensityEst: Float?,
    val brakeState: String,
    val brakeChangedRatio: Float?,
    val brakeConfidence: Float?,
    val brakeIntensityEst: Float?,
    val source: String,
)

@Entity(
    tableName = "lap_markers",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class LapMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val monoNanos: Long,
    val relNanos: Long,
    val sequence: Int,
    /** MANUAL | AUTO (AUTO ainda não implementado). */
    val method: String,
    val confidence: Float? = null,
    val isReference: Boolean = false,
    val notes: String = "",
)

@Entity(
    tableName = "pauses",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class PauseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startMonoNanos: Long,
    val endMonoNanos: Long?,
    /** USER | CAPTURE_REVOKED | CAPTURE_DENIED | CAPTURE_FAILED | SERVICE_STOPPED ... */
    val reason: String,
)

@Entity(tableName = "track_profiles")
data class TrackProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?,
    val roiJson: String,
    val refWidth: Int,
    val refHeight: Int,
    val paramsJson: String,
    val configVersion: Int,
)
