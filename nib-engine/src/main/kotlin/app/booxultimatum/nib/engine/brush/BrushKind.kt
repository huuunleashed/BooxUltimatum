package app.booxultimatum.nib.engine.brush

/**
 * Every brush Nib offers. [id] is stable: it is written to files and used by the app to look up labels.
 */
enum class BrushKind(val id: String) {
    /**
     * Constant width, no pressure, down to 0.5 px: a plain round path, exactly BOOX's own plain pen, and previewed in
     * the same display style (pencil, 0) at every width.
     */
    Fineliner("fineliner"),

    /**
     * Width follows pressure and speed as BOOX's fountain pen does, set by its sensitivity and smoothing, and never
     * thinner than 2 px.
     */
    Fountain("fountain"),

    /** A constant width, as BOOX's ballpoint, in slightly lighter ink. */
    Ballpoint("ballpoint"),

    /**
     * Nib's pencil: BOOX's 1-bit stipple ([app.booxultimatum.nib.engine.render.Stipple]), the ink the display's
     * charcoal v2 style previews, so the preview and the ink look alike. Pressure sets how much of the line the
     * stipple covers (all of it at full pressure), and tilt broadens it without making it lighter, as the preview does.
     */
    Pencil("stipple_pencil"),

    /**
     * The pencil before 0.3: grainy grey dabs at a constant width, darker as the pen presses harder. Its preview, a
     * plain line, never looked like it, so it's no longer offered; strokes drawn with it keep their look.
     */
    GrainPencil("pencil"),

    /**
     * A finer, harder pencil: a constant width, darker as the pen presses harder; like BOOX's pencil, it ignores tilt
     * unless given a tilt scale.
     */
    Graphite("graphite"),

    /**
     * Wide, slightly narrower at a light touch, drawn opaque and laid down at half alpha as BOOX's marker is, so it
     * never darkens itself.
     */
    Marker("marker"),

    /** Multiply blend with a round nib, slightly narrower at a light touch as the marker is (a flat chisel before 0.3). */
    Highlighter("highlighter"),

    /** Width from the square root of pressure, as BOOX's brush and the display's brush style (taper and speed before 0.3). */
    BrushPen("brush_pen"),

    /** Latin calligraphy: a flat nib at +45 degrees, or following the pen's orientation. */
    Calligraphy("calligraphy"),

    /** Asian calligraphy: a flat nib at -45 degrees, the other diagonal from [Calligraphy]. */
    CalligraphyAsian("calligraphy_asian"),

    /** BOOX's brush: width grows with the square root of pressure, whatever the speed. */
    NeoBrush("neo_brush"),

    /** Textured charcoal as BOOX's: pressure drives coverage, and tilt broadens it without making it lighter. */
    Charcoal("charcoal"),

    /** Coarser, more scattered charcoal (the display's charcoal v2); tilt broadens it as BOOX's charcoal does. */
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

    /** True for the brushes drawn as stamped dabs, whose spacing, flow, grain and scatter shape the ink. */
    val rendersAsDabs: Boolean get() = this == GrainPencil || this == Graphite || this == Charcoal || this == CharcoalV2 || this == Airbrush

    /** True for the brushes drawn as BOOX's 1-bit stipple, which only pressure, width and tilt shape. */
    val rendersAsStipple: Boolean get() = this == Pencil

    /** True for the brushes kept only so strokes drawn with them look as they did; they aren't offered any more. */
    val retired: Boolean get() = this == GrainPencil

    /** True for the flat-nib brushes, whose width depends on the stroke's direction against [BrushSpec.nibAngle]. */
    val usesNib: Boolean get() = this == Calligraphy || this == CalligraphyAsian || this == SquarePen || this == Highlighter

    companion object {
        fun fromId(id: String): BrushKind? = entries.firstOrNull { it.id == id }
    }
}
