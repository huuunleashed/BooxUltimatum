package app.booxultimatum.kit.ink.epd

/**
 * The e-ink controller's update modes, numbered as the firmware numbers them (constants of FW 4.3's
 * `android.onyx.ViewUpdateHelper`, read from the decompiled framework). Used with [Epd.refresh],
 * [Epd.repaintEverything] and [Epd.applyTransientUpdate].
 */
enum class UpdateMode(val code: Int) {
    /** Direct update: black and white only, the fastest partial update. */
    DU(1),

    /** Grey update without a flash. */
    GU(2),

    /** Four-level grey with a flash. */
    GC4(3),

    /** Animation (A2): two-level and the fastest; the native apps use it while the page pans or scrolls. */
    Animation(4),

    /** The firmware's default for an app. */
    Default(5),

    /** Regal: a grey update that cleans ghosting without a flash. */
    Regal(6),

    RegalPlus(9),

    /** A full flash that cleans all ghosting. */
    GC(98),

    GCC(107),

    DeepGC(108),

    /** The handwriting flag on GU, which the native note app uses to repaint ink once the pen pauses. */
    HandwritingRepaint(524290),

    DuQuality(2305),

    AnimationQuality(2308),

    DU4(2312),

    AnimationMono(33554436),
}
