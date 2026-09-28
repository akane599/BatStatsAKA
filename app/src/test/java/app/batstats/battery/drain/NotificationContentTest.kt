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
    private fun NotificationContent.cell(label: String) = cells.single { it.label == label }.values.map { it.toString() }
    private fun NotificationContent.value(label: String) = cell(label).first()

    @Test fun onBatteryShowsTheLiveHeadlineSessionGridAndFooter() {
        val content = build(NotificationInput(reading(sample(etaMs = 18_600_000)), session(), StatusIconValue.LEVEL))
        assertEquals("78% · On battery", content.title)
        assertEquals("78%", content.level)
        // "−0.612 A" is no shorter than "−612 mA", so it isn't offered.
        assertEquals(listOf("−612 mA · 2.4 W"), content.headline)
        assertEquals(
            listOf("On 420 mA · Off 38 mA · 5 h 10 min left", "On 420 mA · Off 38 mA · 5:10 h left", "On 420 mA · Off 38 mA", "5:10 h left"),
            content.summary,
        )
        assertEquals(listOf("On battery"), content.state)
        assertEquals(
            listOf("Current" to "−612 mA", "Power" to "2.4 W", "Temp" to "31.2 °C",
                "Voltage" to "3.90 V", "Screen on" to "420 mA", "Screen off" to "38 mA",
                "Deep sleep" to "94%", "Session" to "496 mAh", "Time left" to "5:10 h"),
            content.cells.map { it.label to it.values.first().toString() },
        )
        // The grid's number and unit stay apart, so the unit can be drawn smaller.
        assertEquals(Quantity("−612", "mA"), content.cells.first().values.first())
        // "Since 09:12" is no shorter than the range, so it isn't offered.
        assertEquals(listOf("Since 09:12 · updated 10:31", "09:12–10:31"), content.footer)
        assertTrue(content.issue.isEmpty())
        assertEquals("78", content.statusIcon)
    }

    @Test fun largeReadingsHaveACompactFormInTheLargerUnitNeverAClippedOne() {
        val big = sample(currentUa = -12_345_000, voltageMv = 4_480)
        val content = build(NotificationInput(reading(big), session(deltaUah = 12_345_000)))
        assertEquals(listOf("−12,345 mA", "−12.3 A"), content.cell("Current"))
        assertEquals(listOf("−12,345 mA · 55.3 W", "−12.3 A · 55.3 W"), content.headline)
        assertEquals(listOf("12,345 mAh", "12.3 Ah"), content.cell("Session"))
        // Small values keep only their own unit: the larger one would be no shorter.
        assertEquals(listOf("420 mA"), content.cell("Screen on"))
    }

    @Test fun everyShorterFormIsReallyShorter() {
        val inputs = listOf(
            NotificationInput(reading(sample(etaMs = 18_600_000)), session(start = at("2026-09-27T22:40:00Z")), issue = NotificationIssue.COLLECTION),
            NotificationInput(reading(sample(level = 80, status = 4, plugged = 4, currentUa = 0)), issue = NotificationIssue.ADVANCED),
            NotificationInput(reading(sample(status = 2, plugged = 1, currentUa = 1_240_000, etaMs = 4_800_000))),
        )
        inputs.map { build(it) }.forEach { content ->
            (listOf(content.headline, content.summary, content.state, content.footer, content.issue) +
                content.cells.map { cell -> cell.values.map { it.toString() } }).forEach { forms ->
                assertEquals("No repeated forms: $forms", forms.distinct(), forms)
                forms.zipWithNext().forEach { (longer, shorter) -> assertTrue("'$shorter' is not shorter than '$longer'", shorter.length < longer.length) }
            }
        }
    }

    @Test fun onBatteryWithoutAnEstimateOrSessionDataShowsDashesNotZeros() {
        val content = build(NotificationInput(reading(sample()), session(counterCoveredMs = 0, deltaUah = null)))
        assertEquals(listOf("On — · Off —"), content.summary)
        assertEquals(listOf("—"), content.cell("Screen on"))
        assertEquals(listOf("—"), content.cell("Session"))
        assertEquals(listOf("—"), content.cell("Time left"))
        // Deep sleep comes from the CPU clocks, not the counter.
        assertEquals("94%", content.value("Deep sleep"))
        assertNull("STATIC is the default icon", content.statusIcon)
    }

    @Test fun chargingShowsTimeToFullAndTemperatureOnlyAndIgnoresTheChargeSession() {
        val charging = sample(level = 64, status = 2, plugged = 1, currentUa = 1_240_000, voltageMv = 4_200,
            temperatureDeciC = 305, etaMs = 4_800_000)
        val content = build(NotificationInput(reading(charging), session(type = SessionType.CHARGE)))
        assertEquals("64% · Charging · AC charger", content.title)
        assertEquals("+1,240 mA · 5.2 W", content.headline.first())
        // Line 1 already shows W: line 2 is time to full and temperature only.
        assertEquals(listOf("1 h 20 min to full · 30.5 °C", "1:20 h to full · 30.5 °C"), content.summary)
        assertEquals(listOf("Charging · AC charger", "Charging"), content.state)
        assertEquals("1:20 h", content.value("To full"))
        listOf("Screen on", "Screen off", "Deep sleep", "Session").forEach { assertEquals(it, listOf("—"), content.cell(it)) }
        assertEquals(listOf("Updated 10:31"), content.footer)

        val noEstimate = build(NotificationInput(reading(charging.copy(etaMs = null))))
        assertEquals(listOf("Charging · 30.5 °C"), noEstimate.summary)
        assertEquals("—", noEstimate.value("To full"))
    }

    @Test fun pluggedInStatesNameThemselvesOnLineTwo() {
        val full = build(NotificationInput(reading(sample(level = 100, status = 5, plugged = 2, currentUa = 0))))
        assertEquals(listOf("Fully charged · USB", "Fully charged"), full.state)
        assertEquals(listOf("Fully charged · 31.2 °C"), full.summary)
        assertEquals("—", full.value("Time left"))
        val plugged = build(NotificationInput(reading(sample(level = 80, status = 4, plugged = 4, currentUa = 0))))
        assertEquals(listOf("Plugged in, not charging · Wireless", "Plugged in, not charging", "Not charging"), plugged.state)
        assertEquals(listOf("Plugged in, not charging · 31.2 °C", "Not charging · 31.2 °C"), plugged.summary)
    }

    @Test fun noReadingYetWaitsWithoutInventingValues() {
        val content = build(NotificationInput(EtaHold.Reading(), session(), StatusIconValue.LEVEL))
        assertEquals("Waiting for a battery reading", content.title)
        assertEquals(listOf("Waiting for a battery reading"), content.headline)
        assertEquals("—", content.level)
        assertTrue(content.summary.isEmpty())
        assertTrue(content.footer.isEmpty())
        assertNull(content.statusIcon)
        assertEquals(9, content.cells.size)
        listOf("Current", "Power", "Temp", "Voltage", "Time left").forEach { assertEquals(it, listOf("—"), content.cell(it)) }
    }

    @Test fun issueLineAppearsOnlyWhenNeededAndNeverCarriesRawErrors() {
        val input = NotificationInput(reading(sample()), session())
        assertTrue(build(input).issue.isEmpty())
        assertEquals(listOf("Some readings could not be collected. Open the app for details.", "Collection issue · open the app",
            "Collection issue"), build(input.copy(issue = NotificationIssue.COLLECTION)).issue)
        assertEquals(listOf("Advanced access is unavailable. Open the app for details.", "No advanced access · open the app",
            "No advanced access"), build(input.copy(issue = NotificationIssue.ADVANCED)).issue)
    }

    @Test fun sessionFromAnEarlierDayShowsItsDate() {
        val content = build(NotificationInput(reading(sample()), session(start = at("2026-09-27T22:40:00Z"))))
        assertEquals(listOf("Since Sep 27, 22:40 · updated 10:31", "Sep 27, 22:40–10:31"), content.footer)
        val twelveHour = builder.build(NotificationInput(reading(sample()), session(start = at("2026-09-27T22:40:00Z"))),
            NotificationContent.Formats(Locale.US, utc, SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = utc },
                SimpleDateFormat("MMM d, h:mm a", Locale.US).apply { timeZone = utc }))
        assertEquals(listOf("Since Sep 27, 10:40 PM · updated 10:31 AM", "Sep 27, 10:40 PM–10:31 AM", "Since Sep 27, 10:40 PM"),
            twelveHour.footer)
    }

    @Test fun fahrenheitAndLocaleApplyToValuesAndTheStatusIcon() {
        val input = NotificationInput(reading(sample()), session(), StatusIconValue.TEMPERATURE, fahrenheit = true)
        val us = build(input)
        assertEquals("88.2 °F", us.value("Temp"))
        assertEquals("88", us.statusIcon)
        val turkish = build(input.copy(statusIcon = StatusIconValue.POWER_W), Locale.forLanguageTag("tr-TR"))
        assertEquals("−612 mA · 2,4 W", turkish.headline.first())
        assertEquals("88,2 °F", turkish.value("Temp"))
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
        assertEquals("On 420 mA · Off 38 mA · 5 h 9 min left", content.summary.first())
        assertEquals(PowerState.DISCHARGING, capture.reading.powerState)
    }
}
