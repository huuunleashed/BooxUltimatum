package app.booxultimatum.kit.log

/** How much [Logbook] keeps and where it goes. The defaults suit every suite app. */
data class LogConfig(
    /** The lowest level written to the file. */
    val fileLevel: Level = Level.Info,
    /** Categories that write Debug to the file even when fileLevel is Info (diagnosis-critical subsystems). */
    val debugCategories: Set<String> = setOf("ink", "nib.pen", "nib.render", "nib.probe", "update", "suite", "home", "exec"),
    /** Logcat level; null = Debug for debuggable builds, Info otherwise. */
    val logcatLevel: Level? = null,
    /** An event file is rotated once it would grow past this size. */
    val maxFileBytes: Long = 1_000_000,
    /** Event files kept, the current one included. */
    val maxFiles: Int = 8,
    /** Event files older than this are deleted at init. */
    val maxAgeDays: Int = 14,
    /** Events kept in memory for [Logbook.recent] and crash files. */
    val ringSize: Int = 2000,
    /** Crash files kept. */
    val keepCrashes: Int = 20,
)
