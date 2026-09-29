package app.batstats.battery.apps

import org.junit.Assert.*
import org.junit.Test

class AppUsageDeltaTest {
    private fun row(
        uid: Int,
        power: Double = 0.0,
        cpu: Long? = null,
        fg: Long? = null,
        bg: Long? = null,
        wakelock: Long? = null,
        mobile: Long? = null,
        wifi: Long? = null,
    ) = AppUsageRow(uid, "pkg$uid", power, cpu, fg, bg, wakelock, mobile, wifi)

    private fun snapshot(startedAt: Long?, startCount: Long?, vararg rows: AppUsageRow) =
        AppUsageSnapshot(startedAt, startCount, capturedAt = 0L, rows = rows.toList())

    @Test fun noBaselineIsAbsoluteAndUsesEndValues() {
        val end = snapshot(100L, 1L, row(1, power = 5.0, cpu = 100))
        val result = AppUsageDelta.compute(baseline = null, end = end)
        assertEquals(AppUsageBasis.ABSOLUTE, result.basis)
        assertEquals(listOf(row(1, power = 5.0, cpu = 100)), result.rows)
    }

    @Test fun sameWindowProducesClampedPerUidDelta() {
        val baseline = snapshot(100L, 1L, row(1, power = 2.0, cpu = 1000, mobile = 500))
        val end = snapshot(100L, 1L, row(1, power = 5.0, cpu = 1500, mobile = 400))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.DELTA, result.basis)
        val delta = result.rows.single()
        assertEquals(3.0, delta.powerMah, 0.0)
        assertEquals(500L, delta.cpuTimeMs)
        // Mobile bytes counter went down (rounding/reporting jitter); clamp to zero, never negative.
        assertEquals(0L, delta.mobileBytes)
    }

    @Test fun uidMissingFromBaselineCountsFromZero() {
        val baseline = snapshot(100L, 1L, row(1, power = 1.0))
        val end = snapshot(100L, 1L, row(1, power = 1.0), row(2, power = 3.0, cpu = 200))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.DELTA, result.basis)
        val newApp = result.rows.single { it.uid == 2 }
        assertEquals(3.0, newApp.powerMah, 0.0)
        assertEquals(200L, newApp.cpuTimeMs)
    }

    @Test fun nullFieldsStayNullWhenBothSidesAreNull() {
        val baseline = snapshot(100L, 1L, row(1, power = 1.0, wakelock = null))
        val end = snapshot(100L, 1L, row(1, power = 2.0, wakelock = null))
        val result = AppUsageDelta.compute(baseline, end)
        assertNull(result.rows.single().wakelockTimeMs)
    }

    @Test fun oneSidedNullFieldIsTreatedAsZeroNotDropped() {
        val baseline = snapshot(100L, 1L, row(1, power = 1.0, wakelock = null))
        val end = snapshot(100L, 1L, row(1, power = 1.0, wakelock = 500))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(500L, result.rows.single().wakelockTimeMs)
    }

    @Test fun windowMismatchIsAResetAndUsesEndValues() {
        val baseline = snapshot(100L, 1L, row(1, power = 9.0, cpu = 9000))
        val end = snapshot(200L, 1L, row(1, power = 1.0, cpu = 100))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.WINDOW_RESET, result.basis)
        assertEquals(1.0, result.rows.single().powerMah, 0.0)
        assertEquals(100L, result.rows.single().cpuTimeMs)
    }

    @Test fun sameWindowButDecreasedTotalIsAlsoAReset() {
        val baseline = snapshot(100L, 1L, row(1, power = 9.0))
        val end = snapshot(100L, 1L, row(1, power = 1.0))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.WINDOW_RESET, result.basis)
        assertEquals(1.0, result.rows.single().powerMah, 0.0)
    }

    @Test fun uninstalledAppCannotFakeAResetWhileOthersRise() {
        // uid 1 was heavy in the baseline but is gone from end (uninstalled between snapshots);
        // uid 2 genuinely rose. The naive whole-list total (9.0+1.0=10.0 -> 3.0) looks like a
        // decrease, but the total over uids common to both snapshots (uid 2 only: 1.0 -> 3.0) rose.
        val baseline = snapshot(100L, 1L, row(1, power = 9.0), row(2, power = 1.0))
        val end = snapshot(100L, 1L, row(2, power = 3.0))
        val result = AppUsageDelta.compute(baseline, end)
        assertEquals(AppUsageBasis.DELTA, result.basis)
        val delta = result.rows.single()
        assertEquals(2, delta.uid)
        assertEquals(2.0, delta.powerMah, 0.0)
    }

    @Test fun rowsWithNoUsageAreDropped() {
        val end = snapshot(100L, 1L, row(1, power = 0.0), row(2, power = 3.0))
        val result = AppUsageDelta.compute(null, end)
        assertEquals(listOf(2), result.rows.map { it.uid })
    }

    @Test fun topNKeepsHighestPowerAndFoldsRestIntoOthers() {
        val rows = (1..5).map { row(it, power = it.toDouble(), cpu = it * 10L) }
        val end = snapshot(100L, 1L, *rows.toTypedArray())
        val result = AppUsageDelta.compute(null, end, topN = 3)
        assertEquals(4, result.rows.size)
        assertEquals(listOf(5, 4, 3), result.rows.take(3).map { it.uid })
        val others = result.rows.last()
        assertTrue(others.isOthers)
        assertEquals(-1, others.uid)
        assertEquals("", others.packageName)
        assertEquals(3.0, others.powerMah, 0.0) // uid 2 (power 2) + uid 1 (power 1)
        assertEquals(30L, others.cpuTimeMs) // 20 + 10
    }

    @Test fun othersRowIsOmittedWhenNothingOverflowsTopN() {
        val end = snapshot(100L, 1L, row(1, power = 1.0), row(2, power = 2.0))
        val result = AppUsageDelta.compute(null, end, topN = 5)
        assertTrue(result.rows.none { it.isOthers })
    }
}
