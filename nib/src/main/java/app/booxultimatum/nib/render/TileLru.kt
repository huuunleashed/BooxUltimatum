package app.booxultimatum.nib.render

/**
 * The tile cache's memory policy: entries in least-recently-used order with their sizes, and which to drop once the
 * total passes [budgetBytes]. Entries the caller pins (the tiles on screen) are never chosen, so a view that needs
 * more than the budget runs over it rather than dropping what it's showing. Not thread-safe.
 */
class TileLru<K>(var budgetBytes: Long) {
    private val sizes = LinkedHashMap<K, Long>(64, 0.75f, true)

    var usedBytes: Long = 0L
        private set

    val size: Int get() = sizes.size

    operator fun contains(key: K): Boolean = sizes.containsKey(key)

    /** Adds [key] (or updates its size) as the most recently used. */
    fun put(key: K, bytes: Long) {
        val old = sizes.put(key, bytes)
        usedBytes += bytes - (old ?: 0L)
    }

    /** Marks [key] as just used. */
    fun touch(key: K) {
        sizes[key]
    }

    fun remove(key: K) {
        sizes.remove(key)?.let { usedBytes -= it }
    }

    fun clear() {
        sizes.clear()
        usedBytes = 0L
    }

    /** Removes and returns the least recently used entries that aren't [pinned] until the total fits the budget. */
    fun evict(pinned: (K) -> Boolean): List<K> {
        if (usedBytes <= budgetBytes) return emptyList()
        val out = ArrayList<K>()
        val it = sizes.entries.iterator()
        while (usedBytes > budgetBytes && it.hasNext()) {
            val e = it.next()
            if (pinned(e.key)) continue
            usedBytes -= e.value
            out.add(e.key)
            it.remove()
        }
        return out
    }

    /** Oldest first. */
    fun keys(): List<K> = sizes.keys.toList()
}
