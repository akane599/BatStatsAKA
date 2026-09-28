package app.batstats.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.batstats.R
import app.batstats.ui.navigation.Routes
import app.batstats.ui.navigation.TopLevelBackStack
import app.batstats.ui.screens.BatterySettingsScreen
import app.batstats.ui.screens.DataScreen
import app.batstats.ui.screens.DetailedStatsScreen
import app.batstats.ui.screens.DiagnosticsScreen
import app.batstats.ui.screens.DrainStatsScreen
import app.batstats.ui.screens.HistoryScreen
import app.batstats.ui.screens.SessionDetailsScreen
import app.batstats.ui.screens.now.NowScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Entries for every [Routes] key, rendered against [topLevelBackStack]'s currently visible tab.
 * One entry provider is shared by all 4 tabs: a detail route (e.g. [Routes.SettingsData]) can be
 * pushed onto whichever tab is active when it's reached (see the interim mapping in the P3a brief).
 */
@Composable
fun NavGraph(
    topLevelBackStack: TopLevelBackStack,
    decorators: List<NavEntryDecorator<Any>>,
    modifier: Modifier = Modifier,
) {
    val popBack: () -> Unit = { topLevelBackStack.onBack() }
    NavDisplay(
        backStack = topLevelBackStack.backStack,
        onBack = popBack,
        entryDecorators = decorators,
        modifier = modifier,
        entryProvider = entryProvider {

            // Now -> Health, AppDetails (pushed); Today and "See all" switch tabs
            entry<Routes.Now> {
                NowScreen(
                    onOpenHistory = { topLevelBackStack.select(Routes.History) },
                    onOpenHealth = { topLevelBackStack.navigate(Routes.Health) },
                    onOpenApps = { topLevelBackStack.select(Routes.Apps) },
                    onOpenApp = { uid, packageName -> topLevelBackStack.navigate(Routes.AppDetails(uid, packageName)) },
                )
            }

            // History (interim: HistoryScreen) -> SessionDetails
            entry<Routes.History> {
                HistoryScreen(
                    onBack = popBack,
                    onOpenSession = { id -> topLevelBackStack.navigate(Routes.SessionDetails(id)) },
                )
            }

            entry<Routes.SessionDetails> { args ->
                SessionDetailsScreen(
                    onBack = popBack,
                    vm = koinViewModel(parameters = { parametersOf(args.sessionId) }),
                )
            }

            // Apps (interim: DetailedStatsScreen) -> AppDetails (P4b)
            entry<Routes.Apps> {
                DetailedStatsScreen(onBack = popBack)
            }

            entry<Routes.AppDetails> {
                // Placeholder until P4b wires a real screen.
                Text(stringResource(R.string.app_details_placeholder))
            }

            // Health (P4a wires a real screen; reached from Now's Health card, or the "health" deep link)
            entry<Routes.Health> {
                // Placeholder until P4a wires a real screen.
                Text(stringResource(R.string.battery_health))
            }

            // Settings (interim: BatterySettingsScreen) -> SettingsData, SettingsStatus
            entry<Routes.Settings> {
                BatterySettingsScreen(
                    onBack = popBack,
                    onExportData = { topLevelBackStack.navigate(Routes.SettingsData) },
                )
            }

            entry<Routes.SettingsData> {
                DataScreen(onBack = popBack)
            }

            entry<Routes.SettingsStatus> {
                DiagnosticsScreen(onBack = popBack)
            }

            // Interim only (see Routes.DrainStats); deleted with the old screens in P4c.
            entry<Routes.DrainStats> {
                DrainStatsScreen(onBack = popBack)
            }
        },
    )
}
