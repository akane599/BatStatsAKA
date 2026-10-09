package com.akane.voltwise.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akane.voltwise.R
import com.akane.voltwise.ui.theme.spacing

private val InfoGlyphSize = 20.dp

/**
 * Where explanatory prose lives: a quiet "ⓘ" button (48 dp target) that opens a bottom sheet with [title] and
 * [body]. Put it in a [Panel]'s `trailing` slot or next to a heading. Paragraphs in [body] split on blank lines.
 */
@Composable
fun InfoSheet(title: String, body: String, modifier: Modifier = Modifier) {
    InfoSheet(title, modifier) {
        body.split("\n\n").forEach { paragraph ->
            Text(
                paragraph.trim(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [InfoSheet] with custom sheet [content] (e.g. copyable commands) under the title. */
@Composable
fun InfoSheet(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = stringResource(R.string.component_info_open, title),
            modifier = Modifier.size(InfoGlyphSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            InfoSheetContent(title, content = content)
        }
    }
}

/** The sheet body: title and content, scrollable for long prose (for previews, or a sheet of your own). */
@Composable
fun InfoSheetContent(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = MaterialTheme.spacing.lg, end = MaterialTheme.spacing.lg, bottom = MaterialTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}
