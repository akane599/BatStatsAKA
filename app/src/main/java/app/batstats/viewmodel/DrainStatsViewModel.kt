package app.batstats.viewmodel

import androidx.lifecycle.ViewModel
import app.batstats.battery.drain.AdvancedDrainTracker

class DrainStatsViewModel(private val drainTracker: AdvancedDrainTracker) : ViewModel() {
    val drainState = drainTracker.drainState
    val isTracking = drainTracker.isTracking
    fun resetSession() = drainTracker.resetSession()
    fun startTracking() = drainTracker.start()
    fun stopTracking() = drainTracker.stop()
}
