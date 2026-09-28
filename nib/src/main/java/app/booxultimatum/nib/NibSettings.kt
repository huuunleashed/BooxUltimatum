package app.booxultimatum.nib

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.booxultimatum.nib.engine.brush.MarkerPreview
import app.booxultimatum.nib.pen.PenRecorderStore
import app.booxultimatum.nib.pen.PreviewMatch

/** Nib's switches, kept in shared preferences and readable as Compose state. */
class NibSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("nib", Context.MODE_PRIVATE)

    /** Fingers draw (off: fingers only move the page). */
    var fingerDrawing by pref("finger_drawing", false)

    /** One finger moves the page (when fingers don't draw). */
    var fingerPan by pref("finger_pan", true)

    /** Holding the pen still at a stroke's end for half a second straightens it. */
    var straightLineHold by pref("straight_line_hold", true)

    /** The tool rail sits on the right, for drawing with the left hand. */
    var leftHanded by pref("left_handed", false)

    /** Diagnostics: ask the display for brushes' own preview styles even where they're unverified. */
    var tryUnverifiedStyles by pref("try_unverified_styles", false)

    private val markerState = mutableStateOf(MarkerPreview.of(prefs.getString("marker_preview", null)))

    /** How marker and highlighter previews carry colour (Settings › Display preview). */
    var markerPreview: MarkerPreview
        get() = markerState.value
        set(v) {
            markerState.value = v
            prefs.edit { putString("marker_preview", v.id) }
        }

    /** Diagnostics › Pen recorder. */
    var penRecorder by pref("pen_recorder", false, onChange = { PenRecorderStore.enabled = it })

    /** How wide the display's preview is sent, so it matches Nib's own stroke (see [PreviewMatch]). */
    val previewMatch = PreviewMatch(prefs)

    private val swapState = mutableIntStateOf(prefs.getInt("swap_delay_ms", 0))

    /** Diagnostics: extra wait between the stroke's frame and the swap, in milliseconds. */
    var swapDelayMs: Int
        get() = swapState.intValue
        set(v) {
            swapState.intValue = v
            prefs.edit { putInt("swap_delay_ms", v) }
        }

    init {
        PenRecorderStore.enabled = penRecorder
    }

    private fun pref(key: String, default: Boolean, onChange: (Boolean) -> Unit = {}) = object {
        private val state = mutableStateOf(prefs.getBoolean(key, default))
        operator fun getValue(thisRef: Any?, property: Any?): Boolean = state.value
        operator fun setValue(thisRef: Any?, property: Any?, value: Boolean) {
            state.value = value
            prefs.edit { putBoolean(key, value) }
            onChange(value)
        }
    }

    companion object {
        /** The swap delays Diagnostics offers. */
        val SWAP_DELAYS = listOf(0, 16, 50, 120)

        @Volatile private var instance: NibSettings? = null

        fun get(context: Context): NibSettings =
            instance ?: synchronized(this) { instance ?: NibSettings(context.applicationContext).also { instance = it } }
    }
}
