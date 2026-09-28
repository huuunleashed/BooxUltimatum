package app.booxultimatum.nib.store

import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.log.Redact
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.history.HistoryEvent
import app.booxultimatum.nib.engine.io.Journal
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Saves a drawing as it's edited, on one background thread so the pen never waits for the disk: each applied edit is
 * appended to the journal and synced; every so often ([CompactionPolicy]) and when the editor stops, the journal is
 * folded into a fresh `.nib` snapshot. Edits reach the thread in order, each with the document it produced, so a
 * snapshot always matches the journal records before it.
 */
class AutoSaver(
    private val store: DrawingStore,
    private val id: String,
    opened: OpenedDrawing,
    private val policy: CompactionPolicy = CompactionPolicy(),
) {
    private val log = Logbook.logger("nib.doc")
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "nib-save").apply { isDaemon = false } }

    // Touched only on the executor's thread.
    private var journal: Journal = opened.journal
    private var latest: Document = opened.document
    private var sinceSnapshot = opened.replayed
    private var journalBytes = opened.journalBytes
    private var closed = false
    private var failures = 0

    @Volatile var compactions: Int = 0
        private set

    init {
        if (policy.afterCommand(sinceSnapshot, journalBytes)) executor.execute { compact("open") }
    }

    /** Records an applied edit. */
    fun record(event: HistoryEvent) {
        if (executor.isShutdown) {
            log.w("edit after close not saved", "id" to Redact.hash(id))
            return
        }
        executor.execute {
            if (closed) return@execute
            try {
                journal.append(event.effective)
                journal.sync()
                latest = event.document
                sinceSnapshot++
                journalBytes = store.journalFile(id).length()
                if (policy.afterCommand(sinceSnapshot, journalBytes)) compact("count")
            } catch (e: Exception) {
                failures++
                latest = event.document
                sinceSnapshot++
                log.e("journal append failed", "id" to Redact.hash(id), "failures" to failures, error = e)
                // A snapshot saves the edit even when the journal can't.
                compact("append failed")
            }
        }
    }

    /** Folds the journal into a snapshot if anything is unsaved there (the editor stopped). */
    fun compactIfNeeded(reason: String) {
        if (executor.isShutdown) return
        executor.execute { if (!closed && policy.onStop(sinceSnapshot)) compact(reason) }
    }

    /** Saves what's left, closes the journal and ends the thread; nothing may be recorded after. */
    fun close() {
        if (executor.isShutdown) return
        executor.execute {
            if (closed) return@execute
            if (policy.onStop(sinceSnapshot)) compact("close")
            runCatching { journal.close() }
            closed = true
        }
        executor.shutdown()
    }

    /** Waits until everything queued so far is on disk (tests, and leaving the app). */
    fun flush(timeoutMs: Long = 5_000L): Boolean {
        if (executor.isShutdown) return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        return runCatching { executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }.isSuccess
    }

    private fun compact(reason: String) {
        try {
            runCatching { journal.close() }
            journal = store.writeSnapshot(id, latest, store.freshToken())
            log.i("compacted", "id" to Redact.hash(id), "reason" to reason, "commands" to sinceSnapshot, "journal bytes" to journalBytes)
            sinceSnapshot = 0
            journalBytes = Journal.HEADER_SIZE.toLong()
            compactions++
        } catch (e: Exception) {
            log.e("compaction failed", "id" to Redact.hash(id), "reason" to reason, error = e)
            // Keep journaling into whatever file is there, from its current end.
            runCatching { journal = Journal.openForAppend(store.journalFile(id), store.journalFile(id).length()) }
        }
    }
}
