package app.batstats.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class HistoryViewModel(private val repo: BatteryRepository) : ViewModel() {
    data class Filter(val type: SessionType? = null, val text: String = "", val limit: Int = 50, val revision: Int = 0)
    data class Ui(val sessions: List<ChargeSession> = emptyList(), val loading: Boolean = true,
                  val failed: Boolean = false, val hasMore: Boolean = false)
    private val _filter = MutableStateFlow(Filter())
    val filter = _filter.asStateFlow()
    val recordingObservation = combine(repo.isMonitoringFlow, repo.observation) { monitoring, observation ->
        if (monitoring && !observation.stopped) observation.latest?.generation else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val ui = _filter.flatMapLatest { filter ->
        repo.sessionDao.filteredSessions(filter.type, filter.text.trim(), filter.limit + 1).distinctUntilChanged()
            .map { Ui(it.take(filter.limit), loading = false, hasMore = it.size > filter.limit) }
            .onStart { if (filter.limit == 50) emit(Ui()) }
            .catch { emit(Ui(loading = false, failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ui())
    fun filterBy(type: SessionType?) { _filter.update { it.copy(type = type, limit = 50) } }
    fun search(text: String) { _filter.update { it.copy(text = text.take(240), limit = 50) } }
    fun loadMore() { _filter.update { it.copy(limit = it.limit + 50) } }
    fun retry() { _filter.update { it.copy(revision = it.revision + 1) } }
}
