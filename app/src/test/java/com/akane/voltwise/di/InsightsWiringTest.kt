package com.akane.voltwise.di

import com.akane.voltwise.battery.reconcileAndCatchUpInsights
import com.akane.voltwise.battery.service.refreshOnFinalizedSessions
import com.akane.voltwise.battery.data.db.InsightDao
import com.akane.voltwise.battery.insights.InsightRepository
import com.akane.voltwise.battery.insights.actions.ActionExecutor
import com.akane.voltwise.battery.insights.actions.InsightActionRepository
import com.akane.voltwise.battery.insights.actions.PackageManagerTargetInspector
import com.akane.voltwise.battery.insights.actions.ShellRunnerActionExecutor
import com.akane.voltwise.battery.insights.actions.TargetInspector
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
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsWiringTest {
    private fun definition(type: KClass<*>) = appModule.mappings.values
        .map { it.beanDefinition }.distinct().single { it.primaryType == type }

    @Test fun applicationScopeAndJournalAuthoritiesAreSingletons() = runTest {
        for (type in listOf(CoroutineScope::class, InsightDao::class, InsightRepository::class,
            InsightActionRepository::class, InsightsRepository::class)) {
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

    @Test fun catchUpRefreshesOnlyMissingOrOlderThanSixHours() = runTest {
        val now = 30_000_000L
        val sixHours = 6 * 60 * 60 * 1_000L
        for ((timestamp, expected) in listOf(null to 1, now - sixHours - 1 to 1,
            now - sixHours to 0, now - 1 to 0, now + 1 to 0)) {
            var reconciliations = 0
            var refreshes = 0
            reconcileAndCatchUpInsights(
                { reconciliations++ }, { timestamp }, { refreshes++ }, { now },
            )
            assertEquals(1, reconciliations)
            assertEquals("timestamp=$timestamp", expected, refreshes)
        }
    }

    @Test fun finalizedSessionBurstsCoalesceWithoutConcurrentOrCancelledRefreshes() = runTest {
        val finalized = MutableSharedFlow<String>()
        val firstRefresh = CompletableDeferred<Unit>()
        var refreshes = 0
        var active = 0
        var maxActive = 0
        val collector = launch {
            refreshOnFinalizedSessions(finalized) {
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
