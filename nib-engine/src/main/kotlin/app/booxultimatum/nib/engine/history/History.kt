package app.booxultimatum.nib.engine.history

import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.history.EditResult.Applied
import app.booxultimatum.nib.engine.index.DocumentIndex
import java.util.concurrent.CopyOnWriteArrayList

/** Which way an edit went. */
enum class HistoryAction { Do, Undo, Redo }

/**
 * One applied edit. [effective] is the command that was actually applied to reach [document] (the inverse, for an
 * undo), which is what a journal records so replay reproduces the final state.
 */
data class HistoryEvent(val action: HistoryAction, val effective: Command, val change: Change, val document: Document)

fun interface HistoryListener {
    fun onEdit(event: HistoryEvent)
}

/**
 * The current document with undo and redo. Keeps [index] in step with every edit. A new command clears the redo
 * steps; at most [maxEntries] undo steps are kept. Refused edits change nothing. Not thread-safe: use it from one
 * thread (the app's UI thread).
 */
class History(document: Document, val maxEntries: Int = 500) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private class Entry(val command: Command, var inverse: Command)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<HistoryListener>()
    private var nextFree = maxOf(document.nextId, document.maxUsedId() + 1)

    var document: Document = document
        private set

    /** The spatial index for [document], kept current. */
    val index: DocumentIndex = DocumentIndex(document)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    /**
     * Applies [command]. When [undoable] is false the edit is applied and reported but leaves the undo and redo
     * steps alone (for things like toggling visibility). Edits that change nothing are not recorded.
     */
    fun execute(command: Command, undoable: Boolean = true): EditResult {
        val r = command.apply(document)
        if (r is Applied && !r.change.isNoOp) {
            commit(r)
            if (undoable) {
                undoStack.addLast(Entry(command, r.inverse))
                while (undoStack.size > maxEntries) undoStack.removeFirst()
                redoStack.clear()
            }
            notify(HistoryAction.Do, command, r)
        }
        return r
    }

    /** Undoes the last step; null when there is none. A refusal (say, the layer was locked since) keeps the step. */
    fun undo(): EditResult? {
        val entry = undoStack.lastOrNull() ?: return null
        val r = entry.inverse.apply(document)
        if (r is Applied) {
            undoStack.removeLast()
            commit(r)
            redoStack.addLast(entry)
            notify(HistoryAction.Undo, entry.inverse, r)
        }
        return r
    }

    /** Redoes the last undone step; null when there is none. */
    fun redo(): EditResult? {
        val entry = redoStack.lastOrNull() ?: return null
        val r = entry.command.apply(document)
        if (r is Applied) {
            redoStack.removeLast()
            commit(r)
            entry.inverse = r.inverse
            undoStack.addLast(entry)
            notify(HistoryAction.Redo, entry.command, r)
        }
        return r
    }

    /** A fresh id for a new stroke or layer, never used before in this document. */
    fun allocateId(): Long {
        val id = maxOf(nextFree, document.nextId)
        nextFree = id + 1
        return id
    }

    /** Drops every undo and redo step, keeping the document. */
    fun clearSteps() {
        undoStack.clear()
        redoStack.clear()
    }

    /** Replaces the document (after loading, say), dropping all steps and rebuilding the index. */
    fun reset(document: Document) {
        this.document = document
        nextFree = maxOf(document.nextId, document.maxUsedId() + 1)
        clearSteps()
        index.rebuild(document)
    }

    fun addListener(listener: HistoryListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: HistoryListener) {
        listeners.remove(listener)
    }

    private fun commit(r: Applied) {
        document = r.document
        index.sync(document, r.change.layers)
    }

    private fun notify(action: HistoryAction, effective: Command, r: Applied) {
        if (listeners.isEmpty()) return
        val event = HistoryEvent(action, effective, r.change, r.document)
        for (l in listeners) l.onEdit(event)
    }
}
