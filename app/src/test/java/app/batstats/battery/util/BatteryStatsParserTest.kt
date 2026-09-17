package app.batstats.battery.util

import org.junit.Assert.*
import org.junit.Test

/** Synthetic records follow android16-release BatteryStats.java; they are not device measurements. */
class BatteryStatsParserTest {
    @Test fun jobAndSyncTimePrecedeCountAndKeepBackgroundValues() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,jb,"job,with,commas",12345,7,4321,3
            9,10001,l,sy,authority,7654,2,1234,1
        """.trimIndent())
        val job = snapshot.jobs.single()
        assertEquals("job,with,commas", job.jobName)
        assertEquals(12345L, job.totalTimeMs)
        assertEquals(7, job.count)
        assertEquals(4321L, job.backgroundTimeMs)
        assertEquals(3, job.backgroundCount)
        assertEquals(7654L, snapshot.syncs.single().totalTimeMs)
        assertEquals(2, snapshot.syncs.single().count)
    }
    @Test fun zeroDozeIsAReportedZeroAndIdlingIsNotMaintenance() {
        val zero = BatteryStatsParser.parseCheckin("9,0,l,m," + List(21) { "0" }.joinToString(",")).doze
        assertNotNull(zero)
        assertEquals(0L, zero!!.deepIdleTimeMs)
        val values = MutableList(21) { "0" }
        values[9] = "12000"; values[10] = "2"; values[11] = "20000"; values[12] = "4"
        values[15] = "3000"; values[16] = "1"
        val doze = BatteryStatsParser.parseCheckin("9,0,l,m," + values.joinToString(",")).doze!!
        assertEquals(12000L, doze.deepIdleTimeMs)
        assertEquals(3000L, doze.lightIdleTimeMs)
        assertNull("Idling is not a maintenance-window duration", doze.maintenanceTimeMs)
    }
    @Test fun globalBluetoothDoesNotUsePerUidControllerOrScanRows() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,0,l,gble,100,20,2,1,30
            9,10001,l,ble,1000,2000,20,1,3000
            9,10001,l,blem,120,4,2,140,10,3,1,30,10,20,10
        """.trimIndent())
        val bt = snapshot.bluetooth!!
        assertEquals(100L, bt.idleTimeMs)
        assertEquals(20L, bt.rxTimeMs)
        assertEquals(30L, bt.txTimeMs)
        assertEquals(2.0, bt.powerMah, 0.0)
    }
    @Test fun uidCountersPopulateOnlyReportedActivity() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,0,i,uid,10001,example.app
            9,10001,l,pwi,uid,1.5,0,0.5,2.0
            9,10001,l,cpu,100,200,0
            9,10001,l,fg,1500,2
            9,10001,l,fgs,2000,1
            9,10001,l,st,1000,2000,3000,4000,5000,6000,7000
            9,10001,l,nt,10,20,30,40,1,2,3,4,2000000,5,60,70
        """.trimIndent())
        val app = snapshot.apps.single()
        assertEquals(300L, app.cpuTimeMs)
        assertEquals(1500L, app.foregroundTimeMs)
        assertEquals(2000L, app.foregroundServiceTimeMs)
        assertEquals(4000L, app.backgroundTimeMs)
        assertEquals(7000L, app.cachedTimeMs)
        assertEquals(10L, app.mobileRxBytes)
        assertNotNull(app.screenPowerMah)
        assertEquals(0.5, app.screenPowerMah!!, 0.0)
        assertEquals(2000L, snapshot.network.single().mobileActiveTimeMs)
        assertEquals(60L, snapshot.network.single().btRxBytes)
    }
    @Test fun mappingsCanFollowUsageAndPreserveSharedIdentities() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,3.0,0,0,0
            9,0,i,uid,10001,example.one
            9,0,i,uid,10001,example.two
        """.trimIndent())
        assertEquals(listOf("example.one", "example.two"), snapshot.apps.single().packages)
        assertTrue(snapshot.apps.single().packageName.contains("10001"))
    }
    @Test fun secondaryProfileUsesAppIdMappingWithoutLosingUid() {
        val snapshot = BatteryStatsParser.parseCheckin("9,0,i,uid,10001,example.app\n9,110001,l,pwi,uid,1.0,0,0,0")
        assertEquals(110001, snapshot.apps.single().uid)
        assertEquals(listOf("example.app"), snapshot.apps.single().packages)
    }
    @Test fun packagePrefixesDoNotDetermineWhetherAnUidIsAnApplication() {
        assertTrue(BatteryStatsParser.isUserApp(10001, listOf("com.google.android.gm")))
        assertFalse(BatteryStatsParser.isUserApp(1000, listOf("android")))
    }
    @Test fun invalidEnergyIsUnavailableInsteadOfNaNOrZero() {
        for (value in listOf("NaN", "Infinity", "-1", "broken")) {
            val snapshot = BatteryStatsParser.parseCheckin("9,10001,l,pwi,uid,$value,0,0,0")
            assertTrue("Invalid estimate $value must not create a ranked row", snapshot.apps.isEmpty())
        }
    }
    @Test fun alarmRecordIsAWakeupCountNotAZeroDuration() {
        val alarm = BatteryStatsParser.parseCheckin("9,10001,l,wua,tag,8").alarms.single()
        assertEquals(8, alarm.wakeups)
        assertNull(alarm.totalTimeMs)
    }
    @Test fun fullPartialAndWindowWakelocksRemainDistinct() {
        val snapshot = BatteryStatsParser.parseCheckin("9,10001,l,wl,tag,100,f,2,0,60,100,200,p,3,0,100,200,50,bp,1,0,50,50,300,w,4,0,100,300")
        assertEquals(3, snapshot.wakelocks.size)
        assertEquals(100L, snapshot.wakelocks.single { it.type == BatteryStatsParser.WakelockType.FULL }.totalTimeMs)
        assertEquals(200L, snapshot.wakelocks.single { it.type == BatteryStatsParser.WakelockType.PARTIAL }.totalTimeMs)
        assertEquals(300L, snapshot.wakelocks.single { it.type == BatteryStatsParser.WakelockType.WINDOW }.totalTimeMs)
    }
    @Test fun missingPowerStateIsNotAnOffScreenOrAnEmptyBattery() {
        val power = BatteryStatsParser.parsePowerManager("Wake Locks: size=0")
        assertNull(power.isScreenOn)
        assertNull(power.batteryLevel)
    }
    @Test fun pluggedDoesNotMeanChargingAndBooleansAreParsedIndependently() {
        val power = BatteryStatsParser.parsePowerManager("mIsPowered=true")
        assertFalse(power.batteryStatus.equals("Charging", ignoreCase = true))
        val idle = BatteryStatsParser.parseDeviceIdle("mDeepEnabled=false mLightEnabled=true")
        assertEquals(false, idle.deepEnabled)
        assertEquals(true, idle.lightEnabled)
    }
    @Test fun coreWindowMustHaveValidEpochAndComparableDurations() {
        val good = BatteryStatsParser.parseCheckin("9,0,l,bt,2,60000,50000,100000,80000,1700000000000,30000,20000,4000,3800000,3900000,10000")
        assertTrue(good.hasValidWindow)
        assertEquals(1700000000000L, good.startedAt)
        assertEquals(30000L, good.screenOffTimeMs)
        assertFalse(BatteryStatsParser.parseCheckin("9,0,l,bt,2,60000").hasValidWindow)
        assertFalse(BatteryStatsParser.parseCheckin("9,0,l,bt,2,100,200,100,200,1700000000000").hasValidWindow)
    }
    @Test fun unsupportedComponentAndActivityDoNotBecomeZero() {
        val app = BatteryStatsParser.parseCheckin("9,10001,l,pwi,uid,0,0,0,0").apps.single()
        assertEquals(0.0, app.powerMah, 0.0)
        assertNull(app.cpuTimeMs)
        assertNull(app.cpuPowerMah)
        assertNull(app.foregroundTimeMs)
    }
    @Test fun duplicatePowerRowsDoNotDoubleCountAndFrequencyStatesAreNotSummedTwice() {
        val s = BatteryStatsParser.parseCheckin("""
            9,10001,l,pwi,uid,1.5,0,0,0
            9,10001,l,pwi,uid,1.5,0,0,0
            9,0,l,gcf,100000,200000
            9,10001,l,ctf,A,2,100,200,50,100
            9,10001,l,ctf,T,2,50,100,20,40
        """.trimIndent())
        assertEquals(1.5, s.apps.single().powerMah, 0.0)
        assertEquals(1, s.rejectedRecords)
        assertEquals(listOf(100L, 200L), s.cpuFrequency.map { it.timeMs })
    }
    @Test fun wrongVersionOtherWindowsAndUnclosedQuotesAreRejected() {
        val s = BatteryStatsParser.parseCheckin("8,10001,l,pwi,uid,10\n9,10001,u,pwi,uid,20\n9,10001,l,jb,\"unfinished,100,2")
        assertTrue(s.apps.isEmpty())
        assertTrue(s.jobs.isEmpty())
        assertFalse(s.hasValidWindow)
    }

}
