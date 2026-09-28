package app.batstats.battery.util

import org.junit.Assert.*
import org.junit.Test

class ReadCacheTest {
    private var now = 1_000L
    private val cache = ReadCache<String?>(ttlMs = 30_000, elapsedMs = { now })

    @Test fun resultsAreReusedUntilTheTtlIncludingFailures() {
        cache.put("CPU", "policy=0")
        cache.put("Wake sources", null)
        now += 29_999
        assertEquals("policy=0", cache.get("CPU")?.value)
        assertNotNull("A failed read is not retried either", cache.get("Wake sources"))
        assertNull(cache.get("Wake sources")?.value)
        now += 1
        assertNull(cache.get("CPU"))
        assertNull(cache.get("Wake sources"))
    }

    @Test fun clearDropsEveryEntry() {
        cache.put("Thermal", "zone=0")
        cache.clear()
        assertNull(cache.get("Thermal"))
    }

    @Test fun aClockThatWentBackwardsExpiresTheEntry() {
        cache.put("Battery", "POWER_SUPPLY_CAPACITY=50")
        now -= 1
        assertNull(cache.get("Battery"))
    }
}
