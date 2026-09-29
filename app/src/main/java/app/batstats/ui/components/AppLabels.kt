package app.batstats.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.batstats.R
import app.batstats.battery.apps.AppLabel

/** The name a per-app row shows: the app's label, or "System process" / "Unknown app" (never a raw id). */
@Composable
@ReadOnlyComposable
fun AppLabel.displayName(): String = when (this) {
    is AppLabel.Named -> text
    AppLabel.SystemProcess -> stringResource(R.string.component_app_system_process)
    AppLabel.Unknown -> stringResource(R.string.component_app_unknown)
}

/** [AppIcon] for a named app; a glyph placeholder for a system process or an unknown app. Decorative. */
@Composable
fun AppLabelIcon(packageName: String, label: AppLabel, modifier: Modifier = Modifier) {
    when (label) {
        is AppLabel.Named -> AppIcon(packageName, label.text, modifier)
        AppLabel.SystemProcess -> AppIconPlaceholder(label.displayName(), modifier, icon = Icons.Rounded.Memory)
        AppLabel.Unknown -> AppIconPlaceholder(label.displayName(), modifier, icon = Icons.Rounded.QuestionMark)
    }
}
