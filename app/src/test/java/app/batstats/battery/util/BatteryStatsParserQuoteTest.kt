package app.batstats.battery.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic checkin v9 rows, not device measurements. */
class BatteryStatsParserQuoteTest {
    @Test fun singleQuoteInWakelockTagDoesNotSilentlyLoseTheRow() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,wl,foo"bar_baz,100,f,2,0,60,100,200,p,3,0,100,200,50,bp,1,0,50,50,300,w,4,0,100,300
            9,10002,l,wl,next,400,p,5,0,400,400
            9,10002,l,sy,following,500,6,0,0
        """.trimIndent())

        val affected = snapshot.wakelocks.filter { it.uid == 10001 }
        assertEquals("The affected row must parse or be counted as rejected", true,
            affected.isNotEmpty() || snapshot.rejectedRecords > 0)
        assertEquals("next", snapshot.wakelocks.single { it.uid == 10002 }.tag)
        assertEquals(400L, snapshot.wakelocks.single { it.uid == 10002 }.totalTimeMs)
        assertEquals("following", snapshot.syncs.single().authority)
        assertEquals(500L, snapshot.syncs.single().totalTimeMs)
    }

    @Test fun rawWakelockAndAlarmQuotesArePreserved() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,wl,foo"bar_baz,200,p,3,0,100,200
            9,10001,l,wua,"alarm",7
        """.trimIndent())

        assertEquals("foo\"bar_baz", snapshot.wakelocks.single().tag)
        assertEquals(200L, snapshot.wakelocks.single().totalTimeMs)
        assertEquals(3, snapshot.wakelocks.single().count)
        assertEquals("\"alarm\"", snapshot.alarms.single().tag)
        assertEquals(0, snapshot.rejectedRecords)
    }

    @Test fun jobAndSyncNamesUseNumericTailNotCsvEscaping() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,jb,"foo"bar,baz",12345,7,4321,3
            9,10001,l,sy,""authority",with,commas"",7654,2,1234,1
        """.trimIndent())

        val job = snapshot.jobs.single()
        assertEquals("foo\"bar,baz", job.jobName)
        assertEquals(12345L, job.totalTimeMs)
        assertEquals(7, job.count)
        assertEquals(4321L, job.backgroundTimeMs)
        assertEquals(3, job.backgroundCount)
        val sync = snapshot.syncs.single()
        assertEquals("\"authority\",with,commas\"", sync.authority)
        assertEquals(7654L, sync.totalTimeMs)
        assertEquals(2, sync.count)
        assertEquals(1234L, sync.backgroundTimeMs)
        assertEquals(1, sync.backgroundCount)
        assertEquals(0, snapshot.rejectedRecords)
    }

    @Test fun malformedNamedRowsAreCountedAndFollowingLinesSurvive() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,wl
            9,10001,l,wl,foo"bar,100,p
            9,10001,l,wl,foo"bar,100,unknown,2,0,100,100
            9,10001,l,sy,"foo"bar,baz",broken,2,0,0
            9,10001,l,jb,"unfinished,100,2
            9,10001,l,pr,"unfinished
            9,0,l,kwl,"unfinished
            9,10002,l,sy,"following",500,6,0,0
        """.trimIndent())

        assertEquals(7, snapshot.rejectedRecords)
        assertEquals("following", snapshot.syncs.single().authority)
        assertEquals(500L, snapshot.syncs.single().totalTimeMs)
        assertEquals(true, snapshot.wakelocks.isEmpty())
        assertEquals(true, snapshot.jobs.isEmpty())
        assertEquals(true, snapshot.processStats.isEmpty())
        assertEquals(true, snapshot.kernelWakelocks.isEmpty())
    }

    @Test fun processAndKernelNamesKeepExistingFieldOffsets() {
        val snapshot = BatteryStatsParser.parseCheckin("""
            9,10001,l,pr,"foo"bar",100,200,300,4
            9,0,l,kwl,"foo"bar",100,2,0,60,100
        """.trimIndent())

        assertEquals("foo\"bar", snapshot.processStats.single().processName)
        assertEquals(100L, snapshot.processStats.single().userTimeMs)
        assertEquals(4, snapshot.processStats.single().starts)
        assertEquals("foo\"bar", snapshot.kernelWakelocks.single().name)
        assertEquals(100L, snapshot.kernelWakelocks.single().totalTimeMs)
        assertEquals(60L, snapshot.kernelWakelocks.single().maxTimeMs)
        assertEquals(0, snapshot.rejectedRecords)
    }
}
