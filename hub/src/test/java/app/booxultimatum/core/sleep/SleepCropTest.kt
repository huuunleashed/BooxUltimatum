package app.booxultimatum.core.sleep

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind the sticker plates. Boox decodes the Transparent style's sticker at the panel size in the
 * rotation the tablet sleeps in with `CENTER_CROP` (`PrepareTransparentDreamAction` and `DreamSettingBean` in the
 * decompiled `com.onyx`), so the sheet is scaled to fill the other shape and then cut back to it. Anything outside the
 * centred square of 0.75 of the short side is lost in one of the two rotations, which is what [SleepCrop] marks out.
 * These tests pin the numbers to the panel and check the crop mapping itself.
 */
class SleepCropTest {
    private val short = 1860f
    private val long = 2480f

    @Test
    fun safeSideIsThreeQuartersOfTheShortSide() {
        assertEquals(1395f, SleepCrop.safeSide(short), 0.5f)
        assertEquals(0.75f, SleepCrop.SAFE_FRACTION)
    }

    /** The panel's own numbers: a portrait sheet shown in landscape keeps its rows 542 to 1937 of 2480. */
    @Test
    fun aPortraitSheetShownInLandscapeKeepsTheMiddleOfItsHeight() {
        assertEquals(542.5f, SleepCrop.keptStart(long, short), 0.5f)
        assertEquals(1937.5f, SleepCrop.keptStart(long, short) + SleepCrop.safeSide(short), 0.5f)
    }

    /** Every corner of the safe square lands inside the panel, whichever way the tablet is held. */
    @Test
    fun theSafeSquareMapsIntoThePanelInBothRotations() {
        for ((w, h) in listOf(1860f to 2480f, 2480f to 1860f)) {
            val side = SleepCrop.safeSide(min(w, h))
            val left = (w - side) / 2f
            val top = (h - side) / 2f
            val corners = listOf(left to top, left + side to top, left to top + side, left + side to top + side)
            // Shown the other way up: scaled to fill, then cut back to the panel in that rotation.
            val targetW = h
            val targetH = w
            val scale = max(targetW / w, targetH / h)
            val dx = (targetW - w * scale) / 2f
            val dy = (targetH - h * scale) / 2f
            for ((x, y) in corners) {
                val tx = x * scale + dx
                val ty = y * scale + dy
                // One pixel of slack: the corner sits exactly on the edge, and float rounding puts it a hair outside.
                assertTrue(
                    tx in -1f..targetW + 1f && ty in -1f..targetH + 1f,
                    "$w×$h corner $x,$y landed at $tx,$ty outside $targetW×$targetH",
                )
            }
        }
    }

    /** The plate used to sit on the panel's margin; in landscape that whole strip is cut away. This is why it moved. */
    @Test
    fun aPlateOnThePanelMarginFallsOutsideTheBand() {
        val foot = long - short * 0.095f
        val keptEnd = SleepCrop.keptStart(long, short) + SleepCrop.safeSide(short)
        assertTrue(foot > keptEnd, "a plate at the panel's foot ($foot) is inside the kept band ($keptEnd)")
    }
}
