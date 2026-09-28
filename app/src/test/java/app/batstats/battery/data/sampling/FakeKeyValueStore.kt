package app.batstats.battery.data.sampling

/** In-memory [KeyValueStore]; [writes] counts edits so tests can check nothing is rewritten needlessly. */
class FakeKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    val values = initial.toMutableMap()
    var writes = 0
        private set

    override fun getString(key: String): String? = values[key]

    override fun edit(values: Map<String, String?>) {
        writes++
        values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
    }
}
