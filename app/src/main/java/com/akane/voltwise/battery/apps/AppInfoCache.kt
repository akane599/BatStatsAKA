package com.akane.voltwise.battery.apps

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Blocking PackageManager reads (IPC plus another app's resources); [AppInfoCache] calls them on IO. */
interface PackageLookup<Icon : Any> {
    /** Label and flags, or null when [packageName] is not installed. */
    fun info(packageName: String): AppInfo?

    /** The icon rendered for a 48 dp slot, or null when the package has none or is gone. */
    fun icon(packageName: String): Icon?
}

/** A size-bounded icon cache ([android.util.LruCache] on device). Thread-safe. */
interface IconStore<Icon : Any> {
    operator fun get(packageName: String): Icon?
    fun put(packageName: String, icon: Icon)
    fun remove(packageName: String)
    fun clear()
}

/**
 * The caching half of [AppInfoRepository], free of Android types so it is unit-testable. Lookups run on [io];
 * labels are cached per package, icons in [icons]. A missing package yields its name as the label and no icon.
 * Concurrent first loads may both read; the first still-valid completion fills the cache.
 */
class AppInfoCache<Icon : Any>(
    private val lookup: PackageLookup<Icon>,
    private val icons: IconStore<Icon>,
    private val io: CoroutineDispatcher,
) {
    private val lock = Any()
    private val infos = mutableMapOf<String, AppInfo>()
    private val packageGenerations = mutableMapOf<String, Long>()
    private var globalGeneration = 0L

    private data class Generation(val global: Long, val packageVersion: Long)

    suspend fun info(packageName: String): AppInfo {
        val generation = synchronized(lock) {
            infos[packageName]?.let { return it }
            generation(packageName)
        }
        val info = withContext(io) {
            (lookup.info(packageName) ?: AppInfo(packageName, packageName, isSystem = false, installed = false))
                .let { it.copy(label = it.label.ifBlank { packageName }) }
        }
        synchronized(lock) {
            if (generation == generation(packageName) && packageName !in infos) {
                infos[packageName] = info
            }
        }
        return info
    }

    suspend fun icon(packageName: String): Icon? {
        val generation = synchronized(lock) {
            icons[packageName]?.let { return it }
            generation(packageName)
        }
        val icon = withContext(io) { lookup.icon(packageName) } ?: return null
        synchronized(lock) {
            if (generation == generation(packageName) && icons[packageName] == null) {
                icons.put(packageName, icon)
            }
        }
        return icon
    }

    // Called only under lock, like every cache read, publication and invalidation.
    private fun generation(packageName: String) = Generation(globalGeneration, packageGenerations[packageName] ?: 0L)

    /** The icon already in [icons], or null; never loads. */
    fun cachedIcon(packageName: String): Icon? = synchronized(lock) { icons[packageName] }

    /** A package was added, removed, replaced or changed: its label, flags and icon may differ now. */
    fun invalidate(packageName: String) = synchronized(lock) {
        packageGenerations[packageName] = (packageGenerations[packageName] ?: 0L) + 1L
        infos.remove(packageName)
        icons.remove(packageName)
    }

    /** Memory trim, or a density change that makes every rendered icon the wrong size. */
    fun clear() = synchronized(lock) {
        globalGeneration++
        packageGenerations.clear()
        infos.clear()
        icons.clear()
    }
}
