package app.booxultimatum.core.exec

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val APP = "app.booxultimatum"

class PrivilegedTest {

    @Test
    fun theHelperIsLeftAloneLongerEachTimeItFails() {
        assertEquals(10 * 60_000L, helperRetryDelayMs(1))
        assertEquals(60 * 60_000L, helperRetryDelayMs(2))
        assertEquals(6 * 60 * 60_000L, helperRetryDelayMs(3))
        assertEquals(6 * 60 * 60_000L, helperRetryDelayMs(40))
    }

    @Test
    fun aHelperThatIsRunningButSilentIsNamed() {
        val ps = """
            USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME
            u0_a123       4011   801 8123456 210000 0                   0 S $APP
            shell         4120   780 1234567  60000 0                   0 S $APP:shell
            u0_a999       5000   801 8123456 210000 0                   0 S com.other.app
        """.trimIndent()
        val said = describeHelperProcess(ps, APP)
        assertTrue("$APP:shell is running (pid 4120, user shell)" in said, said)
        assertTrue("com.other.app" !in said, said)
    }

    @Test
    fun aHelperThatWasNeverStartedIsSaidSo() {
        val ps = "USER PID PPID VSZ RSS WCHAN ADDR S NAME\nu0_a123 4011 801 8123456 210000 0 0 S $APP\n"
        val said = describeHelperProcess(ps, APP)
        assertTrue("no $APP:shell process" in said, said)
        assertTrue("$APP (pid 4011, user u0_a123)" in said, said)
    }

    @Test
    fun aPsThatListsNothingOfOursIsFlaggedAsIncomplete() {
        assertTrue("may be incomplete" in describeHelperProcess("USER PID PPID NAME\nroot 1 0 init\n", APP))
    }

    @Test
    fun theLogKeepsShizukuLinesAndOurCrashButNotOtherApps() {
        val log = """
            10-11 10:00:00.000  800  800 I ActivityManager: Start proc 123:com.other.app/u0a5
            10-11 10:00:01.000  900  900 I ShizukuServer: starting user service $APP:shell
            10-11 10:00:02.000  900  900 W UserService: process died
            10-11 10:00:03.000 4120 4120 E AndroidRuntime: FATAL EXCEPTION: main
            10-11 10:00:03.001 4120 4120 E AndroidRuntime: Process: $APP:shell, PID: 4120
            10-11 10:00:03.002 4120 4120 E AndroidRuntime: java.lang.NoClassDefFoundError: Failed resolution
            10-11 10:00:03.003 4120 4120 E AndroidRuntime:   at $APP.core.exec.ShellService.<init>(ShellService.kt:1)
            10-11 10:00:04.000  800  800 I ActivityManager: Displayed com.other.app/.Main
        """.trimIndent()
        val kept = helperLogLines(log, APP)
        assertTrue(kept.any { "ShizukuServer" in it })
        assertTrue(kept.any { "NoClassDefFoundError" in it }, "the exception under the crash header is kept")
        assertTrue(kept.none { "ActivityManager" in it }, kept.toString())
    }

    @Test
    fun anotherAppsCrashIsLeftOut() {
        val log = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: com.other.app, PID: 7\nE AndroidRuntime: java.lang.IllegalStateException"
        assertTrue(helperLogLines(log, APP).isEmpty())
    }

    @Test
    fun onlyTheNewestLinesAreKept() {
        val log = (1..100).joinToString("\n") { "I Shizuku: line $it" }
        val kept = helperLogLines(log, APP, max = 30)
        assertEquals(30, kept.size)
        assertTrue(kept.last().endsWith("line 100"))
    }
}
