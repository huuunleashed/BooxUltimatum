package app.booxultimatum.kit.log

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExitReasonsTest {
    @Test
    fun namesFollowApplicationExitInfo() {
        val expected = listOf(
            "unknown", "exit_self", "signaled", "low_memory", "crash", "crash_native", "anr", "initialization_failure",
            "permission_change", "excessive_resource_usage", "user_requested", "user_stopped", "dependency_died",
            "other", "freezer", "package_state_change", "package_updated",
        )
        assertEquals(expected, (0..16).map(ExitReasons::name))
        assertEquals("reason_99", ExitReasons.name(99))
        assertEquals("reason_-1", ExitReasons.name(-1))
    }

    @Test
    fun failuresAreCrashesAnrsAndKills() {
        assertTrue(ExitReasons.isFailure(ExitReasons.CRASH))
        assertTrue(ExitReasons.isFailure(ExitReasons.CRASH_NATIVE))
        assertTrue(ExitReasons.isFailure(ExitReasons.ANR))
        assertTrue(ExitReasons.isFailure(7))
        assertTrue(ExitReasons.isFailure(9))
        assertFalse(ExitReasons.isFailure(1))
        assertFalse(ExitReasons.isFailure(10))
    }
}
