package app.batstats.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.BatterySample
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SessionDetailsViewModel(
    app: Application,
    private val repo: BatteryRepository,
    private val db: BatteryDatabase,
    private val sessionId: String
) : AndroidViewModel(app) {

    data class Point(val currentMa: Int?, val voltageMv: Int?, val tempC: Double?, val timestamp: Long, val observationId: String?, val gap: Boolean)
    data class Ui(
        val type: String = "",
        val start: Long = 0L,
        val end: Long? = null,
        val levelRange: String = "",
        val capacityMah: Int? = null,
        val avgCurrent: Long? = null,
        val points: List<Point> = emptyList(),
        val source: String = "",
        val observedMs: Long = 0,
        val counterCoveredMs: Long = 0,
        val closeReason: String? = null
    )
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            db.sessionDao().session(sessionId).combine(
                repo.realtimeFlow
            ) { session, _ -> session }.filterNotNull().collect { s ->
                val end = s.endTime ?: System.currentTimeMillis()
                val samples = if (s.source == "legacy") repo.samplesBetween(s.startTime, end).first() else repo.samplesForSession(s.sessionId).first()

                val startPct = s.startLevel
                val endPct = s.endLevel ?: samples.lastOrNull()?.levelPercent

                val points = aggregatePerMinute(samples)
                _ui.value = Ui(
                    type = s.type.name,
                    start = s.startTime,
                    end = s.endTime,
                    levelRange = "${startPct?.let { "$it%" } ?: "—"} → ${endPct?.let { "$it%" } ?: "—"}",
                    capacityMah = s.estCapacityMah,
                    avgCurrent = s.avgCurrentUa,
                    points = points, source = s.source, observedMs = s.observedMs, counterCoveredMs = s.counterCoveredMs, closeReason = s.closeReason
                )
            }
        }
    }

    private fun aggregatePerMinute(samples: List<BatterySample>): List<Point> {
        val step = (samples.size / 360).coerceAtLeast(1)
        return samples.filterIndexed { index, _ -> index % step == 0 }.map { sample ->
            Point(sample.currentNowUa?.div(1000)?.toInt(), sample.voltageMv,
                sample.temperatureDeciC?.div(10.0), sample.timestamp, sample.observationId,
                sample.boundaryReason != null)
        }
    }
}
