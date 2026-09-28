package app.booxultimatum.nib

import android.app.Application
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.log.Housekeeping
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.pen.NibPen
import java.io.File

/** Starts the logbook first, so everything after it, crashes included, is recorded. */
class NibApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The drawings are the owner's work: counted in the storage report, never cleared by housekeeping.
        Housekeeping.registerData("drawings") { listOf(File(it.filesDir, "drawings")) }
        Logbook.init(this)
        val t = Tablet.current(this)
        Logbook.logger("nib.ui").i(
            "start",
            "version" to BuildConfig.VERSION_NAME, "code" to BuildConfig.VERSION_CODE,
            "release" to Suite.isReleaseBuild(this), "boox" to t.isBoox, "model" to t.model,
            "firmware" to t.firmware, "pen" to t.hasPen,
        )
        NibPen.start(this)
    }
}
