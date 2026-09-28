package app.booxultimatum.nib.engine.doc

import app.booxultimatum.nib.engine.geom.Box
import java.util.UUID

/**
 * A layered vector document. [layers] run from the bottom (index 0) to the top; there is no limit on their number.
 * [nextId] is the next free id for strokes and layers; it only ever grows, so ids are never reused.
 * Immutable: edit it through [app.booxultimatum.nib.engine.history.Command]s.
 */
data class Document(
    val id: String,
    val width: Int,
    val height: Int,
    val background: Int = WHITE,
    val layers: List<Layer>,
    val nextId: Long,
) {
    /** The page, in document pixels. */
    val bounds: Box get() = Box(0f, 0f, width.toFloat(), height.toFloat())

    fun layer(id: Long): Layer? = layers.firstOrNull { it.id == id }

    /** The position of the layer with [id], or -1. */
    fun layerIndex(id: Long): Int = layers.indexOfFirst { it.id == id }

    val strokeCount: Int get() = layers.sumOf { it.strokes.size }

    /** A copy with [layer] in place of the layer with the same id. */
    fun withLayer(layer: Layer): Document {
        val i = layerIndex(layer.id)
        require(i >= 0) { "no layer ${layer.id}" }
        val list = ArrayList(layers)
        list[i] = layer
        return copy(layers = list)
    }

    /** The largest stroke or layer id in use, or 0. */
    fun maxUsedId(): Long {
        var m = 0L
        for (l in layers) {
            if (l.id > m) m = l.id
            for (s in l.strokes) if (s.id > m) m = s.id
        }
        return m
    }

    companion object {
        const val WHITE: Int = -1

        /** A new document with one empty layer. */
        fun blank(
            width: Int,
            height: Int,
            background: Int = WHITE,
            id: String = UUID.randomUUID().toString(),
            layerName: String = "",
        ): Document = Document(id, width, height, background, listOf(Layer(1L, layerName)), nextId = 2L)
    }
}
