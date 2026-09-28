package app.booxultimatum.nib.engine.doc

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.geom.Box

/**
 * One layer: an ordered list of strokes, drawn first to last. Immutable; commands return changed copies that share
 * the untouched strokes.
 *
 * A pixel-eraser stroke erases only what was drawn before it in this layer, so erasing stays exact, reversible and
 * independent of zoom.
 *
 * @property opacity how opaque the whole layer is when composited, 0..1.
 * @property blend how the layer composites onto the layers below it ([Blend.Multiply] suits highlighter layers,
 *   [Blend.Atop] clips the layer to the ink beneath it).
 * @property alphaLock new strokes paint only over ink already in the layer (they are stored with [Blend.Atop]).
 */
data class Layer(
    val id: Long,
    val name: String = "",
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val blend: Blend = Blend.Normal,
    val alphaLock: Boolean = false,
    val strokes: List<Stroke> = emptyList(),
) {
    /** The union of the strokes' bounds. */
    val contentBounds: Box by lazy(LazyThreadSafetyMode.PUBLICATION) {
        var b = Box.EMPTY
        for (s in strokes) b = b.union(s.bounds)
        b
    }

    /**
     * True when the layer has to be rendered into its own group (translucent, a non-normal blend, or strokes that
     * erase, multiply or clip within the layer). Other layers can be drawn straight onto the composite.
     */
    val needsIsolation: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        opacity < 1f || blend != Blend.Normal || strokes.any { it.brush.blend != Blend.Normal }
    }

    private val positions: Map<Long, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val m = HashMap<Long, Int>(strokes.size * 2)
        strokes.forEachIndexed { i, s -> m[s.id] = i }
        m
    }

    /** The position of the stroke with [strokeId], or -1. */
    fun indexOf(strokeId: Long): Int = positions[strokeId] ?: -1

    fun stroke(strokeId: Long): Stroke? = positions[strokeId]?.let { strokes[it] }

    fun contains(strokeId: Long): Boolean = positions.containsKey(strokeId)
}
