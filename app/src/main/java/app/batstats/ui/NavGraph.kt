package app.batstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.batstats.ui.navigation.Routes
import app.batstats.ui.navigation.TopLevelBackStack
import app.batstats.ui.screens.AppDetailsScreen
import app.batstats.ui.screens.AppsScreen
import app.batstats.ui.screens.DataScreen
import app.batstats.ui.screens.DrainStatsScreen
import app.batstats.ui.screens.HealthScreen
import app.batstats.ui.screens.HistoryScreen
import app.batstats.ui.screens.SessionDetailsScreen
import app.batstats.ui.screens.SettingsScreen
import app.batstats.ui.screens.StatusScreen
import app.batstats.ui.screens.now.NowScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Entries for every [Routes] key, rendered against [topLevelBackStack]'s currently visible tab.
 * One entry provider is shared by all 4 tabs: a detail route (e.g. [Routes.SettingsStatus]) is pushed onto
 * whichever tab is active when it's reached (Settings, or Apps from its access banner).
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

            // History -> SessionDetails
            entry<Routes.History> {
                HistoryScreen(onOpenSession = { id -> topLevelBackStack.navigate(Routes.SessionDetails(id)) })
            }

            // SessionDetails -> AppDetails (from the per-app list); Back after a delete
            entry<Routes.SessionDetails> { args ->
                SessionDetailsScreen(
                    onBack = popBack,
                    onOpenApp = { uid, packageName -> topLevelBackStack.navigate(Routes.AppDetails(uid, packageName)) },
                    vm = koinViewModel(parameters = { parametersOf(args.sessionId) }),
                )
            }

            // Apps -> AppDetails; the access banner -> Settings › Status
            entry<Routes.Apps> {
                AppsScreen(
                    onOpenApp = { uid, packageName -> topLevelBackStack.navigate(Routes.AppDetails(uid, packageName)) },
                    onOpenAccessSetup = { topLevelBackStack.navigate(Routes.SettingsStatus) },
                )
            }

            entry<Routes.AppDetails> { args ->
                AppDetailsScreen(
                    uid = args.uid,
                    packageName = args.packageName,
                    onBack = popBack,
                    onOpenAccessSetup = { topLevelBackStack.navigate(Routes.SettingsStatus) },
                )
            }

            // Health (from Now's Health card or the "health" deep link) -> Settings (design capacity), SessionDetails
            entry<Routes.Health> {
                HealthScreen(
                    onBack = popBack,
                    onOpenDesignCapacity = { topLevelBackStack.select(Routes.Settings) },
                    onOpenSession = { id -> topLevelBackStack.navigate(Routes.SessionDetails(id)) },
                )
            }

            // Settings -> SettingsData, SettingsStatus (pushed)
            entry<Routes.Settings> {
                SettingsScreen(
                    onOpenData = { topLevelBackStack.navigate(Routes.SettingsData) },
                    onOpenStatus = { topLevelBackStack.navigate(Routes.SettingsStatus) },
                )
            }

            entry<Routes.SettingsData> {
                DataScreen(onBack = popBack)
            }

            entry<Routes.SettingsStatus> {
                StatusScreen(onBack = popBack)
            }

            // Interim only (see Routes.DrainStats); deleted with the old screens in P4c.
            entry<Routes.DrainStats> {
                DrainStatsScreen(onBack = popBack)
            }
        },
    )
}
