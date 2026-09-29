package app.booxultimatum.kit.ink.eink

import android.view.View
import app.booxultimatum.kit.ink.epd.ElevatedRoute
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.epd.UpdateMode
import app.booxultimatum.kit.ink.session.InkGuard

/**
 * Refreshes for the suite apps' own screens, with the display's update modes: a full clean, a clean of one view, and
 * the fast mode the native apps use while something pans or scrolls. The fast mode is display-wide until cleared, so
 * [fastMode] notes it in [InkGuard] and a later process clears it if this one dies first.
 */
object Eink {
    /** Whether the display's refresh calls are reachable (false on other tablets and the emulator). */
    fun available(elevated: ElevatedRoute? = null): Boolean = Epd.connect(elevated) != null

    /**
     * One full clean of the whole panel. [deep] uses the deep GC waveform, which NeoReader uses on colour panels; else
     * one GC flash followed by a repaint, its other colour-panel choice.
     */
    fun cleanScreen(elevated: ElevatedRoute? = null, deep: Boolean = true): Boolean {
        if (!available(elevated)) return false
        return if (deep) Epd.repaintEverything(UpdateMode.DeepGC) else Epd.applyGcOnce() && Epd.repaintEverything()
    }

    /** Refreshes [view]'s area of the screen in [mode] (Regal cleans ghosting without a flash). */
    fun refresh(view: View, mode: UpdateMode = UpdateMode.Regal): Boolean {
        if (!view.isAttachedToWindow || !available()) return false
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        return Epd.refresh(at[0], at[1], view.width, view.height, mode)
    }

    /** Refreshes a screen rectangle (left, top, right, bottom) in [mode]. */
    fun refresh(rect: IntArray, mode: UpdateMode = UpdateMode.Regal): Boolean =
        available() && Epd.refresh(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1], mode)

    /**
     * The native apps' fast mode for panning and scrolling ([UpdateMode.AnimationQuality]) on or off. Off clears it
     * with the reset the native apps use, which also cleans up the screen.
     */
    fun fastMode(on: Boolean, guard: InkGuard = InkGuard.process): Boolean {
        if (!available()) return false
        return if (on) Epd.applyTransientUpdate(UpdateMode.AnimationQuality).also { if (it) guard.fastModeOn() }
        else Epd.clearTransientUpdate(reset = true).also { guard.fastModeOff() }
    }
}
