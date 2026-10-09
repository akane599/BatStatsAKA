package com.akane.voltwise.battery.data.sampling

import android.content.SharedPreferences

/**
 * The few string values the sampler keeps outside Room and outside the exported settings:
 * calibration and sampler state. Both files live in non-backed-up SharedPreferences
 * (backup_rules / data_extraction_rules include only `datastore/`).
 */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Writes every entry at once; a null value removes the key. */
    fun edit(values: Map<String, String?>)
}

class SharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun edit(values: Map<String, String?>) {
        val editor = preferences.edit()
        values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        editor.apply()
    }
}
