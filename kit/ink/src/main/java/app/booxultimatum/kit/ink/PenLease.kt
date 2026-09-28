package app.booxultimatum.kit.ink

import android.os.Process
import app.booxultimatum.kit.log.Logbook
import java.io.File

/**
 * A note, kept while a [PenSession] is open, of which process holds the display's pen session.
 *
 * SurfaceFlinger doesn't notice when the process that opened a session dies. Killed mid-session (an update, a crash,
 * the system), it left the preview drawing over every app, the home screen included, until something stopped it
 * (seen on NA6C FW 4.3 when Nib was updated mid-session, 2026-09-28). The app's next process finds the note and ends
 * that session with [recoverStale].
 */
interface PenLease {
    fun taken()
    fun given()

    object None : PenLease {
        override fun taken() = Unit
        override fun given() = Unit
    }
}

/** A [PenLease] in one small file, which should live in the app's no-backup folder. */
class FileLease(private val file: File) : PenLease {
    private val log = Logbook.logger("ink.session")

    override fun taken() {
        runCatching { file.writeText("${Process.myPid()} ${System.currentTimeMillis()}") }
    }

    override fun given() {
        runCatching { file.delete() }
    }

    /**
     * Ends a session that an earlier process of this app left drawing, and returns whether it did. Call it once, at
     * process start, before this process opens a session. A session found paused is left alone: it draws nothing, and
     * another app may have taken the display since.
     */
    fun recoverStale(display: PenDisplay = SurfaceInkDisplay): Boolean {
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return false
        val pid = text.substringBefore(' ').toIntOrNull()
        if (pid == Process.myPid()) return false
        given()
        if (!display.connect()) return false
        val state = display.penState()
        val live = state == SurfaceInk.START || state == SurfaceInk.DRAW
        log.w("session left by an ended process", "pid" to pid, "since" to text.substringAfter(' ', ""), "state" to state, "ended" to live)
        if (live) display.release()
        return live
    }
}
