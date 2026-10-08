package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.battery.util.DumpOutput
import com.akane.voltwise.battery.util.ShellRunner
import com.akane.voltwise.battery.util.ShellRunner.Mode
import com.akane.voltwise.battery.util.ShellRunner.Outcome
import com.akane.voltwise.battery.util.selectShellMode
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

    private fun TestScope.repository(shell: StatsShell) = AppStatsRepository(
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

    @Test fun forcedReadUsesRootOrAdbFallbackAfterShizukuDenial() = runTest {
        for (fallback in listOf(Mode.ROOT, Mode.ADB)) {
            val selected = selectShellMode(
                shizukuRunning = { true },
                shizukuAuthorized = { false },
                rootAvailable = { fallback == Mode.ROOT },
                adbAvailable = { true },
            )
            val shell = FakeShell().apply { access = selected }
            val ready = repository(shell).snapshot(force = true) as AppStatsResult.Ready
            assertEquals(fallback, selected)
            assertEquals("Android batterystats · ${fallback.name}", ready.snapshot.source)
            assertEquals(listOf(true), shell.probes)
            assertEquals(listOf(AppStatsRepository.COMMAND), shell.commands)
        }
        assertTrue(diagnostics.isEmpty())
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

    @Test fun aDumpAndroidRefusesIsNoAccessNotAFailure() = runTest {
        // ADB grants on Android 16: ShellRunner turns the one-line security exception into this failure.
        val shell = FakeShell().apply { access = Mode.ADB; next = { Outcome.Failure(Mode.ADB, DumpOutput.REFUSED_CROSS_USER) } }
        val repository = repository(shell)
        assertEquals(AppStatsResult.NoAccess, repository.snapshot(force = true))
        shell.next = { Outcome.Failure(Mode.SHIZUKU, DumpOutput.REFUSED) }
        assertEquals(AppStatsResult.NoAccess, repository.snapshot())
        assertTrue("Not a read failure", diagnostics.isEmpty())
    }

    @Test fun cachedShizukuAccessFailuresAreNoAccessAndTheNextReadReselects() = runTest {
        for ((reason, message) in listOf(
            ShizukuBridge.Failure.NOT_RUNNING to "Shizuku is not running",
            ShizukuBridge.Failure.NO_PERMISSION to "Shizuku permission denied",
        )) {
            var running = true
            var authorized = true
            val probes = mutableListOf<String>()
            val commands = mutableListOf<String>()
            val runner = ShellRunner(
                probeMode = {
                    selectShellMode(
                        shizukuRunning = { probes += "shizuku"; running },
                        shizukuAuthorized = { authorized },
                        rootAvailable = { probes += "root"; false },
                        adbAvailable = { probes += "adb"; false },
                    )
                },
                runShizuku = { command, _ ->
                    commands += command
                    ShizukuBridge.RunResult.Error(message, reason)
                },
                shizukuRunning = { running },
                elapsedMs = { 0L }, // The ten-second cache cannot expire during this regression.
            )
            assertEquals(Mode.SHIZUKU, runner.detectMode())
            running = reason != ShizukuBridge.Failure.NOT_RUNNING
            authorized = false
            val repository = repository(ShellRunnerStatsShell(runner))

            assertEquals(AppStatsResult.NoAccess, repository.snapshot())
            assertEquals("The failing command must not fall back", listOf("shizuku"), probes)
            assertEquals(message, runner.lastError.value)
            assertEquals(Mode.NONE, runner.access.value)
            assertNull(repository.cached.value)
            assertTrue(diagnostics.isEmpty())

            assertEquals(AppStatsResult.NoAccess, repository.snapshot())
            assertEquals(listOf("shizuku", "shizuku", "root", "adb"), probes)
            assertEquals("The next read no longer uses Shizuku", listOf(AppStatsRepository.COMMAND), commands)
        }
    }

    @Test fun cachedShizukuCommandFailureStaysFailedAndKeepsTheMode() = runTest {
        var authorized = true
        var probes = 0
        var commands = 0
        val runner = ShellRunner(
            probeMode = {
                probes++
                selectShellMode(
                    shizukuRunning = { true },
                    shizukuAuthorized = { authorized },
                    rootAvailable = { error("A cached command failure must not probe root") },
                    adbAvailable = { error("A cached command failure must not probe ADB") },
                )
            },
            runShizuku = { _, _ ->
                commands++
                ShizukuBridge.RunResult.Error("command permission denied", ShizukuBridge.Failure.COMMAND)
            },
            shizukuRunning = { true },
            elapsedMs = { 0L },
        )
        assertEquals(Mode.SHIZUKU, runner.detectMode())
        authorized = false
        val repository = repository(ShellRunnerStatsShell(runner))
        repeat(2) {
            val failed = repository.snapshot() as AppStatsResult.Failed
            assertTrue(failed.message, failed.message.contains("command permission denied"))
            assertEquals(Mode.SHIZUKU, runner.access.value)
        }
        assertEquals(1, probes)
        assertEquals(2, commands)
        assertEquals(List(2) { DiagnosticCode.ADVANCED_READ_FAILED }, diagnostics)
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

    @Test fun allRejectedAppPowerRowsFailWithoutCachingAndTheNextReadRetries() = runTest {
        val malformed = VALID_DUMP.replace("pwi,uid,1.5", "pwi,uid,NaN")
        val shell = FakeShell().apply { next = { Outcome.Success(malformed, access) } }
        val repository = repository(shell)
        assertEquals(AppStatsResult.Failed(AppStatsRepository.FORMAT_UNAVAILABLE), repository.snapshot())
        assertEquals(listOf(DiagnosticCode.ADVANCED_FORMAT_INVALID), diagnostics)
        assertNull("Malformed empty app data must not be cached", repository.cached.value)

        shell.next = { Outcome.Success(VALID_DUMP, shell.access) }
        val retried = repository.snapshot() as AppStatsResult.Ready
        assertEquals("A failed parse must run the next dump, not serve an empty cache", 2, shell.commands.size)
        assertEquals(1.5, retried.snapshot.apps.single().powerMah, 0.0)
        assertSame(retried.snapshot, repository.cached.value)
    }

    @Test fun validWindowWithoutAppRowsIsReadyEmpty() = runTest {
        val empty = VALID_DUMP.lineSequence().first()
        val shell = FakeShell().apply { next = { Outcome.Success(empty, access) } }
        val repository = repository(shell)
        val ready = repository.snapshot() as AppStatsResult.Ready
        assertTrue(ready.snapshot.apps.isEmpty())
        assertSame(ready.snapshot, repository.cached.value)
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun unrelatedRejectedRowsDoNotPreventReadyEmpty() = runTest {
        val unrelated = VALID_DUMP.lineSequence().first() + "\n9,0,l,pwi,screen,NaN\n9,10001,l,jb,job,broken,2"
        val shell = FakeShell().apply { next = { Outcome.Success(unrelated, access) } }
        val ready = repository(shell).snapshot() as AppStatsResult.Ready
        assertTrue(ready.snapshot.apps.isEmpty())
        assertEquals(2, ready.snapshot.rejectedRecords)
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun partialAppPowerRejectionIsReadyWithAcceptedRows() = runTest {
        val partial = VALID_DUMP + "\n9,10002,l,pwi,uid,NaN"
        val shell = FakeShell().apply { next = { Outcome.Success(partial, access) } }
        val repository = repository(shell)
        val ready = repository.snapshot() as AppStatsResult.Ready
        assertEquals(10001, ready.snapshot.apps.single().uid)
        assertEquals(1.5, ready.snapshot.apps.single().powerMah, 0.0)
        assertEquals(2, ready.snapshot.appPowerRecords)
        assertEquals(1, ready.snapshot.rejectedAppPowerRecords)
        assertSame(ready.snapshot, repository.cached.value)
        assertTrue(diagnostics.isEmpty())
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
