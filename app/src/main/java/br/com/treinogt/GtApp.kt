package br.com.treinogt

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import br.com.treinogt.capture.PipelineMetrics
import br.com.treinogt.core.AndroidTimeSource
import br.com.treinogt.data.AppDatabase
import br.com.treinogt.data.SessionRepository
import br.com.treinogt.engine.SessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private val Context.dataStore by preferencesDataStore("treinogt_settings")

/** Preferências (DataStore). Os dados de telemetria ficam no Room; aqui só configuração leve. */
class SettingsStore(private val ctx: Context) {
    private val kInterval = longPreferencesKey("analysis_interval_ms")

    /** Frequência de análise. Padrão conservador: 300 ms (~3 Hz). Aumentar só após medir precisão/temperatura. */
    val intervalMs: Flow<Long> = ctx.dataStore.data.map { it[kInterval] ?: DEFAULT_INTERVAL_MS }
    suspend fun setIntervalMs(v: Long) { ctx.dataStore.edit { it[kInterval] = v } }

    companion object {
        const val DEFAULT_INTERVAL_MS = 300L
        val OPTIONS = listOf(200L, 300L, 500L, 1000L)
    }
}

class AppContainer(app: Application) {
    val db: AppDatabase by lazy { Room.databaseBuilder(app, AppDatabase::class.java, "treinogt.db").build() }
    val repository: SessionRepository by lazy { SessionRepository(db.dao()) }
    val settings = SettingsStore(app)
    val metrics = PipelineMetrics()
    /** Persistência em UMA thread: preserva a ordem dos eventos (ex.: inserir pausa antes de fechá-la). */
    private val ioDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "treinogt-io") }.asCoroutineDispatcher()
    val engine: SessionEngine by lazy {
        SessionEngine(repository, AndroidTimeSource, ioDispatcher, BuildConfig.VERSION_NAME)
    }
}

class GtApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Processo novo = nenhuma coleta viva: sessões abertas no banco são de um processo que morreu.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { container.engine.recoverInterrupted() }
    }
}
