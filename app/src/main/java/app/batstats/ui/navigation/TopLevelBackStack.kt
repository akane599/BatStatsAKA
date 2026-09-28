package app.batstats.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * One [NavBackStack] per top-level tab (see [TOP_LEVEL_TABS]), plus which tab is currently
 * visible.
 *
 * Rules:
 * - [select] on the already-selected tab pops that tab's stack back to its root.
 * - [select] on a different tab switches the visible stack; no stack's contents change.
 * - [onBack] pops the visible tab's stack. Popping the last entry of a non-[Routes.Now] tab
 *   switches to [Routes.Now] instead of leaving that tab empty. Popping [Routes.Now]'s root
 *   returns `false` so the caller (the system back handler) can finish the activity.
 *
 * Holds no Compose state of its own — [getSelectedTab]/[setSelectedTab] are supplied by the
 * caller — so it can be constructed and exercised with plain JUnit (see `TopLevelBackStackTest`).
 * [rememberTopLevelBackStack] wires it to `rememberNavBackStack`/`rememberSaveable` for Compose.
 */
class TopLevelBackStack(
    private val backStacks: Map<Routes, NavBackStack<NavKey>>,
    private val getSelectedTab: () -> Routes,
    private val setSelectedTab: (Routes) -> Unit,
) {
    val selectedTab: Routes get() = getSelectedTab()

    /** The back stack `NavDisplay` should render: the currently visible tab's. */
    val backStack: NavBackStack<NavKey> get() = backStacks.getValue(selectedTab)

    fun select(tab: Routes) {
        if (tab == selectedTab) {
            val stack = backStacks.getValue(tab)
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else {
            setSelectedTab(tab)
        }
    }

    /** Pushes [route] onto the currently visible tab's stack. */
    fun navigate(route: NavKey) {
        backStack.add(route)
    }

    /** @return `true` if the back press was handled; `false` if the caller should finish the activity. */
    fun onBack(): Boolean {
        val stack = backStack
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }
            selectedTab != Routes.Now -> {
                setSelectedTab(Routes.Now)
                true
            }
            else -> false
        }
    }
}

@Composable
fun rememberTopLevelBackStack(): TopLevelBackStack {
    val nowStack = rememberNavBackStack(Routes.Now)
    val historyStack = rememberNavBackStack(Routes.History)
    val appsStack = rememberNavBackStack(Routes.Apps)
    val settingsStack = rememberNavBackStack(Routes.Settings)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    return remember(nowStack, historyStack, appsStack, settingsStack) {
        val backStacks: Map<Routes, NavBackStack<NavKey>> = mapOf(
            Routes.Now to nowStack,
            Routes.History to historyStack,
            Routes.Apps to appsStack,
            Routes.Settings to settingsStack,
        )
        TopLevelBackStack(
            backStacks = backStacks,
            getSelectedTab = { TOP_LEVEL_TABS[selectedIndex] },
            setSelectedTab = { selectedIndex = TOP_LEVEL_TABS.indexOf(it) },
        )
    }
}

/** Applies a `destination` intent extra value (see [Destinations]) to this back stack. */
fun TopLevelBackStack.openDestination(value: String) {
    val sessionId = Destinations.sessionIdOrNull(value)
    when {
        sessionId != null -> {
            select(Routes.History)
            navigate(Routes.SessionDetails(sessionId))
        }
        value == Destinations.NOW -> select(Routes.Now)
        value == Destinations.HISTORY -> select(Routes.History)
        value == Destinations.APPS -> select(Routes.Apps)
        value == Destinations.SETTINGS -> select(Routes.Settings)
        value == Destinations.HEALTH -> {
            select(Routes.Now)
            navigate(Routes.Health)
        }
        value == Destinations.STATUS -> {
            select(Routes.Settings)
            navigate(Routes.SettingsStatus)
        }
    }
}
