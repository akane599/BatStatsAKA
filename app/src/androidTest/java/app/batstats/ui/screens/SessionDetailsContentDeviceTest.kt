package app.batstats.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.R
import app.batstats.battery.apps.AppLabel
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.measurement.CapacityConfidence
import app.batstats.test.DeviceEnvironment
import app.batstats.ui.theme.MainTheme
import app.batstats.viewmodel.DrainState
import app.batstats.viewmodel.SessionApp
import app.batstats.viewmodel.SessionApps
import app.batstats.viewmodel.SessionCapacity
import app.batstats.viewmodel.SessionCharts
import app.batstats.viewmodel.SessionDetailsEvent
import app.batstats.viewmodel.SessionDetailsUiState
import app.batstats.viewmodel.SessionInsights
import app.batstats.viewmodel.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the stateless [SessionDetailsContent] with hand-built [SessionDetailsUiState]s (no ViewModel, no Room):
 * a discharge session with a per-app breakdown (its basis text, then the NO_ACCESS explanation), a charge session's
 * insights panel, the delete confirm → callback flow, and that neither raw enum names nor uids ever reach the
 * screen (every value goes through its own string resource first).
 */
@RunWith(AndroidJUnit4::class)
class SessionDetailsContentDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int, vararg args: Any): String = DeviceEnvironment.context.getString(id, *args)
    private lateinit var events: MutableList<SessionDetailsEvent>

    private fun summary(type: SessionType, capacity: SessionCapacity? = SessionCapacity(4_200, CapacityConfidence.HIGH)) = SessionSummary(
        type = type, recording = false, startedAtMs = 1_000_000L, endedAtMs = 1_610_000L,
        startLevel = 90, endLevel = 70, chargeMah = 500.0, energyWh = 1.9, averageMa = 400.0,
        counterCoverage = 1.0, capacity = capacity, measured = true,
    )

    /** No points: [SessionCharts.hasReadings] is false, so the chart panels show their quiet "no readings" text. */
    private val noCharts = SessionCharts(window = null)

    private val drainInsights = SessionInsights.Drain(
        screenOn = DrainState(durationMs = 600_000, currentMa = 300.0, percentPerHour = 5.0),
        screenOff = DrainState(durationMs = 300_000, currentMa = 50.0, percentPerHour = 1.0),
        deepSleepPercent = 80.0,
        deepSleepScreenOff = true,
    )

    private fun dischargeState(apps: SessionApps) = SessionDetailsUiState.Ready(
        summary = summary(SessionType.DISCHARGE), charts = noCharts, insights = drainInsights, apps = apps,
        useFahrenheit = false, canDelete = true,
    )

    private fun chargeState() = SessionDetailsUiState.Ready(
        summary = summary(SessionType.CHARGE),
        charts = noCharts,
        insights = SessionInsights.Charging(
            charger = ChargerType.AC, averagePowerW = 9.1, peakPowerW = 18.4, peakTemperature = 38.6, twentyToEightyMs = 3_480_000L,
        ),
        apps = null,
        useFahrenheit = false,
        canDelete = true,
    )

    private fun setContent(state: SessionDetailsUiState) {
        events = mutableListOf()
        compose.setContent {
            MainTheme(dynamicColor = false) {
                SessionDetailsContent(state = state, onEvent = { events += it })
            }
        }
    }

    @Test fun dischargeWithAppsShowsTheBasisTextAndOpensAnApp() {
        val apps = SessionApps.Ready(
            rows = listOf(SessionApp(10_123, "com.android.chrome", AppLabel.Named("Chrome"), 58.0, 0.3f)),
            othersMah = null, othersShare = 0f, basis = AppUsageBasis.DELTA,
        )
        setContent(dischargeState(apps))
        compose.onNodeWithText(label(R.string.sessiondetails_type_discharge)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.sessiondetails_apps_basis_delta)).assertIsDisplayed()
        compose.onNodeWithText("Chrome").performClick()
        assertEquals(listOf(SessionDetailsEvent.OpenApp(10_123, "com.android.chrome")), events)

        // No raw enum names or uids anywhere on screen: every value is resolved through its own string resource.
        listOf("DISCHARGE", "DELTA", "10123").forEach { raw ->
            compose.onAllNodesWithText(raw, substring = true).assertCountEquals(0)
        }
    }

    @Test fun dischargeWithNoAccessAppsShowsTheExplanation() {
        setContent(dischargeState(SessionApps.NoAccess))
        compose.onNodeWithText(label(R.string.sessiondetails_apps_no_access)).assertIsDisplayed()
    }

    @Test fun chargeShowsTheChargingInsightsPanel() {
        setContent(chargeState())
        compose.onNodeWithText(label(R.string.sessiondetails_type_charge)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.sessiondetails_charging_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.sessiondetails_charger)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.sessiondetails_charger_ac)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.sessiondetails_average_power)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.sessiondetails_peak_temperature)).assertIsDisplayed()
        // The Drain panel (discharge-only) must not appear on a charge session.
        compose.onAllNodesWithText(label(R.string.sessiondetails_drain_title)).assertCountEquals(0)
    }

    @Test fun deleteConfirmFiresTheEventAndCancelDoesNothing() {
        setContent(dischargeState(SessionApps.NotRecorded))
        compose.onNodeWithContentDescription(label(R.string.sessiondetails_delete)).performClick()
        compose.onNodeWithText(label(R.string.sessiondetails_delete_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.sessiondetails_delete_body)).assertIsDisplayed()

        compose.onNodeWithText(label(R.string.sessiondetails_cancel)).performClick()
        compose.onAllNodesWithText(label(R.string.sessiondetails_delete_title)).assertCountEquals(0)
        assertTrue(events.isEmpty())

        compose.onNodeWithContentDescription(label(R.string.sessiondetails_delete)).performClick()
        compose.onNodeWithText(label(R.string.sessiondetails_delete_confirm)).performClick()
        assertEquals(listOf(SessionDetailsEvent.Delete), events)
        compose.onAllNodesWithText(label(R.string.sessiondetails_delete_title)).assertCountEquals(0)
    }
}
