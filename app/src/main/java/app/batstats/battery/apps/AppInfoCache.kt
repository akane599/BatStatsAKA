package app.batstats.battery.apps

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
 * Concurrent first loads of one package may both read; the results are identical.
 */
class AppInfoCache<Icon : Any>(
    private val lookup: PackageLookup<Icon>,
    private val icons: IconStore<Icon>,
    private val io: CoroutineDispatcher,
) {
    private val infos = ConcurrentHashMap<String, AppInfo>()

    suspend fun info(packageName: String): AppInfo = infos[packageName] ?: withContext(io) {
        (lookup.info(packageName) ?: AppInfo(packageName, packageName, isSystem = false, installed = false))
            .let { it.copy(label = it.label.ifBlank { packageName }) }
            .also { infos[packageName] = it }
    }

    suspend fun icon(packageName: String): Icon? = icons[packageName] ?: withContext(io) {
        lookup.icon(packageName)?.also { icons.put(packageName, it) }
    }

    /** A package was added, removed, replaced or changed: its label, flags and icon may differ now. */
    fun invalidate(packageName: String) {
        infos.remove(packageName)
        icons.remove(packageName)
    }

    /** Memory trim, or a density change that makes every rendered icon the wrong size. */
    fun clear() {
        infos.clear()
        icons.clear()
    }
}
