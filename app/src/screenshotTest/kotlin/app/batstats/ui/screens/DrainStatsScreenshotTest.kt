package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.battery.measurement.Observation
import app.batstats.battery.measurement.ObservationSummary
import app.batstats.battery.measurement.ObservedBucket
import app.batstats.battery.measurement.PowerState
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest

private val populatedState = ObservationSummary(
    startedAt = FIXED_TIME_MS - 3 * 60 * 60 * 1000,
    latest = Observation(
        wallMs = FIXED_TIME_MS,
        elapsedMs = 3 * 60 * 60 * 1000,
        uptimeMs = 3 * 60 * 60 * 1000,
        level = 62,
        chargeUah = 2_400_000,
        currentUa = -180_000,
        voltageMv = 3_950,
        power = PowerState.DISCHARGING,
        interactive = false,
        dozing = true,
        generation = "gen-1",
    ),
    screenOn = ObservedBucket(
        durationMs = 40 * 60 * 1000,
        chargeCoveredMs = 40 * 60 * 1000,
        chargeChangeUah = -180_000,
        energyCoveredMs = 40 * 60 * 1000,
        energyMwh = 720.0,
    ),
    screenOff = ObservedBucket(
        durationMs = 120 * 60 * 1000,
        chargeCoveredMs = 120 * 60 * 1000,
        chargeChangeUah = -90_000,
        energyCoveredMs = 120 * 60 * 1000,
        energyMwh = 355.0,
    ),
    charging = ObservedBucket(),
    pluggedMs = 0,
    unknownMs = 0,
    cpuSuspendMs = 95 * 60 * 1000,
    cpuObservedMs = 120 * 60 * 1000,
    dozeMs = 60 * 60 * 1000,
    gaps = 0,
    counterGaps = 0,
    lastIssue = null,
    stopped = false,
)

private val gapsState = populatedState.copy(
    gaps = 2,
    counterGaps = 1,
    lastIssue = "Counter reset detected at 14:02",
)

@PreviewTest
@ScreenPreviews
@Composable
fun DrainStatsScreenPreview() {
    ScreenshotTheme {
        DrainStatsContent(
            state = populatedState,
            running = true,
            onBack = {},
            onToggleTracking = {},
            onResetConfirmed = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DrainStatsScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        DrainStatsContent(
            state = populatedState,
            running = true,
            onBack = {},
            onToggleTracking = {},
            onResetConfirmed = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DrainStatsScreenNotStartedPreview() {
    ScreenshotTheme {
        DrainStatsContent(
            state = ObservationSummary(),
            running = false,
            onBack = {},
            onToggleTracking = {},
            onResetConfirmed = {},
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun DrainStatsScreenGapsPreview() {
    ScreenshotTheme {
        DrainStatsContent(
            state = gapsState,
            running = true,
            onBack = {},
            onToggleTracking = {},
            onResetConfirmed = {},
        )
    }
}
