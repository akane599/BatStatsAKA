package com.akane.voltwise.battery.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.SettingsMigrator
import com.akane.voltwise.settings.retentionDays

/** Single-writer age maintenance; size limits remain independent of this settings/clock authority. */
class HistoryRetention(private val migrator: SettingsMigrator, private val store: DataStore<Preferences>) {
    private data class Clock(val wallMs: Long, val elapsedMs: Long, val generation: String)
    private var clock: Clock? = null

    /**
     * Uses the durable retention clock, seeded from history read BEFORE the generation's first write.
     * A future sample can never replace that authority, even across process restarts. Without a
     * prior reference, the first call establishes one but does not purge. Missing/invalid retention
     * choices (including corruption recovery) pause age deletion; failed migrations still throw.
     *
     * whittle: count only monotonic time within a generation, not offline time. History can live
     * longer than the chosen age. Count offline time only when an independent trusted clock exists.
     * Initial adoption assumes the pre-existing history reference was recorded with a sane clock.
     */
    suspend fun cutoff(nowMs: Long, elapsedMs: Long = 0, generation: String = "", previousWallMs: Long? = null): Long? {
        check(migrator.awaitMigrated()) { "Settings migration did not complete; history retention is paused" }
        val before = clock?.takeIf { it.generation == generation && elapsedMs >= it.elapsedMs }
        var hasReference = false
        var days: Long? = null
        val prefs = store.edit {
            val reference = before?.wallMs ?: it[TRUSTED_NOW] ?: previousWallMs
            hasReference = reference != null
            val elapsed = before?.let { elapsedMs - it.elapsedMs } ?: 0
            it[TRUSTED_NOW] = minOf(nowMs, (reference ?: nowMs) + elapsed)
            days = it[RETENTION_INDEX]?.takeIf { index -> index in 0..5 }
                ?.let { index -> AppSettings(dataRetentionIndex = index).retentionDays }
        }
        val trustedNow = checkNotNull(prefs[TRUSTED_NOW])
        clock = Clock(trustedNow, elapsedMs, generation)
        return if (hasReference) days?.let { trustedNow - it * DAY_MS } else null
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        val RETENTION_INDEX = intPreferencesKey("data_retention_index")
        val TRUSTED_NOW = longPreferencesKey("__history_retention_now_ms")
    }
}
