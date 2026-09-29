package app.batstats.battery.drain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.roundToInt

/**
 * Draws [StatusIconText] (≤ 4 glyphs) into a square status-bar icon. SystemUI tints small icons and uses only their
 * alpha, so the glyphs are an opaque mask on transparency; the mask colour is irrelevant. Some OEM skins (e.g. MIUI)
 * ignore bitmap small icons and show the app icon; the static `ic_stat_battery` is the fallback everywhere else.
 */
class StatusIconRenderer(private val sizePx: Int) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(CONDENSED, Typeface.BOLD)
    }

    fun render(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val bounds = Rect()
        paint.textSize = sizePx.toFloat()
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            // One or two digits fill the height like a normal glyph; three or four are limited by the width.
            val scale = minOf(sizePx * WIDTH_FILL / bounds.width(), sizePx * HEIGHT_FILL / bounds.height())
            paint.textSize = sizePx * scale
            paint.getTextBounds(text, 0, text.length, bounds)
            Canvas(bitmap).drawText(text, sizePx / 2f - bounds.exactCenterX(), sizePx / 2f - bounds.exactCenterY(), paint)
        }
        return bitmap
    }

    fun icon(text: String): IconCompat = IconCompat.createWithBitmap(render(text))

    companion object {
        private const val CONDENSED = "sans-serif-condensed"
        private const val ICON_DP = 24
        private const val WIDTH_FILL = 0.98f
        private const val HEIGHT_FILL = 0.72f

        fun of(context: Context) = StatusIconRenderer((ICON_DP * context.resources.displayMetrics.density).roundToInt())
    }
}
