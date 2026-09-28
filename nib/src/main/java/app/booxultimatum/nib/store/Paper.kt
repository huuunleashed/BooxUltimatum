package app.booxultimatum.nib.store

/** The guides a page can carry under its ink. */
enum class Guides(val key: String) {
    None("none"), Dots("dots"), Grid("grid"), Lines("lines");

    companion object {
        fun of(key: String?): Guides = entries.firstOrNull { it.key == key } ?: None
    }
}

/** The paper colours Nib offers: plain, warm, grey, and black for light ink. */
enum class PaperColour(val key: String, val argb: Int) {
    White("white", 0xFFFFFFFF.toInt()),
    Warm("warm", 0xFFF6F0E1.toInt()),
    Grey("grey", 0xFFEEEEEE.toInt()),
    Black("black", 0xFF111111.toInt()),
    ;

    companion object {
        fun of(argb: Int): PaperColour? = entries.firstOrNull { it.argb == (argb or -0x1000000) }
    }
}

/**
 * A drawing's paper: its colour, its guides and their spacing in document pixels, and whether exports show them.
 * Kept beside the drawing (its `.meta`), not in the document, so a 0.1 drawing opens with plain paper.
 */
data class Paper(
    val colour: Int = PaperColour.White.argb,
    val guides: Guides = Guides.None,
    val spacing: Float = DEFAULT_SPACING,
    val guidesInExport: Boolean = false,
) {
    /** Dark paper wants light guides. */
    val dark: Boolean get() {
        val r = (colour shr 16) and 0xFF
        val g = (colour shr 8) and 0xFF
        val b = colour and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b < 110
    }

    /** The guides' ink: a grey that stays clear of the ink on either paper. */
    val guideColour: Int get() = if (dark) 0xFF5C5C5C.toInt() else 0xFFB4B4B4.toInt()

    companion object {
        const val DEFAULT_SPACING = 60f
        val SPACING_RANGE = 20f..400f
        val PLAIN = Paper()
    }
}