package app.booxultimatum.kit.log

internal interface LogClock {
    fun wallMs(): Long
    fun monoNanos(): Long
}

internal object SystemLogClock : LogClock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monoNanos(): Long = System.nanoTime()
}

internal fun interface LogcatSink {
    fun write(level: Level, tag: String, message: String, error: Throwable?)
}
