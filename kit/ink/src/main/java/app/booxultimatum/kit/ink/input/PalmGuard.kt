package app.booxultimatum.kit.ink.input

import app.booxultimatum.kit.ink.canvas.InkScheduler
import app.booxultimatum.kit.ink.session.InkGuard
import app.booxultimatum.kit.log.Logbook

/**
 * Palm rejection by the firmware: while the pen is near the canvas, finger touch is switched off over the canvas
 * (except the app's controls), so a resting hand can't pan, zoom or draw. It comes back [awayMs] after the pen leaves,
 * and at once when the owner stops. Uses [TouchPanel], so it's display-wide while on: [InkGuard] records it and a
 * later process undoes it if this one dies first.
 */
class PalmGuard(
    private val scheduler: InkScheduler,
    private val guard: InkGuard = InkGuard.process,
    private val panel: Panel = Panel.Firmware,
    private val awayMs: Long = 500L,
) {
    /** The finger-touch switch, as an interface for tests. */
    interface Panel {
        fun suppress(disable: IntArray, keep: IntArray): Boolean
        fun reset(): Boolean

        object Firmware : Panel {
            override fun suppress(disable: IntArray, keep: IntArray) = TouchPanel.suppress(disable, keep)
            override fun reset() = TouchPanel.reset()
        }
    }

    private val log = Logbook.logger("ink.touch")
    var enabled: Boolean = false
        set(v) { field = v; if (!v) stop() }

    var active: Boolean = false
        private set

    /** Called whenever finger touch is switched off ([active] true) or back on, for the app's log. */
    var onChanged: ((active: Boolean) -> Unit)? = null
    private var area: IntArray? = null
    private var keep = IntArray(0)
    private var cancelAway: (() -> Unit)? = null
    private var refused = false

    /** The pen is near the canvas, which covers [canvas] on screen, with the app's controls in [controls]. */
    fun penNear(canvas: IntArray, controls: List<IntArray>) {
        if (!enabled || refused) return
        cancelAway?.invoke(); cancelAway = null
        val flat = IntArray(controls.size * 4).also { a -> controls.forEachIndexed { i, r -> r.copyInto(a, i * 4, 0, 4) } }
        if (active && canvas.contentEquals(area) && flat.contentEquals(keep)) return
        if (panel.suppress(canvas, flat)) {
            val was = active
            if (!active) guard.touchSuppressed()
            active = true; area = canvas.copyOf(); keep = flat
            if (!was) onChanged?.invoke(true)
        } else {
            refused = true
            log.w("finger touch can't be switched off here; palm guard stays off")
        }
    }

    /** The pen left; finger touch comes back shortly, unless the pen returns first. */
    fun penAway() {
        if (!active || cancelAway != null) return
        cancelAway = scheduler.post(awayMs) { cancelAway = null; stop() }
    }

    /** Finger touch back everywhere, now. */
    fun stop() {
        cancelAway?.invoke(); cancelAway = null
        if (!active) return
        panel.reset()
        active = false; area = null; keep = IntArray(0)
        guard.touchRestored()
        onChanged?.invoke(false)
    }
}
