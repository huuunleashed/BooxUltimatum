package app.booxultimatum.nib.engine.record

import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.Tool

/** A pen event's action, following Android's `MotionEvent` actions. [code] is stable in files. */
enum class PenAction(val code: Int) {
    Down(0),
    Move(1),
    Up(2),
    HoverEnter(3),
    HoverMove(4),
    HoverExit(5),
    Cancel(6),
    ;

    companion object {
        fun fromCode(code: Int): PenAction? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Where a recording was made.
 *
 * @property pressureMax the digitiser's raw maximum (samples are stored normalised, this is for reference).
 * @property sampleRateNote free text, such as "about 420 Hz".
 */
data class PenHeader(
    val device: String,
    val panelWidth: Int,
    val panelHeight: Int,
    val pressureMax: Float,
    val sampleRateNote: String = "",
)

/** One recorded event. */
sealed class PenEvent {
    abstract val timeNanos: Long

    /** A pen, eraser or finger sample. */
    data class Sample(val action: PenAction, val tool: Tool, val sample: InputSample) : PenEvent() {
        override val timeNanos: Long get() = sample.timeNanos
    }

    /** A display call or other happening worth lining up with the samples, such as `"setPenState"` = `"1"`. */
    data class Marker(val name: String, val value: String, override val timeNanos: Long) : PenEvent()
}

/** A recorded pen session, used to reproduce on the JVM what happened on the tablet. */
data class PenRecording(val header: PenHeader, val events: List<PenEvent>) {
    val samples: List<PenEvent.Sample> get() = events.filterIsInstance<PenEvent.Sample>()
    val markers: List<PenEvent.Marker> get() = events.filterIsInstance<PenEvent.Marker>()
}

/** Collects events into a [PenRecording]. Not thread-safe. */
class PenRecorder(val header: PenHeader) {
    private val events = ArrayList<PenEvent>()

    val size: Int get() = events.size

    fun sample(action: PenAction, tool: Tool, sample: InputSample) {
        events.add(PenEvent.Sample(action, tool, sample))
    }

    fun marker(name: String, value: String, timeNanos: Long) {
        events.add(PenEvent.Marker(name, value, timeNanos))
    }

    fun build(): PenRecording = PenRecording(header, events.toList())
}
