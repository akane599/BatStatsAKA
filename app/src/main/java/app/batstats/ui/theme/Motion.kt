package app.batstats.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * M3 standard motion at three durations. [short] answers a tap (toggle, press, color change); [medium] expands,
 * collapses and swaps content; [long] is reserved for the one orchestrated moment (level fill and trace draw-in on
 * first load). Compose animation APIs already honor the system "Remove animations" setting (duration scale 0 ends
 * them at once); a hand-rolled `withFrameNanos` loop would not, so don't write one.
 */
object BatMotion {
    const val SHORT_MS = 150
    const val MEDIUM_MS = 250
    const val LONG_MS = 400

    /** Elements that start and end on screen. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Elements entering the screen. */
    val StandardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** Elements leaving the screen. */
    val StandardAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    fun <T> short(easing: Easing = Standard): TweenSpec<T> = tween(SHORT_MS, easing = easing)

    fun <T> medium(easing: Easing = Standard): TweenSpec<T> = tween(MEDIUM_MS, easing = easing)

    fun <T> long(easing: Easing = Standard): TweenSpec<T> = tween(LONG_MS, easing = easing)
}
