package app.booxultimatum.core.battery

import android.content.Context

/** OWNER: H (notifications and guard). BatteryLog and DisplayWatch call these; each hands its work to a thread of its own. */
object BatteryEvents {
    /** The display has been ON while the tablet wasn't interactive for 10 minutes. [sinceMs] is when it went on. */
    fun displayStuck(context: Context, sinceMs: Long) {
        SleepGuard.onStuck(context, sinceMs)
    }

    /** The tablet woke (became interactive) after being asleep since [sleptSinceMs]. */
    fun woke(context: Context, sleptSinceMs: Long) {
        BatteryAlerts.noteWake(context, System.currentTimeMillis())
        MorningReport.onWoke(context, sleptSinceMs)
    }

    /** The app started after a real boot (a new boot count). */
    fun booted(context: Context) {
        MorningReport.onBooted(context)
    }
}
