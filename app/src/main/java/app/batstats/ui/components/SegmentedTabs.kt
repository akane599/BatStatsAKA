package app.batstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.batstats.ui.theme.spacing

/**
 * A quiet single-choice row (ranges, modes): equal-width text options, the selected one on a raised pill
 * (`secondaryContainer`, `shapes.small`), no track or outline. Each option is a 48 dp tab target with selected state
 * for TalkBack; the row is a selectable group described by [contentDescription].
 *
 * @param labels short sentence-case labels ("Live", "1h"; "Days", "Sessions"), one line each; the options share the
 *   width equally, so a label longer than its share ellipsizes (use chips for long or many options).
 * @param selectedIndex the selected option; any other value selects none.
 */
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .selectableGroup()
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .selectable(selected = selected, onClick = { onSelect(index) }, role = Role.Tab)
                    .padding(MaterialTheme.spacing.xxs),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xs),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
