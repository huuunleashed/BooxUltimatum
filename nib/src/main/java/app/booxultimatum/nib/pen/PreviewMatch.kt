package app.booxultimatum.nib.pen

import android.content.SharedPreferences
import androidx.compose.runtime.mutableFloatStateOf
import androidx.core.content.edit
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.brush.BrushSpec
import kotlin.math.max

/**
 * Keeps the display's preview as wide as the stroke Nib draws in its place.
 *
 * The display draws its preview at the width it's sent. Nib's brushes thin with pressure: at an ordinary writing
 * pressure a fountain pen is about two thirds of its full width. So a preview sent at the full width looked wider than
 * the stroke that replaced it (owner's report on the Note Air6 C, FW 4.3). The preview is therefore sent at the brush's
 * width at the owner's [typicalPressure], which Nib learns from their strokes. On top of that sits a factor per preview
 * style, which the owner can tune in Diagnostics, since how the firmware scales each style with pressure is unknown
 * *[verify]*. Both values are logged with every stroke, so better defaults can come from real use.
 */
class PreviewMatch(private val prefs: SharedPreferences) {
    private val log = Logbook.logger("nib.pen")
    private val typical = mutableFloatStateOf(prefs.getFloat(KEY_TYPICAL, DEFAULT_PRESSURE))
    private val factors = HashMap<Int, androidx.compose.runtime.MutableFloatState>()

    /** The owner's usual pressure, 0 to 1: a slow average of each stroke's mean pressure. */
    val typicalPressure: Float get() = typical.floatValue

    /** Learns from one finished pen stroke. Returns true when the typical pressure moved enough to resend the preview. */
    fun observe(meanPressure: Float, samples: Int): Boolean {
        if (samples < MIN_SAMPLES || meanPressure.isNaN() || meanPressure <= 0f) return false
        val before = typical.floatValue
        val next = (before * (1f - LEARN_RATE) + meanPressure.coerceIn(0f, 1f) * LEARN_RATE).coerceIn(MIN_PRESSURE, 1f)
        typical.floatValue = next
        prefs.edit { putFloat(KEY_TYPICAL, next) }
        return kotlin.math.abs(next - before) >= RESEND_STEP
    }

    /** The owner's own correction for a firmware style, 1 when untouched. */
    fun factor(style: Int): Float = state(style).floatValue

    fun setFactor(style: Int, value: Float) {
        val v = value.coerceIn(FACTOR_RANGE.start, FACTOR_RANGE.endInclusive)
        state(style).floatValue = v
        prefs.edit { putFloat(KEY_FACTOR + style, v) }
        log.i("preview size set", "style" to style, "factor" to v)
    }

    /** Back to the defaults: the typical pressure is learnt again, and every style's factor is 1. */
    fun reset() {
        prefs.edit {
            remove(KEY_TYPICAL)
            prefs.all.keys.filter { it.startsWith(KEY_FACTOR) }.forEach { remove(it) }
        }
        typical.floatValue = DEFAULT_PRESSURE
        factors.values.forEach { it.floatValue = 1f }
        log.i("preview size reset")
    }

    /**
     * The width to send for [brush] previewed in [style], given the width the engine asked for ([enginePx], already
     * zoomed): scaled to the brush's width at the typical pressure, then by the style's factor, and never below the
     * brush's thinnest preview.
     */
    fun width(brush: BrushSpec, style: Int, enginePx: Float): Float {
        val pressure = brush.curve.factor(typical.floatValue).coerceIn(0.05f, 1f)
        return max(brush.preview.minWidthPx, enginePx * pressure * factor(style))
    }

    private fun state(style: Int) = factors.getOrPut(style) { mutableFloatStateOf(prefs.getFloat(KEY_FACTOR + style, 1f)) }

    companion object {
        private const val KEY_TYPICAL = "preview_typical_pressure"
        private const val KEY_FACTOR = "preview_factor_"
        const val DEFAULT_PRESSURE = 0.5f
        private const val MIN_PRESSURE = 0.1f
        private const val LEARN_RATE = 0.12f
        private const val RESEND_STEP = 0.02f
        private const val MIN_SAMPLES = 8
        /** How far the owner can correct a style's preview size. */
        val FACTOR_RANGE = 0.3f..1.6f
        const val FACTOR_STEP = 0.05f
    }
}
