package com.akane.voltwise.battery.data

import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.SettingsMigrator
import com.akane.voltwise.settings.retentionDays
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** The age cutoff for the writer's history maintenance, read only from migrated (v3) settings. */
class HistoryRetention(private val migrator: SettingsMigrator, private val settings: Flow<AppSettings>) {
    /**
     * Epoch ms before which history is purged, or null for Forever. Suspends until the settings
     * migration has ended, however early the first maintenance comes; throws
     * [IllegalStateException] when it did not reach the current schema, so nothing is purged.
     */
    suspend fun cutoff(nowMs: Long): Long? {
        check(migrator.awaitMigrated()) { "Settings migration did not complete; history retention is paused" }
        return settings.first().retentionDays?.let { nowMs - it * DAY_MS }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
