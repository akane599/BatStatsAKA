package app.batstats.battery.apps

import app.batstats.battery.util.BatteryStatsParser
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

    @Test fun packageNamePrefersTheFirstRealPackageOverTheDisplayLabel() {
        val shared = app(1, packageName = "Shared UID 1", packages = listOf("com.a", "com.b"))
        val full = BatteryStatsParser.FullSnapshot(apps = listOf(shared))
        assertEquals("com.a", full.toAppUsageSnapshot().rows.single().packageName)
    }

    @Test fun packageNameFallsBackToTheDisplayLabelWhenThereIsNoRealPackage() {
        val system = app(1000, packageName = "System UID 1000", packages = emptyList())
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
