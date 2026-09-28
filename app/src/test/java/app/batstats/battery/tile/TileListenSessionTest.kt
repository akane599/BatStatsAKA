package app.batstats.battery.tile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the release-exactly-once contract [TileListenSession] gives [MonitorTileService]:
 * `onStopListening`, `onTileRemoved` and `onDestroy` all call [TileListenSession.stop], and none of
 * them is guaranteed to be the only one that fires (a tile can be removed while still listening, or
 * the process can be torn down without any of the ordinary callbacks running first).
 */
class TileListenSessionTest {
    private class CountingToken : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun doubleStopReleasesTheTokenExactlyOnceAndCancelsTheScope() {
        val session = TileListenSession()
        val token = CountingToken()
        val scope = CoroutineScope(Job())

        session.start(scope, token)
        session.stop()
        session.stop() // e.g. onStopListening then onDestroy for the same session

        assertEquals(1, token.closeCount)
        assertTrue(scope.coroutineContext.job.isCancelled)
    }

    @Test
    fun stopWithoutAPriorStartIsANoOp() {
        // e.g. onDestroy firing when the tile was never listened to.
        TileListenSession().stop()
    }

    @Test
    fun startReplacesAnUnstoppedSessionAndReleasesTheOldOneExactlyOnce() {
        val session = TileListenSession()
        val firstToken = CountingToken()
        val firstScope = CoroutineScope(Job())
        val secondToken = CountingToken()
        val secondScope = CoroutineScope(Job())

        session.start(firstScope, firstToken)
        session.start(secondScope, secondToken) // no stop() between starts
        session.stop()

        assertEquals(1, firstToken.closeCount)
        assertEquals(1, secondToken.closeCount)
        assertTrue(firstScope.coroutineContext.job.isCancelled)
        assertTrue(secondScope.coroutineContext.job.isCancelled)
    }
}
