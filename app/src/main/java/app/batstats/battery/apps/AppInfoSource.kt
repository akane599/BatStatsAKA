package app.batstats.battery.apps

import android.graphics.Bitmap

/** Installed-app labels and icons (QUERY_ALL_PACKAGES); loads on IO and caches. */
interface AppInfoSource {
    suspend fun info(packageName: String): AppInfo
    /** Icon sized for a 48 dp slot, or null when the package has none or is gone. */
    suspend fun icon(packageName: String): Bitmap?
}

data class AppInfo(
    val packageName: String,
    val label: String,       // falls back to packageName when unknown
    val isSystem: Boolean,
    val installed: Boolean,
)
