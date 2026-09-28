package app.booxultimatum.kit.log

import java.util.ArrayDeque

/**
 * The bounded queue between logging threads and the writer. Not thread-safe: [LogPipeline] guards it.
 *
 * When full, the lowest-level event goes first: an incoming event at or below the lowest queued level is dropped,
 * otherwise the oldest event of the lowest queued level is evicted. Warn and Error are only lost when nothing else is
 * queued, and then the oldest goes. Drops are counted for the single "dropped" event the writer emits.
 */
internal class EventQueue(capacity: Int) {
    val capacity: Int = capacity.coerceAtLeast(1)
    private val items = ArrayDeque<LogEvent>(this.capacity)
    private val counts = IntArray(Level.entries.size)

    val size: Int get() = items.size

    var dropped: Int = 0
        private set

    fun offer(event: LogEvent) {
        if (items.size < capacity) {
            add(event)
            return
        }
        dropped++
        val lowest = lowestQueued()
        if (event.level < Level.Warn && (lowest == null || event.level <= lowest)) return
        if (lowest != null && lowest < Level.Warn) removeOldest(lowest) else removeFirst()
        add(event)
    }

    fun drainTo(out: MutableList<LogEvent>) {
        while (true) out.add(items.pollFirst() ?: break)
        counts.fill(0)
    }

    fun takeDropped(): Int = dropped.also { dropped = 0 }

    fun snapshot(): List<LogEvent> = items.toList()

    private fun add(event: LogEvent) {
        items.addLast(event)
        counts[event.level.ordinal]++
    }

    private fun lowestQueued(): Level? {
        for (i in counts.indices) if (counts[i] > 0) return Level.entries[i]
        return null
    }

    private fun removeOldest(level: Level) {
        val it = items.iterator()
        while (it.hasNext()) {
            if (it.next().level == level) {
                it.remove()
                counts[level.ordinal]--
                return
            }
        }
    }

    private fun removeFirst() {
        val e = items.pollFirst() ?: return
        counts[e.level.ordinal]--
    }
}
