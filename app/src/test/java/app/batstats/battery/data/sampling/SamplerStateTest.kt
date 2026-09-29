package app.batstats.battery.data.sampling

import org.junit.Assert.*
import org.junit.Test

class SamplerStateTest {
    @Test fun tapersRoundTripByChargerAndSkipUnreadableEntries() {
        val prefs = FakeKeyValueStore()
        val state = SamplerState(prefs)
        assertEquals(emptyMap<Int, Long>(), state.loadTapers())
        state.saveTapers(mapOf(2 to 240_000L, 1 to 90_000L))
        assertEquals(mapOf(1 to 90_000L, 2 to 240_000L), SamplerState(prefs).loadTapers())
        prefs.values["tapers"] = "1:90000,x:5,4:-3,8:120000,9,2:"
        assertEquals(mapOf(1 to 90_000L, 8 to 120_000L), state.loadTapers())
        state.saveTapers(emptyMap())
        assertNull(prefs.values["tapers"])
    }

    @Test fun backfillFlagIsStoredOnce() {
        val prefs = FakeKeyValueStore()
        assertFalse(SamplerState(prefs).backfillDone)
        SamplerState(prefs).backfillDone = true
        assertTrue(SamplerState(prefs).backfillDone)
        prefs.values["daily_backfill_done"] = "yes"
        assertFalse(SamplerState(prefs).backfillDone)
    }
}
