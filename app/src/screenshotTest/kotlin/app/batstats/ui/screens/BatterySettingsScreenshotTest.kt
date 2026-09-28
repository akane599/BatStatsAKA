package app.batstats.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.batstats.battery.measurement.CalibrationSource
import app.batstats.battery.measurement.CalibrationState
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.CurrentSign
import app.batstats.battery.measurement.CurrentUnit
import app.batstats.settings.AppSettings
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.ui.TallPhonePreview
import app.batstats.viewmodel.SettingsError
import app.batstats.viewmodel.SettingsUiState
import com.android.tools.screenshot.PreviewTest

/** Scrolls a phone past Monitoring and Alerts so the Measurement panel fills the frame. */
private val MeasurementScrollOffset = 560.dp

private val detected = CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.INVERTED)

/** Settings content with no-op callbacks; Material You shown (API 31+). */
@Composable
private fun SettingsFixture(state: SettingsUiState = SettingsUiState(), scrollTo: Dp = 0.dp) {
    val scrollPx = with(LocalDensity.current) { scrollTo.roundToPx() }
    SettingsContent(
        state = state,
        onEvent = {},
        dynamicColorAvailable = true,
        scrollState = rememberScrollState(scrollPx),
    )
}

/** A fresh install: defaults, nothing detected yet. */
@PreviewTest
@ScreenPreviews
@Composable
fun BatterySettingsScreenPreview() {
    ScreenshotTheme {
        SettingsFixture()
    }
}

/** Pure black switched on (and applied). */
@PreviewTest
@PhonePreview
@Composable
fun BatterySettingsScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        SettingsFixture(SettingsUiState(settings = AppSettings(oledBlack = true)))
    }
}

/** Detection found mA with an inverted sign over 4 counter windows; a design capacity is set. */
@PreviewTest
@TallPhonePreview
@Composable
fun BatterySettingsScreenCalibrationPreview() {
    ScreenshotTheme {
        SettingsFixture(
            SettingsUiState(
                settings = AppSettings(designCapacityMah = 4_500, temperatureUnitIndex = 1),
                calibration = CalibrationState(
                    effective = detected,
                    detected = detected,
                    source = CalibrationSource.DETECTED,
                    agreeingWindows = 4,
                ),
            ),
            scrollTo = MeasurementScrollOffset,
        )
    }
}

/** A write the store refused: the inline error under the title. */
@PreviewTest
@PhonePreview
@Composable
fun BatterySettingsScreenErrorPreview() {
    ScreenshotTheme {
        SettingsFixture(SettingsUiState(error = SettingsError.WRITE_FAILED))
    }
}
