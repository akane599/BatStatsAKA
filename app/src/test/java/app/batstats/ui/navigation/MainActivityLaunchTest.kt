package app.batstats.ui.navigation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityLaunchTest {
    @Test
    fun `external launch reuses main activity instead of stacking a second instance`() {
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            mainActivityLaunchFlags(),
        )
    }
}
