package app.booxultimatum.kit.ink.session

import app.booxultimatum.kit.ink.epd.ElevatedRoute
import app.booxultimatum.kit.ink.epd.Epd

/** The display preview's stroke: a firmware style, a width in screen pixels, a colour, and the style's parameters if set. */
class InkStroke(val style: Int, val widthPx: Float, val argb: Int, val params: FloatArray? = null) {
    override fun equals(other: Any?): Boolean = other is InkStroke && style == other.style && widthPx == other.widthPx &&
        argb == other.argb && (params?.contentEquals(other.params) ?: (other.params == null))

    override fun hashCode(): Int = ((style * 31 + widthPx.hashCode()) * 31 + argb) * 31 + (params?.contentHashCode() ?: 0)

    override fun toString(): String = "InkStroke(style=$style, width=$widthPx, argb=${Integer.toHexString(argb)}, params=${params?.joinToString()})"

    fun withWidth(w: Float) = InkStroke(style, w, argb, params)

    companion object {
        /** The dashed style the native app uses for the lasso path, with the parameters it always sends. */
        fun lasso(widthPx: Float = 3f) = InkStroke(STYLE_DASH, widthPx, BLACK, floatArrayOf(5f))

        const val STYLE_PENCIL = 0
        const val STYLE_FOUNTAIN = 1
        const val STYLE_MARKER = 2
        const val STYLE_BRUSH = 3
        const val STYLE_CHARCOAL = 4
        const val STYLE_DASH = 5
        const val STYLE_CHARCOAL_V2 = 6
        const val STYLE_SQUARE = 7

        /** The eraser track style the native app configures for its eraser end, with `[width, 0.5, 0.1]`. */
        const val STYLE_ERASER = 8
        private const val BLACK = -0x1000000
    }
}

/**
 * The display calls a pen session makes, as an interface so the session and the canvas choreography can be tested
 * against a recording fake. In the apps it's [EpdInkDisplay].
 */
interface InkDisplay {
    fun connect(): Boolean
    fun penState(): Int?
    fun setPenState(state: Int): Boolean
    fun setRegionMode(mode: Epd.RegionMode): Boolean

    /** Where the preview may draw: screen rectangles with [screen], else the panel's own frame. */
    fun setLimit(rects: IntArray, screen: Boolean): Boolean

    /** Where the preview must not draw, as screen rectangles; an empty array clears them. */
    fun setExclude(rects: IntArray): Boolean
    fun setStroke(style: Int, widthPx: Float, argb: Int)
    fun strokeParameters(style: Int): FloatArray?
    fun setStrokeParameters(style: Int, params: FloatArray): Boolean
    fun setEraserPreview(on: Boolean, style: Int): Boolean

    /** The display's own brush-stroke preview; the SDK leaves it on. */
    fun setBrushPreview(on: Boolean): Boolean

    /** What each part of the pen draws inside a screen rectangle; an empty list clears every region's configuration. */
    fun setPenButtons(rect: IntArray, buttons: List<Epd.ButtonStroke>): Boolean
    fun enablePost(on: Boolean): Boolean

    /** Ends any session and undoes every display change a session makes. */
    fun release()
}

/** [InkDisplay] through [Epd], optionally through a privileged helper when this process is refused. */
class EpdInkDisplay(private val elevated: ElevatedRoute? = null) : InkDisplay {
    override fun connect() = Epd.connect(elevated) != null
    override fun penState() = Epd.penState()
    override fun setPenState(state: Int) = Epd.setPenState(state)
    override fun setRegionMode(mode: Epd.RegionMode) = Epd.setRegionMode(mode)
    override fun setLimit(rects: IntArray, screen: Boolean) = Epd.setRegionLimit(rects, screen)
    override fun setExclude(rects: IntArray) = Epd.setRegionExclude(rects, screen = true)
    override fun setStroke(style: Int, widthPx: Float, argb: Int) { Epd.setStroke(style, widthPx, argb) }
    override fun strokeParameters(style: Int) = Epd.strokeParameters(style)
    override fun setStrokeParameters(style: Int, params: FloatArray) = Epd.setStrokeParameters(style, params)
    override fun setEraserPreview(on: Boolean, style: Int) = Epd.setEraserRawDrawing(on, style)
    override fun setBrushPreview(on: Boolean) = Epd.setBrushRawDrawing(on)
    override fun setPenButtons(rect: IntArray, buttons: List<Epd.ButtonStroke>) =
        if (buttons.isEmpty()) Epd.clearRegionPenConfig() else Epd.setRegionPenConfig(rect, buttons)
    override fun enablePost(on: Boolean) = Epd.enablePost(on)
    override fun release() = Epd.release()
}
