package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.batstats.R
import app.batstats.ui.components.StatCell
import app.batstats.ui.format.currentLocale
import app.batstats.ui.format.durationString
import app.batstats.ui.format.formatNumber
import app.batstats.ui.format.formatRate
import app.batstats.viewmodel.DrainState

/**
 * Screen-on or screen-off drain on battery, as Now's on-battery window and SessionDetails show it: %/h when the
 * capacity is known, else the average mA; the other figure and the duration go underneath. A screen state the window
 * never had (no time in it) is a bare dash.
 */
@Composable
internal fun DrainCell(label: String, drain: DrainState, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val noValue = stringResource(R.string.component_no_value)
    if (drain.durationMs <= 0) {
        StatCell(label, noValue, modifier)
        return
    }
    val duration = durationString(drain.durationMs)
    val milliamps = drain.currentMa?.let { formatNumber(it, 0, locale) }
    val perHour = drain.percentPerHour
    if (perHour != null) {
        StatCell(
            label,
            formatRate(perHour, locale),
            modifier,
            unit = stringResource(R.string.now_unit_percent_per_hour),
            supporting = stringResource(R.string.now_drain_supporting, milliamps ?: noValue, duration),
        )
    } else {
        StatCell(label, milliamps ?: noValue, modifier, unit = stringResource(R.string.now_unit_ma), supporting = duration)
    }
}
