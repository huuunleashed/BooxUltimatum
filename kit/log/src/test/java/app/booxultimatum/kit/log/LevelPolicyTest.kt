package app.booxultimatum.kit.log

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelPolicyTest {
    private val clock = FakeClock()
    private val policy = LevelPolicy(clock)

    @Test
    fun fileLevelIsAThreshold() {
        policy.configure(Level.Info, Level.Info, emptySet())
        assertFalse(policy.toFile(Level.Verbose, "x"))
        assertFalse(policy.toFile(Level.Debug, "x"))
        assertTrue(policy.toFile(Level.Info, "x"))
        assertTrue(policy.toFile(Level.Warn, "x"))
        assertTrue(policy.toFile(Level.Error, "x"))
        policy.configure(Level.Warn, Level.Info, emptySet())
        assertFalse(policy.toFile(Level.Info, "x"))
        assertTrue(policy.toFile(Level.Warn, "x"))
    }

    @Test
    fun debugCategoriesMatchExactlyAndByPrefix() {
        policy.configure(Level.Info, Level.Info, setOf("ink", "nib"))
        assertTrue(policy.toFile(Level.Debug, "ink"))
        assertTrue(policy.toFile(Level.Debug, "ink.surface"))
        assertTrue(policy.toFile(Level.Debug, "nib.pen"))
        assertFalse(policy.toFile(Level.Debug, "inkwell"))
        assertFalse(policy.toFile(Level.Debug, "hub.ink"))
        assertFalse(policy.toFile(Level.Debug, "other"))
        assertFalse(policy.toFile(Level.Verbose, "ink"), "only Debug is raised")
    }

    @Test
    fun defaultDebugCategories() {
        val c = LogConfig()
        policy.configure(c.fileLevel, Level.Info, c.debugCategories)
        assertTrue(policy.toFile(Level.Debug, "ink"))
        assertTrue(policy.toFile(Level.Debug, "nib.pen"))
        assertTrue(policy.toFile(Level.Debug, "nib.pen.stroke"))
        assertTrue(policy.toFile(Level.Debug, "nib.render"))
        assertTrue(policy.toFile(Level.Debug, "update"))
        assertTrue(policy.toFile(Level.Debug, "suite"))
        assertFalse(policy.toFile(Level.Debug, "nib"))
        assertFalse(policy.toFile(Level.Debug, "nib.ui"))
        assertFalse(policy.toFile(Level.Debug, "sleep"))
    }

    @Test
    fun reconfiguringReplacesTheCachedCategories() {
        policy.configure(Level.Info, Level.Info, setOf("ink"))
        assertTrue(policy.toFile(Level.Debug, "ink.x"))
        policy.configure(Level.Info, Level.Info, setOf("sleep"))
        assertFalse(policy.toFile(Level.Debug, "ink.x"))
        assertTrue(policy.toFile(Level.Debug, "sleep.face"))
    }

    @Test
    fun detailedModeRaisesEverythingUntilItExpires() {
        policy.configure(Level.Info, Level.Info, emptySet())
        assertFalse(policy.toFile(Level.Verbose, "any"))
        policy.detailedUntil = clock.wall + 60_000
        assertTrue(policy.detailed())
        assertTrue(policy.toFile(Level.Verbose, "any"))
        assertTrue(policy.toFile(Level.Debug, "any.thing"))
        clock.wall += 59_999
        assertTrue(policy.toFile(Level.Verbose, "any"))
        clock.wall += 1
        assertFalse(policy.detailed())
        assertFalse(policy.toFile(Level.Verbose, "any"))
        policy.detailedUntil = clock.wall + 1000
        assertTrue(policy.toFile(Level.Verbose, "any"))
        policy.detailedUntil = 0
        assertFalse(policy.toFile(Level.Verbose, "any"))
    }

    @Test
    fun detailedModeDoesNotRaiseLogcat() {
        policy.configure(Level.Info, Level.Info, emptySet())
        policy.detailedUntil = clock.wall + 1000
        assertFalse(policy.toLogcat(Level.Debug))
    }

    @Test
    fun logcatLevelAndIsEnabled() {
        policy.configure(Level.Warn, Level.Debug, emptySet())
        assertFalse(policy.toLogcat(Level.Verbose))
        assertTrue(policy.toLogcat(Level.Debug))
        assertTrue(policy.isEnabled(Level.Debug, "x"), "logcat alone enables")
        assertFalse(policy.isEnabled(Level.Verbose, "x"))
        policy.configure(Level.Info, Level.Error, setOf("ink"))
        assertTrue(policy.isEnabled(Level.Debug, "ink"), "the file alone enables")
        assertFalse(policy.isEnabled(Level.Debug, "x"))
    }
}
