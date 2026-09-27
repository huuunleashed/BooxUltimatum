package app.booxultimatum.core.ink

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.content.edit
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The stylus as the kernel reports it. Boox leaves the input nodes in `/dev/input` readable by apps, so the app can
 * follow hover, pen-down, lift and the eraser end without any privilege. Which node is the pen can't be looked up:
 * SELinux closes `/sys/class/input` (names and capabilities) to apps. So every readable node is opened, and the
 * first one that reports a pen tool (hover, pen or eraser) is the pen: the others are closed and the node is
 * remembered for next time. The thread sleeps in poll() until an input moves or [stop] is called, so nothing wakes
 * the tablet.
 */
class PenInput(private val context: Context? = null, private val onEvent: (Event) -> Unit) {
    enum class Event { Near, Away, Down, Up, EraserNear, EraserAway }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var wakeWrite: FileDescriptor? = null

    /** The node the pen was found on in this session, once it has reported a pen tool. */
    @Volatile var node: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        val remembered = context?.let { remembered(it) }
        val paths = (listOfNotNull(remembered) + readableNodes()).distinct()
        val opened = paths.mapNotNull { p -> runCatching { p to Os.open(p, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0) }.getOrNull() }
        android.util.Log.i("PenInput", "readable ${paths.size}, opened ${opened.map { it.first.removePrefix("/dev/input/") }}")
        if (opened.isEmpty()) return false
        val pipe = runCatching { Os.pipe() }.getOrNull() ?: run { opened.forEach { runCatching { Os.close(it.second) } }; return false }
        wakeWrite = pipe[1]
        running = true
        thread = Thread({ loop(opened, pipe[0]) }, "pen-input").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        running = false
        wakeWrite?.let { runCatching { Os.write(it, byteArrayOf(1), 0, 1) } }
    }

    private fun loop(opened: List<Pair<String, FileDescriptor>>, wake: FileDescriptor) {
        val buf = ByteArray(EVENT_SIZE * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var inputs = opened
        var locked: String? = null
        try {
            while (running) {
                val fds = (inputs.map { it.second } + wake).map { f -> StructPollfd().apply { fd = f; events = OsConstants.POLLIN.toShort() } }.toTypedArray()
                if (Os.poll(fds, -1) <= 0) continue
                if (fds.last().revents.toInt() != 0 || !running) break
                for ((i, pfd) in fds.dropLast(1).withIndex()) {
                    if (pfd.revents.toInt() == 0) continue
                    val (path, fd) = inputs[i]
                    val n = runCatching { Os.read(fd, buf, 0, buf.size) }.getOrDefault(-1)
                    if (n <= 0) continue
                    var off = 0
                    while (off + EVENT_SIZE <= n) {
                        val type = bb.getShort(off + 16).toInt() and 0xffff
                        val code = bb.getShort(off + 18).toInt() and 0xffff
                        val value = bb.getInt(off + 20)
                        off += EVENT_SIZE
                        if (type != EV_KEY) continue
                        if (locked == null) {
                            // Touch panels also report BTN_TOUCH; only a pen tool says which node is the pen.
                            if (code !in PEN_TOOLS) continue
                            locked = path
                            node = path
                            android.util.Log.i("PenInput", "pen found on $path")
                            context?.let { remember(it, path) }
                            inputs.filter { it.first != path }.forEach { runCatching { Os.close(it.second) } }
                            inputs = inputs.filter { it.first == path }
                        } else if (path != locked) continue
                        when (code) {
                            // This pen reports hover as the brush tool (seen on NA6C FW 4.3); the pen code is kept for others.
                            BTN_TOOL_PEN, BTN_TOOL_BRUSH -> onEvent(if (value != 0) Event.Near else Event.Away)
                            // Many drawing apps map a side button to erasing, so a held side button counts as the eraser.
                            BTN_TOOL_RUBBER, BTN_STYLUS, BTN_STYLUS2 -> onEvent(if (value != 0) Event.EraserNear else Event.EraserAway)
                            BTN_TOUCH -> onEvent(if (value != 0) Event.Down else Event.Up)
                        }
                    }
                    if (inputs.size == 1 && locked != null) break
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PenInput", "reader stopped", e)
        } finally {
            inputs.forEach { runCatching { Os.close(it.second) } }
            runCatching { Os.close(wake) }
            wakeWrite?.let { runCatching { Os.close(it) } }
            wakeWrite = null
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
        private val PEN_TOOLS = setOf(BTN_TOOL_PEN, BTN_TOOL_RUBBER, BTN_TOOL_BRUSH)
        private const val MAX_NODES = 32
        private const val PREFS = "ink"
        private const val KEY_NODE = "pen_node"

        /** Input nodes this app may read, probed by path: the directory itself may not be listable. */
        fun readableNodes(): List<String> = (0 until MAX_NODES).map { "/dev/input/event$it" }.filter { File(it).canRead() }

        fun remembered(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NODE, null)?.takeIf { File(it).canRead() }

        private fun remember(context: Context, path: String) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_NODE, path) }
    }
}
