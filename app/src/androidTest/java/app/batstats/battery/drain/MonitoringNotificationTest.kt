package app.batstats.battery.drain

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.settings.StatusIconValue
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import android.text.format.DateFormat as AndroidDateFormat

/** The ongoing notification as built (not posted): template, flags, actions, custom views, status icon, fit, height. */
@RunWith(AndroidJUnit4::class)
class MonitoringNotificationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager by lazy { DrainNotificationManager(context, BatteryGraph.repo) }
    private val hour = 3_600_000L
    private val utc = TimeZone.getTimeZone("UTC")

    private val sample = BatterySample(timestamp = 1_060_000, levelPercent = 78, status = 3, plugged = 0,
        currentNowUa = -612_000, chargeCounterUah = 3_999_000, voltageMv = 3_900, temperatureDeciC = 312,
        health = 2, screenOn = true, etaMs = 18_600_000)
    private val session = ChargeSession(sessionId = "s", type = SessionType.DISCHARGE, startTime = 1_000_000, endTime = null,
        startLevel = 90, endLevel = null, deltaUah = 496_000, avgCurrentUa = null, estCapacityMah = null, observedMs = 3 * hour,
        counterCoveredMs = 3 * hour, screenOnMs = hour, screenOffMs = 2 * hour, screenOnUah = 420_000, screenOffUah = 76_000,
        cpuSuspendMs = 10_152_000)

    private fun input(icon: StatusIconValue = StatusIconValue.LEVEL, issue: NotificationIssue? = null) = NotificationInput(
        EtaHold.next(EtaHold.Reading(), BatteryRepository.Realtime(sample)), session, icon, fahrenheit = false, issue)

    /** [context] at [locale] and [fontScale], as SystemUI would inflate and size the views. */
    private fun configured(locale: Locale = Locale.US, fontScale: Float = 1f): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(locale)
            this.fontScale = fontScale
        })

    /** Inflates [views] in [inContext] at [widthDp] (the shade's custom-content width); returns it and its height in dp. */
    private fun inflate(views: RemoteViews, inContext: Context = configured(), widthDp: Float = CONTENT_WIDTH_DP): Pair<View, Float> {
        lateinit var result: Pair<View, Float>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = views.apply(inContext, FrameLayout(inContext))
            val density = inContext.resources.displayMetrics.density
            view.measure(MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            result = view to view.measuredHeight / density
        }
        return result
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test fun ongoingNotificationIsSilentStableDecoratedAndIndependentFromWidgetIntent() {
        val content = manager.content(input())
        val notification = manager.build(content)
        assertEquals(0L, notification.`when`)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(DrainNotificationManager.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.DecoratedCustomViewStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(listOf(context.getString(R.string.notification_action_stop), context.getString(R.string.notification_action_reset)),
            notification.actions.map { it.title.toString() })
        assertEquals(content.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(content.summary.first(), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val widgetIntent = PendingIntent.getActivity(context, 0, Intent(context, BatteryMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        assertNotEquals(widgetIntent, notification.contentIntent)
        // The startForeground notification uses the same template.
        assertEquals(R.layout.notification_collapsed, manager.getNotification().contentView.layoutId)
    }

    @Test fun customViewsShowTheContentAndTheIssueLineOnlyWhenNeeded() {
        val content = manager.content(input())
        val fitter = NotificationFitter(configured(), CONTENT_WIDTH_DP)
        val notification = manager.build(content, fitter)
        assertEquals(R.layout.notification_collapsed, notification.contentView.layoutId)
        assertEquals(R.layout.notification_expanded, notification.bigContentView.layoutId)
        val (collapsed, _) = inflate(notification.contentView)
        assertTrue(collapsed.text(R.id.notification_headline) in content.headline)
        assertEquals(content.level, collapsed.text(R.id.notification_level))
        assertTrue(collapsed.text(R.id.notification_summary) in content.summary)
        assertEquals(context.getString(R.string.notification_percent, "78"), content.level)

        val (expanded, _) = inflate(notification.bigContentView)
        assertTrue(expanded.text(R.id.notification_state) in content.state)
        CELLS.zip(content.cells).forEach { (ids, cell) ->
            assertEquals(cell.label, expanded.text(ids.first))
            assertTrue("${cell.label}: ${expanded.text(ids.second)}", expanded.text(ids.second) in cell.values.map { it.toString() })
        }
        assertTrue(expanded.text(R.id.notification_footer) in content.footer)
        assertEquals(View.GONE, expanded.findViewById<View>(R.id.notification_issue).visibility)

        val failed = manager.content(input(issue = NotificationIssue.COLLECTION))
        val (withIssue, _) = inflate(manager.build(failed, fitter).bigContentView)
        assertEquals(View.VISIBLE, withIssue.findViewById<View>(R.id.notification_issue).visibility)
        assertTrue(withIssue.text(R.id.notification_issue) in failed.issue)
        // Readings stay in place with an issue.
        assertEquals(content.cells, failed.cells)
    }

    /**
     * No reading is ever clipped. Worst cases, in en/es/tr at 1.0x and 1.3x font, a 12-hour clock, at 260 dp (a
     * 360 dp phone on API 31+): 5-digit currents and session charge, a 9,999 / 1,234 mA screen on/off drain,
     * 100% deep sleep, 140.9 °F, 4.48 V, 99 h 59 min left or to full, a session begun the day before, the longest
     * state ("Plugged in, not charging · Wireless") and both issue lines.
     */
    @Test fun worstCaseReadingsAreNeverClippedInAnyLocaleOrFontScale() {
        val failures = mutableListOf<String>()
        for (locale in listOf(Locale.US, Locale.forLanguageTag("es-ES"), Locale.forLanguageTag("tr-TR"))) {
            for (scale in listOf(1f, 1.3f)) {
                val localized = configured(locale, scale)
                val builder = NotificationContent.Builder(localized)
                val fitter = NotificationFitter(localized, CONTENT_WIDTH_DP)
                worstInputs().forEach { (case, input) ->
                    val notification = manager.build(builder.build(input, twelveHour(locale)), fitter)
                    val (collapsed, _) = inflate(notification.contentView, localized)
                    val (expanded, _) = inflate(notification.bigContentView, localized)
                    val views = listOf(collapsed to "headline" to R.id.notification_headline, collapsed to "level" to R.id.notification_level,
                        collapsed to "line 2" to R.id.notification_summary, expanded to "state" to R.id.notification_state,
                        expanded to "level" to R.id.notification_level, expanded to "footer" to R.id.notification_footer,
                        expanded to "issue" to R.id.notification_issue) +
                        CELLS.flatMapIndexed { index, (label, value) ->
                            listOf(expanded to "cell ${index + 1} value" to value, expanded to "cell ${index + 1} label" to label)
                        }
                    views.forEach { (where, id) ->
                        val (root, slot) = where
                        val view = root.findViewById<TextView>(id)
                        if (view.visibility != View.VISIBLE) return@forEach
                        val ellipsized = view.layout?.getEllipsisCount(0) ?: 0
                        if (ellipsized != 0) failures += "$locale ${scale}x $case $slot: '${view.text}' loses $ellipsized chars"
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun layoutsStayWithinTheSystemsCustomViewHeights() {
        for (scale in listOf(1f, 1.3f)) {
            val scaled = configured(fontScale = scale)
            val (_, input) = worstInputs().first()
            val notification = manager.build(NotificationContent.Builder(scaled).build(input, twelveHour(Locale.US)),
                NotificationFitter(scaled, CONTENT_WIDTH_DP))
            val (_, collapsed) = inflate(notification.contentView, scaled)
            val (_, expanded) = inflate(notification.bigContentView, scaled)
            assertTrue("Collapsed at ${scale}x is $collapsed dp", collapsed <= COLLAPSED_MAX_DP)
            assertTrue("Expanded with the issue line at ${scale}x is $expanded dp", expanded < EXPANDED_MAX_DP)
            if (scale == 1f) assertTrue("Expanded with the issue line at 1.0x is $expanded dp (about 170 dp)", expanded <= 190f)
        }
    }

    @Test fun statusIconSpellsTheChosenValueOrFallsBackToTheStaticIcon() {
        assumeTrue(Build.VERSION.SDK_INT >= 28) // Icon.getType
        val level = manager.build(manager.content(input(StatusIconValue.LEVEL))).smallIcon
        assertEquals(Icon.TYPE_BITMAP, level.type)
        val static = manager.build(manager.content(input(StatusIconValue.STATIC))).smallIcon
        assertEquals(Icon.TYPE_RESOURCE, static.type)
        assertEquals(R.drawable.ic_stat_battery, static.resId)
        val bitmap = StatusIconRenderer.of(context).render("1240")
        val opaque = (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) ushr 24 > 0 } }
        assertTrue("Four glyphs must draw something", opaque > bitmap.width * bitmap.height / 20)
    }

    /** A 12-hour clock (the longer times) in [locale]'s own patterns. */
    private fun twelveHour(locale: Locale) = NotificationContent.Formats(
        locale = locale,
        zone = utc,
        time = SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "hm"), locale).apply { timeZone = utc },
        dateTime = SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "MMMdhm"), locale).apply { timeZone = utc },
    )

    /** Worst-case inputs: on battery, plugged in without charging, charging with an estimate. */
    private fun worstInputs(): List<Pair<String, NotificationInput>> {
        val day = 86_400_000L
        val now = 20 * day + 10 * hour + 31 * 60_000 // 10:31 AM UTC
        val longestEta = 99 * hour + 59 * 60_000L
        fun reading(status: Int, plugged: Int, currentUa: Long, etaMs: Long?, level: Int = 100) = EtaHold.next(EtaHold.Reading(),
            BatteryRepository.Realtime(BatterySample(timestamp = now, levelPercent = level, status = status, plugged = plugged,
                currentNowUa = currentUa, chargeCounterUah = 3_000_000, voltageMv = 4_480, temperatureDeciC = 605, health = 2,
                screenOn = true, etaMs = etaMs)))
        val yesterday = ChargeSession(sessionId = "w", type = SessionType.DISCHARGE, startTime = now - 11 * hour - 51 * 60_000,
            endTime = null, startLevel = 100, endLevel = null, deltaUah = 12_345_000, avgCurrentUa = null, estCapacityMah = null,
            observedMs = 3 * hour, counterCoveredMs = 3 * hour, screenOnMs = hour, screenOffMs = 2 * hour,
            screenOnUah = 9_999_000, screenOffUah = 2_468_000, cpuSuspendMs = 3 * hour)
        return listOf(
            "on battery" to NotificationInput(reading(3, 0, -12_345_000, longestEta), yesterday, fahrenheit = true,
                issue = NotificationIssue.ADVANCED),
            // 99%: at 100% the state would be the shorter "Fully charged".
            "plugged in" to NotificationInput(reading(4, 4, 12_345_000, null, level = 99), fahrenheit = true,
                issue = NotificationIssue.COLLECTION),
            "charging" to NotificationInput(reading(2, 1, 12_345_000, longestEta), fahrenheit = true),
        )
    }

    private companion object {
        /** A 360 dp phone on API 31+ ([NotificationFitter.contentWidthDp]: − 2 × 16 dp card inset − 52 − 16 dp margins). */
        const val CONTENT_WIDTH_DP = 260f
        /** "Collapsed view layouts are limited to as little as 48 dp … expanded view layouts … 252 dp" (Android docs). */
        const val COLLAPSED_MAX_DP = 48f
        const val EXPANDED_MAX_DP = 252f
        val CELLS = listOf(
            R.id.notification_cell_1_label to R.id.notification_cell_1_value, R.id.notification_cell_2_label to R.id.notification_cell_2_value,
            R.id.notification_cell_3_label to R.id.notification_cell_3_value, R.id.notification_cell_4_label to R.id.notification_cell_4_value,
            R.id.notification_cell_5_label to R.id.notification_cell_5_value, R.id.notification_cell_6_label to R.id.notification_cell_6_value,
            R.id.notification_cell_7_label to R.id.notification_cell_7_value, R.id.notification_cell_8_label to R.id.notification_cell_8_value,
            R.id.notification_cell_9_label to R.id.notification_cell_9_value,
        )
    }
}
