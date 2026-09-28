package app.booxultimatum.nib.engine.brush

/**
 * How ink combines with what is already in its layer.
 *
 * [code] is the stable number written to files; never renumber.
 */
enum class Blend(val code: Int) {
    /** Source over. */
    Normal(0),

    /** Separable multiply (W3C / Android `BlendMode.MULTIPLY`): darkens, never covers dark ink. */
    Multiply(1),

    /** Destination out: removes ink from the layer. */
    Erase(2),

    /**
     * Source atop: paints only where the layer already has ink. Strokes added to an alpha-locked layer get this blend.
     * On Android this is `PorterDuff.Mode.SRC_ATOP`.
     */
    Atop(3),
    ;

    companion object {
        /** The blend for a file code; unknown codes from newer files read as [Normal]. */
        fun fromCode(code: Int): Blend = entries.firstOrNull { it.code == code } ?: Normal
    }
}
