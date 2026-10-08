package com.akane.voltwise.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.akane.voltwise.R
import com.akane.voltwise.settings.DesignCapacity
import com.akane.voltwise.ui.format.currentLocale
import com.akane.voltwise.ui.format.formatNumber
import com.akane.voltwise.ui.theme.numericBody

private const val DESIGN_CAPACITY_MAX_DIGITS = 5

/**
 * The design capacity override, from Settings' row and Health's "Set design capacity": mAh as digits; empty means
 * automatic. Save stays off until the value is 0, empty or in range.
 */
@Composable
internal fun DesignCapacityDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable {
        mutableStateOf(if (DesignCapacity.isValid(current) && current != DesignCapacity.AUTO) current.toString() else "")
    }
    val mAh = if (text.isEmpty()) DesignCapacity.AUTO else text.toIntOrNull()
    val valid = mAh != null && DesignCapacity.isValid(mAh)
    val locale = currentLocale()
    val low = formatNumber(DesignCapacity.RANGE_MAH.first.toDouble(), 0, locale)
    val high = formatNumber(DesignCapacity.RANGE_MAH.last.toDouble(), 0, locale)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_design_capacity)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter(Char::isDigit).take(DESIGN_CAPACITY_MAX_DIGITS) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.numericBody,
                placeholder = { Text(stringResource(R.string.option_auto)) },
                suffix = { Text(stringResource(R.string.settings_unit_mah)) },
                supportingText = {
                    Text(stringResource(if (valid) R.string.settings_design_capacity_hint else R.string.settings_design_capacity_error, low, high))
                },
                isError = !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (mAh != null && valid) onSave(mAh) }, enabled = valid) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
