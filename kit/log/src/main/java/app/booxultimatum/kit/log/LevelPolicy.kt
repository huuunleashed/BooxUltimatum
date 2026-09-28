package app.booxultimatum.kit.log

import java.util.concurrent.ConcurrentHashMap

/**
 * Decides where an event goes. Everything it reads per event is a volatile field or a cached map entry, so a check
 * for a disabled level costs a few comparisons.
 */
internal class LevelPolicy(private val clock: LogClock) {
    @Volatile
    var fileLevel: Level = Level.Info
        private set

    /** Before init the build type isn't known, so logcat starts at Info. */
    @Volatile
    var logcatLevel: Level = Level.Info
        private set

    @Volatile
    private var debugCategories: Set<String> = LogConfig().debugCategories
    private val debugCache = ConcurrentHashMap<String, Boolean>()

    /** Wall-clock end of detailed mode; 0 when it's off. */
    @Volatile
    var detailedUntil: Long = 0L

    fun configure(fileLevel: Level, logcatLevel: Level, debugCategories: Set<String>) {
        this.fileLevel = fileLevel
        this.logcatLevel = logcatLevel
        this.debugCategories = debugCategories.toSet()
        debugCache.clear()
    }

    fun detailed(): Boolean {
        val until = detailedUntil
        return until != 0L && clock.wallMs() < until
    }

    fun toFile(level: Level, category: String): Boolean =
        level >= fileLevel || (level == Level.Debug && isDebugCategory(category)) || detailed()

    fun toLogcat(level: Level): Boolean = level >= logcatLevel

    fun isEnabled(level: Level, category: String): Boolean = toLogcat(level) || toFile(level, category)

    /** True when [category] or one of its dotted ancestors ("nib.pen" for "nib.pen.x", then "nib") is listed. */
    fun isDebugCategory(category: String): Boolean {
        debugCache[category]?.let { return it }
        val set = debugCategories
        var match = category in set
        var end = category.lastIndexOf('.')
        while (!match && end > 0) {
            match = category.substring(0, end) in set
            end = category.lastIndexOf('.', end - 1)
        }
        if (set === debugCategories) debugCache[category] = match
        return match
    }
}
