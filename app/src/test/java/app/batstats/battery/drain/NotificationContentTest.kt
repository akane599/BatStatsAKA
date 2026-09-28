package app.batstats.battery.drain

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.PowerState
import app.batstats.settings.StatusIconValue
import app.batstats.support.EnglishStrings
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/** The notification's text (replaces the old MonitoringText.expanded tests). */
class NotificationContentTest {
    private val builder = NotificationContent.Builder(EnglishStrings::get)
    private val utc = TimeZone.getTimeZone("UTC")
    private fun formats(locale: Locale = Locale.US) = NotificationContent.Formats(
        locale = locale,
        zone = utc,
        time = SimpleDateFormat("HH:mm", locale).apply { timeZone = utc },
        dateTime = SimpleDateFormat("MMM d, HH:mm", locale).apply { timeZone = utc },
    )
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private val hour = 3_600_000L
    private val now = at("2026-09-28T10:31:00Z")

    private fun sample(
        level: Int? = 78, status: Int = 3, plugged: Int = 0, currentUa: Long? = -612_000, voltageMv: Int? = 3_900,
        temperatureDeciC: Int? = 312, etaMs: Long? = null, timestamp: Long = now,
    ) = BatterySample(timestamp = timestamp, levelPercent = level, status = status, plugged = plugged, currentNowUa = currentUa,
        chargeCounterUah = 3_000_000, voltageMv = voltageMv, temperatureDeciC = temperatureDeciC, health = 2, screenOn = true,
        etaMs = etaMs)

    private fun session(
        type: SessionType = SessionType.DISCHARGE, start: Long = at("2026-09-28T09:12:00Z"), deltaUah: Long? = 496_000,
        counterCoveredMs: Long = 3 * hour,
    ) = ChargeSession(sessionId = "s", type = type, startTime = start, endTime = null, startLevel = 90, endLevel = null,
        deltaUah = deltaUah, avgCurrentUa = null, estCapacityMah = null, observedMs = 3 * hour, counterCoveredMs = counterCoveredMs,
        screenOnMs = hour, screenOffMs = 2 * hour, screenOnUah = 420_000, screenOffUah = 76_000, cpuSuspendMs = 10_152_000)

    /** The reading as the service sees it: the capture, then the writer's copy with its estimate. */
    private fun reading(sample: BatterySample?) =
        EtaHold.next(EtaHold.Reading(), BatteryRepository.Realtime(sample))

    private fun build(input: NotificationInput, locale: Locale = Locale.US) = builder.build(input, formats(locale))
    private fun NotificationContent.cell(label: String) = cells.single { it.label == label }.value

    @Test fun onBatteryShowsTheLiveHeadlineSessionGridAndFooter() {
        val content = build(NotificationInput(reading(sample(etaMs = 18_600_000)), session(), StatusIconValue.LEVEL))
        assertEquals("78% · On battery", content.title)
        assertEquals("78%", content.level)
        assertEquals("−612 mA · 2.4 W", content.headline)
        assertEquals("On 420 mA · Off 38 mA · 5 h 10 min left", content.summary)
        assertEquals("On battery", content.state)
        assertEquals(
            listOf("Current" to "−612 mA", "Power" to "2.4 W", "Temp" to "31.2 °C",
                "Voltage" to "3.90 V", "Screen on" to "420 mA", "Screen off" to "38 mA",
                "Deep sleep" to "94%", "Session" to "496 mAh", "Time left" to "5:10 h"),
            content.cells.map { it.label to it.value },
        )
        assertEquals("Since 09:12 · updated 10:31", content.footer)
        assertNull(content.issue)
        assertEquals("78", content.statusIcon)
    }

    @Test fun onBatteryWithoutAnEstimateOrSessionDataShowsDashesNotZeros() {
        val content = build(NotificationInput(reading(sample()), session(counterCoveredMs = 0, deltaUah = null)))
        assertEquals("On — · Off —", content.summary)
        assertEquals("—", content.cell("Screen on"))
        assertEquals("—", content.cell("Session"))
        assertEquals("—", content.cell("Time left"))
        // Deep sleep comes from the CPU clocks, not the counter.
        assertEquals("94%", content.cell("Deep sleep"))
        assertNull("STATIC is the default icon", content.statusIcon)
    }

    @Test fun chargingShowsTimeToFullPowerAndTemperatureAndIgnoresTheChargeSession() {
        val charging = sample(level = 64, status = 2, plugged = 1, currentUa = 1_240_000, voltageMv = 4_200,
            temperatureDeciC = 305, etaMs = 4_800_000)
        val content = build(NotificationInput(reading(charging), session(type = SessionType.CHARGE)))
        assertEquals("64% · Charging · AC charger", content.title)
        assertEquals("+1,240 mA · 5.2 W", content.headline)
        assertEquals("1 h 20 min to full · 5.2 W · 30.5 °C", content.summary)
        assertEquals("Charging · AC charger", content.state)
        assertEquals("1:20 h", content.cell("To full"))
        listOf("Screen on", "Screen off", "Deep sleep", "Session").forEach { assertEquals(it, "—", content.cell(it)) }
        assertEquals("Updated 10:31", content.footer)

        val noEstimate = build(NotificationInput(reading(charging.copy(etaMs = null))))
        assertEquals("Charging · 5.2 W · 30.5 °C", noEstimate.summary)
        assertEquals("—", noEstimate.cell("To full"))
    }

    @Test fun pluggedInStatesNameThemselvesOnLineTwo() {
        val full = build(NotificationInput(reading(sample(level = 100, status = 5, plugged = 2, currentUa = 0))))
        assertEquals("Fully charged · USB", full.state)
        assertEquals("Fully charged · 0.00 W · 31.2 °C", full.summary)
        assertEquals("—", full.cell("Time left"))
        val plugged = build(NotificationInput(reading(sample(level = 80, status = 4, plugged = 4, currentUa = 0))))
        assertEquals("Plugged in, not charging · Wireless", plugged.state)
    }

    @Test fun noReadingYetWaitsWithoutInventingValues() {
        val content = build(NotificationInput(EtaHold.Reading(), session(), StatusIconValue.LEVEL))
        assertEquals("Waiting for a battery reading", content.title)
        assertEquals("Waiting for a battery reading", content.headline)
        assertEquals("—", content.level)
        assertNull(content.summary)
        assertNull(content.footer)
        assertNull(content.statusIcon)
        assertEquals(9, content.cells.size)
        assertTrue(content.cells.filter { it.label != "Deep sleep" && !it.label.startsWith("Screen") && it.label != "Session" }
            .all { it.value == "—" })
    }

    @Test fun issueLineAppearsOnlyWhenNeededAndNeverCarriesRawErrors() {
        val input = NotificationInput(reading(sample()), session())
        assertNull(build(input).issue)
        assertEquals("Some readings could not be collected. Open the app for details.",
            build(input.copy(issue = NotificationIssue.COLLECTION)).issue)
        assertEquals("Advanced access is unavailable. Open the app for details.",
            build(input.copy(issue = NotificationIssue.ADVANCED)).issue)
    }

    @Test fun sessionFromAnEarlierDayShowsItsDate() {
        val content = build(NotificationInput(reading(sample()), session(start = at("2026-09-27T22:40:00Z"))))
        assertEquals("Since Sep 27, 22:40 · updated 10:31", content.footer)
    }

    @Test fun fahrenheitAndLocaleApplyToValuesAndTheStatusIcon() {
        val input = NotificationInput(reading(sample()), session(), StatusIconValue.TEMPERATURE, fahrenheit = true)
        val us = build(input)
        assertEquals("88.2 °F", us.cell("Temp"))
        assertEquals("88", us.statusIcon)
        val turkish = build(input.copy(statusIcon = StatusIconValue.POWER_W), Locale.forLanguageTag("tr-TR"))
        assertEquals("−612 mA · 2,4 W", turkish.headline)
        assertEquals("88,2 °F", turkish.cell("Temp"))
        assertEquals("2,4", turkish.statusIcon)
    }

    @Test fun equalInputsGiveEqualContentSoTheGateSkipsThem() {
        val input = NotificationInput(reading(sample(etaMs = 18_600_000)), session(), StatusIconValue.CURRENT_MA)
        assertEquals(build(input), build(input))
        val next = NotificationInput(reading(sample(currentUa = -640_000, etaMs = 18_600_000)), session(), StatusIconValue.CURRENT_MA)
        assertNotEquals(build(input), build(next))
    }

    @Test fun heldEstimateCountsDownAcrossACaptureWithoutOne() {
        val first = reading(sample(etaMs = 18_600_000))
        val capture = EtaHold.next(first, BatteryRepository.Realtime(sample(timestamp = now + 30_000)))
        val content = build(NotificationInput(capture, session()))
        assertEquals("On 420 mA · Off 38 mA · 5 h 9 min left", content.summary)
        assertEquals(PowerState.DISCHARGING, capture.reading.powerState)
    }
}
