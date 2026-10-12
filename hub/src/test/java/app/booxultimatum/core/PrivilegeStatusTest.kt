package app.booxultimatum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeStatusTest {
    private fun status(readLogs: Boolean, readDropbox: Boolean, secure: Boolean = true, dump: Boolean = true, usage: Boolean = true) =
        PrivilegeStatus(
            secureSettings = secure,
            dump = dump,
            readLogs = readLogs,
            readDropbox = readDropbox,
            usageStats = usage,
            shizukuRunning = false,
            shizukuGranted = false,
            suBinary = null,
        )

    @Test
    fun logsAreReadableOnlyWithBothGrantsWhereTheDropboxNeedsTwo() {
        assertTrue(status(readLogs = true, readDropbox = true).logsReadable)
        assertFalse(status(readLogs = true, readDropbox = false).logsReadable)
        assertFalse(status(readLogs = false, readDropbox = true).logsReadable)
    }

    @Test
    fun aMissingDropboxGrantCountsAsOneUngrantedPermission() {
        assertEquals(PrivilegeStatus.T1_GRANTS, status(readLogs = true, readDropbox = true).t1Granted)
        assertEquals(PrivilegeStatus.T1_GRANTS - 1, status(readLogs = true, readDropbox = false).t1Granted)
    }

    @Test
    fun theHelpListsTheDropboxGrant() {
        assertTrue(PrivilegeStatus.ADB_GRANT_HELP.contains("android.permission.READ_DROPBOX_DATA"))
    }
}
