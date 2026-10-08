package app.batstats.battery.drain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [OngoingPosts]: every post of one monitoring session carries the same nonzero `when`, so a shade that sorts by time
 * keeps the notification in place; updates replace it, only stopping cancels it.
 */
class OngoingPostsTest {
    /** A built notification as the fakes see it: what it shows and the `when` it was built with. */
    private data class Posted(val shows: String, val whenMs: Long)

    private class FakePoster : OngoingPosts.Poster<Posted> {
        val calls = mutableListOf<String>()
        override fun show(notification: Posted) { calls += "show ${notification.shows}" }
        override fun cancel() { calls += "cancel" }
    }

    private var clock = 1_790_000_000_000L
    private val poster = FakePoster()
    private val posts = OngoingPosts({ clock }, poster)

    /** Builds as the service and the manager do: with the session's `when`, whatever the clock says now. */
    private fun built(shows: String) = Posted(shows, posts.whenMs)

    @Test fun placeholderFullPostsAndRePromotionShareOneNonzeroWhenWithNoCancelBetweenUpdates() {
        val session = posts.start()
        val placeholder = built("placeholder")
        clock += 2_000
        posts.post(built("first full"))
        clock += 30_000
        posts.post(built("changed reading"))
        clock += 600_000
        val promoted = posts.promotion { built("startup defaults") }

        assertNotEquals("when=0 becomes a fresh creation time on Android 16", 0L, session)
        assertEquals(1_790_000_000_000L, session)
        assertEquals("Re-promotion keeps the live content", "changed reading", promoted.shows)
        listOf(placeholder, promoted).forEach { assertEquals(it.shows, session, it.whenMs) }
        assertEquals(listOf("show first full", "show changed reading"), poster.calls)
    }

    @Test fun rePromotionBeforeAnyFullPostBuildsWithTheSessionWhen() {
        val session = posts.start()
        clock += 5_000
        assertEquals(Posted("startup defaults", session), posts.promotion { built("startup defaults") })
        assertEquals(emptyList<String>(), poster.calls)
    }

    @Test fun onlyTheNextMonitoringSessionRenewsTheWhen() {
        val first = posts.start()
        posts.post(built("live"))
        clock += 3_600_000
        posts.stop()
        assertEquals(listOf("show live", "cancel"), poster.calls)

        val second = posts.start()
        assertEquals(first + 3_600_000, second)
        assertEquals("A new session never re-shows the last session's content",
            Posted("startup defaults", second), posts.promotion { built("startup defaults") })
    }
}
