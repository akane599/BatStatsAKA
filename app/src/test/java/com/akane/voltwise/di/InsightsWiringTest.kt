package com.akane.voltwise.di

import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.reconcileAndCatchUpInsights
import com.akane.voltwise.battery.startInsightNotifications
import com.akane.voltwise.battery.service.refreshOnFinalizedSessions
import com.akane.voltwise.battery.data.db.InsightDao
import com.akane.voltwise.battery.insights.InsightNotifier
import com.akane.voltwise.battery.insights.InsightRepository
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.insights.actions.ActionExecutor
import com.akane.voltwise.battery.insights.actions.InsightActionRepository
import com.akane.voltwise.battery.insights.actions.PackageManagerTargetInspector
import com.akane.voltwise.battery.insights.actions.ShellRunnerActionExecutor
import com.akane.voltwise.battery.insights.actions.TargetInspector
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.akane.voltwise.viewmodel.FakeInsightsRepository
import com.akane.voltwise.viewmodel.InsightApplyResults
import com.akane.voltwise.viewmodel.InsightActionMessage
import com.akane.voltwise.viewmodel.InsightMessageCode
import com.akane.voltwise.viewmodel.InsightsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.koin.dsl.module
import com.akane.voltwise.viewmodel.AppDetailsRepository
import com.akane.voltwise.viewmodel.FindingDetailsViewModel
import com.akane.voltwise.viewmodel.InsightsRepository
import com.akane.voltwise.viewmodel.InsightsViewModel
import com.akane.voltwise.viewmodel.NowRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koin.core.definition.Kind
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import kotlin.reflect.KClass

@OptIn(ExperimentalCoroutinesApi::class, org.koin.core.annotation.KoinInternalApi::class)
class InsightsWiringTest {
    private fun definition(type: KClass<*>) = appModule.mappings.values
        .map { it.beanDefinition }.distinct().single { it.primaryType == type }

    @Test fun applicationScopeAndJournalAuthoritiesAreSingletons() = runTest {
        for (type in listOf(CoroutineScope::class, InsightDao::class, InsightRepository::class,
            InsightActionRepository::class, InsightsRepository::class, InsightApplyResults::class)) {
            assertEquals(type.toString(), Kind.Singleton, definition(type).kind)
        }
        assertTrue(ActionExecutor::class in definition(ShellRunnerActionExecutor::class).secondaryTypes)
        assertTrue(TargetInspector::class in definition(PackageManagerTargetInspector::class).secondaryTypes)
        assertEquals(Kind.Factory, definition(InsightsViewModel::class).kind)
        assertEquals(Kind.Factory, definition(FindingDetailsViewModel::class).kind)
        val application = koinApplication { modules(appModule) }
        val scope = application.koin.get<CoroutineScope>()
        try {
            assertSame(scope, application.koin.get<CoroutineScope>())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            application.close()
        }
    }

    @Test fun bothViewModelsResolveAndConsumeTheSameApplicationResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeInsightsRepository()
        val application = koinApplication {
            modules(appModule, module {
                single<InsightsRepository> { source }
                single<CoroutineScope> { backgroundScope }
            })
        }
        val store = ViewModelStore()
        try {
            val results = application.koin.get<InsightApplyResults>()
            assertSame(results, application.koin.get<InsightApplyResults>())
            val insights = application.koin.get<InsightsViewModel> { parametersOf(SavedStateHandle()) }
            val details = application.koin.get<FindingDetailsViewModel> {
                parametersOf(SavedStateHandle(mapOf("key" to "finding")))
            }
            store.put("insights", insights)
            store.put("details", details)
            backgroundScope.launch { insights.state.collect {} }
            backgroundScope.launch { details.state.collect {} }
            source.result = com.akane.voltwise.battery.insights.actions.ActionResult.Refused(
                com.akane.voltwise.battery.insights.actions.RefusalCode.ROLE_HOLDER,
            )
            details.onEvent(InsightsEvent.Undo(7))
            runCurrent()
            val expected = checkNotNull(results.latest.value)
            assertEquals("details must publish to the application singleton",
                InsightActionMessage(InsightMessageCode.ROLE_HOLDER), expected.copy(seq = 0))
            assertEquals(expected, insights.state.value.apply.lastResult)
            assertEquals(expected, details.state.value.apply.lastResult)
            details.onEvent(InsightsEvent.ResultShown(checkNotNull(details.state.value.apply.lastResult)))
            runCurrent()
            assertEquals("details consumption must clear the singleton", null, results.latest.value)
            assertEquals("details consumption must clear Insights too", null, insights.state.value.apply.lastResult)
            assertEquals(null, details.state.value.apply.lastResult)
            assertEquals(listOf(7L), source.undone)
        } finally {
            store.clear()
            runCurrent()
            application.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun notifierDefinitionIsLazyAndDoesNotRequireAndroidContext() {
        assertEquals(Kind.Singleton, definition(InsightNotifier::class).kind)
        assertTrue(
            "Notifier must not be created at Koin startup",
            appModule.eagerInstances.none { it.beanDefinition.primaryType == InsightNotifier::class },
        )
        koinApplication { modules(appModule) }.close()
    }

    @Test fun notificationStartupWaitsForMigrationReconcileAndRefreshThenForwardsReports() = runTest {
        val events = mutableListOf<String>()
        val migrated = CompletableDeferred<Unit>()
        val reconciled = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Unit>()
        val reports = MutableStateFlow<InsightReport?>(null)
        val first = InsightReport(1L, emptyList(), null)
        val second = InsightReport(2L, emptyList(), null)
        val notified = mutableListOf<InsightReport>()
        val startup = launch {
            startInsightNotifications(
                awaitMigrated = { events += "migration"; migrated.await() },
                catchUp = {
                    reconcileAndCatchUpInsights(
                        reconcile = { events += "reconcile"; reconciled.await() },
                        lastAnalyzedAt = { events += "timestamp"; null },
                        refresh = { events += "refresh"; refreshed.await(); reports.value = first },
                        clock = { 30_000_000L },
                    )
                },
                reports = { events += "reports"; reports },
                maybeNotify = { notified += it },
                onFailure = { fail("Successful catch-up must not record a failure") },
            )
        }
        runCurrent()
        assertEquals(listOf("migration"), events)
        assertTrue(notified.isEmpty())
        migrated.complete(Unit)
        runCurrent()
        assertEquals(listOf("migration", "reconcile"), events)
        reconciled.complete(Unit)
        runCurrent()
        assertEquals(listOf("migration", "reconcile", "timestamp", "refresh"), events)
        assertTrue(notified.isEmpty())
        refreshed.complete(Unit)
        runCurrent()
        assertEquals(listOf("migration", "reconcile", "timestamp", "refresh", "reports"), events)
        assertEquals(listOf(first), notified)
        reports.value = null
        runCurrent()
        assertEquals(listOf(first), notified)
        reports.value = second
        runCurrent()
        assertEquals(listOf(first, second), notified)
        assertTrue(startup.isActive)
        startup.cancelAndJoin()
    }

    @Test fun catchUpFailureDoesNotPreventNotificationCollection() = runTest {
        val report = InsightReport(3L, emptyList(), null)
        val reports = MutableStateFlow<InsightReport?>(report)
        val notified = mutableListOf<InsightReport>()
        val recorded = mutableListOf<DiagnosticCode>()
        val startup = launch {
            startInsightNotifications(
                awaitMigrated = {},
                catchUp = { throw IllegalStateException("catch-up failed") },
                reports = { reports },
                maybeNotify = { notified += it },
                onFailure = { recorded += it },
            )
        }
        runCurrent()

        assertEquals(listOf(report), notified)
        assertEquals(listOf(DiagnosticCode.APP_SCOPE_FAILED), recorded)
        startup.cancelAndJoin()
    }

    @Test fun catchUpCancellationIsRethrownWithoutRecordingFailure() = runTest {
        val recorded = mutableListOf<DiagnosticCode>()
        val startup = launch {
            startInsightNotifications(
                awaitMigrated = {},
                catchUp = { throw kotlinx.coroutines.CancellationException("cancelled") },
                reports = { MutableStateFlow<InsightReport?>(null) },
                maybeNotify = { fail("Cancelled startup must not notify") },
                onFailure = { recorded += it },
            )
        }
        runCurrent()

        assertTrue(startup.isCancelled)
        assertTrue(recorded.isEmpty())
    }

    @Test fun notificationCollectionFailureRecordsAppScopeFailureAndKeepsSiblingsAlive() = runTest {
        val recorded = mutableListOf<DiagnosticCode>()
        val scope = createAppScope { recorded += it }
        val dispatcher = StandardTestDispatcher(testScheduler)
        try {
            val collector = scope.launch(dispatcher) {
                startInsightNotifications(
                    awaitMigrated = {},
                    catchUp = {},
                    reports = { flow { throw IllegalStateException("report collection failed") } },
                    maybeNotify = { fail("Failing source must not notify") },
                    onFailure = { fail("Only catch-up failures should be recorded here") },
                )
            }
            runCurrent()
            collector.join()
            assertTrue(collector.isCancelled)
            assertEquals(listOf(DiagnosticCode.APP_SCOPE_FAILED), recorded)
            assertTrue(scope.isActive)
            var siblingRan = false
            val sibling = scope.launch(dispatcher) { siblingRan = true }
            runCurrent()
            sibling.join()
            assertTrue(siblingRan)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun nowAndAppDetailsDefinitionsRequireRealInsightsSource() {
        val application = koinApplication {}
        try {
            // Invoke the actual production definitions without an InsightRepository. This must fail
            // at that dependency, rather than fall back to flowOf(null) or require Android setup first.
            for (type in listOf(NowRepository::class, AppDetailsRepository::class, InsightsRepository::class)) {
                try {
                    definition(type).definition.invoke(application.koin.scopeRegistry.rootScope, parametersOf())
                    fail("$type must require the shared InsightRepository")
                } catch (e: NoDefinitionFoundException) {
                    assertTrue(e.message.orEmpty().contains(InsightRepository::class.qualifiedName.orEmpty()))
                }
            }
        } finally {
            application.close()
        }
    }

    @Test fun startupReconcilesBeforeReadingTimestampAndRefreshing() = runTest {
        val events = mutableListOf<String>()
        val reconciled = CompletableDeferred<Unit>()
        val startup = launch {
            reconcileAndCatchUpInsights(
                reconcile = { events += "reconcile"; reconciled.await() },
                lastAnalyzedAt = { events += "timestamp"; null },
                refresh = { events += "refresh" },
                clock = { 30_000_000L },
            )
        }
        runCurrent()
        assertEquals(listOf("reconcile"), events)
        reconciled.complete(Unit)
        startup.join()
        assertEquals(listOf("reconcile", "timestamp", "refresh"), events)
    }

    @Test fun catchUpRefreshesMissingOldOrFutureTimestamps() = runTest {
        val now = 30_000_000L
        val sixHours = 6 * 60 * 60 * 1_000L
        for ((timestamp, expected) in listOf(null to 1, now - sixHours - 1 to 1,
            now - sixHours to 0, now - 1 to 0, now to 0, now + 1 to 1)) {
            var reconciliations = 0
            var refreshes = 0
            reconcileAndCatchUpInsights(
                { reconciliations++ }, { timestamp }, { refreshes++ }, { now },
            )
            assertEquals(1, reconciliations)
            assertEquals("timestamp=$timestamp", expected, refreshes)
        }
    }

    @Test fun refreshFailureIsContainedAndCollectorContinues() = runTest {
        val finalized = MutableSharedFlow<String>()
        var refreshes = 0
        val recorded = mutableListOf<DiagnosticCode>()
        val collector = launch {
            refreshOnFinalizedSessions(finalized, { recorded += it }) {
                refreshes++
                if (refreshes == 1) throw IllegalStateException("refresh failed")
            }
        }
        runCurrent()
        finalized.emit("a")
        runCurrent()
        finalized.emit("b")
        runCurrent()
        assertEquals(2, refreshes)
        assertTrue(collector.isActive)
        assertEquals(listOf(DiagnosticCode.APP_SCOPE_FAILED), recorded)
        collector.cancelAndJoin()
    }

    @Test fun refreshCancellationStopsCollectorWithoutRecordingFailure() = runTest {
        val finalized = MutableSharedFlow<String>()
        val recorded = mutableListOf<DiagnosticCode>()
        val collector = launch {
            refreshOnFinalizedSessions(finalized, { recorded += it }) {
                throw kotlinx.coroutines.CancellationException("service stopped")
            }
        }
        runCurrent()
        finalized.emit("a")
        runCurrent()
        collector.join()
        assertTrue(collector.isCancelled)
        assertTrue(recorded.isEmpty())
    }

    @Test fun finalizedSessionBurstsCoalesceWithoutConcurrentOrCancelledRefreshes() = runTest {
        val finalized = MutableSharedFlow<String>()
        val firstRefresh = CompletableDeferred<Unit>()
        var refreshes = 0
        var active = 0
        var maxActive = 0
        val collector = launch {
            refreshOnFinalizedSessions(finalized, { fail("Successful refresh must not record a failure") }) {
                refreshes++
                active++
                maxActive = maxOf(maxActive, active)
                if (refreshes == 1) firstRefresh.await()
                active--
            }
        }
        runCurrent()
        finalized.emit("first")
        runCurrent()
        finalized.emit("second")
        finalized.emit("third")
        finalized.emit("fourth")
        runCurrent()
        assertEquals(1, refreshes)
        assertEquals(1, active)
        firstRefresh.complete(Unit)
        runCurrent()
        assertEquals(2, refreshes)
        assertEquals(1, maxActive)
        assertEquals(0, active)
        collector.cancelAndJoin()
        finalized.emit("after stop")
        runCurrent()
        assertEquals(2, refreshes)
    }
}
