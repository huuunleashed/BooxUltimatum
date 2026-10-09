package app.booxultimatum.nib

import app.booxultimatum.nib.pen.RevealChoice
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NibSettingsTest {
    @Test fun thePreviewDefaultsAreTheNativeOnes() {
        val s = NibSettings(MemoryPrefs())
        assertEquals(RevealChoice.Auto, s.reveal)
        assertEquals(800, s.revealPauseMs)
        assertTrue(s.fastGestures, "fast refresh while moving the page is on")
        assertFalse(s.palmGuard, "Palm guard is off until it's verified")
        assertTrue(s.eraserEndPreview)
        assertTrue(s.instantInk, "Instant ink stays on for owners who already have it")
    }

    @Test fun instantInkCanBeSwitchedOffAndStaysOff() {
        val prefs = MemoryPrefs()
        val s = NibSettings(prefs)
        s.instantInk = false
        assertFalse(s.instantInk)
        assertFalse(NibSettings(prefs).instantInk)
        s.instantInk = true
        assertTrue(NibSettings(prefs).instantInk)
    }

    @Test fun choicesAreKeptAndThePauseIsClamped() {
        val prefs = MemoryPrefs()
        val s = NibSettings(prefs)
        s.reveal = RevealChoice.AfterPause
        s.revealPauseMs = 5_000
        s.fastGestures = false
        s.palmGuard = true
        s.eraserEndPreview = false
        assertEquals(2000, s.revealPauseMs)
        val again = NibSettings(prefs)
        assertEquals(RevealChoice.AfterPause, again.reveal)
        assertEquals(2000, again.revealPauseMs)
        assertFalse(again.fastGestures)
        assertTrue(again.palmGuard)
        assertFalse(again.eraserEndPreview)
    }

    @Test fun aDamagedPauseReadsWithinRange() {
        val prefs = MemoryPrefs().apply { map["reveal_pause_ms"] = 10; map["reveal"] = "sideways" }
        val s = NibSettings(prefs)
        assertEquals(400, s.revealPauseMs)
        assertEquals(RevealChoice.Auto, s.reveal)
    }
}
