package com.akane.voltwise.battery.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.akane.voltwise.battery.data.sampling.SamplerState
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.SETTINGS_RECOVERED
import com.akane.voltwise.settings.SettingsMigrator
import com.akane.voltwise.settings.retentionDays
import kotlinx.coroutines.flow.first

/** Single-writer age maintenance; size limits remain independent of this settings/clock authority. */
class HistoryRetention(
    private val migrator: SettingsMigrator,
    private val store: DataStore<Preferences>,
    private val state: SamplerState,
    private val bootCount: () -> Int,
) {
    /**
     * Uses a non-backed-up clock, seeded from history read BEFORE the generation's first write.
     * A future sample cannot replace that authority; a backwards wall clock cannot lower it.
     * Without a prior reference the first call establishes one but does not purge. An absent choice
     * uses the normal default unless settings recovered from corruption; invalid choices pause.
     *
     * whittle: elapsedRealtime counts same-boot gaps, including monitoring stops/process restarts,
     * but time across a reboot is not trusted. History can live longer than the chosen age. Count
     * cross-boot offline time only when an independent trusted clock exists. Initial adoption
     * assumes the pre-existing history reference was recorded with a sane clock.
     */
    suspend fun cutoff(nowMs: Long, elapsedMs: Long = 0, previousWallMs: Long? = null): Long? {
        check(migrator.awaitMigrated()) { "Settings migration did not complete; history retention is paused" }
        val prefs = store.data.first()
        val boot = bootCount()
        val before = state.retentionClock
        val reference = before?.wallMs ?: previousWallMs?.coerceAtMost(nowMs)
        val elapsed = if (before != null && before.bootCount == boot && elapsedMs >= before.elapsedMs) {
            elapsedMs - before.elapsedMs
        } else 0
        val trustedNow = reference?.let { maxOf(it, minOf(nowMs, it + elapsed)) } ?: nowMs
        state.retentionClock = SamplerState.RetentionClock(trustedNow, elapsedMs, boot)
        val index = prefs[RETENTION_INDEX]
            ?: if (prefs[SETTINGS_RECOVERED] != true) AppSettings().dataRetentionIndex else null
        val days = index?.takeIf { it in 0..5 }?.let { AppSettings(dataRetentionIndex = it).retentionDays }
        return if (reference != null) days?.let { trustedNow - it * DAY_MS } else null
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        val RETENTION_INDEX = intPreferencesKey("data_retention_index")
    }
}
