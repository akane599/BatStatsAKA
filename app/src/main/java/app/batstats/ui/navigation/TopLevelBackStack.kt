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
 * - [openRoot] shows a tab at its root, popping that tab's stack (only that one) whether or not it is visible.
 * - Neither pops a stack holding a [blockLeaving] entry: the stack stays as it is and the blocker is told.
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

    /** [tab]'s own stack: every tab's entries stay decorated (state, ViewModels) while another tab is visible. */
    fun stack(tab: Routes): NavBackStack<NavKey> = backStacks.getValue(tab)

    private val leaveBlockers = mutableMapOf<NavKey, () -> Unit>()

    /**
     * Until the returned function is called, popping a tab to its root ([select] on the visible tab, [openRoot])
     * leaves [entry]'s stack as it is and calls [onBlocked] instead. For a screen whose pop would cancel work in its
     * ViewModel; Back is guarded on the screen itself. Registering [entry] again replaces its blocker.
     */
    fun blockLeaving(entry: NavKey, onBlocked: () -> Unit): () -> Unit {
        leaveBlockers[entry] = onBlocked
        return { leaveBlockers.remove(entry) }
    }

    fun select(tab: Routes) {
        if (tab == selectedTab) popToRoot(backStacks.getValue(tab)) else setSelectedTab(tab)
    }

    /**
     * Shows [tab] at its root, whichever tab is visible: a link to what the tab itself shows first.
     * @return `false` if a [blockLeaving] entry kept the stack above its root.
     */
    fun openRoot(tab: Routes): Boolean {
        val atRoot = popToRoot(backStacks.getValue(tab))
        if (tab != selectedTab) setSelectedTab(tab)
        return atRoot
    }

    private fun popToRoot(stack: NavBackStack<NavKey>): Boolean {
        val onBlocked = stack.drop(1).firstNotNullOfOrNull { leaveBlockers[it] }
        if (onBlocked != null) {
            onBlocked()
            return false
        }
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        return true
    }

    /** Pushes [route] onto the currently visible tab's stack, unless it is already on top (a double tap). */
    fun navigate(route: NavKey) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    /**
     * An entry's own up action: pops only while [entry] is still the visible stack's top, so a double tap on Back (or
     * a late "leave" signal after the user already left) cannot pop the parent as well. Always returns `true`: a
     * stale up is handled by doing nothing.
     */
    fun onBack(entry: NavKey): Boolean {
        if (backStack.lastOrNull() == entry) onBack()
        return true
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

/**
 * Applies a `destination` intent extra value (see [Destinations]) to this back stack. Every value is an explicit
 * link, so the target tab is reset to its root first ([openRoot]): a retained detail stack never stands in for the
 * requested screen, and a detail link (session, health, status) leaves exactly root + that detail however often it
 * repeats. Bottom-bar [TopLevelBackStack.select] keeps its stack retention. A detail link whose tab a
 * [TopLevelBackStack.blockLeaving] entry kept off its root pushes nothing: the blocked screen stays on top.
 */
fun TopLevelBackStack.openDestination(value: String) {
    val sessionId = Destinations.sessionIdOrNull(value)
    when {
        sessionId != null -> if (openRoot(Routes.History)) navigate(Routes.SessionDetails(sessionId))
        value == Destinations.NOW -> openRoot(Routes.Now)
        value == Destinations.HISTORY -> openRoot(Routes.History)
        value == Destinations.APPS -> openRoot(Routes.Apps)
        value == Destinations.SETTINGS -> openRoot(Routes.Settings)
        value == Destinations.HEALTH -> if (openRoot(Routes.Now)) navigate(Routes.Health)
        value == Destinations.STATUS -> if (openRoot(Routes.Settings)) navigate(Routes.SettingsStatus)
    }
}
