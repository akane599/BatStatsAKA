package com.akane.voltwise.battery.drain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PromotionNotificationTest {
    @Test fun `repeat start preserves the last posted session icon and units`() {
        val posted = "session=discharge; icon=250mA; temperature=F"
        val promoted = promotionNotification(posted) { "session=none; icon=static; temperature=C" }

        assertEquals("Repeat promotion must retain the live notification", posted, promoted)
    }

    @Test fun `repeat start does not build a default notification`() {
        val posted = Any()
        var builds = 0

        val promoted = promotionNotification(posted) {
            builds++
            Any()
        }

        assertEquals("A cached promotion must not rebuild defaults", 0, builds)
        assertSame(posted, promoted)
    }

    @Test fun `without a posted notification promotion builds the fallback`() {
        val fallback = Any()
        var builds = 0

        val promoted = promotionNotification(null) {
            builds++
            fallback
        }

        assertEquals(1, builds)
        assertSame(fallback, promoted)
    }
}
