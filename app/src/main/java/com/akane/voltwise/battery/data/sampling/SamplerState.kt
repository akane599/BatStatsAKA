package com.akane.voltwise.battery.data.sampling

/**
 * Sampler bookkeeping kept outside Room and outside the settings export, in non-backed-up
 * SharedPreferences (file [PREFS_NAME]): ChargeEta's learned 80→100 % tapers per charger and the
 * one-time daily-summaries backfill flag. "Clear all data" keeps both (learned device behaviour).
 */
class SamplerState(private val store: KeyValueStore) {
    var backfillDone: Boolean
        get() = store.getString(KEY_BACKFILL) == true.toString()
        set(value) = store.edit(mapOf(KEY_BACKFILL to if (value) true.toString() else null))

    /** `EXTRA_PLUGGED` value → ms per percent; unreadable or non-positive entries are skipped. */
    fun loadTapers(): Map<Int, Long> = store.getString(KEY_TAPERS).orEmpty().split(',').mapNotNull { entry ->
        val parts = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val plugged = parts[0].toIntOrNull() ?: return@mapNotNull null
        val msPerPercent = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        plugged to msPerPercent
    }.toMap()

    fun saveTapers(tapers: Map<Int, Long>) = store.edit(
        mapOf(KEY_TAPERS to tapers.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { null }),
    )

    companion object {
        const val PREFS_NAME = "sampler_state"
        private const val KEY_BACKFILL = "daily_backfill_done"
        private const val KEY_TAPERS = "tapers"
    }
}
