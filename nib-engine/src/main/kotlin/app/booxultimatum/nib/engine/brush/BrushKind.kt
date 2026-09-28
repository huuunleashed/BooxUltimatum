package app.booxultimatum.nib.engine.brush

/**
 * Every brush Nib offers. [id] is stable: it is written to files and used by the app to look up labels.
 */
enum class BrushKind(val id: String) {
    /** Constant width, no pressure, down to 0.5 px. */
    Fineliner("fineliner"),

    /** Width follows pressure. */
    Fountain("fountain"),

    /** Slight pressure response, slightly lighter ink. */
    Ballpoint("ballpoint"),

    /** Grainy pencil that matches the firmware's pencil preview. */
    Pencil("pencil"),

    /** A fine pencil: width and grain density follow pressure. */
    Graphite("graphite"),

    /** Wide, 50 % group opacity, no pressure. */
    Marker("marker"),

    /** Multiply blend with a flat chisel tip. */
    Highlighter("highlighter"),

    /** Width from pressure and speed, tapered ends. */
    BrushPen("brush_pen"),

    /** A flat nib at a fixed angle or following the pen's orientation. */
    Calligraphy("calligraphy"),

    /** The firmware's brush look: strong pressure response and long tapers. */
    NeoBrush("neo_brush"),

    /** Textured charcoal: pressure drives coverage. */
    Charcoal("charcoal"),

    /** Coarser, more scattered charcoal. */
    CharcoalV2("charcoal_v2"),

    /** A dashed line. */
    Dash("dash"),

    /** A square nib. */
    SquarePen("square_pen"),

    /** Soft stamped dabs. */
    Airbrush("airbrush"),

    /** Erases pixels drawn before it in the same layer. */
    PixelEraser("pixel_eraser"),

    /** Removes whole strokes it touches; never rendered or stored. */
    StrokeEraser("stroke_eraser"),

    /** Removes strokes inside a lasso; never rendered or stored. */
    LassoEraser("lasso_eraser"),
    ;

    val isEraser: Boolean get() = this == PixelEraser || this == StrokeEraser || this == LassoEraser

    /** False for the erasers that act on whole strokes and leave nothing in the document. */
    val isRendered: Boolean get() = this != StrokeEraser && this != LassoEraser

    companion object {
        fun fromId(id: String): BrushKind? = entries.firstOrNull { it.id == id }
    }
}
