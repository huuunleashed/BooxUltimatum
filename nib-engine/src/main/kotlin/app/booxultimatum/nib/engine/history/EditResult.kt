package app.booxultimatum.nib.engine.history

import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.geom.Box

/**
 * What an edit changed, for redrawing: the layers whose content or properties changed (including added and removed
 * layers), the document area whose pixels may differ, and whether the layer stack itself changed.
 */
data class Change(val layers: Set<Long>, val bounds: Box, val structural: Boolean) {
    /** True when nothing changed at all. */
    val isNoOp: Boolean get() = layers.isEmpty() && !structural && bounds.isEmpty

    /** Both changes together. */
    fun merge(other: Change): Change = when {
        isNoOp -> other
        other.isNoOp -> this
        else -> Change(layers + other.layers, bounds.union(other.bounds), structural || other.structural)
    }

    companion object {
        val NONE = Change(emptySet(), Box.EMPTY, false)
    }
}

/** Why an edit was refused. Refusals never change the document. */
sealed class EditError {
    data class LayerNotFound(val layerId: Long) : EditError()

    data class LayerLocked(val layerId: Long) : EditError()

    data class StrokeNotFound(val layerId: Long, val strokeIds: List<Long>) : EditError()

    data class DuplicateId(val id: Long) : EditError()

    data class InvalidIndex(val index: Int) : EditError()

    /** The transform can't be undone (it is singular or not finite). */
    data object InvalidTransform : EditError()

    /** A document keeps at least one layer. */
    data object LastLayer : EditError()

    /** The bottom layer has nothing to merge into. */
    data object NothingBelow : EditError()
}

/** The outcome of applying a [Command]. */
sealed interface EditResult {
    /** The edit happened: the new [document], the [inverse] that exactly undoes it, and what [change]d. */
    data class Applied(val document: Document, val inverse: Command, val change: Change) : EditResult

    data class Rejected(val error: EditError) : EditResult
}
