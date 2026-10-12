package app.booxultimatum.core.battery

import android.content.Context

/** BatteryLog and DisplayWatch call these; each hands its work to a thread of its own. */
object BatteryEvents {
    /** The display has been ON while the tablet wasn't interactive for 10 minutes. [sinceMs] is when it went on. */
    @Suppress("UNUSED_PARAMETER")
    fun displayStuck(context: Context, sinceMs: Long) {
        // The log row and the warning are written by DisplayWatch; the guard runs from [guardRound], from minute 2.
    }

    /** A round of the sleep guard is due: [round] counts from 0 within the episode that began at [sinceMs]. */
    fun guardRound(context: Context, sinceMs: Long, round: Int) {
        SleepGuard.onStuck(context, sinceMs, round)
    }

    /** The tablet woke (became interactive) after being asleep since [sleptSinceMs]. */
    fun woke(context: Context, sleptSinceMs: Long) {
        // The guard's own wake-and-lock is not the owner getting up, and must neither send the morning report nor count as input.
        if (SleepGuard.causedWake()) return
        BatteryAlerts.noteWake(context, System.currentTimeMillis())
        MorningReport.onWoke(context, sleptSinceMs)
    }

    /** The app started after a real boot (a new boot count). */
    fun booted(context: Context) {
        MorningReport.onBooted(context)
    }
}
