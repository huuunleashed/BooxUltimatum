package app.booxultimatum.kit.ink.session

import android.os.Process
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.input.TouchPanel
import app.booxultimatum.kit.log.Logbook
import java.io.File

/**
 * Remembers, while it lasts, every display-wide change this process has made: an open pen session, a temporary update
 * mode, finger touch switched off, a style's stroke parameters replaced. The display keeps all of these after the
 * process that made them dies (a Nib killed mid-session left its preview drawing over every app, NA6C FW 4.3), so the
 * record lives in a small file, and the app's next process undoes whatever an ended one left ([recoverStale]).
 *
 * Without a file ([attach] not called, as in tests) it only keeps the record in memory.
 */
class InkGuard(private val undo: Undo = Undo.Display) {
    /** How each kind of change is undone; the display's own calls in the apps. */
    interface Undo {
        fun endLiveSession(): Boolean
        fun clearFastMode()
        fun restoreTouch()
        fun restoreParams(style: Int, params: FloatArray)

        object Display : Undo {
            override fun endLiveSession(): Boolean {
                if (Epd.connect() == null) return false
                val live = Epd.PenState.isLive(Epd.penState())
                if (live) Epd.release()
                return live
            }
            override fun clearFastMode() { if (Epd.connect() != null) Epd.clearTransientUpdate(reset = true) }
            override fun restoreTouch() { TouchPanel.reset() }
            override fun restoreParams(style: Int, params: FloatArray) { if (Epd.connect() != null) Epd.setStrokeParameters(style, params) }
        }
    }

    private val log = Logbook.logger("ink.guard")
    private var file: File? = null
    private var session = false
    private var fastMode = false
    private var touch = false
    private val params = LinkedHashMap<Int, FloatArray>()

    /** Starts keeping the record in [f], after undoing whatever an ended process of this app left in it. */
    @Synchronized
    fun attach(f: File) {
        file = f
        runCatching { recoverStale(f) }.onFailure { log.w("stale record not recovered", error = it) }
        write()
    }

    @Synchronized fun sessionOpened() { session = true; write() }
    @Synchronized fun sessionClosed() { session = false; write() }
    @Synchronized fun fastModeOn() { fastMode = true; write() }
    @Synchronized fun fastModeOff() { fastMode = false; write() }
    @Synchronized fun touchSuppressed() { touch = true; write() }
    @Synchronized fun touchRestored() { touch = false; write() }

    /** A style's parameters were replaced; [original] is what the display had before, the first time only. */
    @Synchronized fun paramsChanged(style: Int, original: FloatArray) { if (style !in params) { params[style] = original.copyOf(); write() } }
    @Synchronized fun paramsRestored(style: Int) { if (params.remove(style) != null) write() }

    /** The parameters to put back for each style changed so far. */
    @Synchronized fun changedParams(): Map<Int, FloatArray> = params.mapValues { it.value.copyOf() }

    val outstanding: Boolean @Synchronized get() = session || fastMode || touch || params.isNotEmpty()

    /** Undoes what [text], a record written by another process, says it left. Returns what was undone. */
    fun undoRecord(text: String): List<String> {
        val r = Record.parse(text) ?: return emptyList()
        if (r.pid == Process.myPid()) return emptyList()
        val done = ArrayList<String>()
        if (r.session && undo.endLiveSession()) done += "session"
        if (r.fastMode) { undo.clearFastMode(); done += "fast mode" }
        if (r.touch) { undo.restoreTouch(); done += "finger touch" }
        r.params.forEach { (style, p) -> undo.restoreParams(style, p); done += "style $style parameters" }
        if (done.isNotEmpty() || r.anything) log.w("display changes left by an ended process", "pid" to r.pid, "since" to r.since, "undone" to done.joinToString())
        return done
    }

    private fun recoverStale(f: File) {
        val text = f.takeIf { it.exists() }?.readText() ?: return
        f.delete()
        undoRecord(text)
    }

    private fun write() {
        val f = file ?: return
        runCatching {
            if (!outstanding) { f.delete(); return }
            f.writeText(Record(Process.myPid(), System.currentTimeMillis(), session, fastMode, touch, params).format())
        }
    }

    /** The record's text form: one `key=value` per line, parameters as `style:a,b,c`. */
    internal data class Record(
        val pid: Int, val since: Long, val session: Boolean, val fastMode: Boolean, val touch: Boolean, val params: Map<Int, FloatArray>,
    ) {
        val anything get() = session || fastMode || touch || params.isNotEmpty()

        fun format(): String = buildString {
            appendLine("pid=$pid"); appendLine("since=$since")
            appendLine("session=${if (session) 1 else 0}"); appendLine("fast=${if (fastMode) 1 else 0}"); appendLine("touch=${if (touch) 1 else 0}")
            params.forEach { (s, p) -> appendLine("params=$s:${p.joinToString(",")}") }
        }

        companion object {
            fun parse(text: String): Record? {
                val lines = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }
                val map = lines.filter { it.first != "params" }.toMap()
                val pid = map["pid"]?.toIntOrNull() ?: return null
                val params = LinkedHashMap<Int, FloatArray>()
                lines.filter { it.first == "params" }.forEach { (_, v) ->
                    val style = v.substringBefore(':').toIntOrNull() ?: return@forEach
                    val values = v.substringAfter(':', "").split(',').filter { it.isNotBlank() }.mapNotNull { it.toFloatOrNull() }
                    params[style] = values.toFloatArray()
                }
                return Record(pid, map["since"]?.toLongOrNull() ?: 0L, map["session"] == "1", map["fast"] == "1", map["touch"] == "1", params)
            }
        }
    }

    companion object {
        /** The process's guard; apps [attach] it to a file at start (`noBackupFilesDir/ink-guard`). */
        val process = InkGuard()
    }
}
