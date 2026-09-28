package app.batstats.ui.screens.now

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import app.batstats.R
import app.batstats.ui.components.chart.ChartScrubState
import app.batstats.ui.components.chart.rememberChartScrubState
import app.batstats.ui.theme.spacing

/** Two columns from this window width (the Material "expanded" breakpoint): live on the start, cards on the end. */
private const val TWO_COLUMN_MIN_WIDTH_DP = 840

/**
 * Now, stateless: [state] in, [onEvent] out. One scrolling page under the status bar (no top bar: the hero is the
 * header). Phones stack notice, hero, trace, since unplug, Today, Health and Top apps; from 840 dp the live half
 * (through since unplug) and the cards sit side by side. The Reset confirmation is local UI state.
 */
@Composable
fun NowContent(
    state: NowUiState,
    onEvent: (NowEvent) -> Unit,
    modifier: Modifier = Modifier,
    scrubState: ChartScrubState = rememberChartScrubState(),
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing
    val twoColumns = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density >= TWO_COLUMN_MIN_WIDTH_DP
    val column = Arrangement.spacedBy(spacing.sm)

    val live: @Composable () -> Unit = {
        state.calibrationNotice?.let { calibration ->
            CalibrationNotice(
                calibration,
                onUndo = { onEvent(NowEvent.UndoCalibration) },
                onKeep = { onEvent(NowEvent.KeepCalibration) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        NowHero(state.hero, onToggleMonitoring = { onEvent(NowEvent.ToggleMonitoring) }, modifier = Modifier.fillMaxWidth())
        NowTracePanel(
            state.trace,
            state.readouts,
            state.useFahrenheit,
            onSelectRange = { onEvent(NowEvent.SelectRange(it)) },
            modifier = Modifier.fillMaxWidth(),
            scrubState = scrubState,
        )
        SinceUnplugPanel(state.sinceUnplug, onReset = { confirmReset = true }, modifier = Modifier.fillMaxWidth())
    }
    val cards: @Composable () -> Unit = {
        TodayPanel(state.today, onOpen = { onEvent(NowEvent.OpenHistory) }, modifier = Modifier.fillMaxWidth())
        HealthPanel(state.health, onOpen = { onEvent(NowEvent.OpenHealth) }, modifier = Modifier.fillMaxWidth())
        TopAppsPanel(
            state.topApps,
            onOpenApps = { onEvent(NowEvent.OpenApps) },
            onOpenApp = { uid, packageName -> onEvent(NowEvent.OpenApp(uid, packageName)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = column,
    ) {
        if (twoColumns) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Column(Modifier.weight(1f), verticalArrangement = column) { live() }
                Column(Modifier.weight(1f), verticalArrangement = column) { cards() }
            }
        } else {
            live()
            cards()
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.now_reset_title)) },
            text = { Text(stringResource(R.string.now_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onEvent(NowEvent.ResetObservation)
                }) { Text(stringResource(R.string.now_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.now_cancel)) }
            },
        )
    }
}
