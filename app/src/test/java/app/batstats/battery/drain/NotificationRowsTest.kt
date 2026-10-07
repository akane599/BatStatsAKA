package app.batstats.battery.drain

import app.batstats.battery.drain.NotificationFitter.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NotificationFitter.rows]: which optional rows the custom views keep at a font scale. */
class NotificationRowsTest {
    @Test fun upToTheLayoutsDesignScaleEveryRowShows() {
        for (scale in listOf(0.85f, 1f, 1.15f, 1.3f)) {
            assertEquals("${scale}x", Row.entries.toSet(), NotificationFitter.rows(scale))
        }
    }

    @Test fun aboveItTheIssueLineSurvivesAndTheSummaryAndFooterGo() {
        for (scale in listOf(1.5f, 2f)) {
            val rows = NotificationFitter.rows(scale)
            assertTrue("${scale}x must keep the issue line", Row.ISSUE in rows)
            assertFalse("${scale}x must drop the collapsed summary (it is the content text)", Row.SUMMARY in rows)
            assertFalse("${scale}x must drop the expanded footer before the issue line", Row.FOOTER in rows)
        }
    }
}
