package app.booxultimatum.kit.ink

/**
 * The display's handwriting calls, as an interface so pen logic can be tested against a recording fake. In the app
 * it's [SurfaceInkDisplay]; the numbers are SurfaceFlinger's pen states ([SurfaceInk.STOP] and the rest).
 */
interface PenDisplay {
    /** Finds the display route; false when this firmware offers none (the app then draws without a preview). */
    fun connect(): Boolean
    fun penState(): Int?
    fun setPenState(state: Int): Boolean
    /** Left, top, right, bottom in the panel's own frame. */
    fun setRegion(rect: IntArray): Boolean
    fun setStroke(widthPx: Float, argb: Int, style: Int)
    /** The one rectangle the preview leaves alone, in screen coordinates; null clears it. */
    fun setExclude(screenRect: IntArray?): Boolean
    fun enablePost(on: Boolean): Boolean
    /** Ends any session and hands the panel back to normal drawing. */
    fun release()
}

/** [PenDisplay] through [SurfaceInk], without an elevated route: an app drawing in its own window needs none. */
object SurfaceInkDisplay : PenDisplay {
    override fun connect() = SurfaceInk.connect() != null
    override fun penState() = SurfaceInk.penState()
    override fun setPenState(state: Int) = SurfaceInk.setPenState(state)
    override fun setRegion(rect: IntArray) = SurfaceInk.setRegion(rect)
    override fun setStroke(widthPx: Float, argb: Int, style: Int) = SurfaceInk.setStroke(widthPx, argb, style)
    override fun setExclude(screenRect: IntArray?) = SurfaceInk.setExclude(screenRect ?: IntArray(0), screen = true)
    override fun enablePost(on: Boolean) = SurfaceInk.enablePost(on)
    override fun release() = SurfaceInk.release()
}
