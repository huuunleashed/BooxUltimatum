package app.booxultimatum.nib.ui.studio

import android.content.SharedPreferences
import androidx.core.content.edit

/** A rectangle in pixels, for placing panels without Android types (so placement is tested on the JVM). */
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val landscape: Boolean get() = width > height

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** A panel's top-left corner, in pixels. */
data class Spot(val x: Float, val y: Float)

/**
 * Where a floating panel goes: where the owner last dragged it in this orientation, or else beside the key that
 * opened it, and in either case wholly on screen.
 */
object PanelPlacement {
    /**
     * The top-left for a [w] by [h] panel inside [area]. [saved] wins when there is one; otherwise the panel sits
     * next to [anchor]: below a key in the top band (within two key heights of the top edge; its right edge lined
     * up with a key on the right half), and beside any other key, on the side away from its edge. [margin] is kept from every edge of [area] and [gap] from the anchor.
     */
    fun place(w: Float, h: Float, area: Area, anchor: Area?, saved: Spot?, margin: Float, gap: Float): Spot {
        if (saved != null) return clamp(saved, w, h, area, margin)
        if (anchor == null) return clamp(Spot(area.centerX - w / 2f, area.top + margin), w, h, area, margin)
        val nearTop = anchor.top - area.top < 2f * anchor.height
        val nearBottom = area.bottom - anchor.bottom < 2f * anchor.height
        val spot = when {
            nearTop && anchor.centerX >= area.centerX -> Spot(anchor.right - w, anchor.bottom + gap)
            nearTop -> Spot(anchor.left, anchor.bottom + gap)
            nearBottom -> Spot(anchor.centerX - w / 2f, anchor.top - gap - h)
            anchor.centerX < area.centerX -> Spot(anchor.right + gap, anchor.top)
            else -> Spot(anchor.left - gap - w, anchor.top)
        }
        return clamp(spot, w, h, area, margin)
    }

    /** [spot] moved just enough for the panel to be wholly inside [area]; a panel larger than it keeps its top-left in. */
    fun clamp(spot: Spot, w: Float, h: Float, area: Area, margin: Float): Spot {
        val minX = area.left + margin
        val minY = area.top + margin
        val maxX = area.right - margin - w
        val maxY = area.bottom - margin - h
        val x = if (maxX < minX) minX else spot.x.coerceIn(minX, maxX)
        val y = if (maxY < minY) minY else spot.y.coerceIn(minY, maxY)
        return Spot(x, y)
    }
}

/** Where each panel was left and whether it's pinned, per orientation, kept across launches. Values are in dp. */
class PanelMemory(private val prefs: SharedPreferences) {
    fun spot(panel: String, landscape: Boolean): Spot? {
        val k = key(panel, landscape)
        if (!prefs.contains("$k.x")) return null
        return Spot(prefs.getFloat("$k.x", 0f), prefs.getFloat("$k.y", 0f))
    }

    fun setSpot(panel: String, landscape: Boolean, spot: Spot?) {
        val k = key(panel, landscape)
        prefs.edit {
            if (spot == null) {
                remove("$k.x")
                remove("$k.y")
            } else {
                putFloat("$k.x", spot.x)
                putFloat("$k.y", spot.y)
            }
        }
    }

    fun pinned(panel: String): Boolean = prefs.getBoolean("panel.$panel.pinned", false)

    fun setPinned(panel: String, pinned: Boolean) = prefs.edit { putBoolean("panel.$panel.pinned", pinned) }

    /** Every panel back beside its key. */
    fun forgetSpots() = prefs.edit { prefs.all.keys.filter { it.startsWith("panel.") && (it.endsWith(".x") || it.endsWith(".y")) }.forEach { remove(it) } }

    private fun key(panel: String, landscape: Boolean) = "panel.$panel.${if (landscape) "land" else "port"}"
}