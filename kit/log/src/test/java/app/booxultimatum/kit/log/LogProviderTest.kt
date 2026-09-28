package app.booxultimatum.kit.log

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogProviderTest {
    @Test
    fun theSameUidOrThePermissionIsNeeded() {
        assertTrue(LogProvider.callerAllowed(callingUid = 10_100, myUid = 10_100, permissionGranted = false))
        assertTrue(LogProvider.callerAllowed(callingUid = 10_200, myUid = 10_100, permissionGranted = true))
        assertFalse(LogProvider.callerAllowed(callingUid = 10_200, myUid = 10_100, permissionGranted = false))
    }

    @Test
    fun suiteAuthorityMatchesTheManifest() {
        assertEquals("app.booxultimatum.nib.logs", SuiteLogs.authority("app.booxultimatum.nib"))
        assertEquals("app.booxultimatum.permission.SUITE", LogProvider.PERMISSION)
    }
}
