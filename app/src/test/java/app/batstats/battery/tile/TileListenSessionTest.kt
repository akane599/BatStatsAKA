package app.batstats.battery.tile

import app.batstats.battery.service.SamplingDemand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the release-exactly-once contract [TileListenSession] gives [MonitorTileService]:
 * `onStopListening`, `onTileRemoved` and `onDestroy` all call [TileListenSession.stop], and none of
 * them is guaranteed to be the only one that fires (a tile can be removed while still listening, or
 * the process can be torn down without any of the ordinary callbacks running first).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileListenSessionTest {
    private class CountingToken : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
        }
    }

    private class FakeDemand : SamplingDemand {
        val tokens = mutableListOf<CountingToken>()
        val tags = mutableListOf<String>()

        override fun acquire(tag: String): AutoCloseable {
            tags += tag
            return CountingToken().also { tokens += it }
        }
    }

    @Test
    fun listeningWhileMonitoringIsOffDoesNotAcquireDemand() = runTest {
        val session = TileListenSession()
        val monitoring = MutableStateFlow(false)
        val demand = FakeDemand()
        val scope = CoroutineScope(coroutineContext + Job())

        session.start(scope, monitoring) { demand.acquire("qs_tile") }
        runCurrent()

        assertEquals(0, demand.tokens.size)
        session.stop()
    }

    @Test
    fun listeningWhileMonitoringIsOnHoldsDemand() = runTest {
        val session = TileListenSession()
        val monitoring = MutableStateFlow(true)
        val demand = FakeDemand()
        val scope = CoroutineScope(coroutineContext + Job())

        session.start(scope, monitoring) { demand.acquire("qs_tile") }
        runCurrent()

        assertEquals(listOf("qs_tile"), demand.tags)
        assertEquals(0, demand.tokens.single().closeCount)
        session.stop()
        assertEquals(1, demand.tokens.single().closeCount)
    }

    @Test
    fun togglingMonitoringAcquiresAndReleasesOncePerOnPeriod() = runTest {
        val session = TileListenSession()
        val monitoring = MutableStateFlow(false)
        val demand = FakeDemand()
        val scope = CoroutineScope(coroutineContext + Job())

        session.start(scope, monitoring) { demand.acquire("qs_tile") }
        runCurrent()
        assertEquals(0, demand.tokens.size)

        monitoring.value = true
        runCurrent()
        assertEquals(1, demand.tokens.size)
        assertEquals(0, demand.tokens.single().closeCount)

        monitoring.value = true
        runCurrent()
        assertEquals(1, demand.tokens.size)

        monitoring.value = false
        runCurrent()
        assertEquals(1, demand.tokens.single().closeCount)

        monitoring.value = true
        runCurrent()
        assertEquals(2, demand.tokens.size)
        assertEquals(0, demand.tokens.last().closeCount)

        monitoring.value = false
        runCurrent()
        session.stop()
        assertEquals(listOf(1, 1), demand.tokens.map { it.closeCount })
    }

    @Test
    fun doubleStopReleasesTheTokenExactlyOnceAndCancelsTheScope() = runTest {
        val session = TileListenSession()
        val monitoring = MutableStateFlow(true)
        val token = CountingToken()
        val scope = CoroutineScope(coroutineContext + Job())

        session.start(scope, monitoring) { token }
        runCurrent()
        session.stop()
        session.stop() // e.g. onStopListening then onDestroy for the same session

        assertEquals(1, token.closeCount)
        assertTrue(scope.coroutineContext.job.isCancelled)
        monitoring.value = false
        runCurrent()
        monitoring.value = true
        runCurrent()
        assertEquals(1, token.closeCount)
    }

    @Test
    fun stopBeforeCollectionDoesNotAcquireDemand() = runTest {
        val session = TileListenSession()
        val monitoring = MutableStateFlow(true)
        val demand = FakeDemand()
        val scope = CoroutineScope(coroutineContext + Job())

        session.start(scope, monitoring) { demand.acquire("qs_tile") }
        session.stop()
        runCurrent()

        assertEquals(0, demand.tokens.size)
        assertTrue(scope.coroutineContext.job.isCancelled)
    }

    @Test
    fun stopWithoutAPriorStartIsANoOp() {
        // e.g. onDestroy firing when the tile was never listened to.
        TileListenSession().stop()
    }

    @Test
    fun startReplacesAnUnstoppedSessionAndReleasesTheOldOneExactlyOnce() = runTest {
        val session = TileListenSession()
        val firstMonitoring = MutableStateFlow(true)
        val firstToken = CountingToken()
        val firstScope = CoroutineScope(coroutineContext + Job())
        val secondMonitoring = MutableStateFlow(true)
        val secondToken = CountingToken()
        val secondScope = CoroutineScope(coroutineContext + Job())

        session.start(firstScope, firstMonitoring) { firstToken }
        runCurrent()
        session.start(secondScope, secondMonitoring) { secondToken } // no stop() between starts
        runCurrent()
        assertEquals(1, firstToken.closeCount)
        assertEquals(0, secondToken.closeCount)
        session.stop()

        assertEquals(1, firstToken.closeCount)
        assertEquals(1, secondToken.closeCount)
        assertTrue(firstScope.coroutineContext.job.isCancelled)
        assertTrue(secondScope.coroutineContext.job.isCancelled)
    }
}
