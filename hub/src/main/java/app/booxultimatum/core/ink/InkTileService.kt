package app.booxultimatum.core.ink

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.booxultimatum.MainActivity
import app.booxultimatum.R

/**
 * The Instant ink switch in Quick Settings, so it can be turned on or off without leaving the drawing app. With no
 * app chosen yet, a tap opens the Ink page instead.
 */
class InkTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val p = InkPrefs.load(this)
        if (p.apps.isEmpty()) { openInkPage(); return }
        InstantInk.apply(this, p.copy(enabled = !p.enabled))
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val p = InkPrefs.load(this)
        tile.state = if (p.enabled && p.apps.isNotEmpty()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.ink_title)
        tile.subtitle = getString(
            when {
                p.apps.isEmpty() -> R.string.ink_tile_pick
                p.enabled -> R.string.ink_tile_on
                else -> R.string.ink_tile_off
            },
        )
        tile.updateTile()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openInkPage() {
        val intent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, "Ink").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
