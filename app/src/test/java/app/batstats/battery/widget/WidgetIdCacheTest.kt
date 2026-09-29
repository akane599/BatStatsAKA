package app.batstats.battery.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetIdCacheTest {
    private val cache = WidgetIdCache<String>()
    private var lookups = 0
    private fun lookup(vararg ids: Int): () -> IntArray = { lookups++; ids }

    @Test fun answerIsCachedUntilTheProviderInvalidates() {
        assertArrayEquals(intArrayOf(1, 2), cache.get("level", lookup(1, 2)))
        assertArrayEquals(intArrayOf(1, 2), cache.get("level", lookup(9)))
        assertEquals(1, lookups)
        cache.invalidate("level")
        assertArrayEquals(intArrayOf(3), cache.get("level", lookup(3)))
        assertEquals(2, lookups)
    }

    @Test fun providersAreCachedSeparately() {
        cache.get("level", lookup())
        cache.get("temp", lookup(4))
        cache.invalidate("temp")
        assertArrayEquals(intArrayOf(), cache.get("level", lookup(7)))
        assertArrayEquals(intArrayOf(5), cache.get("temp", lookup(5)))
        assertEquals(3, lookups)
    }

    @Test fun invalidationDuringALookupKeepsItsAnswerOutOfTheCache() {
        val stale = cache.get("level") { cache.invalidate("level"); intArrayOf() }
        assertArrayEquals(intArrayOf(), stale)
        assertArrayEquals(intArrayOf(8), cache.get("level", lookup(8)))
        assertEquals(1, lookups)
    }
}
