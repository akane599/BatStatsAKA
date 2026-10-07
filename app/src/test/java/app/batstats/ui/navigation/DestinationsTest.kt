package app.batstats.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationsTest {
    @Test
    fun `restored activity ignores original launch destination`() {
        assertNull(
            Destinations.initialDestination(
                extra = Destinations.NOW,
                restored = true,
                launchedFromHistory = false,
            ),
        )
    }

    @Test
    fun `history launch without saved state ignores original destination`() {
        assertNull(
            Destinations.initialDestination(
                extra = Destinations.NOW,
                restored = false,
                launchedFromHistory = true,
            ),
        )
    }

    @Test
    fun `restored history launch ignores original destination`() {
        assertNull(
            Destinations.initialDestination(
                extra = Destinations.NOW,
                restored = true,
                launchedFromHistory = true,
            ),
        )
    }

    @Test
    fun `fresh launch forwards destination unchanged`() {
        val destinations = listOf(Destinations.NOW, Destinations.APPS, Destinations.session("42"))

        for (extra in destinations) {
            assertEquals(
                extra,
                Destinations.initialDestination(
                    extra = extra,
                    restored = false,
                    launchedFromHistory = false,
                ),
            )
        }
    }

    @Test
    fun `fresh launch without destination has no request`() {
        assertNull(
            Destinations.initialDestination(
                extra = null,
                restored = false,
                launchedFromHistory = false,
            ),
        )
    }
}
