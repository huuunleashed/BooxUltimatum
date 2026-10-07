package app.booxultimatum.nib.pen

import android.content.SharedPreferences
import androidx.compose.runtime.mutableFloatStateOf
import androidx.core.content.edit
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import kotlin.math.max

/**
 * Keeps the display's preview as wide as the stroke Nib draws in its place.
 *
 * The display draws its preview at the width it's sent. The styles that apply pressure themselves ([followsPressure]:
 * fountain, marker, neo brush) are sent the brush's full width, since the engine's pens follow the same laws as BOOX's
 * (measured with penlab: the fountain's p^(2s), the marker's 0.8 + 0.2p, the brush's √p; `docs/09-ink.md`). The
 * fountain style draws 3 px wider than it's sent, as BOOX's own ink allows for, so the fountain pen's ink is drawn that
 * much wider instead (the engine's `BrushSpec.inkAt`). The other styles draw at a constant width, so a brush that thins
 * with pressure is sent its width at the owner's [typicalPressure], which Nib learns from their strokes (verified in
 * Diagnostics › Match preview on the Note Air6 C). The native-matched pencil, graphite and charcoals don't thin, so
 * they're sent their width as it is. On top sits a factor per preview style, which the owner can tune in Diagnostics.
 * Both values are logged with every stroke.
 */
class PreviewMatch(private val prefs: SharedPreferences) {
    private val log = Logbook.logger("nib.pen")
    private val typical = mutableFloatStateOf(prefs.getFloat(KEY_TYPICAL, DEFAULT_PRESSURE))
    private val factors = HashMap<Int, androidx.compose.runtime.MutableFloatState>()

    init {
        // Until 0.3.0-test.6 the fountain pen's ink was about 3 px thinner than the display's preview of it, which a
        // size set for the fountain style in Diagnostics may have made up for. The ink now matches the preview (the
        // engine's BrushSpec.inkAt), so that size starts again from 1, once.
        if (prefs.getInt(KEY_VERSION, 1) < VERSION) {
            val key = KEY_FACTOR + HardwareStyle.Fountain.code
            val had = prefs.contains(key)
            prefs.edit {
                remove(key)
                putInt(KEY_VERSION, VERSION)
            }
            if (had) log.i("preview size reset", "style" to HardwareStyle.Fountain.code, "why" to "fountain ink now drawn as the display previews it")
        }
    }

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
     * zoomed): for a style that doesn't apply pressure itself, scaled to the brush's width at the typical pressure; then
     * by the style's factor, and never below the brush's thinnest preview.
     */
    fun width(brush: BrushSpec, style: Int, enginePx: Float): Float {
        val pressure = if (followsPressure(style)) 1f else brush.curve.factor(typical.floatValue).coerceIn(0.05f, 1f)
        return max(brush.preview.minWidthPx, enginePx * pressure * factor(style))
    }

    private fun state(style: Int) = factors.getOrPut(style) { mutableFloatStateOf(prefs.getFloat(KEY_FACTOR + style, 1f)) }

    companion object {
        private const val KEY_TYPICAL = "preview_typical_pressure"
        private const val KEY_FACTOR = "preview_factor_"
        private const val KEY_VERSION = "preview_match_version"

        /** 2: the fountain pen's ink carries the display's pad, so the fountain style's size starts again from 1. */
        private const val VERSION = 2
        const val DEFAULT_PRESSURE = 0.5f
        private const val MIN_PRESSURE = 0.1f
        private const val LEARN_RATE = 0.12f
        private const val RESEND_STEP = 0.02f
        private const val MIN_SAMPLES = 8
        /** How far the owner can correct a style's preview size. */
        val FACTOR_RANGE = 0.3f..1.6f
        const val FACTOR_STEP = 0.05f

        /**
         * The styles whose preview width follows the pen's pressure on the display itself, by the laws the engine's pens
         * now share (penlab): fountain 1, marker 2, neo brush 3. The charcoals and the square pen don't change width
         * with pressure natively.
         */
        fun followsPressure(style: Int): Boolean =
            style == HardwareStyle.Fountain.code || style == HardwareStyle.Marker.code || style == HardwareStyle.NeoBrush.code
    }
}
