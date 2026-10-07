package app.booxultimatum.nib.pen

import android.os.Build
import android.view.SurfaceControl
import android.view.View

/**
 * Tells when SurfaceFlinger has taken the frame a window draws next.
 *
 * A transaction with a committed listener is handed to the window to ride on its next frame
 * (`AttachedSurfaceControl.applyTransactionOnDraw`), and the listener runs once SurfaceFlinger has applied it, with the
 * frame's buffer. The window gathers such transactions just before it draws, so one handed over when a stroke ends,
 * before the stroke's commit invalidates the canvas, names exactly the frame that shows the stroke.
 *
 * Nib lets the display's held frames through only after that: let through earlier, as the next vsync after drawing
 * could be, the panel can be given the frame before, without the stroke just lifted, and then the right one, so the
 * stroke vanishes for a refresh and comes back.
 */
object FrameLatch {
    /**
     * Calls [onLatched] on the main thread once SurfaceFlinger has the frame [view]'s window draws next. Returns false
     * when this Android version can't tell (before 13) or the view has no window yet; the caller falls back to timing.
     */
    fun watchNextFrame(view: View, onLatched: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val root = view.rootSurfaceControl ?: return false
        val t = SurfaceControl.Transaction()
        t.addTransactionCommittedListener(view.context.mainExecutor) { onLatched() }
        return root.applyTransactionOnDraw(t)
    }
}
