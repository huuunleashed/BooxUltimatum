package app.booxultimatum.kit.update

import app.booxultimatum.kit.core.SuiteApp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What counts as busy decides whether the Install key can be pressed twice for one release. */
class AppUpdateTest {
    private val app = SuiteApp.entries.first()
    private val release = Release(
        app = app,
        version = Version.parse("1.2.3")!!,
        tag = "v1.2.3",
        test = false,
        preRelease = false,
        htmlUrl = "https://example.invalid/release",
        apkName = "App-1.2.3.apk",
        apkUrl = "https://example.invalid/a.apk",
        checksum = "0".repeat(64),
        checksumUrl = null,
        notes = "",
    )

    private fun phase(p: UpdatePhase) = AppUpdate(app, null, p, release)

    @Test
    fun confirmingCountsAsBusy() {
        // Android's own confirmation screen is up: offering Install again would start a second download and a second
        // installer session for the same release.
        assertTrue(phase(UpdatePhase.Confirming).busy)
        assertFalse(phase(UpdatePhase.Confirming).canInstall)
    }

    @Test
    fun theWorkingStepsAreBusyAndIdleIsNot() {
        assertTrue(phase(UpdatePhase.Checking).busy)
        assertTrue(phase(UpdatePhase.Downloading(0)).busy)
        assertTrue(phase(UpdatePhase.Verifying).busy)
        assertTrue(phase(UpdatePhase.Installing).busy)
        assertFalse(phase(UpdatePhase.Idle).busy)
        assertFalse(phase(UpdatePhase.Installed).busy)
    }

    @Test
    fun aReleaseWithoutAChecksumIsNeverInstalled() {
        val noChecksum = release.copy(checksum = null, checksumUrl = null)
        assertFalse(AppUpdate(app, null, UpdatePhase.Idle, noChecksum).canInstall)
        assertTrue(AppUpdate(app, null, UpdatePhase.Idle, release).canInstall)
    }
}
