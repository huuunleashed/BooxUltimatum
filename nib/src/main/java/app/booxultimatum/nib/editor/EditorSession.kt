package app.booxultimatum.nib.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Viewport
import app.booxultimatum.nib.engine.history.AddLayer
import app.booxultimatum.nib.engine.history.AddStroke
import app.booxultimatum.nib.engine.history.Command
import app.booxultimatum.nib.engine.history.EditError
import app.booxultimatum.nib.engine.history.EditResult
import app.booxultimatum.nib.engine.history.History
import app.booxultimatum.nib.engine.history.HistoryEvent
import app.booxultimatum.nib.engine.history.InsertStrokes
import app.booxultimatum.nib.engine.history.MergeDown
import app.booxultimatum.nib.engine.history.MoveLayer
import app.booxultimatum.nib.engine.history.MoveStrokes
import app.booxultimatum.nib.engine.history.PlacedStroke
import app.booxultimatum.nib.engine.history.RemoveLayer
import app.booxultimatum.nib.engine.history.RemoveStrokes
import app.booxultimatum.nib.engine.history.ReplaceStrokes
import app.booxultimatum.nib.engine.history.SetLayerProps
import app.booxultimatum.nib.engine.history.TransformStrokes
import app.booxultimatum.nib.store.AutoSaver
import app.booxultimatum.nib.store.DrawingInfo
import app.booxultimatum.nib.store.Paper

/**
 * One open drawing: its [History] (the document, undo and redo), the layer new strokes go to, and the [AutoSaver]
 * that journals every edit. Every change, the layers panel's included, goes through [execute], so all of it undoes
 * and all of it is saved. [revision] changes with every edit for Compose. Main thread only.
 */
class EditorSession(val info: DrawingInfo, initial: Document, private val saver: AutoSaver, private val savePaper: (Paper) -> Unit = {}) {
    private val log = Logbook.logger("nib.doc")
    val history = History(initial)
    private val editListeners = ArrayList<(HistoryEvent) -> Unit>()

    var revision by mutableIntStateOf(0)
        private set

    /** Where the canvas last looked, so coming back from Diagnostics or About finds the page as it was left. */
    var lastViewport: Viewport? = null

    /** Whether [lastViewport] was the page fitted and left alone, so the canvas fits it again when the tablet turns. */
    var lastViewportFitted = false

    var activeLayerId by mutableLongStateOf(initial.layers.last().id)
        private set

    val document: Document get() = history.document
    val activeLayer: Layer get() = document.layer(activeLayerId) ?: document.layers.last()

    /** The drawing's paper: colour and guides, kept beside it rather than in the document. */
    private val paperState = mutableStateOf(info.paper)
    val paper: Paper get() = paperState.value

    private val paperListeners = ArrayList<() -> Unit>()

    /** Why the last edit was refused, for the editor to say. */
    var lastError: EditError? = null
        private set

    init {
        history.addListener { e ->
            saver.record(e)
            if (e.document.layer(activeLayerId) == null) activeLayerId = e.document.layers.last().id
            for (l in editListeners.toList()) l(e)
            revision++
        }
    }

    fun addEditListener(l: (HistoryEvent) -> Unit) {
        editListeners.add(l)
    }

    fun removeEditListener(l: (HistoryEvent) -> Unit) {
        editListeners.remove(l)
    }

    fun execute(command: Command): Boolean {
        val r = history.execute(command)
        if (r is EditResult.Rejected) {
            lastError = r.error
            log.i("edit refused", "command" to command::class.simpleName, "error" to r.error::class.simpleName)
            return false
        }
        lastError = null
        return true
    }

    fun undo(): Boolean = history.undo() is EditResult.Applied

    fun redo(): Boolean = history.redo() is EditResult.Applied

    fun newId(): Long = history.allocateId()

    fun addStroke(stroke: Stroke): Boolean = execute(AddStroke(activeLayerId, stroke))

    fun removeStrokes(ids: Collection<Long>): Boolean = ids.isEmpty() || execute(RemoveStrokes(activeLayerId, ids.toList()))

    fun selectLayer(id: Long) {
        if (document.layer(id) != null) {
            activeLayerId = id
            revision++
        }
    }

    /** Adds an empty layer above the active one and makes it active. */
    fun addLayer(name: String): Boolean {
        val id = newId()
        val index = document.layerIndex(activeLayerId) + 1
        if (!execute(AddLayer(Layer(id, name), index))) return false
        activeLayerId = id
        return true
    }

    fun deleteLayer(id: Long): Boolean = execute(RemoveLayer(id))

    fun renameLayer(id: Long, name: String): Boolean = execute(SetLayerProps(id, name = name))

    fun setVisible(id: Long, visible: Boolean): Boolean = execute(SetLayerProps(id, visible = visible))

    fun setLocked(id: Long, locked: Boolean): Boolean = execute(SetLayerProps(id, locked = locked))

    fun setOpacity(id: Long, opacity: Float): Boolean = execute(SetLayerProps(id, opacity = opacity))

    fun setBlend(id: Long, blend: Blend): Boolean = execute(SetLayerProps(id, blend = blend))

    fun setAlphaLock(id: Long, on: Boolean): Boolean = execute(SetLayerProps(id, alphaLock = on))

    // ---- Selections ----

    /** Moves, scales or turns strokes of a layer; one undo step. */
    fun transformStrokes(layerId: Long, ids: List<Long>, affine: Affine): Boolean = execute(TransformStrokes(layerId, ids, affine))

    /** Moves strokes to the top of another layer, which becomes the active one. */
    fun moveStrokes(from: Long, to: Long, ids: List<Long>): Boolean {
        if (from == to || !execute(MoveStrokes(from, to, ids))) return false
        activeLayerId = to
        return true
    }

    /** Copies of the strokes, mapped through [affine], on top of the same layer; returns their ids, or null when refused. */
    fun duplicateStrokes(layerId: Long, ids: List<Long>, affine: Affine): List<Long>? {
        val layer = document.layer(layerId) ?: return null
        val originals = ids.mapNotNull { layer.stroke(it) }.sortedBy { layer.indexOf(it.id) }
        if (originals.isEmpty()) return null
        val base = layer.strokes.size
        val copies = originals.mapIndexed { k, s -> PlacedStroke(base + k, s.transformed(affine).copy(id = newId())) }
        if (!execute(InsertStrokes(layerId, copies))) return null
        return copies.map { it.stroke.id }
    }

    /** Gives the strokes [argb]'s colour, keeping each one's own transparency. */
    fun recolourStrokes(layerId: Long, ids: List<Long>, argb: Int): Boolean {
        val layer = document.layer(layerId) ?: return false
        val changed = ids.mapNotNull { layer.stroke(it) }.map { s -> s.copy(color = (s.color and -0x1000000) or (argb and 0xFFFFFF)) }
        return changed.isNotEmpty() && execute(ReplaceStrokes(layerId, changed))
    }

    fun deleteStrokes(layerId: Long, ids: List<Long>): Boolean = ids.isNotEmpty() && execute(RemoveStrokes(layerId, ids))

    // ---- Paper ----

    fun setPaper(p: Paper) {
        if (p == paper) return
        paperState.value = p
        savePaper(p)
        for (l in paperListeners.toList()) l()
    }

    fun addPaperListener(l: () -> Unit) {
        paperListeners.add(l)
    }

    fun removePaperListener(l: () -> Unit) {
        paperListeners.remove(l)
    }

    /** Moves a layer one place up (towards the top) or down. */
    fun moveLayer(id: Long, up: Boolean): Boolean {
        val i = document.layerIndex(id)
        val to = if (up) i + 1 else i - 1
        if (i < 0 || to !in document.layers.indices) return false
        return execute(MoveLayer(id, to))
    }

    fun mergeDown(id: Long): Boolean {
        val below = document.layers.getOrNull(document.layerIndex(id) - 1)
        if (!execute(MergeDown(id))) return false
        below?.let { activeLayerId = it.id }
        return true
    }

    /** A copy of the layer, with new ids for it and its strokes, just above it. */
    fun duplicateLayer(id: Long, name: String): Boolean {
        val src = document.layer(id) ?: return false
        val copy = src.copy(id = newId(), name = name, strokes = src.strokes.map { it.copy(id = newId()) })
        if (!execute(AddLayer(copy, document.layerIndex(id) + 1))) return false
        activeLayerId = copy.id
        return true
    }

    /** The journal is folded into a snapshot (the editor stopped). */
    fun saveNow(reason: String) = saver.compactIfNeeded(reason)

    /** Waits until every edit so far is on disk. */
    fun flush(timeoutMs: Long = 5_000L): Boolean = saver.flush(timeoutMs)

    fun close() {
        editListeners.clear()
        paperListeners.clear()
        saver.close()
    }
}
