package app.batstats.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.batstats.ui.theme.spacing

/** What a [Notice] is about. It colors the leading icon only: the text keeps the on-surface colors. */
enum class NoticeTone {
    /** Something failed or is blocked: the error (heat) accent. */
    PROBLEM,

    /** A hint or a change to know about (access setup, a calibration correction): the info accent. */
    INFO,

    /** Something finished as asked: the primary accent. */
    DONE,
}

/**
 * The app's one treatment for problems, hints and outcomes: a quiet line or block, never a saturated banner. The
 * [tone] colors only the leading [icon]; [title] is on-surface, the [message] quieter under it (on-surface when it
 * stands alone). The text is announced politely when it appears or changes. [actions] (text buttons, in the primary
 * interactive color) sit under the text, at the end.
 *
 * @param framed on a standard tonal [Panel], for a notice that stands on its own among panels (top of a list);
 *   unframed it sits inside an existing panel, dialog or row.
 */
@Composable
fun Notice(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: NoticeTone = NoticeTone.PROBLEM,
    icon: ImageVector = tone.icon,
    framed: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val body: @Composable ColumnScope.() -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = tone.accent())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (title != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (actions != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
    if (framed) {
        Panel(modifier.fillMaxWidth(), content = body)
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs), content = body)
    }
}

private val NoticeTone.icon: ImageVector
    get() = when (this) {
        NoticeTone.PROBLEM -> Icons.Rounded.ErrorOutline
        NoticeTone.INFO -> Icons.Rounded.Info
        NoticeTone.DONE -> Icons.Rounded.CheckCircle
    }

@Composable
private fun NoticeTone.accent(): Color = when (this) {
    NoticeTone.PROBLEM -> MaterialTheme.colorScheme.error
    NoticeTone.INFO -> MaterialTheme.colorScheme.tertiary
    NoticeTone.DONE -> MaterialTheme.colorScheme.primary
}
