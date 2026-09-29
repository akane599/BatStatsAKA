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
}
