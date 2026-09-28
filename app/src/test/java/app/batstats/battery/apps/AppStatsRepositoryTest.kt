package app.batstats.battery.apps

import app.batstats.battery.diagnostics.DiagnosticCode
import app.batstats.battery.util.ShellRunner.Mode
import app.batstats.battery.util.ShellRunner.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppStatsRepositoryTest {
    private class FakeShell : StatsShell {
        var access = Mode.SHIZUKU
        val commands = mutableListOf<String>()
        val probes = mutableListOf<Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        var next: () -> Outcome = { Outcome.Success(VALID_DUMP, access) }

        override val mode: Mode get() = access
        override suspend fun detectMode(forceRefresh: Boolean): Mode = access.also { probes += forceRefresh }
        override suspend fun exec(command: String): Outcome {
            commands += command
            gate?.await()
            return next()
        }
    }

    private val diagnostics = mutableListOf<DiagnosticCode>()

    private fun TestScope.repository(shell: FakeShell) = AppStatsRepository(
        shell = shell,
        scope = backgroundScope,
        onDiagnostic = { diagnostics += it },
        parseDispatcher = StandardTestDispatcher(testScheduler),
        elapsedMs = { testScheduler.currentTime },
    )

    @Test fun concurrentCallersJoinOneDumpEvenWhenForced() = runTest {
        val shell = FakeShell().apply { gate = CompletableDeferred() }
        val repository = repository(shell)
        val first = async { repository.snapshot() }
        val second = async { repository.snapshot() }
        val forced = async { repository.snapshot(force = true) }
        runCurrent()
        assertEquals(listOf(AppStatsRepository.COMMAND), shell.commands)
        shell.gate?.complete(Unit)
        val results = listOf(first.await(), second.await(), forced.await())
        assertTrue(results.all { it is AppStatsResult.Ready })
        assertEquals(1, results.map { (it as AppStatsResult.Ready).snapshot }.distinct().size)
        assertEquals(1, shell.commands.size)
    }

    @Test fun aCallerThatGoesAwayDoesNotCancelTheSharedDump() = runTest {
        val shell = FakeShell().apply { gate = CompletableDeferred() }
        val repository = repository(shell)
        val leaving = async { repository.snapshot() }
        val staying = async { repository.snapshot() }
        runCurrent()
        leaving.cancel()
        shell.gate?.complete(Unit)
        assertTrue(staying.await() is AppStatsResult.Ready)
        assertEquals(1, shell.commands.size)
    }

    @Test fun lastSnapshotAnswersForSixtySecondsThenANewDumpRuns() = runTest {
        val shell = FakeShell()
        val repository = repository(shell)
        val first = repository.snapshot() as AppStatsResult.Ready
        advanceTimeBy(AppStatsRepository.TTL_MS - 1)
        assertSame(first.snapshot, (repository.snapshot() as AppStatsResult.Ready).snapshot)
        assertEquals(1, shell.commands.size)
        advanceTimeBy(1)
        assertTrue(repository.snapshot() is AppStatsResult.Ready)
        assertEquals(2, shell.commands.size)
    }

    @Test fun forceBypassesTheCacheAndReprobesAccess() = runTest {
        val shell = FakeShell()
        val repository = repository(shell)
        repository.snapshot()
        assertEquals(emptyList<Boolean>(), shell.probes) // A plain read uses exec's cached mode.
        repository.snapshot(force = true)
        assertEquals(2, shell.commands.size)
        assertEquals(listOf(true), shell.probes)
    }

    @Test fun cachedSnapshotIsDroppedWhenTheAccessModeChanges() = runTest {
        val shell = FakeShell()
        val repository = repository(shell)
        repository.snapshot()
        shell.access = Mode.ROOT
        val again = repository.snapshot() as AppStatsResult.Ready
        assertEquals(2, shell.commands.size)
        assertTrue(again.snapshot.source.endsWith("ROOT"))
    }

    @Test fun noAccessIsReportedWithoutADumpAndIsNotCached() = runTest {
        val shell = FakeShell().apply { access = Mode.NONE }
        val repository = repository(shell)
        assertEquals(AppStatsResult.NoAccess, repository.snapshot(force = true))
        assertTrue("A forced read probes first and stops at NONE", shell.commands.isEmpty())
        shell.next = { Outcome.Failure(Mode.NONE, "Privileged access unavailable") }
        assertEquals(AppStatsResult.NoAccess, repository.snapshot())
        assertEquals(1, shell.commands.size)
        assertNull(repository.cached.value)
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun commandFailuresAreResultsNotExceptionsAndAreNotCached() = runTest {
        val shell = FakeShell().apply { next = { Outcome.Failure(Mode.SHIZUKU, "Helper protocol unavailable") } }
        val repository = repository(shell)
        val failed = repository.snapshot() as AppStatsResult.Failed
        assertTrue(failed.message, failed.message.contains("Helper protocol unavailable"))
        assertEquals(listOf(DiagnosticCode.ADVANCED_READ_FAILED), diagnostics)
        shell.next = { error("binder died") }
        assertEquals(AppStatsResult.Failed("Collection failed: IllegalStateException"), repository.snapshot())
        assertEquals("Failures are retried, never served from cache", 2, shell.commands.size)
    }

    @Test fun aDumpWithoutAValidWindowIsAFormatFailure() = runTest {
        val shell = FakeShell().apply { next = { Outcome.Success("9,0,l,bt,2,60000", access) } }
        val repository = repository(shell)
        assertEquals(AppStatsResult.Failed(AppStatsRepository.FORMAT_UNAVAILABLE), repository.snapshot())
        assertEquals(listOf(DiagnosticCode.ADVANCED_FORMAT_INVALID), diagnostics)
    }

    @Test fun readySnapshotsAreParsedLabelledAndKeptAsTheCachedSnapshotPastTheTtl() = runTest {
        val shell = FakeShell()
        val repository = repository(shell)
        val ready = repository.snapshot() as AppStatsResult.Ready
        assertEquals("Android batterystats · SHIZUKU", ready.snapshot.source)
        assertEquals(1.5, ready.snapshot.apps.single { it.uid == 10001 }.powerMah, 1e-9)
        advanceTimeBy(10 * AppStatsRepository.TTL_MS)
        assertSame("Reading cached never dumps", ready.snapshot, repository.cached.value)
        assertEquals(1, shell.commands.size)
    }

    @Test fun invalidateDropsTheCacheAndTheDumpInFlightStillAnswersItsCallers() = runTest {
        val shell = FakeShell()
        val repository = repository(shell)
        repository.snapshot()
        shell.gate = CompletableDeferred()
        val inFlight = async { repository.snapshot(force = true) }
        runCurrent()
        repository.invalidate()
        assertNull(repository.cached.value)
        shell.gate?.complete(Unit)
        assertTrue(inFlight.await() is AppStatsResult.Ready)
        assertNull("A dump from before the reset is not cached", repository.cached.value)
        repository.snapshot()
        assertEquals(3, shell.commands.size)
    }

    private companion object {
        val VALID_DUMP = """
            9,0,l,bt,2,60000,50000,100000,80000,1700000000000,30000,20000,4000,3800000,3900000,10000
            9,0,i,uid,10001,example.app
            9,10001,l,pwi,uid,1.5,0,0.5,2.0
        """.trimIndent()
    }
}
