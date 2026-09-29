package app.booxultimatum.nib.diag

import kotlin.math.abs

/**
 * Diagnostics › Lab: one probe per display call Nib now relies on that hasn't been seen working on the tablet yet
 * (`docs/09-ink.md` › Verified and not). Each has a drawing surface ([surface], a [Probe]) or none, and its questions;
 * every answer is logged at Info under `nib.probe` with what was sent.
 */
enum class LabProbe(val id: String, val surface: Probe?) {
    PushInk("push_ink", Probe.LabPushInk),
    PushControls("push_controls", Probe.LabPushControls),
    EraserEnd("eraser_end", Probe.LabEraserEnd),
    Lasso("lasso", Probe.LabLasso),
    FastMode("fast_mode", Probe.LabFastMode),
    PalmGuard("palm_guard", Probe.LabPalmGuard),
    StyleParams("style_params", Probe.LabParams),
    Limit("limit", Probe.LabLimit),
    FedStroke("fed_stroke", Probe.LabFedStroke),
    Geometry("geometry", null),
    CleanScreen("clean_screen", null),
    ;

    /** The questions, each with its answers' ids, in the order the page shows them. */
    val questions: List<LabQuestion>
        get() = when (this) {
            PushInk -> listOf(
                LabQuestion("push black", PUSH), LabQuestion("push red", PUSH), LabQuestion("push charcoal", PUSH),
                LabQuestion("pushed colour", listOf("in_colour", "as_grey", "not_shown")),
            )
            PushControls -> listOf(LabQuestion("counter", listOf("kept_up", "stuck", "garbled")))
            EraserEnd -> listOf(
                LabQuestion("eraser track", listOf("track_shown", "no_track", "drew_ink")),
                LabQuestion("strokes erased", listOf("erased", "not_erased")),
            )
            Lasso -> listOf(LabQuestion("lasso path", listOf("dashed", "solid", "nothing")))
            FastMode -> listOf(
                LabQuestion("fast on", listOf("smooth", "jumps", "smears")),
                LabQuestion("fast off", listOf("smooth", "jumps", "smears")),
            )
            PalmGuard -> listOf(LabQuestion("resting hand", listOf("ignored", "touched", "stayed_off")))
            StyleParams -> listOf(LabQuestion("preview follows", listOf("follows", "no_change", "broken")))
            Limit -> listOf(LabQuestion("limit", listOf("inside_only", "everywhere", "nowhere", "wrong_place")))
            FedStroke -> listOf(LabQuestion("fed stroke drawn", listOf("drawn", "nothing")))
            Geometry -> emptyList()
            CleanScreen -> listOf(
                LabQuestion("deep clean", listOf("clean", "ghosting", "no_refresh")),
                LabQuestion("gc then repaint", listOf("clean", "ghosting", "no_refresh")),
            )
        }

    companion object {
        private val PUSH = listOf("exact", "nothing_changed", "garbled", "wrong_place")
    }
}

/** One question of a Lab probe: its key in the log, and the ids of its answers. */
data class LabQuestion(val key: String, val answers: List<String>)

/** The display's geometry as Lab › Geometry reads and checks it. */
object PanelGeometry {
    /**
     * The inverse of a 3 × 3 row-major affine transform (the display's panel-to-screen matrix, whose last row is
     * 0, 0, 1), or null when it can't be inverted.
     */
    fun invert(m: FloatArray): FloatArray? {
        if (m.size < 9) return null
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val det = a * e - b * d
        if (abs(det) < 1e-6f) return null
        val ia = e / det; val ib = -b / det
        val id = -d / det; val ie = a / det
        return floatArrayOf(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f), 0f, 0f, 1f)
    }

    /** [m] applied to a point. */
    fun apply(m: FloatArray, x: Float, y: Float): FloatArray = floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    /** Whether two points agree within [tolerance] pixels on both axes. */
    fun agree(a: FloatArray?, b: FloatArray?, tolerance: Float = 2f): Boolean =
        a != null && b != null && a.size >= 2 && b.size >= 2 && abs(a[0] - b[0]) <= tolerance && abs(a[1] - b[1]) <= tolerance

    /** A screen of [width] × [height]'s corners: top left, top right, bottom right, bottom left. */
    fun corners(width: Float, height: Float): List<FloatArray> = listOf(
        floatArrayOf(0f, 0f), floatArrayOf(width, 0f), floatArrayOf(width, height), floatArrayOf(0f, height),
    )
}
