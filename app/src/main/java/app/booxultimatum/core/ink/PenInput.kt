package app.booxultimatum.core.ink

import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The stylus as the kernel reports it. The EMR pen's event node is world-readable on this firmware, so the app
 * can follow pen-down, lift and the eraser end without any privilege. The thread sleeps in poll() until the pen
 * moves or [stop] is called: nothing wakes the tablet.
 */
class PenInput(private val onEvent: (Event) -> Unit) {
    enum class Event { Near, Away, Down, Up, EraserNear, EraserAway }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeRead: FileDescriptor? = null
    private var wakeWrite: FileDescriptor? = null

    val available: Boolean get() = device() != null

    fun start(): Boolean {
        if (running) return true
        val path = device() ?: return false
        val fd = runCatching { Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0) }.getOrNull() ?: return false
        val pipe = runCatching { Os.pipe() }.getOrNull() ?: run { Os.close(fd); return false }
        wakeRead = pipe[0]; wakeWrite = pipe[1]
        running = true
        thread = Thread({ loop(fd, pipe[0]) }, "pen-input").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        running = false
        wakeWrite?.let { runCatching { Os.write(it, byteArrayOf(1), 0, 1) } }
    }

    private fun loop(fd: FileDescriptor, wake: FileDescriptor) {
        val buf = ByteArray(EVENT_SIZE * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val fds = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }, StructPollfd().apply { this.fd = wake; events = OsConstants.POLLIN.toShort() })
        try {
            while (running) {
                if (Os.poll(fds, -1) <= 0) continue
                if (fds[1].revents.toInt() != 0 || !running) break
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) break
                var off = 0
                while (off + EVENT_SIZE <= n) {
                    val type = bb.getShort(off + 16).toInt() and 0xffff
                    val code = bb.getShort(off + 18).toInt() and 0xffff
                    val value = bb.getInt(off + 20)
                    if (type == EV_KEY) when (code) {
                        // This pen reports hover as the brush tool (seen on NA6C FW 4.3); the pen code is kept for others.
                        BTN_TOOL_PEN, BTN_TOOL_BRUSH -> onEvent(if (value != 0) Event.Near else Event.Away)
                        // Many drawing apps map a side button to erasing, so a held side button counts as the eraser.
                        BTN_TOOL_RUBBER, BTN_STYLUS, BTN_STYLUS2 -> onEvent(if (value != 0) Event.EraserNear else Event.EraserAway)
                        BTN_TOUCH -> onEvent(if (value != 0) Event.Down else Event.Up)
                    }
                    off += EVENT_SIZE
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { Os.close(fd) }
            runCatching { Os.close(wake) }
            wakeWrite?.let { runCatching { Os.close(it) } }
            wakeRead = null; wakeWrite = null
            running = false
        }
    }

    companion object {
        // struct input_event on arm64: 16-byte timeval, u16 type, u16 code, s32 value.
        private const val EVENT_SIZE = 24
        private const val EV_KEY = 0x01
        private const val BTN_TOOL_PEN = 0x140
        private const val BTN_TOOL_RUBBER = 0x141
        private const val BTN_TOOL_BRUSH = 0x142
        private const val BTN_STYLUS = 0x14b
        private const val BTN_STYLUS2 = 0x14c
        private const val BTN_TOUCH = 0x14a

        /** The pen's event node, found by its kernel name; event5 on the Note Air6 C. */
        fun device(): String? {
            val named = runCatching {
                File("/sys/class/input").listFiles { f -> f.name.startsWith("event") }?.firstOrNull { dir ->
                    val name = runCatching { File(dir, "device/name").readText().trim() }.getOrDefault("")
                    name.contains("_pen") || name.contains("emp")
                }?.let { "/dev/input/${it.name}" }
            }.getOrNull()
            return named ?: "/dev/input/event5".takeIf { File(it).canRead() }
        }
    }
}
