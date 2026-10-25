package br.com.treinogt.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, TelemetrySampleEntity::class, LapMarkerEntity::class,
        PauseEntity::class, TrackProfileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TelemetryDao
}
