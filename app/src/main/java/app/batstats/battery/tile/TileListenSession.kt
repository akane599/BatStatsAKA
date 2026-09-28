package app.batstats.battery.tile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Holds the listening [CoroutineScope] and `SamplingDemand` token [MonitorTileService] owns while
 * a session is active, so they can be released exactly once no matter which lifecycle callback
 * fires first — `onStopListening` in the ordinary case, or `onTileRemoved`/`onDestroy` when the
 * tile is removed or the process is torn down without a clean stop. Without this, either path
 * leaks the token and leaves the sampler stuck at the 2 s demand cadence for the rest of the
 * process's life.
 */
internal class TileListenSession {
    private var scope: CoroutineScope? = null
    private var token: AutoCloseable? = null

    /** Starts a new session, releasing any previous one first (defensive: a start always follows a stop). */
    fun start(scope: CoroutineScope, token: AutoCloseable) {
        stop()
        this.scope = scope
        this.token = token
    }

    /** Idempotent: safe to call from more than one lifecycle callback, or with no session active. */
    fun stop() {
        scope?.cancel()
        scope = null
        token?.close()
        token = null
    }
}
