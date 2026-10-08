package com.akane.voltwise.battery.tile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Holds the listening [CoroutineScope] and, only while monitoring is on, the `SamplingDemand`
 * token [MonitorTileService] owns, so they can be released exactly once no matter which callback
 * fires first — `onStopListening` in the ordinary case, or `onTileRemoved`/`onDestroy` when the
 * tile is removed or the process is torn down without a clean stop. Without this, either path
 * leaks the token and leaves the sampler stuck at the 2 s demand cadence for the rest of the
 * process's life.
 */
internal class TileListenSession {
    private var scope: CoroutineScope? = null
    private var token: AutoCloseable? = null

    /** Starts a new session, releasing any previous one first (defensive: a start always follows a stop). */
    fun start(
        scope: CoroutineScope,
        isMonitoring: StateFlow<Boolean>,
        acquireDemand: () -> AutoCloseable,
    ) {
        stop()
        this.scope = scope
        scope.launch {
            isMonitoring.collect { on ->
                token?.close()
                token = if (on) acquireDemand() else null
            }
        }
    }

    /** Idempotent: safe to call from more than one lifecycle callback, or with no session active. */
    fun stop() {
        scope?.cancel()
        scope = null
        token?.close()
        token = null
    }
}
