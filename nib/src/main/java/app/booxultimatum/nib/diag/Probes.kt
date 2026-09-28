package app.booxultimatum.nib.diag

import app.booxultimatum.kit.ink.PreviewStroke
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle

/**
 * One band of a probe: what the display is asked to preview while the pen is in it ([preview], sent as is, with no
 * stand-ins or minimums), the brush the app draws the stroke with afterwards, and any extra wait before the swap.
 */
data class Band(val key: String, val preview: PreviewStroke, val brush: BrushSpec, val color: Int, val swapDelayMs: Long = 0L)

/**
 * The Diagnostics probes: questions only the tablet can answer, each a set of bands the owner draws one stroke in
 * and then judges. Every answer is logged as a `nib.probe` event.
 */
enum class Probe(val id: String) {
    Styles("styles"),
    WidthsFountain("widths_fountain"),
    WidthsPencil("widths_pencil"),
    Colours("colours"),
    SwapDelay("swap_delay"),
    ;

    val bands: List<Band> by lazy {
        when (this) {
            Styles -> HardwareStyle.entries.map { s ->
                Band("style ${s.code}", PreviewStroke(s.code, 4f, BLACK), BrushSpec.defaults(brushFor(s)).withWidth(4f), BLACK)
            }
            WidthsFountain -> WIDTHS.map { w ->
                Band("fountain $w px", PreviewStroke(HardwareStyle.Fountain.code, w, BLACK), BrushSpec.defaults(BrushKind.Fineliner).withWidth(w), BLACK)
            }
            WidthsPencil -> WIDTHS.map { w ->
                Band("pencil $w px", PreviewStroke(HardwareStyle.Pencil.code, w, BLACK), BrushSpec.defaults(BrushKind.Pencil).withWidth(w), BLACK)
            }
            Colours -> listOf(
                Band("red", PreviewStroke(HardwareStyle.Fountain.code, 4f, RED), fineliner(4f), RED),
                Band("blue", PreviewStroke(HardwareStyle.Fountain.code, 4f, BLUE), fineliner(4f), BLUE),
                Band("green", PreviewStroke(HardwareStyle.Fountain.code, 4f, GREEN), fineliner(4f), GREEN),
                Band("grey 50", PreviewStroke(HardwareStyle.Fountain.code, 4f, GREY), fineliner(4f), GREY),
                Band("translucent black fountain", PreviewStroke(HardwareStyle.Fountain.code, 4f, HALF_BLACK), fineliner(4f), HALF_BLACK),
                Band("translucent black pencil", PreviewStroke(HardwareStyle.Pencil.code, 4f, HALF_BLACK), BrushSpec.defaults(BrushKind.Pencil).withWidth(4f), HALF_BLACK),
            )
            SwapDelay -> SWAP_DELAYS.map { d ->
                Band("swap $d ms", PreviewStroke(HardwareStyle.Fountain.code, 3f, BLACK), fineliner(3f), BLACK, d)
            }
        }
    }

    companion object {
        val WIDTHS = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f)
        val SWAP_DELAYS = listOf(0L, 16L, 50L, 120L)
        const val BLACK = -0x1000000
        const val RED = 0xFFD2232A.toInt()
        const val BLUE = 0xFF1F4FB8.toInt()
        const val GREEN = 0xFF1F8A3B.toInt()
        const val GREY = 0xFF808080.toInt()
        const val HALF_BLACK = 0x80000000.toInt()

        private fun fineliner(w: Float) = BrushSpec.defaults(BrushKind.Fineliner).withWidth(w)

        /** The engine brush that asks for [style], for drawing each Styles band's stroke in the app. */
        fun brushFor(style: HardwareStyle): BrushKind = when (style) {
            HardwareStyle.Pencil -> BrushKind.Pencil
            HardwareStyle.Fountain -> BrushKind.Fountain
            HardwareStyle.Marker -> BrushKind.Marker
            HardwareStyle.NeoBrush -> BrushKind.NeoBrush
            HardwareStyle.Charcoal -> BrushKind.Charcoal
            HardwareStyle.Dash -> BrushKind.Dash
            HardwareStyle.CharcoalV2 -> BrushKind.CharcoalV2
            HardwareStyle.SquarePen -> BrushKind.SquarePen
        }
    }
}

/** What the owner says about a band. */
enum class Answer(val id: String) { LooksRight("looks_right"), Broken("broken"), NothingDrawn("nothing_drawn") }
