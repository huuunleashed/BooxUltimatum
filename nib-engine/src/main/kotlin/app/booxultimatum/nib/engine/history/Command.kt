package app.booxultimatum.nib.engine.history

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.history.EditResult.Applied
import app.booxultimatum.nib.engine.history.EditResult.Rejected

/**
 * An edit to a [Document]. [apply] never throws for bad input: it returns [EditResult.Rejected] and leaves the
 * document alone. A successful apply returns the [EditResult.Applied.inverse] that restores the exact previous state,
 * so undo never re-computes anything. Locked layers refuse content edits; their properties and position can still
 * change. Commands are plain values, so they can be journaled and replayed.
 */
sealed class Command {
    abstract fun apply(doc: Document): EditResult
}

/** A stroke and the position it holds (or will hold) in its layer. */
data class PlacedStroke(val index: Int, val stroke: Stroke)

/** Appends [stroke] to the layer. On an alpha-locked layer a normal stroke is stored with [Blend.Atop]. */
data class AddStroke(val layerId: Long, val stroke: Stroke) : Command() {
    override fun apply(doc: Document): EditResult {
        val layer = doc.layer(layerId) ?: return Rejected(EditError.LayerNotFound(layerId))
        if (layer.locked) return Rejected(EditError.LayerLocked(layerId))
        // A plain scan: building the layer's id map for every appended stroke would cost more.
        if (layer.strokes.any { it.id == stroke.id }) return Rejected(EditError.DuplicateId(stroke.id))
        val stored = if (layer.alphaLock && stroke.brush.blend == Blend.Normal) {
            stroke.copy(brush = stroke.brush.copy(blend = Blend.Atop))
        } else {
            stroke
        }
        val updated = layer.copy(strokes = layer.strokes + stored)
        return Applied(
            doc.withLayer(updated).bumpedPast(stroke.id),
            RemoveStrokes(layerId, listOf(stroke.id)),
            Change(setOf(layerId), stored.bounds, false),
        )
    }
}

/** Puts strokes back at their positions; the inverse of [RemoveStrokes] and [Clear]. Indices are final positions. */
data class InsertStrokes(val layerId: Long, val strokes: List<PlacedStroke>) : Command() {
    override fun apply(doc: Document): EditResult {
        val layer = doc.layer(layerId) ?: return Rejected(EditError.LayerNotFound(layerId))
        if (layer.locked) return Rejected(EditError.LayerLocked(layerId))
        if (strokes.isEmpty()) return Applied(doc, RemoveStrokes(layerId, emptyList()), Change.NONE)
        val placed = strokes.sortedBy { it.index }
        val total = layer.strokes.size + placed.size
        val ids = HashSet<Long>(placed.size * 2)
        var prev = -1
        var maxId = 0L
        for (p in placed) {
            if (p.index <= prev || p.index >= total) return Rejected(EditError.InvalidIndex(p.index))
            if (!ids.add(p.stroke.id) || layer.contains(p.stroke.id)) return Rejected(EditError.DuplicateId(p.stroke.id))
            prev = p.index
            if (p.stroke.id > maxId) maxId = p.stroke.id
        }
        val result = ArrayList<Stroke>(total)
        var src = 0
        var pi = 0
        var bounds = Box.EMPTY
        for (pos in 0 until total) {
            if (pi < placed.size && placed[pi].index == pos) {
                val s = placed[pi++].stroke
                result.add(s)
                bounds = bounds.union(s.bounds)
            } else {
                result.add(layer.strokes[src++])
            }
        }
        return Applied(
            doc.withLayer(layer.copy(strokes = result)).bumpedPast(maxId),
            RemoveStrokes(layerId, placed.map { it.stroke.id }),
            Change(setOf(layerId), bounds, false),
        )
    }
}

/** Removes strokes by id, remembering their positions for an exact undo. */
data class RemoveStrokes(val layerId: Long, val ids: List<Long>) : Command() {
    override fun apply(doc: Document): EditResult {
        val layer = doc.layer(layerId) ?: return Rejected(EditError.LayerNotFound(layerId))
        if (layer.locked) return Rejected(EditError.LayerLocked(layerId))
        if (ids.isEmpty()) return Applied(doc, InsertStrokes(layerId, emptyList()), Change.NONE)
        val wanted = LinkedHashSet(ids)
        val missing = wanted.filter { !layer.contains(it) }
        if (missing.isNotEmpty()) return Rejected(EditError.StrokeNotFound(layerId, missing))
        val placed = ArrayList<PlacedStroke>(wanted.size)
        val kept = ArrayList<Stroke>(layer.strokes.size - wanted.size)
        var bounds = Box.EMPTY
        layer.strokes.forEachIndexed { i, s ->
            if (s.id in wanted) {
                placed.add(PlacedStroke(i, s))
                bounds = bounds.union(s.bounds)
            } else {
                kept.add(s)
            }
        }
        return Applied(
            doc.withLayer(layer.copy(strokes = kept)),
            InsertStrokes(layerId, placed),
            Change(setOf(layerId), bounds, false),
        )
    }
}

/** Replaces strokes with new versions that have the same ids, in place; the inverse of [TransformStrokes]. */
data class ReplaceStrokes(val layerId: Long, val strokes: List<Stroke>) : Command() {
    override fun apply(doc: Document): EditResult {
        val layer = doc.layer(layerId) ?: return Rejected(EditError.LayerNotFound(layerId))
        if (layer.locked) return Rejected(EditError.LayerLocked(layerId))
        if (strokes.isEmpty()) return Applied(doc, ReplaceStrokes(layerId, emptyList()), Change.NONE)
        val byId = HashMap<Long, Stroke>(strokes.size * 2)
        for (s in strokes) if (byId.put(s.id, s) != null) return Rejected(EditError.DuplicateId(s.id))
        val missing = byId.keys.filter { !layer.contains(it) }
        if (missing.isNotEmpty()) return Rejected(EditError.StrokeNotFound(layerId, missing))
        val old = ArrayList<Stroke>(strokes.size)
        var bounds = Box.EMPTY
        val result = layer.strokes.map { s ->
            val r = byId[s.id]
            if (r == null) {
                s
            } else {
                old.add(s)
                bounds = bounds.union(s.bounds).union(r.bounds)
                r
            }
        }
        return Applied(
            doc.withLayer(layer.copy(strokes = result)),
            ReplaceStrokes(layerId, old),
            Change(setOf(layerId), bounds, false),
        )
    }
}

/** Maps strokes through [affine] (a lasso move, scale or rotation). Undo restores the originals exactly. */
data class TransformStrokes(val layerId: Long, val ids: List<Long>, val affine: Affine) : Command() {
    override fun apply(doc: Document): EditResult {
        val values = floatArrayOf(affine.scaleX, affine.skewX, affine.transX, affine.skewY, affine.scaleY, affine.transY)
        if (values.any { !it.isFinite() } || affine.invert() == null) return Rejected(EditError.InvalidTransform)
        val layer = doc.layer(layerId) ?: return Rejected(EditError.LayerNotFound(layerId))
        if (layer.locked) return Rejected(EditError.LayerLocked(layerId))
        val distinct = LinkedHashSet(ids)
        val missing = distinct.filter { !layer.contains(it) }
        if (missing.isNotEmpty()) return Rejected(EditError.StrokeNotFound(layerId, missing))
        return ReplaceStrokes(layerId, distinct.map { layer.stroke(it)!!.transformed(affine) }).apply(doc)
    }
}

/** Moves strokes to the top of another layer, keeping their order. */
data class MoveStrokes(val fromLayer: Long, val toLayer: Long, val ids: List<Long>) : Command() {
    override fun apply(doc: Document): EditResult {
        val from = doc.layer(fromLayer) ?: return Rejected(EditError.LayerNotFound(fromLayer))
        val to = doc.layer(toLayer) ?: return Rejected(EditError.LayerNotFound(toLayer))
        if (from.locked) return Rejected(EditError.LayerLocked(fromLayer))
        if (to.locked) return Rejected(EditError.LayerLocked(toLayer))
        val distinct = LinkedHashSet(ids)
        val missing = distinct.filter { !from.contains(it) }
        if (missing.isNotEmpty()) return Rejected(EditError.StrokeNotFound(fromLayer, missing))
        if (distinct.isEmpty()) return Applied(doc, Batch(emptyList()), Change.NONE)
        val moved = from.strokes.filter { it.id in distinct }
        val removal = RemoveStrokes(fromLayer, moved.map { it.id }).apply(doc)
        if (removal !is Applied) return removal
        val base = removal.document.layer(toLayer)!!.strokes.size
        val insertion = InsertStrokes(toLayer, moved.mapIndexed { k, s -> PlacedStroke(base + k, s) }).apply(removal.document)
        if (insertion !is Applied) return insertion
        return Applied(
            insertion.document,
            Batch(listOf(insertion.inverse, removal.inverse)),
            removal.change.merge(insertion.change),
        )
    }
}

/** Inserts [layer] at [index] (0 is the bottom). */
data class AddLayer(val layer: Layer, val index: Int) : Command() {
    override fun apply(doc: Document): EditResult {
        if (index < 0 || index > doc.layers.size) return Rejected(EditError.InvalidIndex(index))
        if (doc.layer(layer.id) != null) return Rejected(EditError.DuplicateId(layer.id))
        val list = ArrayList(doc.layers)
        list.add(index, layer)
        var maxId = layer.id
        for (s in layer.strokes) if (s.id > maxId) maxId = s.id
        return Applied(
            doc.copy(layers = list).bumpedPast(maxId),
            RemoveLayer(layer.id, evenIfLocked = true),
            Change(setOf(layer.id), visibleBounds(layer), true),
        )
    }
}

/** Removes a layer. Locked layers stay unless [evenIfLocked]; the last layer always stays. */
data class RemoveLayer(val layerId: Long, val evenIfLocked: Boolean = false) : Command() {
    override fun apply(doc: Document): EditResult {
        val index = doc.layerIndex(layerId)
        if (index < 0) return Rejected(EditError.LayerNotFound(layerId))
        val layer = doc.layers[index]
        if (layer.locked && !evenIfLocked) return Rejected(EditError.LayerLocked(layerId))
        if (doc.layers.size == 1) return Rejected(EditError.LastLayer)
        val list = ArrayList(doc.layers)
        list.removeAt(index)
        return Applied(doc.copy(layers = list), AddLayer(layer, index), Change(setOf(layerId), visibleBounds(layer), true))
    }
}

/** Moves a layer to [toIndex] in the stack (0 is the bottom). Allowed on locked layers. */
data class MoveLayer(val layerId: Long, val toIndex: Int) : Command() {
    override fun apply(doc: Document): EditResult {
        val from = doc.layerIndex(layerId)
        if (from < 0) return Rejected(EditError.LayerNotFound(layerId))
        if (toIndex < 0 || toIndex >= doc.layers.size) return Rejected(EditError.InvalidIndex(toIndex))
        if (from == toIndex) return Applied(doc, MoveLayer(layerId, from), Change.NONE)
        val list = ArrayList(doc.layers)
        val layer = list.removeAt(from)
        list.add(toIndex, layer)
        return Applied(doc.copy(layers = list), MoveLayer(layerId, from), Change(setOf(layerId), visibleBounds(layer), true))
    }
}

/** Changes a layer's properties; null leaves a property as it is. Allowed on locked layers (so they can be unlocked). */
data class SetLayerProps(
    val layerId: Long,
    val name: String? = null,
    val visible: Boolean? = null,
    val locked: Boolean? = null,
    val opacity: Float? = null,
    val blend: Blend? = null,
    val alphaLock: Boolean? = null,
) : Command() {
    override fun apply(doc: Document): EditResult {
        val layer = doc.layer(layerId) ?: return Rejected(EditError.LayerNotFound(layerId))
        val newOpacity = opacity?.let { if (it.isNaN()) layer.opacity else it.coerceIn(0f, 1f) }
        val updated = layer.copy(
            name = name ?: layer.name,
            visible = visible ?: layer.visible,
            locked = locked ?: layer.locked,
            opacity = newOpacity ?: layer.opacity,
            blend = blend ?: layer.blend,
            alphaLock = alphaLock ?: layer.alphaLock,
        )
        val inverse = SetLayerProps(
            layerId,
            name = name?.let { layer.name },
            visible = visible?.let { layer.visible },
            locked = locked?.let { layer.locked },
            opacity = newOpacity?.let { layer.opacity },
            blend = blend?.let { layer.blend },
            alphaLock = alphaLock?.let { layer.alphaLock },
        )
        val visual = updated.visible != layer.visible || (layer.visible && (updated.opacity != layer.opacity || updated.blend != layer.blend))
        val bounds = if (visual) layer.contentBounds else Box.EMPTY
        return Applied(doc.withLayer(updated), inverse, Change(setOf(layerId), bounds, visual))
    }
}

/**
 * Merges a layer into the one below it: its strokes go on top of the lower layer's and the layer is removed.
 * Exact when the upper layer is opaque, uses [Blend.Normal] and holds no pixel-eraser strokes (see [isExact]);
 * otherwise its opacity and blend are baked into its strokes, and its pixel erasers then also erase the lower
 * layer's ink beneath them.
 */
data class MergeDown(val layerId: Long) : Command() {
    override fun apply(doc: Document): EditResult {
        val index = doc.layerIndex(layerId)
        if (index < 0) return Rejected(EditError.LayerNotFound(layerId))
        if (index == 0) return Rejected(EditError.NothingBelow)
        val upper = doc.layers[index]
        val lower = doc.layers[index - 1]
        if (upper.locked) return Rejected(EditError.LayerLocked(upper.id))
        if (lower.locked) return Rejected(EditError.LayerLocked(lower.id))
        val merged = upper.strokes.map { s ->
            var b = s.brush
            if (upper.opacity < 1f) b = b.copy(opacity = b.opacity * upper.opacity)
            if (upper.blend != Blend.Normal && b.blend == Blend.Normal) b = b.copy(blend = upper.blend)
            if (b == s.brush) s else s.copy(brush = b)
        }
        val list = ArrayList(doc.layers)
        list[index - 1] = lower.copy(strokes = lower.strokes + merged)
        list.removeAt(index)
        val inverse = Batch(listOf(RemoveStrokes(lower.id, merged.map { it.id }), AddLayer(upper, index)))
        return Applied(doc.copy(layers = list), inverse, Change(setOf(upper.id, lower.id), upper.contentBounds, true))
    }

    companion object {
        /** True when merging [upper] down changes nothing on screen. */
        fun isExact(upper: Layer): Boolean =
            upper.opacity >= 1f && upper.blend == Blend.Normal && upper.strokes.none { it.brush.blend == Blend.Erase }
    }
}

/** Removes every stroke from a layer. */
data class Clear(val layerId: Long) : Command() {
    override fun apply(doc: Document): EditResult {
        val layer = doc.layer(layerId) ?: return Rejected(EditError.LayerNotFound(layerId))
        if (layer.locked) return Rejected(EditError.LayerLocked(layerId))
        if (layer.strokes.isEmpty()) return Applied(doc, InsertStrokes(layerId, emptyList()), Change.NONE)
        return Applied(
            doc.withLayer(layer.copy(strokes = emptyList())),
            InsertStrokes(layerId, layer.strokes.mapIndexed { i, s -> PlacedStroke(i, s) }),
            Change(setOf(layerId), layer.contentBounds, false),
        )
    }
}

/** Several commands as one undo step. All or nothing: if one is refused, none is applied. */
data class Batch(val commands: List<Command>) : Command() {
    override fun apply(doc: Document): EditResult {
        var d = doc
        val inverses = ArrayList<Command>(commands.size)
        var change = Change.NONE
        for (c in commands) {
            when (val r = c.apply(d)) {
                is Rejected -> return r
                is Applied -> {
                    d = r.document
                    inverses.add(r.inverse)
                    change = change.merge(r.change)
                }
            }
        }
        inverses.reverse()
        return Applied(d, Batch(inverses), change)
    }
}

private fun Document.bumpedPast(id: Long): Document = if (id >= nextId) copy(nextId = id + 1) else this

private fun visibleBounds(layer: Layer): Box = if (layer.visible) layer.contentBounds else Box.EMPTY
