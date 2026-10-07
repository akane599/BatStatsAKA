package app.batstats.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.batstats.R
import app.batstats.ui.ComponentPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.theme.chartColors
import app.batstats.ui.theme.numericHeadline
import app.batstats.ui.theme.spacing
import com.android.tools.screenshot.PreviewTest

@Composable
private fun Frame(content: @Composable () -> Unit) {
    ScreenshotTheme {
        Column(
            Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) { content() }
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun PanelPreview() {
    Frame {
        Panel(
            title = "Since unplug",
            trailing = { InfoSheet("Since unplug", "Drain rates since the charger was last removed.") },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)) {
                StatCell("Screen on", "6.2", unit = "%/h", supporting = "412 mA")
                StatCell("Screen off", "0.8", unit = "%/h", supporting = "38 mA")
                StatCell("Deep sleep", "91", unit = "%")
            }
        }
        Panel(title = "Health", onClick = {}, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
            Text(
                "Tap target panel on the high tier with the hero radius.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun StatCellPreview() {
    val colors = MaterialTheme.chartColors
    Frame {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            StatCell("Current", "−412", Modifier.weight(1f), unit = "mA", indicator = colors.drain)
            StatCell("Power", "1.58", Modifier.weight(1f), unit = "W")
            StatCell("Temperature", "31.4", Modifier.weight(1f), unit = "°C")
            StatCell("Voltage", "3.84", Modifier.weight(1f), unit = "V")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            StatCell(
                "Time left",
                "7 h 40",
                Modifier.weight(1f),
                unit = "min",
                supporting = "At today's rate",
                valueStyle = MaterialTheme.typography.numericHeadline,
            )
            StatCell("Charging", "+1,250", Modifier.weight(1f), unit = "mA", indicator = colors.charge, valueStyle = MaterialTheme.typography.numericHeadline)
        }
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun AppRowPreview() {
    Frame {
        Panel(title = "Top apps", contentPadding = PaddingValues(vertical = MaterialTheme.spacing.md)) {
            Column {
                AppRow({ AppIcon("com.android.chrome", "Chrome") }, "Chrome", "312 mAh", 0.42f, onClick = {})
                AppRow({ AppIcon("com.google.android.youtube", "YouTube") }, "YouTube", "188 mAh", 0.25f, onClick = {})
                AppRow(
                    { AppIcon("org.example.long", "Ömer's extremely long application name for truncation") },
                    "Ömer's extremely long application name for truncation",
                    "12.4 mAh",
                    0.016f,
                    onClick = {},
                )
                AppRow({ AppIcon("7zip", "7-Zip") }, "7-Zip", "0.3 mAh", 0.0004f)
                AppRow({ AppIconPlaceholder("Other apps", icon = Icons.Rounded.MoreHoriz) }, "Other apps", "96 mAh", 0.13f)
            }
        }
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun EmptyStatePreview() {
    Frame {
        EmptyState(
            title = "No sessions yet",
            body = "A session is recorded each time you unplug or plug in the charger.",
            icon = Icons.Rounded.History,
            actionLabel = "Start monitoring",
            onAction = {},
        )
        EmptyState(title = "Nothing matches this search")
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun InfoSheetPreview() {
    Frame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Screen-off drain", style = MaterialTheme.typography.titleMedium)
            InfoSheet("Screen-off drain", "How fast the battery empties while the screen is off.")
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
            InfoSheetContent("Screen-off drain", Modifier.padding(top = MaterialTheme.spacing.lg)) {
                Text(
                    "How fast the battery empties while the screen is off, in percent per hour.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Deep sleep is the share of that time the phone spent suspended. Low values point to wakelocks.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 360 dp phone at 1× and 1.5× font: the Now readouts' narrowest case. */
@Preview(name = "W360", widthDp = 360)
@Preview(name = "W360LargeFont", widthDp = 360, fontScale = 1.5f)
annotation class NarrowPreviews

/**
 * The Now readouts, 4 across inside a panel (~65 dp cells on a 360 dp phone): values shrink in steps (≥70 %), then
 * units wrap under the value; nothing is cut. The last row is two equal-height panels (`IntrinsicSize.Max`), which
 * a subcomposing cell would crash.
 */
@PreviewTest
@NarrowPreviews
@Composable
fun StatCellNarrowPreview() {
    val colors = MaterialTheme.chartColors
    Frame {
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                StatCell("Current", "−412", Modifier.weight(1f), unit = "mA", indicator = colors.drain)
                StatCell("Power", "−1.61", Modifier.weight(1f), unit = "W")
                StatCell("Temp.", "31.5", Modifier.weight(1f), unit = "°C")
                StatCell("Voltage", "3.91", Modifier.weight(1f), unit = "V")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                StatCell("Current", "+12,480", Modifier.weight(1f), unit = "mA", indicator = colors.charge)
                StatCell("Power", "−48.25", Modifier.weight(1f), unit = "W")
                StatCell("Temp.", "−10.5", Modifier.weight(1f), unit = "°C")
                StatCell("Capacity", "12,345", Modifier.weight(1f), unit = "mAh")
            }
        }
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "Screen on") {
                StatCell("Drain", "6.2", unit = "%/h", supporting = "412 mA")
            }
            Panel(Modifier.weight(1f).fillMaxHeight(), title = "Screen off") {
                StatCell("Drain", "0.8", unit = "%/h")
            }
        }
    }
}

/**
 * Apps' summary rows (3 cells, no 2×2 fallback) in Spanish on a 360 dp phone at 2× font, ~88 dp cells: long labels
 * wrap (hyphenated) to a second line before ellipsizing, so "Pantalla encendida" and "Pantalla apagada" stay
 * distinct ("Panta-lla en…" / "Panta-lla ap…") instead of both reading "Panta…".
 */
@PreviewTest
@Preview(name = "W360Font2Es", widthDp = 360, fontScale = 2f, locale = "es")
@Composable
fun StatCellLargeFontLabelsPreview() {
    val hours = stringResource(R.string.now_unit_hours)
    Frame {
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                StatCell(stringResource(R.string.apps_summary_on_battery), "14:20", Modifier.weight(1f), unit = hours)
                StatCell(
                    stringResource(R.string.apps_summary_screen_on),
                    "2:05",
                    Modifier.weight(1f),
                    unit = hours,
                    supporting = stringResource(R.string.apps_summary_used, "27"),
                )
                StatCell(
                    stringResource(R.string.apps_summary_screen_off),
                    "12:15",
                    Modifier.weight(1f),
                    unit = hours,
                    supporting = stringResource(R.string.apps_summary_used, "8"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                StatCell(stringResource(R.string.apps_summary_deep_doze), "9:40", Modifier.weight(1f), unit = hours)
                StatCell(stringResource(R.string.apps_summary_light_doze), "1:10", Modifier.weight(1f), unit = hours)
                StatCell(stringResource(R.string.apps_summary_capacity), "4.812", Modifier.weight(1f), unit = "mAh")
            }
        }
    }
}

/** One Now readout row; the templates are the widest values each readout expects. */
@Composable
private fun LiveReadouts(current: String, power: String, temperature: String, voltage: String, charging: Boolean) {
    val colors = MaterialTheme.chartColors
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        StatCell(
            "Current",
            current,
            Modifier.weight(1f),
            unit = "mA",
            indicator = if (charging) colors.charge else colors.drain,
            sizingTemplate = "−8,888",
        )
        StatCell("Power", power, Modifier.weight(1f), unit = "W", sizingTemplate = "−88.88")
        StatCell("Temp.", temperature, Modifier.weight(1f), unit = "°C", sizingTemplate = "−88.8")
        StatCell("Voltage", voltage, Modifier.weight(1f), unit = "V", sizingTemplate = "8.88")
    }
}

/**
 * The same live readouts a few minutes apart (discharging, then fast charging past 10 W): with sizing templates each
 * cell keeps its size, unit position and height while digits come and go, so both panels match.
 */
@PreviewTest
@NarrowPreviews
@Composable
fun StatCellLivePreview() {
    Frame {
        Panel(title = "Discharging") {
            LiveReadouts("−412", "−1.61", "31.5", "3.91", charging = false)
        }
        Panel(title = "Charging") {
            LiveReadouts("+2,480", "+12.40", "38.0", "4.35", charging = true)
        }
    }
}

private fun fakeIcon(color: Color): ImageBitmap {
    val bitmap = ImageBitmap(96, 96)
    Canvas(bitmap).drawCircle(Offset(48f, 48f), 48f, Paint().apply { this.color = color })
    return bitmap
}

/**
 * A caching loader that knows one app: its icon replaces the placeholder from the first frame; unknown and failing
 * packages keep the placeholder.
 */
@PreviewTest
@ComponentPreviews
@Composable
fun AppIconLoadedPreview() {
    val icon = fakeIcon(MaterialTheme.colorScheme.tertiary)
    val loader = object : AppIconLoader {
        override suspend fun load(packageName: String): ImageBitmap? = cached(packageName)

        override fun cached(packageName: String): ImageBitmap? = when (packageName) {
            "com.android.chrome" -> icon
            "com.example.broken" -> error("PackageManager failed")
            else -> null
        }
    }
    CompositionLocalProvider(LocalAppIconLoader provides loader) {
        Frame {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                AppIcon("com.android.chrome", "Chrome")
                AppIcon("org.unknown", "Unknown")
                AppIcon("com.example.broken", "Broken")
            }
        }
    }
}

/** Range and mode choices: 4 short options (Now's trace), 2 options (History's Days | Sessions), 3 with none selected. */
@PreviewTest
@ComponentPreviews
@Composable
fun SegmentedTabsPreview() {
    Frame {
        Panel {
            SegmentedTabs(labels = listOf("Live", "1h", "6h", "24h"), selectedIndex = 0, onSelect = {})
            SegmentedTabs(labels = listOf("Days", "Sessions"), selectedIndex = 1, onSelect = {})
            SegmentedTabs(labels = listOf("All", "Discharge", "Charge"), selectedIndex = -1, onSelect = {})
        }
    }
}
