package app.booxultimatum.kit.log

/**
 * One logged event, as kept in memory and written as one JSON line.
 *
 * [wallMs] is the wall clock in ms since the epoch, [monoNanos] `System.nanoTime()` (CLOCK_MONOTONIC on Android, the
 * clock input events use), [fields] the stringified named values, [error] the stack trace when a throwable was logged.
 */
data class LogEvent(
    val wallMs: Long,
    val monoNanos: Long,
    val level: Level,
    val category: String,
    val message: String,
    val fields: Map<String, String>,
    val thread: String,
    val pid: Int,
    val session: String,
    val error: String?,
)
