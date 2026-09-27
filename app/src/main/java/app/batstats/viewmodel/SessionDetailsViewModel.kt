package app.batstats.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.SessionEvidence
import app.batstats.battery.data.db.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class SessionDetailsViewModel(
    private val repo: BatteryRepository,
    private val db: BatteryDatabase,
    private val sessionId: String
) : ViewModel() {
    data class Ui(val session: ChargeSession? = null, val points: List<SessionChartReading> = emptyList(),
                  val loading: Boolean = true, val failed: Boolean = false)
    private val revision = MutableStateFlow(0)
    val recordingObservation = combine(repo.isMonitoringFlow, repo.observation) { monitoring, observation ->
        if (monitoring && !observation.stopped) observation.latest?.generation else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<Ui> = revision.flatMapLatest {
        db.sessionDao().session(sessionId).distinctUntilChanged().mapLatest { session ->
            if (session == null) Ui(loading = false)
            else Ui(session, db.batteryDao().sessionChartSamples(sessionId, session.startTime,
                SessionEvidence.lastEvidence(session), SessionEvidence.chartBucketMs(session)), loading = false)
        }.onStart { emit(Ui()) }.catch { emit(Ui(loading = false, failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ui())
    fun refresh() { revision.update { it + 1 } }
}
