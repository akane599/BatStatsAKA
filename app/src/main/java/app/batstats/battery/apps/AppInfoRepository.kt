package app.batstats.battery.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Installed-app labels and icons (QUERY_ALL_PACKAGES). PackageManager runs on IO; icons are rendered to
 * 48 dp bitmaps in device pixels and kept in a ≈4 MB [LruCache]. The cache is cleared on memory trim and on
 * density changes ([BatteryApp][app.batstats.battery.BatteryApp] forwards both), and per package on package
 * broadcasts (receiver registered at first use).
 */
class AppInfoRepository(private val context: Context) : AppInfoSource {
    private val cache = AppInfoCache(AndroidPackageLookup(context), LruIconStore(ICON_CACHE_BYTES), Dispatchers.IO)
    private val receiverRegistered = AtomicBoolean(false)
    @Volatile private var densityDpi = context.resources.configuration.densityDpi

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart
            if (packageName.isNullOrEmpty()) cache.clear() else cache.invalidate(packageName)
        }
    }

    override suspend fun info(packageName: String): AppInfo {
        registerPackageReceiver()
        return cache.info(packageName)
    }

    override suspend fun icon(packageName: String): Bitmap? {
        registerPackageReceiver()
        return cache.icon(packageName)
    }

    fun onTrimMemory() = cache.clear()

    fun onConfigurationChanged(config: Configuration) {
        if (config.densityDpi == densityDpi) return
        densityDpi = config.densityDpi
        cache.clear()
    }

    private fun registerPackageReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Protected broadcasts sent by the system: Android asks for EXPORTED for system senders, and no app
        // can send these actions.
        ContextCompat.registerReceiver(context, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private class AndroidPackageLookup(private val context: Context) : PackageLookup<Bitmap> {
        private val packageManager get() = context.packageManager

        override fun info(packageName: String): AppInfo? {
            val app = applicationInfo(packageName) ?: return null
            val system = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            return AppInfo(packageName, packageManager.getApplicationLabel(app).toString(), system, installed = true)
        }

        override fun icon(packageName: String): Bitmap? {
            val app = applicationInfo(packageName) ?: return null
            val size = (ICON_SIZE_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
            return try {
                packageManager.getApplicationIcon(app).toBitmap(size, size, Bitmap.Config.ARGB_8888)
            } catch (_: RuntimeException) {
                null // A broken icon resource in the other package.
            }
        }

        private fun applicationInfo(packageName: String): ApplicationInfo? {
            if (packageName.isBlank()) return null // The "others" row, or a uid without a package.
            return try {
                if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    private class LruIconStore(maxBytes: Int) : IconStore<Bitmap> {
        private val lru = object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

        override fun get(packageName: String): Bitmap? = lru.get(packageName)
        override fun put(packageName: String, icon: Bitmap) { lru.put(packageName, icon) }
        override fun remove(packageName: String) { lru.remove(packageName) }
        override fun clear() = lru.evictAll()
    }

    companion object {
        const val ICON_SIZE_DP = 48
        const val ICON_CACHE_BYTES = 4 * 1024 * 1024
    }
}
