package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.eligibility.AppWindows
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.insights.model.WindowBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWindowsTest {
    private val subject = Subject.App(UID, APP)

    @Test fun onlyClosedDischargeDeltaNotImportedHourWindowsAreEligible() {
        val valid = session(0)
        val invalid = listOf(
            valid.copy(kind = SessionKind.CHARGE), valid.copy(kind = SessionKind.PLUGGED),
            valid.copy(imported = true), valid.copy(appWindow = null),
            valid.copy(endMs = valid.startMs),
            valid.copy(appWindow = valid.appWindow!!.copy(basis = WindowBasis.ABSOLUTE)),
            valid.copy(appWindow = valid.appWindow!!.copy(basis = WindowBasis.WINDOW_RESET)),
            valid.copy(appWindow = valid.appWindow!!.copy(captureEndMs = valid.startMs + HOUR - 1)),
            valid.copy(appWindow = valid.appWindow!!.copy(captureEndMs = valid.endMs + HOUR)),
            valid.copy(appWindow = valid.appWindow!!.copy(captureStartMs = valid.startMs + HOUR, captureEndMs = valid.endMs + HOUR)),
        )
        for (candidate in invalid) assertTrue(AppWindows.select(inputs(listOf(candidate), emptyList())).isEmpty())
        assertEquals(1, AppWindows.select(inputs(listOf(valid), emptyList())).size)
        assertTrue(AppWindows.select(inputs(listOf(valid), emptyList()).copy(nowMs = valid.endMs - 1)).isEmpty())
    }

    @Test fun durationToleranceIncludesBoundaryButNotMore() {
        val valid = session(0, 2 * HOUR)
        val allowed = valid.copy(appWindow = valid.appWindow!!.copy(captureEndMs = valid.endMs - HOUR / 5))
        assertEquals(1, AppWindows.select(inputs(listOf(allowed), emptyList())).size)
        val rejected = allowed.copy(appWindow = allowed.appWindow!!.copy(captureEndMs = allowed.appWindow!!.captureEndMs - 1))
        assertTrue(AppWindows.select(inputs(listOf(rejected), emptyList())).isEmpty())
    }

    @Test fun everyTotalUsesCaptureHoursAndSharesUseCaptureDuration() {
        val session = session(0, 2 * HOUR)
        val row = highRow(session.id)
        val window = AppWindows.select(inputs(listOf(session), listOf(row))).single()
        val expected = mapOf(
            Metric.POWER_MAH_PER_H to 20.0, Metric.WAKEUP_ALARMS_PER_H to 50.0,
            Metric.CPU_MS_PER_H to 150_000.0, Metric.JOBS_PER_H to 50.0, Metric.SYNCS_PER_H to 50.0,
            Metric.GPS_MS_PER_H to 500_000.0, Metric.RADIO_ACTIVE_MS_PER_H to 500_000.0,
            Metric.BG_TIME_SHARE to (2_000_000.0 / (2 * HOUR)),
            Metric.PARTIAL_WAKELOCK_BG_SHARE to (1_000_000.0 / (2 * HOUR)),
            Metric.FGS_TO_FOREGROUND_RATIO to 30.0,
        )
        for ((metric, value) in expected) assertEquals(metric.name, value, AppWindows.value(window, row, metric)!!, 1e-9)
        for (metric in Metric.entries) assertNull(AppWindows.value(window, row.unsupported(), metric))
    }

    @Test fun fullRowSetAbsenceCarriesMetricSpecificUpperBoundNotZero() {
        val session = session(0).let { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) }
        val others = listOf(
            row(session.id).copy(uid = 2, packageName = "example.two", powerMah = 8.0, wakeupAlarms = 80),
            row(session.id).copy(uid = 3, packageName = "example.three", powerMah = 12.0, wakeupAlarms = 4),
            row(session.id).copy(uid = -1, packageName = "", isOthers = true, powerMah = 0.0, wakeupAlarms = 0),
        )
        val window = AppWindows.select(inputs(listOf(session), others)).single()
        val power = AppWindows.point(window, subject, Metric.POWER_MAH_PER_H)!!
        assertNull(power.value)
        assertEquals(8.0, power.upperBound!!, 0.0)
        assertTrue(power.censored)
        assertFalse(power.present)
        assertEquals(4.0, AppWindows.point(window, subject, Metric.WAKEUP_ALARMS_PER_H)!!.upperBound!!, 0.0)
    }

    @Test fun powerCutoffUsesPowerLeadersNotAdditionalWakerRows() {
        val session = session(0, 2 * HOUR).let { it.copy(appWindow = it.appWindow!!.copy(fullRowSet = true)) }
        val leaders = (0 until 30).map { rank ->
            row(session.id).copy(uid = 20_000 + rank, packageName = "example.leader$rank", rank = rank,
                powerMah = 37.0 - rank, wakeupAlarms = 80)
        }
        val waker = row(session.id).copy(uid = 30_000, packageName = "example.waker", rank = 30,
            powerMah = 0.5, wakeupAlarms = 4)
        val others = row(session.id).copy(uid = -1, packageName = "", rank = 31, isOthers = true,
            powerMah = 0.0, wakeupAlarms = 0)
        // Storage order is rank-based, not the caller's list order.
        val window = AppWindows.select(inputs(listOf(session), listOf(waker, others) + leaders.reversed())).single()
        for (powerMah in listOf(0.5, 0.0)) {
            val withWaker = window.copy(rows = window.rows.map { if (it.uid == waker.uid) it.copy(powerMah = powerMah) else it })
            val power = AppWindows.point(withWaker, subject, Metric.POWER_MAH_PER_H)!!
            assertNull(power.value)
            assertEquals(4.0, power.upperBound!!, 0.0)
            assertTrue(power.censored)
            assertFalse(power.present)
        }
        assertEquals(2.0, AppWindows.point(window, subject, Metric.WAKEUP_ALARMS_PER_H)!!.upperBound!!, 0.0)
        val presentWaker = AppWindows.point(window, Subject.App(waker.uid, waker.packageName), Metric.POWER_MAH_PER_H)!!
        assertEquals(0.25, presentWaker.value!!, 0.0)
        assertTrue(presentWaker.present)
        assertFalse(presentWaker.censored)
        assertNull(AppWindows.point(window.copy(rows = listOf(waker, others)), subject, Metric.POWER_MAH_PER_H))
    }

    @Test fun nonFullAbsenceIsZeroButPresentNullAndUnsupportedSessionAreExcluded() {
        val session = session(0)
        val absent = row(session.id).copy(uid = 2, packageName = "example.other")
        val window = AppWindows.select(inputs(listOf(session), listOf(absent))).single()
        val point = AppWindows.point(window, subject, Metric.WAKEUP_ALARMS_PER_H)!!
        assertEquals(0.0, point.value!!, 0.0)
        assertFalse(point.censored)
        assertNull(AppWindows.point(window.copy(rows = listOf(absent.copy(wakeupAlarms = null))), subject, Metric.WAKEUP_ALARMS_PER_H))
        assertNull(AppWindows.point(window.copy(rows = listOf(row(session.id).copy(wakeupAlarms = null))), subject, Metric.WAKEUP_ALARMS_PER_H))
        assertNull(AppWindows.point(window.copy(rows = emptyList()), subject, Metric.WAKEUP_ALARMS_PER_H))
    }

    @Test fun changedUidDoesNotInheritAnotherInstallationsBaseline() {
        val session = session(0)
        val window = AppWindows.select(inputs(listOf(session), listOf(row(session.id).copy(uid = UID + 1)))).single()
        assertFalse(AppWindows.point(window, subject, Metric.POWER_MAH_PER_H)!!.present)
    }

    @Test fun totalDrainRequiresCoveredEnergyOrCapacityAndLevelDrop() {
        val session = session(0)
        val window = AppWindows.select(inputs(listOf(session), listOf(highRow(session.id)))).single()
        assertEquals(200.0, AppWindows.drainMah(window, null)!!, 0.0)
        val incomplete = window.copy(session = session.copy(screenOffCoveredMs = 0))
        assertNull(AppWindows.drainMah(incomplete, null))
        assertEquals(200.0, AppWindows.drainMah(incomplete, 4_000_000)!!, 0.0)
        assertNull(AppWindows.drainMah(incomplete.copy(session = incomplete.session.copy(endLevel = 90)), 4_000_000))
    }
}
