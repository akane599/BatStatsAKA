package app.batstats.battery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.batstats.battery.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryBrowseTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BatteryDatabase::class.java).build()
    @Test fun pagingAndFiltersReachRecordsOlderThanTheFirstHundred() = runBlocking {
        val db = database()
        try {
            repeat(125) { i -> db.sessionDao().insert(ChargeSession("s$i", if (i == 0) SessionType.CHARGE else SessionType.DISCHARGE,
                i * 1000L, i * 1000L + 500, 80, 80, null, null, null,
                source = if (i == 0) "import:saved_origin" else "BatteryManager observed interval")) }
            assertEquals(51, db.sessionDao().filteredSessions(null, "", 51).first().size)
            assertEquals(125, db.sessionDao().filteredSessions(null, "", 151).first().size)
            assertEquals(listOf("s0"), db.sessionDao().filteredSessions(SessionType.CHARGE, "SAVED_", 51).first().map { it.sessionId })
        } finally { db.close() }
    }
    @Test fun representativeSessionChartKeepsDiscontinuitiesAndExcludesOtherSessions() = runBlocking {
        val db = database()
        try {
            val sample = BatterySample(timestamp = 1000, levelPercent = 80, status = 3, plugged = 0, currentNowUa = -123,
                chargeCounterUah = 4_000_000, voltageMv = 4000, temperatureDeciC = 250, health = 2,
                screenOn = true, observationId = "local", sessionId = "session", source = "BatteryManager")
            repeat(1001) { i -> db.batteryDao().insertSample(sample.copy(timestamp = i * 1000L,
                currentNowUa = if (i == 400) null else -123, boundaryReason = if (i == 501) "gap" else null)) }
            db.batteryDao().insertSample(sample.copy(sessionId = "foreign", currentNowUa = 999999))
            val rows = db.batteryDao().sessionChartSamples("session", 0, 1_000_000, 1_000_000 / 360 + 1)
            assertTrue(rows.size <= 361)
            assertTrue(rows.count { it.discontinuity } >= 2)
            assertFalse(rows.any { it.currentNowUa == 999999L })
            assertEquals(1002, db.batteryDao().count())
        } finally { db.close() }
    }
}
