package app.booxultimatum.nib.pen

import app.booxultimatum.kit.core.TabletProfile

/**
 * How long the pen waits before re-arming after a pause: a colour (Kaleido) panel finishes an update more slowly, and
 * NeoReader waits 500 ms there, 200 ms on monochrome panels.
 */
object PanelKind {
    const val REARM_COLOUR_MS = 500L
    const val REARM_MONO_MS = 200L

    fun isColour(profile: TabletProfile): Boolean = profile.hasColourPanel

    fun isColourModel(model: String): Boolean = TabletProfile.isColourModel(model)

    fun rearmMs(profile: TabletProfile): Long = if (isColour(profile)) REARM_COLOUR_MS else REARM_MONO_MS
}
