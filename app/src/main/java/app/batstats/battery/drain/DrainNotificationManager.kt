package app.batstats.battery.drain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import app.batstats.R
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.measurement.EtaHold
import app.batstats.battery.measurement.HealthSummary
import app.batstats.battery.util.UpdateGate
import app.batstats.ui.navigation.Destinations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import android.text.format.DateFormat as AndroidDateFormat

internal fun <T> promotionNotification(lastPosted: T?, fallback: () -> T): T = lastPosted ?: fallback()

/** Stop and Reset require unlock on API 31+; older Android versions have no equivalent action protection. */
internal fun actionsRequireAuth(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

/**
 * The ongoing monitoring notification: custom collapsed and expanded views in the system's decorated template,
 * Stop and Reset actions, a tap that opens Now, and a status-bar icon that can spell a live value. The service
 * owns its lifetime; [run] keeps it current.
 */
class DrainNotificationManager(private val context: Context, private val repository: BatteryRepository) {
    companion object {
        const val CHANNEL_ID = "drain_stats_channel"
        const val NOTIFICATION_ID = 2001
        private const val LEGACY_MONITOR_CHANNEL_ID = "battery_monitor"
        private const val TAG = "DrainNotification"
        private const val REQUEST_RESET = 1
        private const val REQUEST_STOP = 2

        /** The expanded grid's label/value views, row by row ([NotificationContent.cells] order). */
        private val CELLS = listOf(
            R.id.notification_cell_1_label to R.id.notification_cell_1_value,
            R.id.notification_cell_2_label to R.id.notification_cell_2_value,
            R.id.notification_cell_3_label to R.id.notification_cell_3_value,
            R.id.notification_cell_4_label to R.id.notification_cell_4_value,
            R.id.notification_cell_5_label to R.id.notification_cell_5_value,
            R.id.notification_cell_6_label to R.id.notification_cell_6_value,
            R.id.notification_cell_7_label to R.id.notification_cell_7_value,
            R.id.notification_cell_8_label to R.id.notification_cell_8_value,
            R.id.notification_cell_9_label to R.id.notification_cell_9_value,
        )

        /** Also called by [app.batstats.battery.util.Notifier] so its boot prompt shares this channel. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel(LEGACY_MONITOR_CHANNEL_ID)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.monitor_channel_description)
                    setShowBadge(false); enableLights(false); enableVibration(false)
                }
            )
        }
    }

    private var lastPostedNotification: Notification? = null
    private val contentBuilder = NotificationContent.Builder(context)
    private val icons = StatusIconRenderer.of(context)
    init { ensureChannel(context) }

    /** The viewer's locale and the system's 12/24-hour clock, read per build so a change applies on the next update. */
    private fun formats(): NotificationContent.Formats {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val zone = TimeZone.getDefault()
        val skeleton = if (AndroidDateFormat.is24HourFormat(context)) "MMMdHm" else "MMMdhm"
        return NotificationContent.Formats(
            locale = locale,
            zone = zone,
            time = AndroidDateFormat.getTimeFormat(context).apply { timeZone = zone },
            dateTime = SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, skeleton), locale).apply { timeZone = zone },
        )
    }

    fun content(input: NotificationInput): NotificationContent = contentBuilder.build(input, formats())

    /** The first notification, for `startForeground`: the latest reading; [run] adds the session at once. */
    fun getNotification(): Notification = build(content(NotificationInput(EtaHold.next(EtaHold.Reading(), repository.realtimeFlow.value))))

    /** Re-promoting a running service must not replace the gated live content with startup defaults. */
    fun promotionNotification(): Notification = promotionNotification(lastPostedNotification, ::getNotification)

    fun post(notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        lastPostedNotification = notification
    }

    /**
     * Keeps the notification current until cancelled: the calibrated reading (with its held estimate), the open
     * DISCHARGE session, the status-icon and temperature settings and [issue]. Updates only with the screen on, on
     * changed content, ≥ 5 s apart; the screen turning on pushes at once ([UpdateGate]). [deliver] posts the
     * content and reports whether that worked.
     */
    suspend fun run(issue: Flow<NotificationIssue?>, deliver: (NotificationContent) -> Boolean) {
        val gate = UpdateGate<NotificationContent>()
        NotificationInputs.of(
            repository.realtimeFlow,
            repository.activeSessionFlow,
            repository.settingsFlow,
            issue,
            repository.sessionDao.filteredSessions(null, "", HealthSummary.SESSIONS),
        )
            .map { content(it.input) to it.screenOn }
            .collectLatest { (content, screenOn) ->
                if (!gate.awaitTurn(content, screenOn, SystemClock::uptimeMillis)) return@collectLatest
                val shown = deliver(content)
                // A failed update retries on the next change, still ≥5 s after this attempt.
                gate.pushed(content.takeIf { shown }, SystemClock.uptimeMillis())
            }
    }

    /**
     * [fitter] picks each slot's longest form that fits this device's shade at its font scale, and hides the rows a
     * font above the layouts' design scale leaves no room for ([NotificationFitter.rows]); tests pass their own.
     */
    fun build(content: NotificationContent, fitter: NotificationFitter = NotificationFitter(context)): Notification {
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, BatteryMainActivity::class.java).setAction("app.batstats.OPEN_DRAIN")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Destinations.EXTRA_DESTINATION, Destinations.NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.summary.firstOrNull() ?: content.headline.first())
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed(content, fitter))
            .setCustomBigContentView(expanded(content, fitter))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setWhen(0L).setShowWhen(false).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Action icons are not drawn by the Android 7+ templates.
            .addAction(action(context.getString(R.string.notification_action_stop), DrainNotificationReceiver.ACTION_STOP, REQUEST_STOP))
            .addAction(action(context.getString(R.string.notification_action_reset), DrainNotificationReceiver.ACTION_RESET, REQUEST_RESET))
        val icon = content.statusIcon?.let { text ->
            try {
                icons.icon(text)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Status icon not rendered (${e.javaClass.simpleName})")
                null
            }
        }
        if (icon != null) builder.setSmallIcon(icon) else builder.setSmallIcon(R.drawable.ic_stat_battery)
        return builder.build()
    }

    private fun action(title: String, action: String, requestCode: Int): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(context, requestCode,
            Intent(context, DrainNotificationReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, title, intent)
            .setAuthenticationRequired(actionsRequireAuth(Build.VERSION.SDK_INT))
            .build()
    }

    private fun collapsed(content: NotificationContent, fitter: NotificationFitter) =
        RemoteViews(context.packageName, R.layout.notification_collapsed).apply {
            setText(R.id.notification_headline, fitter.headline(content))
            setTextViewText(R.id.notification_level, content.level)
            setText(R.id.notification_summary, fitter.summary(content))
        }

    private fun expanded(content: NotificationContent, fitter: NotificationFitter) =
        RemoteViews(context.packageName, R.layout.notification_expanded).apply {
            setText(R.id.notification_state, fitter.state(content))
            setTextViewText(R.id.notification_level, content.level)
            CELLS.zip(content.cells).forEach { (ids, cell) ->
                setTextViewText(ids.first, cell.label)
                // The unit is drawn smaller than the number, as on Now.
                setTextViewText(ids.second, fitter.value(cell))
            }
            setText(R.id.notification_footer, fitter.footer(content))
            setText(R.id.notification_issue, fitter.issue(content))
        }

    /** Shows [text], or hides the view when there is none. */
    private fun RemoteViews.setText(id: Int, text: CharSequence?) {
        setTextViewText(id, text ?: "")
        setViewVisibility(id, if (text == null) View.GONE else View.VISIBLE)
    }

    fun stopNotification() {
        lastPostedNotification = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
