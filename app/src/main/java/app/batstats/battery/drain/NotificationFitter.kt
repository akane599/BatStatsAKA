package app.batstats.battery.drain

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import app.batstats.R
import kotlin.math.roundToInt

/**
 * Picks, for each text slot of [NotificationContent], the longest form that fits its view unclipped: a reading is
 * never shown as "+1,2…". RemoteViews can't autosize reliably on API 26, so the text is measured here with the paints
 * the layouts use (the system notification text appearances at the current font scale, tabular figures) against
 * the width the shade gives custom content ([contentWidthDp]). If no form fits, the shortest is shown.
 */
class NotificationFitter(context: Context, contentWidthDp: Float = contentWidthDp(context)) {
    private val resources = context.resources
    private val density = resources.displayMetrics.density
    private val title = paint(context, R.style.TextAppearance_Compat_Notification_Title)
    private val line2 = paint(context, R.style.TextAppearance_Compat_Notification_Line2)
    private val contentPx = contentWidthDp * density
    private val cellPx = contentPx / COLUMNS - resources.getDimension(R.dimen.notification_cell_gap)
    private val levelGapPx = resources.getDimension(R.dimen.notification_level_gap)
    private val slackPx = SLACK_DP * density

    fun headline(content: NotificationContent): String? = pick(content.headline, title, leadPx(content.level))
    fun state(content: NotificationContent): String? = pick(content.state, title, leadPx(content.level))
    fun summary(content: NotificationContent): String? = pick(content.summary, line2, contentPx)
    fun footer(content: NotificationContent): String? = pick(content.footer, line2, contentPx)
    fun issue(content: NotificationContent): String? = pick(content.issue, line2, contentPx)
    fun value(cell: NotificationContent.Cell): CharSequence = pick(cell.values.map(::styled), title, cellPx) ?: NO_VALUE

    /** The width a first line has next to the level trailing it. */
    private fun leadPx(level: String) = contentPx - Layout.getDesiredWidth(level, title) - levelGapPx

    private fun <T : CharSequence> pick(forms: List<T>, paint: TextPaint, widthPx: Float): T? =
        forms.firstOrNull { Layout.getDesiredWidth(it, paint) <= widthPx - slackPx } ?: forms.lastOrNull()

    companion object {
        /** The unit beside a grid value, relative to its number (Now's `StatCellDefaults.UnitScale`). */
        const val UNIT_SCALE = 0.6f
        private const val COLUMNS = 3
        /** Rounding in the weighted grid and the measurement itself. */
        private const val SLACK_DP = 1f
        /** Wider shades (tablets, landscape split shade) still cap the notification column around this width. */
        private const val MAX_SHADE_DP = 412
        /** Card inset from the screen edges (both sides) and, on API 31+, the content's start/end margins (52 + 16). */
        private const val SHADE_MARGINS_DP = 32
        private const val CONTENT_MARGINS_DP = 68

        /** "−1,240" with " mA" at [UNIT_SCALE], as the grid shows it. */
        fun styled(quantity: Quantity): CharSequence {
            val unit = quantity.unit ?: return quantity.number
            return SpannableStringBuilder(quantity.number).append(' ')
                .append(unit, RelativeSizeSpan(UNIT_SCALE), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        /**
         * The custom content's width on this device, conservatively: the portrait width (capped), minus the card
         * inset and API 31+'s content margins (older decorated templates give more room). 360 dp → 260 dp.
         */
        fun contentWidthDp(context: Context): Float {
            val configuration = context.resources.configuration
            val screen = configuration.smallestScreenWidthDp.takeIf { it > 0 } ?: configuration.screenWidthDp
            return (minOf(screen, MAX_SHADE_DP) - SHADE_MARGINS_DP - CONTENT_MARGINS_DP).toFloat()
        }

        /** A paint for a text appearance as a TextView would apply it (size at the font scale, family, style). */
        @SuppressLint("ResourceType") // The indices are positions in the attribute array below, not styleable ids.
        private fun paint(context: Context, appearance: Int): TextPaint {
            // Sorted by attribute id, as obtainStyledAttributes requires.
            val attributes = context.obtainStyledAttributes(appearance,
                intArrayOf(android.R.attr.textSize, android.R.attr.textStyle, android.R.attr.fontFamily))
            try {
                return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    // Whole pixels, as TextView reads a text appearance's size: 12 sp at 2.625x is drawn at 32 px, not
                    // 31.5, and that rounding alone widens a full line 2 by a few characters.
                    textSize = attributes.getDimensionPixelSize(0, 0).takeIf { it > 0 }?.toFloat()
                        ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_TEXT_SP, context.resources.displayMetrics).roundToInt().toFloat()
                    typeface = Typeface.create(attributes.getString(2), attributes.getInt(1, Typeface.NORMAL))
                    // The layouts draw every value, line 2 and the footer with tabular figures.
                    fontFeatureSettings = "tnum"
                }
            } finally {
                attributes.recycle()
            }
        }

        private const val DEFAULT_TEXT_SP = 14f
    }
}
