package app.batstats.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
