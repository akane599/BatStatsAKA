package app.batstats.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TopLevelBackStackTest {
    private var selected: Routes = Routes.Now
    private lateinit var backStacks: Map<Routes, NavBackStack<NavKey>>
    private lateinit var subject: TopLevelBackStack

    @Before
    fun setUp() {
        selected = Routes.Now
        backStacks = TOP_LEVEL_TABS.associateWith { NavBackStack<NavKey>(it) }
        subject = TopLevelBackStack(backStacks, { selected }, { selected = it })
    }

    @Test
    fun `select on a different tab switches without clearing any stack`() {
        backStacks.getValue(Routes.History).add(Routes.SessionDetails("42"))

        subject.select(Routes.History)

        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History, Routes.SessionDetails("42")), backStacks.getValue(Routes.History).toList())
        // The tab we left is untouched too.
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `select on the current tab pops it back to its root`() {
        selected = Routes.Apps
        backStacks.getValue(Routes.Apps).add(Routes.AppDetails(1, "app.batstats"))

        subject.select(Routes.Apps)

        assertEquals(Routes.Apps, subject.selectedTab)
        assertEquals(listOf(Routes.Apps), backStacks.getValue(Routes.Apps).toList())
    }

    @Test
    fun `select on the current tab is a no-op when already at its root`() {
        selected = Routes.Settings

        subject.select(Routes.Settings)

        assertEquals(listOf(Routes.Settings), backStacks.getValue(Routes.Settings).toList())
    }

    @Test
    fun `openRoot switches to a tab at its root and leaves the other stacks alone`() {
        backStacks.getValue(Routes.History).add(Routes.SessionDetails("42"))
        backStacks.getValue(Routes.Now).add(Routes.Health)

        subject.openRoot(Routes.History)

        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History), backStacks.getValue(Routes.History).toList())
        assertEquals(listOf(Routes.Now, Routes.Health), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `stack is each tab's own back stack`() {
        TOP_LEVEL_TABS.forEach { assertEquals(backStacks.getValue(it), subject.stack(it)) }
    }

    @Test
    fun `navigate pushes onto the currently selected tab`() {
        selected = Routes.Apps

        subject.navigate(Routes.AppDetails(9, "app.batstats"))

        assertEquals(
            listOf(Routes.Apps, Routes.AppDetails(9, "app.batstats")),
            backStacks.getValue(Routes.Apps).toList(),
        )
        // Other stacks are untouched.
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `onBack pops a detail off the current tab`() {
        selected = Routes.History
        backStacks.getValue(Routes.History).add(Routes.SessionDetails("7"))

        val handled = subject.onBack()

        assertTrue(handled)
        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History), backStacks.getValue(Routes.History).toList())
    }

    @Test
    fun `onBack on a non-Now root switches to Now without touching that tab's stack`() {
        selected = Routes.Settings

        val handled = subject.onBack()

        assertTrue(handled)
        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Settings), backStacks.getValue(Routes.Settings).toList())
    }

    @Test
    fun `onBack on Now's root is unhandled so the caller can finish the activity`() {
        selected = Routes.Now

        val handled = subject.onBack()

        assertFalse(handled)
        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `backStack exposes the currently selected tab's stack`() {
        selected = Routes.History

        assertEquals(backStacks.getValue(Routes.History), subject.backStack)
    }

    @Test
    fun `openDestination selects the matching top-level tab`() {
        subject.openDestination(Destinations.HISTORY)

        assertEquals(Routes.History, subject.selectedTab)
    }

    @Test
    fun `openDestination for a session id switches to History and pushes SessionDetails`() {
        subject.openDestination(Destinations.session("99"))

        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History, Routes.SessionDetails("99")), backStacks.getValue(Routes.History).toList())
    }

    @Test
    fun `openDestination for health switches to Now and pushes Health`() {
        selected = Routes.Settings

        subject.openDestination(Destinations.HEALTH)

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Now, Routes.Health), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `openDestination for status switches to Settings and pushes SettingsStatus`() {
        subject.openDestination(Destinations.STATUS)

        assertEquals(Routes.Settings, subject.selectedTab)
        assertEquals(listOf(Routes.Settings, Routes.SettingsStatus), backStacks.getValue(Routes.Settings).toList())
    }

    // C05: explicit links open the requested root, not a retained detail stack.

    @Test
    fun `See all link opens Apps at its root even when AppDetails is retained`() {
        backStacks.getValue(Routes.Apps).add(Routes.AppDetails(3, "app.batstats"))

        // What Now's "See all" (NavGraph onOpenApps) calls.
        subject.openRoot(Routes.Apps)

        assertEquals(Routes.Apps, subject.selectedTab)
        assertEquals(listOf(Routes.Apps), backStacks.getValue(Routes.Apps).toList())
    }

    @Test
    fun `openDestination for apps opens Apps at its root even when AppDetails is retained`() {
        backStacks.getValue(Routes.Apps).add(Routes.AppDetails(3, "app.batstats"))

        subject.openDestination(Destinations.APPS)

        assertEquals(Routes.Apps, subject.selectedTab)
        assertEquals(listOf(Routes.Apps), backStacks.getValue(Routes.Apps).toList())
    }

    @Test
    fun `openDestination for now opens Now at its root even when Health is retained`() {
        selected = Routes.History
        backStacks.getValue(Routes.Now).add(Routes.Health)

        subject.openDestination(Destinations.NOW)

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `openDestination for now from Now itself still drops a retained Health`() {
        backStacks.getValue(Routes.Now).add(Routes.Health)

        subject.openDestination(Destinations.NOW)

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `repeated detail links leave only the root and the latest detail`() {
        // The user switches tabs between links, so each link arrives while its tab is retained but not visible.
        repeat(3) {
            subject.select(Routes.Apps)
            subject.openDestination(Destinations.HEALTH)
        }
        assertEquals(listOf(Routes.Now, Routes.Health), backStacks.getValue(Routes.Now).toList())

        listOf("1", "2", "3").forEach { id ->
            subject.select(Routes.Apps)
            subject.openDestination(Destinations.session(id))
        }
        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History, Routes.SessionDetails("3")), backStacks.getValue(Routes.History).toList())

        repeat(3) {
            subject.select(Routes.Apps)
            subject.openDestination(Destinations.STATUS)
        }
        assertEquals(listOf(Routes.Settings, Routes.SettingsStatus), backStacks.getValue(Routes.Settings).toList())
    }

    // A busy Settings › Data blocks leaving: re-tapping its tab or a link to its root must not pop it (and so clear
    // its ViewModel, cancelling the running export, import or clear).

    @Test
    fun `select on the current tab keeps a blocked entry and reports the refusal`() {
        selected = Routes.Settings
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData) { refusals++ }

        subject.select(Routes.Settings)

        assertEquals(listOf(Routes.Settings, Routes.SettingsData), backStacks.getValue(Routes.Settings).toList())
        assertEquals(1, refusals)
    }

    @Test
    fun `openRoot of the current tab keeps a blocked entry and reports the refusal`() {
        selected = Routes.Settings
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData) { refusals++ }

        val atRoot = subject.openRoot(Routes.Settings)

        assertFalse(atRoot)
        assertEquals(Routes.Settings, subject.selectedTab)
        assertEquals(listOf(Routes.Settings, Routes.SettingsData), backStacks.getValue(Routes.Settings).toList())
        assertEquals(1, refusals)
    }

    @Test
    fun `a status link does not push over a blocked entry`() {
        selected = Routes.Settings
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        subject.blockLeaving(Routes.SettingsData) {}

        subject.openDestination(Destinations.STATUS)

        assertEquals(listOf(Routes.Settings, Routes.SettingsData), backStacks.getValue(Routes.Settings).toList())
    }

    @Test
    fun `switching to another tab is not blocked and keeps the blocked entry`() {
        selected = Routes.Settings
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData) { refusals++ }

        subject.select(Routes.Now)

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Settings, Routes.SettingsData), backStacks.getValue(Routes.Settings).toList())
        assertEquals(0, refusals)
    }

    @Test
    fun `once unblocked, select and openRoot pop to the root again`() {
        selected = Routes.Settings
        val settings = backStacks.getValue(Routes.Settings)
        settings.add(Routes.SettingsData)
        var refusals = 0
        val unblock = subject.blockLeaving(Routes.SettingsData) { refusals++ }

        unblock()
        subject.select(Routes.Settings)

        assertEquals(listOf(Routes.Settings), settings.toList())

        settings.add(Routes.SettingsData)
        subject.blockLeaving(Routes.SettingsData) { refusals++ }()
        assertTrue(subject.openRoot(Routes.Settings))

        assertEquals(listOf(Routes.Settings), settings.toList())
        assertEquals(0, refusals)
    }

    @Test
    fun `plain tab select still restores retained details after an explicit link elsewhere`() {
        backStacks.getValue(Routes.Apps).add(Routes.AppDetails(3, "app.batstats"))
        subject.openDestination(Destinations.HISTORY)

        subject.select(Routes.Apps)

        assertEquals(Routes.Apps, subject.selectedTab)
        assertEquals(
            listOf(Routes.Apps, Routes.AppDetails(3, "app.batstats")),
            backStacks.getValue(Routes.Apps).toList(),
        )
    }
}
