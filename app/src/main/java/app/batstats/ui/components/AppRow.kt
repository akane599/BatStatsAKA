package app.batstats.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.batstats.ui.theme.chartColors
import app.batstats.ui.theme.numericBody
import app.batstats.ui.theme.spacing
import kotlin.math.max

private val RowMinHeight = 56.dp
private val ShareBarHeight = 4.dp

/**
 * A list row: icon · label · value, with a share bar under the label showing this row's part of the total.
 * Used by Apps and the per-session breakdown. TalkBack reads it as one item (label, value, share as a percent).
 *
 * @param icon usually `AppIcon(packageName, label)`; `AppIconPlaceholder(label, icon = …)` for an aggregate row.
 * @param value formatted, e.g. "12.3 mAh".
 * @param share 0..1 of the list total (clamped); any share above zero stays visible.
 * @param onClick makes the whole row the tap target (≥ 56 dp tall).
 * @param shareColor drain by default (energy out); pass another chart color for non-energy lists.
 * @param contentPadding horizontal gutter + vertical rhythm; the default suits a list on the screen or a panel
 *   with no horizontal padding.
 */
@Composable
fun AppRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    share: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shareColor: Color = MaterialTheme.chartColors.drain,
    contentPadding: PaddingValues = PaddingValues(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs),
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .then(clickable)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = RowMinHeight)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        icon()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    modifier = Modifier.padding(start = MaterialTheme.spacing.xs),
                    style = MaterialTheme.typography.numericBody,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            ShareBar(share, shareColor)
        }
    }
}

/** Track + fill; a non-zero share never shrinks below a round dot. */
@Composable
private fun ShareBar(share: Float, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fraction = if (share.isNaN()) 0f else share.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(ShareBarHeight)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                if (fraction > 0f) {
                    drawRoundRect(color, size = Size(max(size.width * fraction, size.height), size.height), cornerRadius = radius)
                }
            },
    )
}
