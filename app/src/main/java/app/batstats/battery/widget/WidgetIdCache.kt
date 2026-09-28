package app.batstats.battery.widget

/**
 * `AppWidgetManager.getAppWidgetIds` (a Binder call) per provider, cached until that provider
 * reports a change through [invalidate]. An invalidation while a lookup is in flight keeps that
 * possibly stale answer out of the cache. Thread-safe; lookups run outside the lock.
 */
class WidgetIdCache<K : Any> {
    private val lock = Any()
    private val cached = HashMap<K, IntArray>()
    private var version = 0

    fun get(provider: K, lookup: () -> IntArray): IntArray {
        val started = synchronized(lock) { cached[provider]?.let { return it }; version }
        val ids = lookup()
        synchronized(lock) { if (version == started) cached[provider] = ids }
        return ids
    }

    fun invalidate(provider: K) {
        synchronized(lock) {
            version++
            cached.remove(provider)
        }
    }
}
