package app.batstats.battery.data

import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.apps.AppUsageStatus
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionAppUsage
import app.batstats.battery.data.db.SessionType
import org.junit.Assert.*
import org.junit.Test

class ImportUsageMergeTest {
    private fun session(end: Long = 2000) = ChargeSession(
        "session", SessionType.DISCHARGE, 1000, end, 60, 59, end - 1000, -1000, null,
        activeKey = null, observationId = "observation", lastSampleTime = end,
        observedMs = end - 1000, counterCoveredMs = end - 1000,
        screenOnMs = end - 1000, screenOnUah = end - 1000,
        appUsageStatus = AppUsageStatus.READY, appUsageBasis = AppUsageBasis.DELTA,
    )

    private fun rows(packageName: String) = listOf(
        SessionAppUsage("session", 0, 10_123, packageName, 1.5, basis = AppUsageBasis.DELTA),
    )

    /** In-memory writes driven by the same parent and usage plans consumed by importPayload. */
    private class History(var local: ChargeSession? = null) {
        var stored: ChargeSession? = null
        var usage = emptyList<SessionAppUsage>()
        var usageWrites = 0

        fun import(session: ChargeSession? = null, rows: List<SessionAppUsage>): HistoryImportResult {
            val parent = session?.let { HistoryPolicy.planSessionImport(stored, it) }
            if (parent != null) stored = parent.session
            val disposition = parent?.disposition
            val plan = HistoryPolicy.planUsageImport(
                disposition, local?.source, stored != null, usage, HistoryPolicy.appUsage(rows),
            )
            if (plan != null) {
                usage = plan.rows
                usageWrites++
            }
            return HistoryImportResult(
                0, disposition?.added ?: 0,
                (disposition?.updated ?: 0) + (plan?.updated ?: 0),
                (disposition?.unchanged ?: 0) + (plan?.unchanged ?: 0),
            )
        }
    }

    @Test fun newerAThenOlderBKeepsAAndReportsNoUsageChange() {
        val history = History()
        assertEquals(HistoryImportResult(0, 1, 0, 0), history.import(session(), rows("app.a")))
        val newer = history.stored
        val breakdownA = history.usage
        assertEquals(HistoryImportResult(0, 0, 0, 1), history.import(session(1500), rows("app.b")))
        assertEquals(newer, history.stored)
        assertEquals(2000L, history.stored?.endTime)
        assertEquals(breakdownA, history.usage)
        assertEquals(1, history.usageWrites)
    }

    @Test fun sameWindowBReplacesAAndCountsOneUpdatedSession() {
        val history = History()
        history.import(session(), rows("app.a"))
        val parent = history.stored
        assertEquals(HistoryImportResult(0, 0, 1, 0), history.import(session(), rows("app.b")))
        assertEquals(parent, history.stored)
        assertEquals(HistoryPolicy.appUsage(rows("app.b")), history.usage)
        assertEquals(2, history.usageWrites)
        assertEquals(HistoryImportResult(0, 0, 0, 1), history.import(session(), rows("app.b")))
        assertEquals(2, history.usageWrites)
    }

    @Test fun standaloneUsageStillAttachesToAnExistingImportedSession() {
        val history = History()
        history.stored = HistoryPolicy.session(session())
        assertEquals(HistoryImportResult(0, 0, 1, 0), history.import(rows = rows("app.b")))
        assertEquals(HistoryPolicy.appUsage(rows("app.b")), history.usage)
        assertEquals(1, history.usageWrites)
        assertEquals(HistoryImportResult(0, 0, 0, 0), history.import(rows = rows("app.b")))
        assertEquals(1, history.usageWrites)
    }

    @Test fun usageForALocalSessionIsIgnoredEvenWithoutAnImportedParent() {
        val history = History(local = session())
        val breakdownA = rows("app.a")
        history.usage = breakdownA
        assertEquals(HistoryImportResult(0, 0, 0, 0), history.import(rows = rows("app.b")))
        assertEquals(breakdownA, history.usage)
        assertEquals(0, history.usageWrites)
    }

    @Test fun advancingParentAndBreakdownCountsOnlyOneUpdate() {
        val history = History()
        history.import(session(1500), rows("app.a"))
        assertEquals(HistoryImportResult(0, 0, 1, 0), history.import(session(), rows("app.b")))
        assertEquals(2000L, history.stored?.endTime)
        assertEquals(HistoryPolicy.appUsage(rows("app.b")), history.usage)
    }

    @Test fun derivedParentUpdateAndBreakdownCountsOnlyOneUpdate() {
        val history = History()
        history.import(session(), rows("app.a"))
        val enriched = session().copy(capacityEstimateMah = 4800)
        assertEquals(HistoryImportResult(0, 0, 1, 0), history.import(enriched, rows("app.b")))
        assertEquals(4800, history.stored?.capacityEstimateMah)
        assertEquals(HistoryPolicy.appUsage(rows("app.b")), history.usage)
    }

    @Test fun unchangedBreakdownIsNotRewrittenAndIncomingRanksAreOrdered() {
        val history = History()
        val ordered = rows("app.a") + rows("app.b").single().copy(rank = 1)
        history.import(session(), ordered)
        assertEquals(HistoryImportResult(0, 0, 0, 1), history.import(session(), ordered.reversed()))
        assertEquals(HistoryPolicy.appUsage(ordered), history.usage)
        assertEquals(1, history.usageWrites)
    }

    @Test fun standaloneUsageWithoutAParentIsRejected() {
        val history = History()
        assertThrows(IllegalArgumentException::class.java) { history.import(rows = rows("app.b")) }
        assertEquals(emptyList<SessionAppUsage>(), history.usage)
        assertEquals(0, history.usageWrites)
    }

    @Test fun conflictingOriginAndSameWindowMeasurementsRemainRejected() {
        val previous = HistoryPolicy.session(session())
        for (incoming in listOf(session(1500).copy(observationId = "foreign"), session().copy(endLevel = 58))) {
            assertThrows(IllegalArgumentException::class.java) {
                HistoryPolicy.planSessionImport(previous, incoming)
            }
        }
    }
}
