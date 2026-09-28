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
import org.junit.Test
import org.junit.runner.RunWith

/** The ongoing notification as built (not posted): template, flags, actions, custom views, status icon, height. */
@RunWith(AndroidJUnit4::class)
class MonitoringNotificationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager by lazy { DrainNotificationManager(context, BatteryGraph.repo) }
    private val hour = 3_600_000L

    private val sample = BatterySample(timestamp = 1_060_000, levelPercent = 78, status = 3, plugged = 0,
        currentNowUa = -612_000, chargeCounterUah = 3_999_000, voltageMv = 3_900, temperatureDeciC = 312,
        health = 2, screenOn = true, etaMs = 18_600_000)
    private val session = ChargeSession(sessionId = "s", type = SessionType.DISCHARGE, startTime = 1_000_000, endTime = null,
        startLevel = 90, endLevel = null, deltaUah = 496_000, avgCurrentUa = null, estCapacityMah = null, observedMs = 3 * hour,
        counterCoveredMs = 3 * hour, screenOnMs = hour, screenOffMs = 2 * hour, screenOnUah = 420_000, screenOffUah = 76_000,
        cpuSuspendMs = 10_152_000)

    private fun input(icon: StatusIconValue = StatusIconValue.LEVEL, issue: NotificationIssue? = null) = NotificationInput(
        EtaHold.next(EtaHold.Reading(), BatteryRepository.Realtime(sample)), session, icon, fahrenheit = false, issue)

    /** Inflates [views] as SystemUI would, at [fontScale]; returns the view and its height in dp at [widthDp]. */
    private fun inflate(views: RemoteViews, fontScale: Float = 1f, widthDp: Int = CONTENT_WIDTH_DP): Pair<View, Float> {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        lateinit var result: Pair<View, Float>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = views.apply(scaled, FrameLayout(scaled))
            val density = scaled.resources.displayMetrics.density
            view.measure(MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
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
        assertEquals(content.summary, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val widgetIntent = PendingIntent.getActivity(context, 0, Intent(context, BatteryMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        assertNotEquals(widgetIntent, notification.contentIntent)
        // The startForeground notification uses the same template.
        assertEquals(R.layout.notification_collapsed, manager.getNotification().contentView.layoutId)
    }

    @Test fun customViewsShowTheContentAndTheIssueLineOnlyWhenNeeded() {
        val content = manager.content(input())
        val notification = manager.build(content)
        assertEquals(R.layout.notification_collapsed, notification.contentView.layoutId)
        assertEquals(R.layout.notification_expanded, notification.bigContentView.layoutId)
        val (collapsed, _) = inflate(notification.contentView)
        assertEquals(content.headline, collapsed.text(R.id.notification_headline))
        assertEquals(content.level, collapsed.text(R.id.notification_level))
        assertEquals(content.summary, collapsed.text(R.id.notification_summary))
        assertEquals(context.getString(R.string.notification_percent, "78"), content.level)

        val (expanded, _) = inflate(notification.bigContentView)
        assertEquals(content.state, expanded.text(R.id.notification_state))
        val cells = listOf(
            R.id.notification_cell_1_label to R.id.notification_cell_1_value, R.id.notification_cell_2_label to R.id.notification_cell_2_value,
            R.id.notification_cell_3_label to R.id.notification_cell_3_value, R.id.notification_cell_4_label to R.id.notification_cell_4_value,
            R.id.notification_cell_5_label to R.id.notification_cell_5_value, R.id.notification_cell_6_label to R.id.notification_cell_6_value,
            R.id.notification_cell_7_label to R.id.notification_cell_7_value, R.id.notification_cell_8_label to R.id.notification_cell_8_value,
            R.id.notification_cell_9_label to R.id.notification_cell_9_value,
        )
        assertEquals(content.cells.map { it.label to it.value }, cells.map { (label, value) -> expanded.text(label) to expanded.text(value) })
        assertEquals(content.footer, expanded.text(R.id.notification_footer))
        assertEquals(View.GONE, expanded.findViewById<View>(R.id.notification_issue).visibility)

        val failed = manager.content(input(issue = NotificationIssue.COLLECTION))
        val (withIssue, _) = inflate(manager.build(failed).bigContentView)
        assertEquals(View.VISIBLE, withIssue.findViewById<View>(R.id.notification_issue).visibility)
        assertEquals(context.getString(R.string.notification_issue_collection), withIssue.text(R.id.notification_issue))
        // Readings stay in place with an issue.
        assertEquals(content.cells, failed.cells)
    }

    @Test fun layoutsStayWithinTheSystemsCustomViewHeights() {
        val notification = manager.build(manager.content(input(issue = NotificationIssue.ADVANCED)))
        for (scale in listOf(1f, 1.3f)) {
            val (_, collapsed) = inflate(notification.contentView, scale)
            val (_, expanded) = inflate(notification.bigContentView, scale)
            assertTrue("Collapsed at ${scale}x is $collapsed dp", collapsed <= COLLAPSED_MAX_DP)
            assertTrue("Expanded at ${scale}x is $expanded dp", expanded < EXPANDED_MAX_DP)
        }
        val (_, normal) = inflate(notification.bigContentView)
        assertTrue("Expanded with the issue line at 1.0x is $normal dp (about 170 dp)", normal <= 190f)
    }

    @Test fun statusIconSpellsTheChosenValueOrFallsBackToTheStaticIcon() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 28) // Icon.getType
        val level = manager.build(manager.content(input(StatusIconValue.LEVEL))).smallIcon
        assertEquals(Icon.TYPE_BITMAP, level.type)
        val static = manager.build(manager.content(input(StatusIconValue.STATIC))).smallIcon
        assertEquals(Icon.TYPE_RESOURCE, static.type)
        assertEquals(R.drawable.ic_stat_battery, static.resId)
        val renderer = StatusIconRenderer.of(context)
        val bitmap = renderer.render("1240")
        val opaque = (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) ushr 24 > 0 } }
        assertTrue("Four glyphs must draw something", opaque > bitmap.width * bitmap.height / 20)
    }

    private companion object {
        /** The custom content width on a 360 dp phone: API 36's content margins are 52 dp (start) and 16 dp (end). */
        const val CONTENT_WIDTH_DP = 276
        /** "Collapsed view layouts are limited to as little as 48 dp … expanded view layouts … 252 dp" (Android docs). */
        const val COLLAPSED_MAX_DP = 48f
        const val EXPANDED_MAX_DP = 252f
    }
}
