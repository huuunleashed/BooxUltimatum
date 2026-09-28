package app.booxultimatum

import android.app.Application
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.ink.InstantInk
import app.booxultimatum.core.sleep.SleepScheduler
import app.booxultimatum.core.suite.Module
import app.booxultimatum.core.suite.Modules
import app.booxultimatum.kit.log.Logbook

/** Process start: the battery log's and the sleep studio's receivers live as long as the process, which as home is nearly always. */
class BooxUltimatumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only the main process; the Shizuku helper runs our code in ":shell" and must not register receivers.
        if (getProcessName() == packageName) {
            Logbook.init(this)
            Logbook.logger("app").i("start", "version" to BuildConfig.VERSION_NAME, "code" to BuildConfig.VERSION_CODE)
            BatteryLog.watch(this)
            if (Modules.added(this, Module.Sleep)) SleepScheduler.watch(this)
            if (Modules.added(this, Module.Ink)) InstantInk.resume(this)
        }
    }
}
