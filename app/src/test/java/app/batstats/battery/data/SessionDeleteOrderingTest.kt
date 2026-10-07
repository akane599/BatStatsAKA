package app.batstats.battery.data

import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real repository queue and deletion decision; only the Android sampling/storage callbacks are fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDeleteOrderingTest {
    @Test fun queuedStopThenDeleteCannotResurrectTheSessionOrItsChildren() = runTest {
        val fixture = Fixture()
        fixture.cached = fixture.row
        fixture.monitoring = true
        val writer = writer(fixture)
        // The writer has a cached session, but is paused before the queued sample and Stop.
        writer.trySend(Event.SAMPLE)
        fixture.monitoring = false
        writer.trySend(Event.STOP)
        val deletion = async(start = CoroutineStart.UNDISPATCHED) { writer.deleteSession(ID) }
        assertFalse(fixture.monitoring)
        assertFalse(deletion.isCompleted)
        assertNotNull(fixture.cached)

        runCurrent()
        assertTrue(deletion.await())
        assertEquals(listOf("sample", "close", "delete"), fixture.writes)
        assertNull("No later upsert recreated the deleted session", fixture.row)
        assertNull(fixture.cached)
        assertTrue("All sample, snapshot, uid and usage children are gone", fixture.children.isEmpty())
        runCurrent()
        assertNull(fixture.row)
    }

    @Test fun cachedRecordingSessionIsRefusedEvenWhenPublicMonitoringIsFalse() = runTest {
        val fixture = Fixture().apply { cached = row }
        val writer = writer(fixture)
        assertFalse(writer.deleteSession(ID))
        assertNotNull(fixture.row)
        assertEquals(4, fixture.children.size)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun abandonedEarlierGenerationActiveRowIsDeletableWithoutStartingMonitoring() = runTest {
        val fixture = Fixture()
        assertEquals(1, fixture.row?.activeKey)
        assertEquals("earlier-generation", fixture.row?.observationId)
        assertNull(fixture.cached)
        val writer = writer(fixture)
        assertTrue(writer.deleteSession(ID))
        assertNull(fixture.row)
        assertTrue(fixture.children.isEmpty())
        assertEquals(listOf("delete"), fixture.writes)
    }

    @Test fun missingRowReturnsFalse() = runTest {
        val fixture = Fixture().apply { row = null }
        assertFalse(writer(fixture).deleteSession(ID))
    }

    @Test fun storageFailureReachesCallerAndWriterAcceptsTheNextRequest() = runTest {
        val fixture = Fixture()
        val failure = IllegalStateException("storage failed")
        fixture.deleteFailure = failure
        val writer = writer(fixture)
        val reported = runCatching { writer.deleteSession(ID) }.exceptionOrNull()
        assertTrue(reported is IllegalStateException)
        assertEquals("storage failed", reported?.message)
        assertTrue("Delete failures are reported to the caller, not sampling", fixture.failures.isEmpty())
        assertNotNull(fixture.row)
        fixture.deleteFailure = null
        assertTrue(writer.deleteSession(ID))
    }

    @Test fun writerCancelledBeforeItStartsFailsQueuedAndFutureRequests() = runTest {
        val fixture = Fixture()
        val owner = Job(backgroundScope.coroutineContext[Job])
        val writer = writer(fixture, CoroutineScope(backgroundScope.coroutineContext + owner))
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { writer.deleteSession(ID) }.exceptionOrNull()
        }
        owner.cancel()
        runCurrent()
        assertTrue(queued.await() is CancellationException)
        assertTrue(runCatching { writer.deleteSession(ID) }.exceptionOrNull() is CancellationException)
        assertNotNull(fixture.row)
    }

    @Test fun writerCancelledDuringDeletionFailsCallerWithoutHanging() = runTest {
        val fixture = Fixture().apply { beforeDelete = CompletableDeferred() }
        val owner = Job(backgroundScope.coroutineContext[Job])
        val writer = writer(fixture, CoroutineScope(backgroundScope.coroutineContext + owner))
        val deletion = async { runCatching { writer.deleteSession(ID) }.exceptionOrNull() }
        runCurrent()
        owner.cancel()
        runCurrent()
        assertTrue(deletion.await() is CancellationException)
        assertNotNull(fixture.row)
        assertFalse(fixture.maintenance.mutations.isLocked)
    }

    @Test fun callerCancellationKeepsMutationLockUntilQueuedDeleteFinishes() = runTest {
        val fixture = Fixture().apply { beforeDelete = CompletableDeferred() }
        val writer = writer(fixture)
        val caller = launch { writer.deleteSession(ID) }
        runCurrent()
        caller.cancel()
        runCurrent()
        assertTrue(fixture.maintenance.mutations.isLocked)
        assertFalse(caller.isCompleted)
        fixture.beforeDelete?.complete(Unit)
        runCurrent()
        caller.join()
        assertNull(fixture.row)
        assertFalse(fixture.maintenance.mutations.isLocked)
    }

    @Test fun clearWaitsForEarlierDeleteWithoutDeadlockingTheWriter() = runTest {
        val fixture = Fixture().apply { beforeDelete = CompletableDeferred() }
        val writer = writer(fixture)
        val deletion = async { writer.deleteSession(ID) }
        runCurrent()
        val clearing = launch {
            fixture.maintenance.clear({}, {
                writer.send(Event.CLEAR)
                fixture.cleared.await()
            })
        }
        runCurrent()
        assertTrue(fixture.maintenance.isClearing)
        assertFalse(writer.deleteSession(ID))
        fixture.beforeDelete?.complete(Unit)
        runCurrent()
        assertTrue(deletion.await())
        clearing.join()
        assertEquals(listOf("delete", "clear"), fixture.writes)
        assertFalse(fixture.maintenance.isClearing)
    }

    @Test fun deleteWaitingForImportRechecksClearGateAfterAcquiringLock() = runTest {
        val fixture = Fixture()
        val writer = writer(fixture)
        fixture.maintenance.mutations.lock() // An import owns the mutex.
        val deletion = async { writer.deleteSession(ID) }
        runCurrent()
        val clearing = launch {
            fixture.maintenance.clear({}, {
                writer.send(Event.CLEAR)
                fixture.cleared.await()
            })
        }
        runCurrent()
        fixture.maintenance.mutations.unlock()
        runCurrent()
        assertFalse(deletion.await())
        clearing.join()
        assertEquals(listOf("clear"), fixture.writes)
    }

    private fun TestScope.writer(fixture: Fixture, owner: CoroutineScope = backgroundScope) = HistoryWriter(
        scope = owner,
        dispatcher = StandardTestDispatcher(testScheduler),
        maintenance = fixture.maintenance,
        openSessionId = { fixture.cached?.sessionId },
        deleteRow = fixture::delete,
        handleEvent = fixture::handle,
        onFailure = { fixture.failures += it },
    )

    private enum class Event { SAMPLE, STOP, CLEAR }

    private class Fixture {
        val maintenance = HistoryMaintenance()
        var row: ChargeSession? = ChargeSession(
            sessionId = ID, type = SessionType.DISCHARGE, startTime = 100,
            endTime = null, startLevel = 90, endLevel = null, deltaUah = null,
            avgCurrentUa = null, estCapacityMah = null, observationId = "earlier-generation",
        )
        var cached: ChargeSession? = null
        var monitoring = false
        val children = mutableSetOf("sample", "snapshot", "snapshotUid", "appUsage")
        val writes = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        var deleteFailure: Exception? = null
        var beforeDelete: CompletableDeferred<Unit>? = null
        val cleared = CompletableDeferred<Unit>()

        suspend fun delete(id: String): Boolean {
            beforeDelete?.await()
            deleteFailure?.let { throw it }
            if (row?.sessionId != id) return false
            children.clear()
            row = null
            writes += "delete"
            return true
        }

        fun handle(event: Event) {
            when (event) {
                Event.SAMPLE -> {
                    // Fake persistence, including the update-or-insert behavior responsible for C21.
                    row = cached
                    writes += "sample"
                }
                Event.STOP -> {
                    row = cached?.copy(endTime = 200, activeKey = null)
                    cached = null
                    writes += "close"
                }
                Event.CLEAR -> {
                    row = null
                    children.clear()
                    writes += "clear"
                    cleared.complete(Unit)
                }
            }
        }
    }

    private companion object {
        const val ID = "session-1"
    }
}
