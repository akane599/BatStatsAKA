package com.akane.voltwise.battery.drain

import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.StatusIconValue
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The pure part of DrainNotificationManager.run: what feeds the content and the update gate. */
class NotificationInputsTest {
    private fun sample(timestamp: Long = 1_000_000, screenOn: Boolean = true, etaMs: Long? = null) = BatterySample(
        timestamp = timestamp, levelPercent = 70, status = 3, plugged = 0, currentNowUa = -500_000, chargeCounterUah = null,
        voltageMv = 3_900, temperatureDeciC = 300, health = 2, screenOn = screenOn, etaMs = etaMs)

    private fun session(type: SessionType) = ChargeSession(sessionId = type.name, type = type, startTime = 0, endTime = null,
        startLevel = 90, endLevel = null, deltaUah = null, avgCurrentUa = null, estCapacityMah = null)

    private fun updates(
        realtime: List<BatteryRepository.Realtime>,
        session: ChargeSession? = null,
        settings: List<AppSettings> = listOf(AppSettings()),
        issue: NotificationIssue? = null,
    ) = NotificationInputs.of(flowOf(*realtime.toTypedArray()), flowOf(session), flowOf(*settings.toTypedArray()), flowOf(issue))

    @Test fun fullCapacityUsesLatestCounterBeforeStoredHealthEstimate() = runTest {
        val realtime = MutableStateFlow(BatteryRepository.Realtime(sample().copy(chargeCounterUah = 2_800_000)))
        val stored = MutableStateFlow(listOf(
            session(SessionType.CHARGE).copy(capacityEstimateMah = 5_000, capacityConfidence = "HIGH"),
            session(SessionType.DISCHARGE).copy(capacityEstimateMah = 3_000, capacityConfidence = "LOW"),
            session(SessionType.UNKNOWN).copy(capacityEstimateMah = 9_000, capacityConfidence = "unknown"),
        ))
        val inputs = NotificationInputs.of(realtime, flowOf(null), flowOf(AppSettings()), flowOf(null), stored)
        assertEquals(4_000_000L, inputs.first().input.fullUah)
        realtime.value = BatteryRepository.Realtime(sample().copy(chargeCounterUah = 400_000, levelPercent = 9))
        assertEquals("Low level uses the confidence-weighted stored median", 5_000_000L, inputs.first().input.fullUah)
        realtime.value = BatteryRepository.Realtime(sample().copy(chargeCounterUah = 10_000))
        assertEquals(5_000_000L, inputs.first().input.fullUah)
        stored.value = emptyList()
        assertNull(inputs.first().input.fullUah)
    }

    @Test fun failedRecentSessionsStillEmitLiveCounterUpdates() = runTest {
        val realtime = MutableStateFlow(BatteryRepository.Realtime(sample().copy(chargeCounterUah = 2_800_000)))
        val all = mutableListOf<NotificationInputs.Update>()
        backgroundScope.launch {
            NotificationInputs.of(realtime, flowOf(null), flowOf(AppSettings()), flowOf(NotificationIssue.COLLECTION),
                flow { throw IOException("Room query failed") }).toList(all)
        }
        runCurrent()
        assertEquals("Failed history uses the live counter", 4_000_000L, all.last().input.fullUah)
        assertEquals("History writer's collection issue is preserved", NotificationIssue.COLLECTION, all.last().input.issue)
        assertNull(all.last().input.session)
        realtime.value = BatteryRepository.Realtime(sample(timestamp = 1_030_000).copy(chargeCounterUah = 2_100_000))
        runCurrent()
        assertEquals("Live readings continue after the query fails", 3_000_000L, all.last().input.fullUah)
    }

    @Test fun failedActiveSessionStillEmitsLiveCounterUpdates() = runTest {
        val realtime = MutableStateFlow(BatteryRepository.Realtime(sample().copy(chargeCounterUah = 2_800_000)))
        val all = mutableListOf<NotificationInputs.Update>()
        backgroundScope.launch {
            NotificationInputs.of(realtime, flow { throw IOException("Room query failed") },
                flowOf(AppSettings()), flowOf(null)).toList(all)
        }
        runCurrent()
        assertNull("Failed active query falls back to no session", all.last().input.session)
        assertEquals(4_000_000L, all.last().input.fullUah)
        realtime.value = BatteryRepository.Realtime(sample(timestamp = 1_030_000).copy(chargeCounterUah = 2_100_000))
        runCurrent()
        assertEquals("Live readings continue after the query fails", 3_000_000L, all.last().input.fullUah)
    }

    @Test fun queryFailuresClearStaleHistoryAndRecoverAfterBackoff() = runTest {
        val open = session(SessionType.DISCHARGE)
        val stored = listOf(session(SessionType.CHARGE).copy(capacityEstimateMah = 5_000, capacityConfidence = "HIGH"))
        var activeAttempts = 0
        var recentAttempts = 0
        val all = mutableListOf<NotificationInputs.Update>()
        backgroundScope.launch {
            NotificationInputs.of(flowOf(BatteryRepository.Realtime(sample())), flow {
                activeAttempts++
                emit(open)
                if (activeAttempts == 1) throw IOException("Room active query failed")
            }, flowOf(AppSettings()), flowOf(null), flow {
                recentAttempts++
                emit(stored)
                if (recentAttempts == 1) throw IOException("Room recent query failed")
            }).toList(all)
        }
        runCurrent()
        assertNull("Query failure clears the stale session", all.last().input.session)
        assertNull("Query failure clears the stale stored capacity", all.last().input.fullUah)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals("Active query is not retried in a tight loop", 1, activeAttempts)
        assertEquals("Recent query is not retried in a tight loop", 1, recentAttempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, activeAttempts)
        assertEquals(2, recentAttempts)
        assertEquals("Recovered active query restores the session", open, all.last().input.session)
        assertEquals("Recovered recent query restores stored capacity", 5_000_000L, all.last().input.fullUah)
    }

    @Test fun cancellationFromEitherHistorySourcePropagates() = runTest {
        val cancellation = CancellationException("Monitoring stopped")
        val reading = flowOf(BatteryRepository.Realtime(sample()))
        for (inputs in listOf(
            NotificationInputs.of(reading, flow { throw cancellation }, flowOf(AppSettings()), flowOf(null)),
            NotificationInputs.of(reading, flowOf(null), flowOf(AppSettings()), flowOf(null), flow { throw cancellation }),
        )) {
            try {
                inputs.first()
                fail("History cancellation must not become fallback data")
            } catch (actual: CancellationException) {
                assertEquals(cancellation.message, actual.message)
            }
        }
    }

    @Test fun stoppingMonitoringCancelsPendingHistoryRetries() = runTest {
        var activeAttempts = 0
        var recentAttempts = 0
        val job = backgroundScope.launch {
            NotificationInputs.of(flowOf(BatteryRepository.Realtime(sample())), flow {
                activeAttempts++
                throw IOException("Room active query failed")
            }, flowOf(AppSettings()), flowOf(null), flow {
                recentAttempts++
                throw IOException("Room recent query failed")
            }).toList()
        }
        runCurrent()
        job.cancel()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("Stopping monitoring cancels the notification collector", job.isCancelled)
        assertEquals("Cancelled active query is not retried", 1, activeAttempts)
        assertEquals("Cancelled recent query is not retried", 1, recentAttempts)
    }

    @Test fun onlyAnOpenDischargeSessionReachesTheContent() = runTest {
        val reading = listOf(BatteryRepository.Realtime(sample()))
        assertEquals(session(SessionType.DISCHARGE), updates(reading, session(SessionType.DISCHARGE)).first().input.session)
        assertNull(updates(reading, session(SessionType.CHARGE)).first().input.session)
        assertNull(updates(reading, session(SessionType.PLUGGED)).first().input.session)
        assertNull(updates(reading, null).first().input.session)
    }

    @Test fun screenStateComesFromTheLatestCaptureAndCountsAsOnBeforeTheFirst() = runTest {
        assertFalse(updates(listOf(BatteryRepository.Realtime(sample(screenOn = false)))).first().screenOn)
        assertTrue(updates(listOf(BatteryRepository.Realtime(sample(screenOn = true)))).first().screenOn)
        assertTrue("No capture yet: the first content is pushed at once", updates(listOf(BatteryRepository.Realtime())).first().screenOn)
    }

    @Test fun estimateIsHeldAcrossCapturesWithoutOne() = runTest {
        val readings = listOf(
            BatteryRepository.Realtime(sample(timestamp = 1_000_000, etaMs = 3_600_000)),
            BatteryRepository.Realtime(sample(timestamp = 1_030_000)),
        )
        assertEquals(3_570_000L, updates(readings).toList().last().input.reading.remainingMs)
    }

    @Test fun onlyTheStatusIconAndTemperatureSettingsMatter() = runTest {
        val settings = listOf(
            AppSettings(statusIconValue = StatusIconValue.POWER_W, temperatureUnitIndex = 1),
            AppSettings(statusIconValue = StatusIconValue.POWER_W, temperatureUnitIndex = 1, lowBatteryThreshold = 5),
        )
        val all = updates(listOf(BatteryRepository.Realtime(sample())), settings = settings, issue = NotificationIssue.ADVANCED).toList()
        assertEquals("An unrelated setting does not re-emit", 1, all.size)
        with(all.single().input) {
            assertEquals(StatusIconValue.POWER_W, statusIcon)
            assertTrue(fahrenheit)
            assertEquals(NotificationIssue.ADVANCED, issue)
        }
    }
}
