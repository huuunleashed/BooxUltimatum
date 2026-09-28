package app.booxultimatum.kit.log

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Hands events from logging threads to one daemon writer thread. Logging threads only take a short lock and never
 * touch the disk. The writer sleeps while the queue is empty and otherwise writes in batches: [intervalNanos] after
 * the first queued event, at once for Warn and Error, when the queue is half full, or when [flush] asks.
 */
internal class LogPipeline(
    private val clock: LogClock,
    private val session: String,
    private val pid: Int,
    capacity: Int = DEFAULT_CAPACITY,
    private val intervalNanos: Long = FLUSH_INTERVAL_NANOS,
) {
    private val lock = ReentrantLock()
    private val work = lock.newCondition()
    private val done = lock.newCondition()
    private val queue = EventQueue(capacity)
    private val wakeAt = (queue.capacity / 2).coerceAtLeast(1)
    private var windowStart = 0L
    private var urgent = false
    private var flushRequested = 0L
    private var flushed = 0L
    private var thread: Thread? = null

    @Volatile
    private var writer: LogFileWriter? = null

    val size: Int get() = lock.withLock { queue.size }

    val started: Boolean get() = lock.withLock { thread != null }

    fun enqueue(event: LogEvent) {
        lock.withLock {
            val wasEmpty = queue.size == 0
            queue.offer(event)
            if (wasEmpty) windowStart = System.nanoTime()
            val warn = event.level >= Level.Warn
            if (warn) urgent = true
            if (thread != null && (wasEmpty || warn || queue.size >= wakeAt)) work.signal()
        }
    }

    /** Starts the writer thread once; [setup] runs on it first and supplies the file writer. */
    fun start(setup: () -> LogFileWriter?) {
        val t = lock.withLock {
            if (thread != null) return
            Thread({
                writer = try {
                    setup()
                } catch (_: Throwable) {
                    null
                }
                loop()
            }, THREAD_NAME).also {
                it.isDaemon = true
                thread = it
            }
        }
        t.start()
    }

    /** Waits up to [timeoutMs] until everything queued before the call is on disk. False on timeout or no writer. */
    fun flush(timeoutMs: Long): Boolean {
        try {
            lock.withLock {
                val t = thread ?: return false
                if (t === Thread.currentThread()) return false
                val target = ++flushRequested
                work.signal()
                var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                while (flushed < target) {
                    if (left <= 0L) return false
                    left = done.awaitNanos(left)
                }
                return true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    /** Uses [writer] without a thread; with [drainNow], for tests. */
    fun attach(writer: LogFileWriter) {
        this.writer = writer
    }

    /** Writes whatever is queued on the calling thread. */
    fun drainNow() {
        val batch = ArrayList<LogEvent>()
        var dropped = 0
        lock.withLock {
            queue.drainTo(batch)
            dropped = queue.takeDropped()
            urgent = false
        }
        write(batch, dropped)
    }

    private fun loop() {
        val batch = ArrayList<LogEvent>(256)
        while (true) {
            try {
                cycle(batch)
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {
                batch.clear()
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun cycle(batch: ArrayList<LogEvent>) {
        var dropped = 0
        var target = 0L
        lock.withLock {
            while (queue.size == 0 && flushRequested <= flushed) work.await()
            while (!writeNow()) {
                val left = windowStart + intervalNanos - System.nanoTime()
                if (left <= 0L) break
                work.awaitNanos(left)
            }
            queue.drainTo(batch)
            dropped = queue.takeDropped()
            urgent = false
            target = flushRequested
        }
        write(batch, dropped)
        batch.clear()
        lock.withLock {
            if (target > flushed) flushed = target
            done.signalAll()
        }
    }

    private fun writeNow(): Boolean = urgent || flushRequested > flushed || queue.size >= wakeAt

    private fun write(batch: List<LogEvent>, dropped: Int) {
        val w = writer ?: return
        try {
            for (i in batch.indices) w.write(batch[i])
            if (dropped > 0) w.write(droppedEvent(dropped))
            w.flush()
        } catch (_: IOException) {
            w.close()
        }
    }

    private fun droppedEvent(count: Int) = LogEvent(
        wallMs = clock.wallMs(),
        monoNanos = clock.monoNanos(),
        level = Level.Warn,
        category = "log",
        message = "dropped",
        fields = mapOf("count" to count.toString()),
        thread = Thread.currentThread().name,
        pid = pid,
        session = session,
        error = null,
    )

    companion object {
        const val DEFAULT_CAPACITY = 4096
        const val FLUSH_INTERVAL_NANOS = 2_000_000_000L
        const val THREAD_NAME = "kit.log"
    }
}
