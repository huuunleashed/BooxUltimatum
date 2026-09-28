package app.booxultimatum.kit.log

/** One category's logger, e.g. Logbook.logger("ink"). Cheap to create; cache it in a val. */
class Logger internal constructor(val category: String) {
    private val tag = "BU/$category"

    /** Whether an event at [level] would be kept anywhere (file, logcat or the ring). */
    fun isEnabled(level: Level): Boolean = Logbook.core.isEnabled(level, category)

    fun v(message: String, vararg fields: Pair<String, Any?>) {
        Logbook.core.emit(category, tag, Level.Verbose, message, fields, null)
    }

    fun d(message: String, vararg fields: Pair<String, Any?>) {
        Logbook.core.emit(category, tag, Level.Debug, message, fields, null)
    }

    fun i(message: String, vararg fields: Pair<String, Any?>) {
        Logbook.core.emit(category, tag, Level.Info, message, fields, null)
    }

    fun w(message: String, vararg fields: Pair<String, Any?>, error: Throwable? = null) {
        Logbook.core.emit(category, tag, Level.Warn, message, fields, error)
    }

    fun e(message: String, vararg fields: Pair<String, Any?>, error: Throwable? = null) {
        Logbook.core.emit(category, tag, Level.Error, message, fields, error)
    }

    /** Builds the event only when Debug is enabled for this category. */
    inline fun debug(build: () -> String) {
        if (isEnabled(Level.Debug)) d(build())
    }

    /** Runs block and logs its duration in ms at Debug with the given fields plus "ms". Returns the block's value. */
    inline fun <T> span(name: String, vararg fields: Pair<String, Any?>, block: () -> T): T {
        if (!isEnabled(Level.Debug)) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            spanEnd(name, fields, start)
        }
    }

    @PublishedApi
    internal fun spanEnd(name: String, fields: Array<out Pair<String, Any?>>, startNanos: Long) {
        val ms: Pair<String, Any?> = "ms" to EventFormat.millis(System.nanoTime() - startNanos)
        val all = Array(fields.size + 1) { if (it < fields.size) fields[it] else ms }
        Logbook.core.emit(category, tag, Level.Debug, name, all, null)
    }

    override fun toString(): String = "Logger($category)"
}
