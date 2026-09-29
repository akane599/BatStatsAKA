package app.batstats.battery.drain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [NotificationIssue.of]: which problem line the ongoing notification shows. */
class NotificationIssueTest {
    @Test fun aHistoryProblemWins() {
        assertEquals(NotificationIssue.COLLECTION, NotificationIssue.of("disk full", "dump failed", hasAdvancedAccess = true))
        assertEquals(NotificationIssue.COLLECTION, NotificationIssue.of("disk full", null, hasAdvancedAccess = false))
    }

    @Test fun aFailedPrivilegedReadCountsOnlyWithAdvancedAccessSetUp() {
        assertEquals(NotificationIssue.ADVANCED, NotificationIssue.of(null, "dump failed", hasAdvancedAccess = true))
        assertNull("No access: every read fails by design", NotificationIssue.of(null, "Privileged access unavailable", hasAdvancedAccess = false))
    }

    @Test fun nothingWrongIsNoLine() {
        assertNull(NotificationIssue.of(null, null, hasAdvancedAccess = true))
    }
}
