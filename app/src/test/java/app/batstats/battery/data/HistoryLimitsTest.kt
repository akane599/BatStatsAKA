package app.batstats.battery.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryLimitsTest {
    @Test fun samplesRemainExportableAndImportableUntilTheNextTrim() {
        assertTrimHeadroom(HistoryLimits.SAMPLE_TRIM_TARGET, HistoryLimits.MAX_SAMPLES)
    }

    @Test fun sessionsRemainExportableAndImportableUntilTheNextTrim() {
        // A persisted sample can open one new session at a power/gap boundary.
        assertTrimHeadroom(HistoryLimits.SESSION_TRIM_TARGET, HistoryLimits.MAX_SESSIONS)
    }

    @Test fun duplicateImportIsAllowedWithExistingExcessSamples() {
        val existing = HistoryLimits.MAX_SAMPLES + 199
        assertTrue("Unchanged excess samples must not block an import", HistoryLimits.importWithinLimit(existing, existing, HistoryLimits.MAX_SAMPLES))
    }

    @Test fun sampleOnlyImportIsAllowedWithExistingExcessSessions() {
        val existing = HistoryLimits.MAX_SESSIONS + 199
        assertTrue("Unchanged excess sessions must not block an import", HistoryLimits.importWithinLimit(existing, existing, HistoryLimits.MAX_SESSIONS))
    }

    @Test fun importsMayFillEitherStoreExactlyToItsCap() {
        for (limit in listOf(HistoryLimits.MAX_SAMPLES, HistoryLimits.MAX_SESSIONS)) {
            assertTrue(HistoryLimits.importWithinLimit(limit - 1, limit, limit))
        }
    }

    @Test fun importsCannotGrowEitherStorePastItsCap() {
        for (limit in listOf(HistoryLimits.MAX_SAMPLES, HistoryLimits.MAX_SESSIONS)) {
            assertFalse(HistoryLimits.importWithinLimit(limit, limit + 1, limit))
            assertFalse(HistoryLimits.importWithinLimit(limit + 199, limit + 200, limit))
        }
    }

    @Test fun reducingExistingExcessDoesNotBlockAnImport() {
        assertTrue(HistoryLimits.importWithinLimit(100_199, 100_198, HistoryLimits.MAX_SAMPLES))
    }

    private fun assertTrimHeadroom(target: Int, limit: Int) {
        for (added in 0..HistoryLimits.CLEANUP_SAMPLE_INTERVAL) {
            val stored = target + added
            assertTrue("Post-trim count $target plus $added rows must remain exportable (cap $limit)", stored <= limit)
            assertTrue("Post-trim history must remain importable", HistoryLimits.importWithinLimit(target, stored, limit))
        }
    }
}
