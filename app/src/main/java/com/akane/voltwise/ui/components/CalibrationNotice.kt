package com.akane.voltwise.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.akane.voltwise.R
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.CurrentSign
import com.akane.voltwise.battery.measurement.CurrentUnit

/**
 * A detected current correction was applied: what changed, with Undo and Keep. One notice and one string set for
 * Now and Settings › Status; announced when it appears.
 */
@Composable
fun CalibrationNotice(
    calibration: CurrentCalibration,
    onUndo: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = when {
        calibration.unit == CurrentUnit.MILLIAMPS && calibration.sign == CurrentSign.INVERTED -> R.string.calibration_notice_both
        calibration.unit == CurrentUnit.MILLIAMPS -> R.string.calibration_notice_unit
        calibration.sign == CurrentSign.INVERTED -> R.string.calibration_notice_sign
        else -> R.string.calibration_notice_same
    }
    Notice(
        message = stringResource(body),
        modifier = modifier,
        title = stringResource(R.string.calibration_notice_title),
        tone = NoticeTone.INFO,
        icon = Icons.Rounded.Tune,
        framed = true,
    ) {
        TextButton(onClick = onUndo) { Text(stringResource(R.string.calibration_notice_undo)) }
        TextButton(onClick = onKeep) { Text(stringResource(R.string.calibration_notice_keep)) }
    }
}
