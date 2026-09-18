package app.batstats.battery.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HistoryMaintenanceTest {
    @Test fun clearingBlocksStartsBeforeWaitingForAnImportAndFinishesAfterCallerCancellation() = runTest {
        val maintenance = HistoryMaintenance()
        maintenance.mutations.lock() // An earlier import owns the mutation lock.
        var stopped = false
        var deleted = false
        val job = launch { maintenance.clear({ stopped = true }, { deleted = true }) }
        yield()
        assertTrue(maintenance.isClearing); assertTrue(stopped); assertFalse(deleted)
        job.cancel()
        maintenance.mutations.unlock()
        job.join()
        assertTrue(deleted); assertFalse(maintenance.isClearing)
    }
    @Test fun failedClearReleasesTheStartGateAndMutex() = runTest {
        val maintenance = HistoryMaintenance()
        try { maintenance.clear({}, { error("Disk write failed") }); fail("Must surface the failure") }
        catch (_: IllegalStateException) { }
        assertFalse(maintenance.isClearing)
        assertTrue(maintenance.mutations.tryLock())
        maintenance.mutations.unlock()
    }
}
