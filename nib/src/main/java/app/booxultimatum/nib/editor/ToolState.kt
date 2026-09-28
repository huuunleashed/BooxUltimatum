package app.booxultimatum.nib.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.booxultimatum.nib.brush.BrushPreset
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec

/** Whether the pen draws or erases. */
enum class ToolMode { Pen, Eraser }

/**
 * The toolbar's state: four favourite pens, which one is chosen, the eraser and its widths. Kept across launches and
 * readable as Compose state; the canvas reads it at the start of each stroke.
 */
class ToolState private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("nib.tools", Context.MODE_PRIVATE)

    var slots: List<BrushPreset> by mutableStateOf(
        List(SLOTS) { i -> BrushPreset.decode(prefs.getString("slot$i", null)) ?: BrushPreset.DEFAULTS[i] },
    )
        private set

    private val selectedState = mutableIntStateOf(prefs.getInt("selected", 0).coerceIn(0, SLOTS - 1))
    var selected: Int
        get() = selectedState.intValue
        set(v) {
            selectedState.intValue = v.coerceIn(0, SLOTS - 1)
            prefs.edit { putInt("selected", selectedState.intValue) }
        }

    var mode by mutableStateOf(ToolMode.Pen)

    var eraser: BrushKind by mutableStateOf(prefs.getString("eraser", null)?.let { BrushKind.fromId(it) }?.takeIf { it.isEraser } ?: BrushKind.StrokeEraser)
        private set

    private val eraserWidths = mutableStateOf(
        ERASERS.associateWith { k -> prefs.getFloat("eraser_width_${k.id}", BrushSpec.defaults(k).width) },
    )

    val current: BrushPreset get() = slots[selected]

    fun setSlot(index: Int, preset: BrushPreset) {
        slots = slots.toMutableList().also { it[index] = preset }
        prefs.edit { putString("slot$index", preset.encode()) }
    }

    fun updateCurrent(change: (BrushPreset) -> BrushPreset) = setSlot(selected, change(current))

    fun chooseEraser(kind: BrushKind) {
        require(kind.isEraser)
        eraser = kind
        prefs.edit { putString("eraser", kind.id) }
    }

    fun eraserWidth(kind: BrushKind): Float = eraserWidths.value[kind] ?: BrushSpec.defaults(kind).width

    fun setEraserWidth(kind: BrushKind, width: Float) {
        val w = BrushSpec.defaults(kind).withWidth(width).width
        eraserWidths.value = eraserWidths.value + (kind to w)
        prefs.edit { putFloat("eraser_width_${kind.id}", w) }
    }

    /** The brush for an eraser of [kind] at its chosen width. */
    fun eraserSpec(kind: BrushKind = eraser): BrushSpec = BrushSpec.defaults(kind).withWidth(eraserWidth(kind))

    companion object {
        const val SLOTS = 4
        val ERASERS = listOf(BrushKind.PixelEraser, BrushKind.StrokeEraser, BrushKind.LassoEraser)

        @Volatile private var instance: ToolState? = null

        fun get(context: Context): ToolState =
            instance ?: synchronized(this) { instance ?: ToolState(context.applicationContext).also { instance = it } }
    }
}
