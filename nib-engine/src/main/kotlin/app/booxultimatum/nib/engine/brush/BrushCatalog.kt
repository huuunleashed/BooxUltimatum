package app.booxultimatum.nib.engine.brush

/** The brushes in toolbar order, with the stable ids the app maps to translated labels. */
object BrushCatalog {
    /** One toolbar entry. */
    data class Entry(val id: String, val kind: BrushKind) {
        val defaults: BrushSpec get() = BrushSpec.defaults(kind)
        val isEraser: Boolean get() = kind.isEraser
    }

    /** Toolbar order: inks, pencils, broad and paint brushes, then the erasers. */
    val kinds: List<BrushKind> = listOf(
        BrushKind.Fineliner,
        BrushKind.Fountain,
        BrushKind.Ballpoint,
        BrushKind.BrushPen,
        BrushKind.Calligraphy,
        BrushKind.SquarePen,
        BrushKind.Dash,
        BrushKind.Pencil,
        BrushKind.Graphite,
        BrushKind.Charcoal,
        BrushKind.CharcoalV2,
        BrushKind.NeoBrush,
        BrushKind.Marker,
        BrushKind.Highlighter,
        BrushKind.Airbrush,
        BrushKind.PixelEraser,
        BrushKind.StrokeEraser,
        BrushKind.LassoEraser,
    )

    val entries: List<Entry> = kinds.map { Entry(it.id, it) }

    fun byId(id: String): Entry? = entries.firstOrNull { it.id == id }
}
