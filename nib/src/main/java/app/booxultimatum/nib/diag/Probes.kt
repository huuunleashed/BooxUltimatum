package app.booxultimatum.nib.diag

import app.booxultimatum.kit.ink.canvas.InkCanvasController.Reveal
import app.booxultimatum.kit.ink.session.InkStroke
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.DisplayParams
import app.booxultimatum.nib.engine.brush.HardwareStyle

/** What a band's stroke is: ink the app draws afterwards, or a lasso path the display previews and the app drops. */
enum class BandKind { Ink, Lasso }

/**
 * One band of a probe: what the display is asked to preview while the pen is in it ([preview], sent as is, with no
 * stand-ins or minimums, and with the style's parameters from the band's brush where the style has them), the brush
 * the app draws the stroke with afterwards, and any extra wait before the preview is replaced.
 */
data class Band(
    val key: String,
    val preview: InkStroke,
    val brush: BrushSpec,
    val color: Int,
    val swapDelayMs: Long = 0L,
    val kind: BandKind = BandKind.Ink,
)

/**
 * The Diagnostics probes: questions only the tablet can answer, each a set of bands the owner draws in and then
 * judges. The first seven are the preview's own (their pages in Diagnostics); the [lab] ones are Diagnostics › Lab's
 * surfaces for the display calls not yet verified. Every answer is logged as a `nib.probe` event.
 */
enum class Probe(val id: String, val lab: Boolean = false) {
    MatchPreview("match_preview"),
    Styles("styles"),
    WidthsFountain("widths_fountain"),
    WidthsPencil("widths_pencil"),
    Colours("colours"),
    MarkerColours("marker_colours"),
    SwapDelay("swap_delay"),
    LabPushInk("lab_push_ink", lab = true),
    LabPushControls("lab_push_controls", lab = true),
    LabEraserEnd("lab_eraser_end", lab = true),
    LabLasso("lab_lasso", lab = true),
    LabFastMode("lab_fast_mode", lab = true),
    LabPalmGuard("lab_palm_guard", lab = true),
    LabParams("lab_params", lab = true),
    LabLimit("lab_limit", lab = true),
    LabFedStroke("lab_fed_stroke", lab = true),
    LabPenState4("lab_pen_state_4", lab = true),
    LabEraserPainters("lab_eraser_painters", lab = true),
    ;

    /**
     * When Nib's own stroke replaces the preview in this probe. The preview's own probes judge each stroke against
     * the one that replaces it, so they swap after every stroke; the Lab's keep the native hold where the question
     * needs it.
     */
    val reveal: Reveal
        get() = when (this) {
            LabPushInk -> Reveal.AfterPause
            LabPushControls, LabEraserEnd, LabLasso, LabFastMode -> Reveal.AtBreaks
            else -> Reveal.EveryStroke
        }

    /** The band whose area limits the preview (Lab › Limit), or -1. */
    val limitBand: Int get() = if (this == LabLimit) 1 else -1

    val bands: List<Band> by lazy {
        when (this) {
            // One band per verified style, each with a brush that thins with pressure (but the marker): the preview is
            // sized as the canvas sizes it, through PreviewMatch, so the owner can match it to the stroke that replaces it.
            MatchPreview -> listOf(
                band("match fountain", HardwareStyle.Fountain, 4f, BLACK, BrushSpec.defaults(BrushKind.Fountain).withWidth(4f), BLACK),
                band("match pencil", HardwareStyle.Pencil, 4f, BLACK, BrushSpec.defaults(BrushKind.Pencil).withWidth(4f), BLACK),
                band("match marker", HardwareStyle.Marker, 16f, 0x80000000.toInt(), BrushSpec.defaults(BrushKind.Marker).withWidth(16f), BLACK),
            )
            Styles -> HardwareStyle.entries.map { s ->
                band("style ${s.code}", s, 4f, BLACK, BrushSpec.defaults(brushFor(s)).withWidth(4f), BLACK)
            }
            WidthsFountain -> WIDTHS.map { w ->
                band("fountain $w px", HardwareStyle.Fountain, w, BLACK, BrushSpec.defaults(BrushKind.Fineliner).withWidth(w), BLACK)
            }
            WidthsPencil -> WIDTHS.map { w ->
                band("pencil $w px", HardwareStyle.Pencil, w, BLACK, BrushSpec.defaults(BrushKind.Pencil).withWidth(w), BLACK)
            }
            Colours -> listOf(
                band("red", HardwareStyle.Fountain, 4f, RED, fineliner(4f), RED),
                band("blue", HardwareStyle.Fountain, 4f, BLUE, fineliner(4f), BLUE),
                band("green", HardwareStyle.Fountain, 4f, GREEN, fineliner(4f), GREEN),
                band("grey 50", HardwareStyle.Fountain, 4f, GREY, fineliner(4f), GREY),
                band("translucent black fountain", HardwareStyle.Fountain, 4f, HALF_BLACK, fineliner(4f), HALF_BLACK),
                band("translucent black pencil", HardwareStyle.Pencil, 4f, HALF_BLACK, BrushSpec.defaults(BrushKind.Pencil).withWidth(4f), HALF_BLACK),
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
                band("swap $d ms", HardwareStyle.Fountain, 3f, BLACK, fineliner(3f), BLACK, d)
            }
            LabPushInk -> listOf(
                band("push black", HardwareStyle.Fountain, 4f, BLACK, BrushSpec.defaults(BrushKind.Fountain).withWidth(4f), BLACK),
                band("push red", HardwareStyle.Fountain, 4f, RED, BrushSpec.defaults(BrushKind.Fountain).withWidth(4f), RED),
                band("push charcoal", HardwareStyle.Charcoal, 12f, BLACK, BrushSpec.defaults(BrushKind.Charcoal).withWidth(12f), BLACK),
            )
            LabPushControls -> listOf(band("counter", HardwareStyle.Fountain, 3f, BLACK, fineliner(3f), BLACK))
            LabEraserEnd -> listOf(band("eraser end", HardwareStyle.Fountain, 3f, BLACK, fineliner(3f), BLACK))
            LabLasso -> listOf(
                band("ink", HardwareStyle.Fountain, 3f, BLACK, fineliner(3f), BLACK),
                Band("lasso", InkStroke.lasso(), fineliner(1f), BLACK, kind = BandKind.Lasso),
            )
            LabFastMode -> listOf(band("fast mode", HardwareStyle.Fountain, 3f, BLACK, fineliner(3f), BLACK))
            LabPalmGuard -> listOf(band("palm guard", HardwareStyle.Fountain, 3f, BLACK, fineliner(3f), BLACK))
            LabParams -> listOf(band("parameters", HardwareStyle.Fountain, 6f, BLACK, BrushSpec.defaults(BrushKind.Fountain).withWidth(6f), BLACK))
            LabLimit -> listOf(
                band("above the limit", HardwareStyle.Fountain, 4f, BLACK, fineliner(4f), BLACK),
                band("inside the limit", HardwareStyle.Fountain, 4f, BLACK, fineliner(4f), BLACK),
                band("below the limit", HardwareStyle.Fountain, 4f, BLACK, fineliner(4f), BLACK),
            )
            LabFedStroke -> listOf(band("fed stroke", HardwareStyle.Fountain, 4f, BLACK, fineliner(4f), BLACK))
            LabPenState4 -> listOf(band("state 4", HardwareStyle.Fountain, 4f, BLACK, fineliner(4f), BLACK))
            LabEraserPainters -> PAINTERS.map { p ->
                band("painter $p", HardwareStyle.Fountain, 4f, BLACK, fineliner(4f), BLACK)
            }
        }
    }

    companion object {
        val PAINTERS = (0..8).toList()
        val WIDTHS = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f)
        val SWAP_DELAYS = listOf(0L, 16L, 50L, 120L)
        const val BLACK = -0x1000000
        const val RED = 0xFFD2232A.toInt()
        const val BLUE = 0xFF1F4FB8.toInt()
        const val GREEN = 0xFF1F8A3B.toInt()
        const val GREY = 0xFF808080.toInt()
        const val HALF_BLACK = 0x80000000.toInt()

        /** The preview's own probes, one Diagnostics page each. */
        val pages: List<Probe> get() = entries.filter { !it.lab }

        private fun fineliner(w: Float) = BrushSpec.defaults(BrushKind.Fineliner).withWidth(w)

        /**
         * A band asking for [style] at [width] in [argb], with the parameters [brush]'s own settings give that style
         * (none for styles that take none).
         */
        fun band(key: String, style: HardwareStyle, width: Float, argb: Int, brush: BrushSpec, color: Int, swapDelayMs: Long = 0L): Band {
            val params = DisplayParams.of(style, brush, width).toFloatArray().takeIf { it.isNotEmpty() }
            return Band(key, InkStroke(style.code, width, argb, params), brush, color, swapDelayMs)
        }

        private fun marker(key: String, argb: Int, strokeColor: Int) =
            band(key, HardwareStyle.Marker, 16f, argb, BrushSpec.defaults(BrushKind.Marker).withWidth(16f), strokeColor)

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
