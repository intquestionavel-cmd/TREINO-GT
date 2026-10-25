package br.com.treinogt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.treinogt.analysis.ChartData
import br.com.treinogt.analysis.ChartDataBuilder
import br.com.treinogt.analysis.ReportCalculator
import br.com.treinogt.analysis.ReportModel
import br.com.treinogt.data.SessionData
import br.com.treinogt.data.SessionEntity
import br.com.treinogt.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(private val repo: SessionRepository) : ViewModel() {
    val sessions: StateFlow<List<SessionEntity>> =
        repo.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(id: Long, name: String) { viewModelScope.launch(Dispatchers.IO) { repo.rename(id, name) } }
    fun setNotes(id: Long, notes: String) { viewModelScope.launch(Dispatchers.IO) { repo.setNotes(id, notes) } }
    fun delete(id: Long) { viewModelScope.launch(Dispatchers.IO) { repo.delete(id) } }
}

data class ReportUi(val data: SessionData, val model: ReportModel, val chart: ChartData)

class ReportViewModel(private val repo: SessionRepository, private val id: Long) : ViewModel() {
    private val _ui = MutableStateFlow<ReportUi?>(null)
    val ui: StateFlow<ReportUi?> = _ui
    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            val ui = withContext(Dispatchers.Default) {
                val d = repo.load(id) ?: return@withContext null
                val m = ReportCalculator.compute(d.session, d.samples, d.laps, d.pauses)
                ReportUi(d, m, ChartDataBuilder.build(d.session, d.samples, d.laps, d.pauses, m.gaps))
            }
            if (ui == null) _notFound.value = true else _ui.value = ui
        }
    }

    fun rename(name: String) { viewModelScope.launch(Dispatchers.IO) { repo.rename(id, name); reload() } }
    fun setNotes(notes: String) { viewModelScope.launch(Dispatchers.IO) { repo.setNotes(id, notes); reload() } }
    fun setReference(seq: Int) { viewModelScope.launch(Dispatchers.IO) { repo.setReferenceLap(id, seq); reload() } }
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.delete(id) }; onDone() }
    }
}
