package app.booxultimatum.kit.log

/**
 * The platform-free heart of [Logbook]: level policy, ring buffer and the file pipeline. Logging threads call [emit];
 * nothing here blocks on I/O or throws.
 */
internal class LogCore(
    val clock: LogClock,
    val session: String,
    val pid: Int,
    queueCapacity: Int = LogPipeline.DEFAULT_CAPACITY,
    @Volatile var logcat: LogcatSink? = null,
) {
    val policy = LevelPolicy(clock)
    val ring = RingBuffer(LogConfig().ringSize)
    val pipeline = LogPipeline(clock, session, pid, queueCapacity)

    fun configure(config: LogConfig, logcatLevel: Level) {
        policy.configure(config.fileLevel, logcatLevel, config.debugCategories)
        ring.resize(config.ringSize)
    }

    fun isEnabled(level: Level, category: String): Boolean = policy.isEnabled(level, category)

    fun emit(
        category: String,
        tag: String,
        level: Level,
        message: String,
        fields: Array<out Pair<String, Any?>>,
        error: Throwable?,
    ) {
        try {
            val toFile = policy.toFile(level, category)
            val toLogcat = policy.toLogcat(level)
            if (!toFile && !toLogcat) return
            val event = LogEvent(
                wallMs = clock.wallMs(),
                monoNanos = clock.monoNanos(),
                level = level,
                category = category,
                message = EventFormat.truncate(message, EventFormat.MAX_MESSAGE),
                fields = EventFormat.fields(fields),
                thread = Thread.currentThread().name,
                pid = pid,
                session = session,
                error = if (error != null) EventFormat.stackTrace(error) else null,
            )
            ring.add(event)
            if (toFile) pipeline.enqueue(event)
            if (toLogcat) logcat?.write(level, tag, EventFormat.logcatMessage(event.message, event.fields), error)
        } catch (_: Throwable) {
            // Logging must never take the app down.
        }
    }
}
