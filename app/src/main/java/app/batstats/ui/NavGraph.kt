package app.batstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.batstats.ui.navigation.Routes
import app.batstats.ui.navigation.TOP_LEVEL_TABS
import app.batstats.ui.navigation.TopLevelBackStack
import app.batstats.ui.screens.AppDetailsScreen
import app.batstats.ui.screens.AppsScreen
import app.batstats.ui.screens.DataScreen
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
 *
 * Every tab's stack is decorated all the time, each with its own saved-state and ViewModel stores; `NavDisplay`
 * only gets the visible tab's entries. Switching tabs therefore pops nothing: each tab keeps its screen state
 * (History's mode and filter, Apps' search and sort, scroll positions) and its ViewModels. Only a real pop (back,
 * re-tapping the tab) clears an entry's state.
 */
@Composable
fun NavGraph(
    topLevelBackStack: TopLevelBackStack,
    modifier: Modifier = Modifier,
) {
    val popBack: () -> Unit = { topLevelBackStack.onBack() }
    // One-shot: Now's Today card asks History for today's figures.
    var historyShowToday by rememberSaveable { mutableStateOf(false) }
    val entryProvider = entryProvider<NavKey> {
        // Now -> Health, AppDetails (pushed); Today opens History › Days at today, "See all" opens Apps at its root
        entry<Routes.Now> {
            NowScreen(
                onOpenHistory = {
                    historyShowToday = true
                    topLevelBackStack.openRoot(Routes.History)
                },
                onOpenHealth = { topLevelBackStack.navigate(Routes.Health) },
                onOpenApps = { topLevelBackStack.openRoot(Routes.Apps) },
                onOpenApp = { uid, packageName -> topLevelBackStack.navigate(Routes.AppDetails(uid, packageName)) },
            )
        }

        // History -> SessionDetails
        entry<Routes.History> {
            HistoryScreen(
                onOpenSession = { id -> topLevelBackStack.navigate(Routes.SessionDetails(id)) },
                showToday = historyShowToday,
                onTodayShown = { historyShowToday = false },
            )
        }

        // SessionDetails -> AppDetails (from the per-app list), Settings › Status (no access); Back after a delete
        entry<Routes.SessionDetails> { args ->
            SessionDetailsScreen(
                onBack = popBack,
                onOpenApp = { uid, packageName -> topLevelBackStack.navigate(Routes.AppDetails(uid, packageName)) },
                onOpenAccessSetup = { topLevelBackStack.navigate(Routes.SettingsStatus) },
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

        // Health (from Now's Health card or the "health" deep link) -> SessionDetails; design capacity is set in place
        entry<Routes.Health> {
            HealthScreen(
                onBack = popBack,
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

        // A running task holds re-taps of Settings and links to its root off this entry too (Back is guarded inside).
        entry<Routes.SettingsData> { key ->
            DataScreen(onBack = popBack, blockLeaving = { onBlocked -> topLevelBackStack.blockLeaving(key, onBlocked) })
        }

        entry<Routes.SettingsStatus> {
            StatusScreen(onBack = popBack)
        }
    }
    val entries = TOP_LEVEL_TABS.associateWith { tab ->
        key(tab) {
            rememberDecoratedNavEntries(
                backStack = topLevelBackStack.stack(tab),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider,
            )
        }
    }
    NavDisplay(
        entries = entries.getValue(topLevelBackStack.selectedTab),
        onBack = popBack,
        modifier = modifier,
    )
}
