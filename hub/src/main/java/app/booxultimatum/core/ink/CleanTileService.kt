package app.booxultimatum.core.ink

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.booxultimatum.R
import app.booxultimatum.kit.core.Tablet
import java.util.concurrent.Executors

/**
 * Clean screen in Quick Settings: one full clean of the panel (a deep GC repaint of everything), which clears the
 * ghosting partial e-ink updates leave behind. A clean while the panel is open would clean the panel rather than the
 * app under it, so a tap waits until Quick Settings closes (the tile stops listening), or [OPEN_PANEL_WAIT_MS] at
 * most. T0: the display call needs no permission; Shizuku is only tried when the direct route is refused.
 */
class CleanTileService : TileService() {
    override fun onStartListening() {
        Pending.shown = ::refresh
        refresh()
    }

    override fun onClick() {
        if (!Tablet.current(this).isBoox) return
        Pending.request()
        refresh()
    }

    override fun onStopListening() {
        Pending.shown = null
        Pending.panelClosed()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        // Hidden at start on tablets without Boox's display (see Modules.syncTiles); unavailable until then.
        tile.state = when {
            !Tablet.current(this).isBoox -> Tile.STATE_UNAVAILABLE
            Pending.waiting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.ink_clean)
        tile.subtitle = getString(
            when {
                Pending.waiting -> R.string.ink_clean_tile_pending
                Pending.lastFailed -> R.string.ink_clean_tile_failed
                else -> R.string.ink_clean_tile_idle
            },
        )
        tile.updateTile()
    }

    /** The tap's clean, kept for the process: the tile's service may be unbound as soon as the panel closes. */
    private object Pending {
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "clean-screen").apply { isDaemon = true } }
        @Volatile var waiting = false
            private set
        @Volatile var lastFailed = false
            private set

        /** The tile's redraw while Quick Settings shows it, so a clean made with the panel open shows its result. */
        var shown: (() -> Unit)? = null

        private val clean = Runnable {
            if (!waiting) return@Runnable
            waiting = false
            shown?.invoke()
            worker.execute {
                lastFailed = !InstantInk.cleanScreen("tile")
                main.post { shown?.invoke() }
            }
        }

        fun request() {
            waiting = true
            main.removeCallbacks(clean)
            main.postDelayed(clean, OPEN_PANEL_WAIT_MS)
        }

        fun panelClosed() {
            if (!waiting) return
            main.removeCallbacks(clean)
            main.postDelayed(clean, PANEL_CLOSED_SETTLE_MS)
        }
    }

    companion object {
        /** A tap with Quick Settings left open still cleans after this long. */
        private const val OPEN_PANEL_WAIT_MS = 5_000L

        /** Once the panel has closed, the app under it is drawn again before the flash. */
        private const val PANEL_CLOSED_SETTLE_MS = 400L
    }
}
