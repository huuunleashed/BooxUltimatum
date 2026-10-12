package app.booxultimatum.core

import app.booxultimatum.core.Refreeze.Entry
import app.booxultimatum.core.Refreeze.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreezeTest {
    private val now = 1_790_000_000_000L
    private val old = Entry("eu.kanade.tachiyomi.j2k", 0, now - 5 * 60_000L)
    private val fresh = Entry("org.example.reader", 0, now - 5_000L)

    @Test
    fun anEntryRoundTripsThroughItsStoredForm() {
        assertEquals(old, Refreeze.decode(Refreeze.encode(old)))
        assertEquals(Entry("a.b", 10, 1L), Refreeze.decode("10|a.b|1"))
    }

    @Test
    fun aDamagedStoredEntryIsIgnored() {
        assertNull(Refreeze.decode(""))
        assertNull(Refreeze.decode("0|a.b"))
        assertNull(Refreeze.decode("x|a.b|1"))
        assertNull(Refreeze.decode("0|a.b|y"))
        assertNull(Refreeze.decode("0||1"))
    }

    @Test
    fun aLockFreezesEverythingOnTheListWhileTheTabletIsLocked() {
        val entries = listOf(old, fresh)
        assertEquals(entries, Refreeze.due(entries, Trigger.ScreenOff, front = old.pkg, frontKnown = true, interactive = false, nowMs = now))
    }

    @Test
    fun aLockThatTheOwnerUndidFreezesNothing() {
        assertEquals(emptyList<Entry>(), Refreeze.due(listOf(old), Trigger.ScreenOff, front = null, frontKnown = false, interactive = true, nowMs = now))
        assertEquals(emptyList<Entry>(), Refreeze.due(listOf(old), Trigger.Start, front = null, frontKnown = false, interactive = true, nowMs = now))
    }

    @Test
    fun aProcessStartedWhileLockedFreezesWhatWasLeft() {
        assertEquals(listOf(old), Refreeze.due(listOf(old), Trigger.Start, front = null, frontKnown = false, interactive = false, nowMs = now))
    }

    @Test
    fun comingHomeLeavesTheAppInFrontAlone() {
        val due = Refreeze.due(listOf(old), Trigger.Home, front = old.pkg, frontKnown = true, interactive = true, nowMs = now)
        assertEquals(emptyList<Entry>(), due)
    }

    @Test
    fun comingHomeFreezesTheAppsThatAreNotInFront() {
        val due = Refreeze.due(listOf(old), Trigger.Home, front = "app.booxultimatum", frontKnown = true, interactive = true, nowMs = now)
        assertEquals(listOf(old), due)
    }

    @Test
    fun anAppOpenedAMomentAgoMayStillBeStartingSoItWaits() {
        val due = Refreeze.due(listOf(old, fresh), Trigger.Home, front = "app.booxultimatum", frontKnown = true, interactive = true, nowMs = now)
        assertEquals(listOf(old), due)
    }

    @Test
    fun withoutKnowingWhatIsInFrontComingHomeFreezesNothing() {
        val due = Refreeze.due(listOf(old), Trigger.Home, front = null, frontKnown = false, interactive = true, nowMs = now)
        assertEquals(emptyList<Entry>(), due)
    }
}
