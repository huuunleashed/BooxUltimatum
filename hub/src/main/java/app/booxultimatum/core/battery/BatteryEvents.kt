package app.booxultimatum.core.battery

import android.content.Context

/** OWNER: H (notifications and guard). BatteryLog calls these; the empty bodies are replaced by the notifications and the guard. */
object BatteryEvents {
    /** The display has been ON while the tablet wasn't interactive for 10 minutes. [sinceMs] is when it went on. */
    fun displayStuck(context: Context, sinceMs: Long) {}

    /** The tablet woke (became interactive) after being asleep since [sleptSinceMs]. */
    fun woke(context: Context, sleptSinceMs: Long) {}

    /** The app started after a real boot (a new boot count). */
    fun booted(context: Context) {}
}