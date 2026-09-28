package app.booxultimatum.kit.log

/** The newest events in memory, oldest overwritten first. */
internal class RingBuffer(capacity: Int) {
    private var items = arrayOfNulls<LogEvent>(capacity.coerceAtLeast(1))
    private var head = 0
    private var count = 0

    val capacity: Int
        @Synchronized get() = items.size

    val size: Int
        @Synchronized get() = count

    @Synchronized
    fun add(event: LogEvent) {
        items[head] = event
        head = (head + 1) % items.size
        if (count < items.size) count++
    }

    /** The newest [max] events, oldest first. */
    @Synchronized
    fun snapshot(max: Int = Int.MAX_VALUE): List<LogEvent> {
        val n = minOf(max.coerceAtLeast(0), count)
        val cap = items.size
        val out = ArrayList<LogEvent>(n)
        var i = (head - n + cap) % cap
        repeat(n) {
            out.add(items[i]!!)
            i = (i + 1) % cap
        }
        return out
    }

    /** Changes the capacity, keeping the newest events that fit. */
    @Synchronized
    fun resize(capacity: Int) {
        val cap = capacity.coerceAtLeast(1)
        if (cap == items.size) return
        val keep = snapshot(cap)
        items = arrayOfNulls(cap)
        head = 0
        count = 0
        keep.forEach(::add)
    }
}
