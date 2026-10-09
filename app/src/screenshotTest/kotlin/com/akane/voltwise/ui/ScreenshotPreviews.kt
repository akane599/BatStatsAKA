package com.akane.voltwise.ui

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.akane.voltwise.ui.theme.MainTheme

// Preview names must not contain ".": the engine keeps only the text after the last one ("Font 1.5" -> "5_….png").
// The app is dark-only, so the night uiMode flag is repurposed as the OLED switch: "Oled" variants set it and
// ScreenshotTheme / MainTheme(oled = isSystemInDarkTheme()) read it.

/** Component variants: default (dark), OLED black, and 1.5× font. */
@Preview(name = "Default")
@Preview(name = "Oled", uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "LargeFont", fontScale = 1.5f)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class ComponentPreviews

/** Screen matrix: widths 400/610/900 dp × heights 400/500/1000 dp, plus OLED black and 1.5× font on a 400×500 phone. */
@Preview(name = "W400H400", widthDp = 400, heightDp = 400)
@Preview(name = "W400H500", widthDp = 400, heightDp = 500)
@Preview(name = "W400H1000", widthDp = 400, heightDp = 1000)
@Preview(name = "W610H400", widthDp = 610, heightDp = 400)
@Preview(name = "W610H500", widthDp = 610, heightDp = 500)
@Preview(name = "W610H1000", widthDp = 610, heightDp = 1000)
@Preview(name = "W900H400", widthDp = 900, heightDp = 400)
@Preview(name = "W900H500", widthDp = 900, heightDp = 500)
@Preview(name = "W900H1000", widthDp = 900, heightDp = 1000)
@Preview(
    name = "Oled",
    widthDp = 400,
    heightDp = 500,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Preview(name = "LargeFont", widthDp = 400, heightDp = 500, fontScale = 1.5f)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class ScreenPreviews

/** Single 400×500 phone frame, for alternative themes and secondary states (empty, loading, error). */
@Preview(name = "Phone", widthDp = 400, heightDp = 500)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class PhonePreview

/** Tall 400×1000 phone frame, for secondary states whose distinguishing content sits below a 500 dp fold. */
@Preview(name = "PhoneTall", widthDp = 400, heightDp = 1000)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
annotation class TallPhonePreview

/** App theme as the screenshot suite renders it; [oledBlack] (or an "Oled" preview variant) forces pure black. */
@Composable
fun ScreenshotTheme(oledBlack: Boolean = false, content: @Composable () -> Unit) {
    MainTheme(oled = oledBlack || isSystemInDarkTheme()) {
        Surface(content = content)
    }
}

/** Fixed instant (2025-10-09 09:20 UTC) so rendered dates never depend on when the suite runs. */
const val FIXED_TIME_MS = 1_760_001_600_000L
