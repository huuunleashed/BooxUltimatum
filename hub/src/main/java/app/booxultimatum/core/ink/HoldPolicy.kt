package app.booxultimatum.core.ink

import app.booxultimatum.kit.ink.canvas.InkScheduler

/**
 * When Instant ink lets the chosen app's frames reach the panel again, which is also the swap that replaces the
 * display's preview with the app's own strokes (`ENABLE_POST`, see `docs/09-ink.md`).
 *
 * It batches strokes the way BOOX's own apps do (traced on NA6C FW 4.3). Every release of the hold makes the e-ink
 * controller switch update schemes, which delays the start of the next stroke, so quick strokes share one hold: the
 * frames stay held from the pen's approach until the pen has rested [latencyMs] since the last lift, and a new touch
 * always cancels a pending swap. BOOX's own screen notes wait the same way, 400 ms to 2 s. The hold also ends at once
 * when the pen leaves range, the eraser end comes near, the chosen app is no longer in front or the screen turns off;
 * the service reports those with [end]. Unlike Nib, Instant ink can't put another app's ink into the display's layer,
 * so it has to let that app's frames through at each pause.
 *
 * Pure Kotlin and single-threaded (the service's worker): timing comes from [scheduler] and [clockMs], so it's tested
 * on the JVM.
 */
class HoldPolicy(
    private val frames: Frames,
    private val scheduler: InkScheduler,
    private val clockMs: () -> Long,
) {
    /** The display calls behind a hold. */
    interface Frames {
        /** Holds the app's frames back from the panel. */
        fun hold()

        /** Lets them through again, which swaps the preview for the app's own pixels. */
        fun letThrough()
    }

    /** Why a hold ended, for the log. */
    enum class End { Pause, HoverIdle, Away, Eraser, LeftApp, Locked, ScreenOff, PenLost, Watchdog, Settings, Off, Recover }

    /** A hold that ended: how many strokes it covered, how long the frames were held and why it ended. */
    data class Hold(val strokes: Int, val heldMs: Long, val end: End)

    /** How long the pen rests after a lift before the frames go through: the owner's choice. */
    var latencyMs: Long = DEFAULT_LATENCY_MS.toLong()

    /** The pen is in range (hovering or touching), as its node reports it. */
    var near = false

    /** The pen touches the screen. */
    var touching = false

    /** Asked before the hold resumes by itself after a pause: whether a chosen app is still in front and previewed. */
    var mayHold: () -> Boolean = { true }

    var onEnded: (Hold) -> Unit = {}

    /** A stroke began with nothing held; gives the time since the last swap, or null before the first. */
    var onUnheldStroke: (sinceSwapMs: Long?) -> Unit = {}

    /** The app's frames are held. */
    var holding = false
        private set

    /** Strokes drawn under the current hold. */
    var strokes = 0
        private set

    private var heldAt = 0L
    private var lastSwap: Long? = null
    private var swapLater: (() -> Unit)? = null
    private var reholdLater: (() -> Unit)? = null
    private var idleLater: (() -> Unit)? = null
    private var watchdog: (() -> Unit)? = null

    /**
     * The pen hovers over a chosen app: hold now, as BOOX's SDK arms at hover, so the stroke's first points land on a
     * held screen. A pen resting in range without touching lets the frames through after [HOVER_IDLE_MS].
     *
     * Within [SETTLE_MS] of letting the frames through, the hold waits: the panel is still showing the app's strokes,
     * and holding now would cut into that update. NeoReader re-arms after a pause only once the panel has finished
     * updating, at least 500 ms on colour panels. A touch meanwhile still holds at once ([down]).
     */
    fun hover() {
        val settling = lastSwap?.let { SETTLE_MS - (clockMs() - it) } ?: 0L
        if (settling > 0) {
            reholdLater?.invoke()
            reholdLater = scheduler.post(settling) { reholdLater = null; if (near && !touching && mayHold()) hover() }
            return
        }
        hold()
        idleLater?.invoke()
        idleLater = scheduler.post(HOVER_IDLE_MS) { idleLater = null; if (!touching && holding) swap(End.HoverIdle) }
    }

    /** Drops a pending swap and the hover timers; every touch does this first, since a swap now would cover its start. */
    fun cancelPending() {
        swapLater?.invoke(); swapLater = null
        reholdLater?.invoke(); reholdLater = null
        idleLater?.invoke(); idleLater = null
    }

    /** A touch over a chosen app: the hold goes on, or starts. */
    fun down() {
        cancelPending()
        if (!holding) onUnheldStroke(lastSwap?.let { clockMs() - it })
        hold()
        strokes++
    }

    /** The lift: the frames go through once the pen has rested [latencyMs], unless it touches again first. */
    fun up() {
        if (!holding) return
        swapLater?.invoke()
        swapLater = scheduler.post(latencyMs) { swapLater = null; paused() }
    }

    /** Lets the frames through now, for [reason]. */
    fun end(reason: End) {
        cancelPending()
        swap(reason)
    }

    /** Forgets the hold without a display call, when the whole session is being released anyway (which lets the frames through). */
    fun forget(reason: End) {
        cancelPending()
        watchdog?.invoke(); watchdog = null
        if (holding) { report(reason); lastSwap = clockMs() }
        holding = false
        strokes = 0
    }

    private fun paused() {
        swap(End.Pause)
        // With the pen still hovering, hold again once the app's strokes have reached the panel ([hover] waits for
        // that), so the next stroke's start isn't raced by the app's drawing.
        if (near) hover()
    }

    private fun hold() {
        if (!holding) {
            frames.hold()
            holding = true
            heldAt = clockMs()
            strokes = 0
        }
        // Never leave the app's frames held for long, whatever the pen reports.
        watchdog?.invoke()
        watchdog = scheduler.post(WATCHDOG_MS) { watchdog = null; if (holding) end(End.Watchdog) }
    }

    private fun swap(reason: End) {
        watchdog?.invoke(); watchdog = null
        if (!holding) return
        frames.letThrough()
        report(reason)
        holding = false
        strokes = 0
        lastSwap = clockMs()
    }

    private fun report(reason: End) = onEnded(Hold(strokes, clockMs() - heldAt, reason))

    companion object {
        /** The pause choices offered, as BOOX's own screen notes offer them: 400 ms to 2 s. */
        val LATENCY_CHOICES = listOf(400, 500, 800, 1200, 2000)
        const val MIN_LATENCY_MS = 400
        const val MAX_LATENCY_MS = 2000
        const val DEFAULT_LATENCY_MS = 800

        /** A saved pause, kept as saved but brought into the offered range (earlier builds offered 250 ms). */
        fun latencyFor(savedMs: Int): Int = savedMs.coerceIn(MIN_LATENCY_MS, MAX_LATENCY_MS)

        /**
         * After a swap, frames flow at least this long before a hovering pen holds them again: the time the colour
         * panel takes to show the app's strokes (NeoReader waits for the update to finish, 500 ms at least).
         */
        const val SETTLE_MS = 500L

        /** A hovering pen that doesn't touch down lets the app's frames through after this long. */
        const val HOVER_IDLE_MS = 2_500L

        /** The app's frames are never held longer than this without a new touch, whatever the pen reports. */
        const val WATCHDOG_MS = 30_000L
    }
}
