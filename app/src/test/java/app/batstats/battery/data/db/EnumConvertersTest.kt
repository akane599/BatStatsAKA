package app.batstats.battery.data.db

import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.apps.AppUsageStatus
import org.junit.Assert.*
import org.junit.Test

class EnumConvertersTest {
    private val converters = EnumConverters()

    @Test fun everyKnownNameRoundTrips() {
        SessionType.entries.forEach { assertEquals(it, converters.toSessionType(converters.fromSessionType(it))) }
        AppSnapshotKind.entries.forEach { assertEquals(it, converters.toSnapshotKind(converters.fromSnapshotKind(it))) }
        AppUsageStatus.entries.forEach { assertEquals(it, converters.toAppUsageStatus(converters.fromAppUsageStatus(it))) }
        AppUsageBasis.entries.forEach { assertEquals(it, converters.toAppUsageBasis(converters.fromAppUsageBasis(it))) }
    }

    @Test fun unknownNamesInNullableColumnsReadAsNull() {
        listOf("SOMETHING_NEW", "ready", "", " READY").forEach { junk ->
            assertNull(junk, converters.toAppUsageStatus(junk))
            assertNull(junk, converters.toAppUsageBasis(junk))
        }
        assertNull(converters.toAppUsageStatus(null))
        assertNull(converters.toAppUsageBasis(null))
    }

    @Test fun unknownNamesInNotNullColumnsReadAsTheSafeFallback() {
        assertEquals(SessionType.UNKNOWN, converters.toSessionType("HYBRID"))
        assertEquals(SessionType.UNKNOWN, converters.toSessionType("discharge"))
        assertEquals("Never a protected baseline", AppSnapshotKind.END, converters.toSnapshotKind("MIDPOINT"))
        assertNull(converters.toSessionType(null))
        assertNull(converters.toSnapshotKind(null))
    }
}
