package app.batstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.batstats.ui.theme.numericTitle
import app.batstats.ui.theme.spacing

private val IndicatorSize = 8.dp

/** Units sit at this fraction of the value's size, on the same baseline. */
private const val UNIT_SCALE = 0.6f

/**
 * A labelled number: label above, value (tabular Space Grotesk) with its unit on the same baseline, and an
 * optional supporting line. Read as one item by TalkBack.
 *
 * @param value the formatted number only ("−412"); put the unit in [unit] so it renders quieter.
 * @param indicator a small dot before the label, tying the value to a chart color (e.g. the trace direction).
 * @param valueStyle `numericTitle` by default; `numericHeadline` for the large Now readouts.
 */
@Composable
fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    supporting: String? = null,
    indicator: Color? = null,
    valueStyle: TextStyle = MaterialTheme.typography.numericTitle,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            if (indicator != null) Box(Modifier.size(IndicatorSize).background(indicator, CircleShape))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
            Text(
                value,
                modifier = Modifier.alignByBaseline(),
                style = valueStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (unit != null) {
                Text(
                    unit,
                    modifier = Modifier.alignByBaseline(),
                    style = valueStyle.copy(fontSize = valueStyle.fontSize * UNIT_SCALE),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
