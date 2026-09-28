package app.booxultimatum.nib.pen

import android.os.Build
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.Tool
import app.booxultimatum.nib.engine.record.PenAction
import app.booxultimatum.nib.engine.record.PenEvent
import app.booxultimatum.nib.engine.record.PenHeader
import app.booxultimatum.nib.engine.record.PenRecording
import app.booxultimatum.nib.engine.record.PenRecordingCodec
import java.io.File

/**
 * Diagnostics › Pen recorder: while on, keeps the raw samples of the last [MAX_STROKES] strokes (with markers for the
 * display calls around them) so a stroke that looked wrong on the tablet can be replayed on the JVM. Off by default;
 * nothing is kept while off, and nothing leaves the app unless the owner shares it. Main thread only.
 */
object PenRecorderStore {
    const val MAX_STROKES = 50

    private val log = Logbook.logger("nib.probe")
    private val strokes = ArrayDeque<List<PenEvent>>()
    private var current: ArrayList<PenEvent>? = null

    @Volatile var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) clear()
        }

    val strokeCount: Int get() = strokes.size

    fun begin(surface: String, timeNanos: Long) {
        if (!enabled) return
        current = ArrayList<PenEvent>(256).also { it.add(PenEvent.Marker("surface", surface, timeNanos)) }
    }

    fun sample(action: PenAction, tool: Tool, sample: InputSample) {
        current?.add(PenEvent.Sample(action, tool, sample))
    }

    fun marker(name: String, value: String, timeNanos: Long) {
        current?.add(PenEvent.Marker(name, value, timeNanos))
    }

    fun end() {
        val c = current ?: return
        current = null
        strokes.addLast(c)
        while (strokes.size > MAX_STROKES) strokes.removeFirst()
    }

    fun clear() {
        strokes.clear()
        current = null
    }

    fun recording(panelWidth: Int, panelHeight: Int, pressureMax: Float): PenRecording = PenRecording(
        PenHeader("${Build.MANUFACTURER} ${Build.MODEL}", panelWidth, panelHeight, pressureMax, "MotionEvent samples, document pixels"),
        strokes.flatten(),
    )

    /** Writes [rec] (taken with [recording] on the main thread) to [file] as a `.penrec`; any thread. */
    fun export(file: File, rec: PenRecording): File {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { PenRecordingCodec.write(rec, it) }
        log.i("pen recording exported", "events" to rec.events.size, "bytes" to file.length())
        return file
    }
}
