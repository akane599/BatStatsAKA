package com.akane.voltwise.battery.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopAppsTest {
    private fun row(uid: Int, mah: Double) = AppUsageRow(uid = uid, packageName = "app.$uid", powerMah = mah)
    private fun dump(at: Long, vararg rows: AppUsageRow) = AppUsageSnapshot(100, 1, at, rows.toList())

    @Test fun sinceTheBaselineTheTopThreeWithSharesOfEveryApp() {
        val usage = dump(2_000, row(1, 130.0), row(2, 60.0), row(3, 8.0), row(4, 2.0))
        val top = TopApps.of(usage, baseline = dump(1_000, row(1, 6.0), row(2, 0.0)))!!
        assertEquals(AppUsageBasis.DELTA, top.basis)
        assertEquals(2_000L, top.capturedAt)
        assertEquals(listOf(1, 2, 3), top.rows.map { it.uid })
        assertEquals(listOf(124.0, 60.0, 8.0), top.rows.map { it.powerMah })
        assertEquals((124.0 / 194.0).toFloat(), top.rows.first().share, 1e-6f)
    }

    @Test fun aBaselineNewerThanTheDumpIsIgnored() {
        val usage = dump(2_000, row(1, 130.0))
        val top = TopApps.of(usage, baseline = dump(3_000, row(1, 100.0)))!!
        assertEquals(AppUsageBasis.ABSOLUTE, top.basis)
        assertEquals(130.0, top.rows.single().powerMah, 1e-9)
    }

    @Test fun theFoldedTailCountsTowardTheTotalButIsNeverARow() {
        val usage = dump(2_000, *Array(40) { row(it + 1, 40.0 - it) })
        val top = TopApps.of(usage, baseline = null, count = 3)!!
        assertEquals(listOf(1, 2, 3), top.rows.map { it.uid })
        val total = (1..40).sumOf { it.toDouble() }
        assertEquals((40.0 / total).toFloat(), top.rows.first().share, 1e-6f)
    }

    @Test fun nothingUsedPowerMeansNoTopApps() {
        assertNull(TopApps.of(dump(2_000), baseline = null))
        assertNull(TopApps.of(dump(2_000, row(1, 0.0)), baseline = null))
    }
}
