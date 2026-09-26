package app.booxultimatum

import android.app.Application

/** Process start: the battery log's and the sleep studio's receivers live as long as the process, which as home is nearly always. */
class BooxUltimatumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only the main process; the Shizuku helper runs our code in ":shell" and must not register receivers.
        if (Application.getProcessName() == packageName) {
            app.booxultimatum.core.BatteryLog.watch(this)
            app.booxultimatum.core.sleep.SleepScheduler.watch(this)
            app.booxultimatum.core.ink.InstantInk.resume(this)
        }
    }
}
