package app.booxultimatum.nib.ui

import androidx.annotation.StringRes
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.BrushGroup
import app.booxultimatum.nib.brush.Palette
import app.booxultimatum.nib.brush.PressurePreset
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.HardwareStyle

/** The words for the engine's stable ids. */
object Names {
    @StringRes
    fun brush(kind: BrushKind): Int = when (kind) {
        BrushKind.Fineliner -> R.string.brush_fineliner
        BrushKind.Fountain -> R.string.brush_fountain
        BrushKind.Ballpoint -> R.string.brush_ballpoint
        BrushKind.Pencil -> R.string.brush_pencil
        BrushKind.Graphite -> R.string.brush_graphite
        BrushKind.Marker -> R.string.brush_marker
        BrushKind.Highlighter -> R.string.brush_highlighter
        BrushKind.BrushPen -> R.string.brush_brush_pen
        BrushKind.Calligraphy -> R.string.brush_calligraphy
        BrushKind.CalligraphyAsian -> R.string.brush_calligraphy_asian
        BrushKind.NeoBrush -> R.string.brush_neo_brush
        BrushKind.Charcoal -> R.string.brush_charcoal
        BrushKind.CharcoalV2 -> R.string.brush_charcoal_v2
        BrushKind.Dash -> R.string.brush_dash
        BrushKind.SquarePen -> R.string.brush_square_pen
        BrushKind.Airbrush -> R.string.brush_airbrush
        BrushKind.PixelEraser -> R.string.eraser_pixel
        BrushKind.StrokeEraser -> R.string.eraser_stroke
        BrushKind.LassoEraser -> R.string.eraser_lasso
    }

    @StringRes
    fun group(group: BrushGroup): Int = when (group) {
        BrushGroup.Pens -> R.string.group_pens
        BrushGroup.Pencils -> R.string.group_pencils
        BrushGroup.Markers -> R.string.group_markers
        BrushGroup.Brushes -> R.string.group_brushes
        BrushGroup.Textured -> R.string.group_textured
    }

    @StringRes
    fun style(style: HardwareStyle): Int = when (style) {
        HardwareStyle.Pencil -> R.string.style_pencil
        HardwareStyle.Fountain -> R.string.style_fountain
        HardwareStyle.Marker -> R.string.style_marker
        HardwareStyle.NeoBrush -> R.string.style_neo_brush
        HardwareStyle.Charcoal -> R.string.style_charcoal
        HardwareStyle.Dash -> R.string.style_dash
        HardwareStyle.CharcoalV2 -> R.string.style_charcoal_v2
        HardwareStyle.SquarePen -> R.string.style_square_pen
    }

    @StringRes
    fun pressure(p: PressurePreset): Int = when (p) {
        PressurePreset.Soft -> R.string.pressure_soft
        PressurePreset.Medium -> R.string.pressure_medium
        PressurePreset.Firm -> R.string.pressure_firm
    }

    @StringRes
    fun swatch(s: Palette.Swatch): Int = when (s.key) {
        "black" -> R.string.colour_black
        "dark_grey" -> R.string.colour_dark_grey
        "mid_grey" -> R.string.colour_mid_grey
        "white" -> R.string.colour_white
        "red" -> R.string.colour_red
        "orange" -> R.string.colour_orange
        "yellow" -> R.string.colour_yellow
        "green" -> R.string.colour_green
        "teal" -> R.string.colour_teal
        "blue" -> R.string.colour_blue
        "purple" -> R.string.colour_purple
        else -> R.string.colour_brown
    }
}
