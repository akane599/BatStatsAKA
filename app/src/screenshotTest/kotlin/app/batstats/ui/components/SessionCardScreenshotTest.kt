package app.batstats.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.ui.ComponentPreviews
import app.batstats.ui.FIXED_TIME_MS
import app.batstats.ui.theme.MainTheme
import com.android.tools.screenshot.PreviewTest

private val observedDischarge = ChargeSession(
    sessionId = "s-1",
    type = SessionType.DISCHARGE,
    startTime = FIXED_TIME_MS,
    endTime = FIXED_TIME_MS + 95 * 60_000L,
    startLevel = 84,
    endLevel = 61,
    deltaUah = -690_000,
    avgCurrentUa = -436_000,
    estCapacityMah = null,
    observationId = "obs-1",
    observedMs = 95 * 60_000L,
    counterCoveredMs = 95 * 60_000L,
    source = "BatteryManager",
)

@PreviewTest
@ComponentPreviews
@Composable
fun SessionCardObservedPreview() {
    MainTheme(darkTheme = isSystemInDarkTheme()) {
        Surface { SessionCard(observedDischarge) }
    }
}

@PreviewTest
@ComponentPreviews
@Composable
fun SessionCardRecordingPreview() {
    MainTheme(darkTheme = isSystemInDarkTheme()) {
        Surface {
            SessionCard(
                observedDischarge.copy(type = SessionType.CHARGE, endTime = null, endLevel = null, startLevel = 23),
                isRecording = true,
            )
        }
    }
}
