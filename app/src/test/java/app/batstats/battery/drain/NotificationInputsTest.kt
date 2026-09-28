package app.batstats.battery.drain

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.settings.AppSettings
import app.batstats.settings.StatusIconValue
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
