package app.batstats.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.batstats.ui.theme.spacing
import kotlin.math.min

/**
 * A tonal panel: the app's container instead of a card. No border and no shadow; hierarchy comes from the
 * container tier ([color]) and radius ([shape]: `shapes.medium` for panels, `shapes.large` for the hero).
 * Children stack with `spacing.sm` between them.
 *
 * @param title optional heading (sentence case), announced as a heading. It always keeps the `spacing.md`
 *   gutter, even when [contentPadding] has no horizontal padding.
 * @param trailing end of the title row, e.g. an [InfoSheet] button or a text action. It is centered on the title
 *   and its 48 dp touch target may extend into the panel padding, so the row stays as tall as the title.
 * @param onClick makes the whole panel one tap target (e.g. "Today" → History).
 * @param contentPadding padding around the content; pass `PaddingValues(vertical = …)` for edge-to-edge rows
 *   such as [AppRow].
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(MaterialTheme.spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val body: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            if (title != null || trailing != null) PanelHeader(title, trailing)
            Column(
                Modifier.fillMaxWidth().padding(
                    start = contentPadding.calculateStartPadding(direction),
                    end = contentPadding.calculateEndPadding(direction),
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                content = content,
            )
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, content = body)
    } else {
        Surface(modifier = modifier, shape = shape, color = color, content = body)
    }
}

@Composable
private fun PanelHeader(title: String?, trailing: (@Composable () -> Unit)?) {
    val spacing = MaterialTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.orEmpty(),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) Box(if (title != null) Modifier.headerActionOverhang(spacing.sm) else Modifier) { trailing() }
    }
}

/**
 * Lets a touch-target-sized control (48 dp, e.g. an [InfoSheet] button) sit at the end of a text-height row, as
 * [Panel]'s header does: it reports no height and [endShift] less width, so the row keeps the text's height and the
 * control is drawn centered on it, shifted into the end padding so its glyph lines up with the gutter. Its touch
 * target stays whole (it overlaps the padding). Pass `MaterialTheme.spacing.sm` inside a `spacing.md` gutter.
 */
fun Modifier.headerActionOverhang(endShift: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val shift = min(endShift.roundToPx(), placeable.width / 2)
    layout(placeable.width - shift, 0) {
        placeable.placeRelative(0, -placeable.height / 2)
    }
}
