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
    MatchPreview("match_preview"),
    Styles("styles"),
    WidthsFountain("widths_fountain"),
    WidthsPencil("widths_pencil"),
    Colours("colours"),
    MarkerColours("marker_colours"),
    SwapDelay("swap_delay"),
    ;

    val bands: List<Band> by lazy {
        when (this) {
            // One band per verified style, each with a brush that thins with pressure (but the marker): the preview is
            // sized as the canvas sizes it, through PreviewMatch, so the owner can match it to the stroke that replaces it.
            MatchPreview -> listOf(
                Band("match fountain", PreviewStroke(HardwareStyle.Fountain.code, 4f, BLACK), BrushSpec.defaults(BrushKind.Fountain).withWidth(4f), BLACK),
                Band("match pencil", PreviewStroke(HardwareStyle.Pencil.code, 4f, BLACK), BrushSpec.defaults(BrushKind.Pencil).withWidth(4f), BLACK),
                Band("match marker", PreviewStroke(HardwareStyle.Marker.code, 16f, 0x80000000.toInt()), BrushSpec.defaults(BrushKind.Marker).withWidth(16f), BLACK),
            )
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
            // The marker style at half alpha showed black, grey and yellow but no red, blue, green or teal (owner's
            // test on the Note Air6 C, FW 4.3). Each band is one way round that, sent as is.
            MarkerColours -> listOf(
                marker("red, half alpha", 0x80D2232A.toInt(), RED),
                marker("red, opaque", RED, RED),
                marker("red, blended with white", 0xFFE89194.toInt(), RED),
                marker("red as grey", 0x80585858.toInt(), RED),
                marker("blue, opaque", BLUE, BLUE),
                marker("blue as grey", 0x804D4D4D.toInt(), BLUE),
                marker("mid grey, half alpha", 0x80808080.toInt(), GREY),
                marker("light grey, half alpha", 0x80BBBBBB.toInt(), 0xFFBBBBBB.toInt()),
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

        private fun marker(key: String, argb: Int, strokeColor: Int) =
            Band(key, PreviewStroke(HardwareStyle.Marker.code, 16f, argb), BrushSpec.defaults(BrushKind.Marker).withWidth(16f), strokeColor)

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
