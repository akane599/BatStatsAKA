package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.util.BatteryStatsParser
import org.junit.Assert.*
import org.junit.Test

class AppUsageSnapshotTest {
    private fun app(
        uid: Int,
        packageName: String = "uid$uid",
        packages: List<String> = emptyList(),
        power: Double = 1.0,
        wakeLock: Long? = null,
        mobileRx: Long? = null,
        mobileTx: Long? = null,
        wifiRx: Long? = null,
        wifiTx: Long? = null,
    ) = BatteryStatsParser.AppPowerStats(
        uid = uid, packageName = packageName, packages = packages, powerMah = power,
        wakeLockTimeMs = wakeLock,
        mobileRxBytes = mobileRx, mobileTxBytes = mobileTx,
        wifiRxBytes = wifiRx, wifiTxBytes = wifiTx,
    )

    @Test fun windowFieldsAreCopiedFromTheFullSnapshot() {
        val full = BatteryStatsParser.FullSnapshot(
            capturedAt = 12345L, startedAt = 999L, startCount = 3L, apps = emptyList(),
        )
        val snapshot = full.toAppUsageSnapshot()
        assertEquals(999L, snapshot.windowStartedAt)
        assertEquals(3L, snapshot.windowStartCount)
        assertEquals(12345L, snapshot.capturedAt)
        assertTrue(snapshot.rows.isEmpty())
    }

    @Test fun wakelockTimeIsMappedFromWakeLockTimeMs() {
        val full = BatteryStatsParser.FullSnapshot(apps = listOf(app(1, wakeLock = 4200L)))
        assertEquals(4200L, full.toAppUsageSnapshot().rows.single().wakelockTimeMs)
    }

    @Test fun aSinglePackageUidIsThatApp() {
        val single = app(10_123, packageName = "com.a", packages = listOf("com.a"))
        assertEquals(UidIdentity.App("com.a"), single.identity())
        val full = BatteryStatsParser.FullSnapshot(apps = listOf(single))
        assertEquals("com.a", full.toAppUsageSnapshot().rows.single().packageName)
    }

    @Test fun aMultiPackageUidIsSharedWithItsMemberCountWhateverTheDumpOrder() {
        val forward = app(10_050, packageName = "Shared UID 10050", packages = listOf("com.b", "com.a", "com.c"))
        val reversed = forward.copy(packages = listOf("com.c", "com.a", "com.b"))
        val identity = forward.identity()
        assertEquals(UidIdentity.Shared(listOf("com.a", "com.b", "com.c")), identity)
        assertEquals(3, (identity as UidIdentity.Shared).memberCount)
        assertEquals("the dump's package order never changes the identity", identity, reversed.identity())
        // Power stays the uid's: one row, the whole uid's mAh, under the stable representative.
        val rows = BatteryStatsParser.FullSnapshot(apps = listOf(forward.copy(powerMah = 42.0))).toAppUsageSnapshot().rows
        assertEquals(10_050, rows.single().uid)
        assertEquals(42.0, rows.single().powerMah, 1e-9)
        assertEquals("com.a", rows.single().packageName)
        assertEquals("com.a", BatteryStatsParser.FullSnapshot(apps = listOf(reversed)).toAppUsageSnapshot().rows.single().packageName)
    }

    @Test fun aRepeatedPackageIsOneMember() {
        assertEquals(UidIdentity.App("com.a"), app(10_001, packages = listOf("com.a", "com.a")).identity())
    }

    @Test fun packageNameFallsBackToTheDisplayLabelWhenThereIsNoRealPackage() {
        val system = app(1000, packageName = "System UID 1000", packages = emptyList())
        assertEquals(UidIdentity.NoPackage("System UID 1000"), system.identity())
        val full = BatteryStatsParser.FullSnapshot(apps = listOf(system))
        assertEquals("System UID 1000", full.toAppUsageSnapshot().rows.single().packageName)
    }

    @Test fun bytesSumRxAndTxNullOnlyWhenBothSidesAreUnknown() {
        val bothNull = app(1, mobileRx = null, mobileTx = null)
        val oneNull = app(2, mobileRx = 100L, mobileTx = null, wifiRx = null, wifiTx = 50L)
        val both = app(3, mobileRx = 100L, mobileTx = 200L, wifiRx = 10L, wifiTx = 20L)
        val full = BatteryStatsParser.FullSnapshot(apps = listOf(bothNull, oneNull, both))
        val rows = full.toAppUsageSnapshot().rows.associateBy { it.uid }
        assertNull(rows.getValue(1).mobileBytes)
        assertNull(rows.getValue(1).wifiBytes)
        assertEquals(100L, rows.getValue(2).mobileBytes)
        assertEquals(50L, rows.getValue(2).wifiBytes)
        assertEquals(300L, rows.getValue(3).mobileBytes)
        assertEquals(30L, rows.getValue(3).wifiBytes)
    }
}
