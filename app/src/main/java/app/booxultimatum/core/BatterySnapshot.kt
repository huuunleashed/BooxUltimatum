package app.booxultimatum.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock

data class BatterySnapshot(
    val levelPct: Int,
    val currentNowMa: Double?,
    val currentAvgMa: Double?,
    val chargeCounterMah: Double?,
    val voltageMv: Int,
    val tempC: Double,
    val status: String,
    val plugged: String,
    val interactive: Boolean,
    val sinceBootMin: Long,
    val deepSleepPct: Double,
    val sysfsCurrentNow: String?,
) {
    fun rows(): List<Pair<String, String>> = listOf(
        "Level" to "$levelPct %",
        "Current now" to (currentNowMa?.let { "%.1f mA".format(it) } ?: "n/a"),
        "Current avg" to (currentAvgMa?.let { "%.1f mA".format(it) } ?: "n/a"),
        "Charge counter" to (chargeCounterMah?.let { "%.0f mAh".format(it) } ?: "n/a"),
        "Voltage" to "$voltageMv mV",
        "Temperature" to "%.1f °C".format(tempC),
        "Status / plugged" to "$status / $plugged",
        "Screen interactive" to interactive.toString(),
        "Since boot" to "$sinceBootMin min",
        "Deep sleep since boot" to "%.1f %%".format(deepSleepPct),
        "sysfs current_now" to (sysfsCurrentNow ?: "not readable"),
    )

    companion object {
        fun read(context: Context): BatterySnapshot {
            val bm = context.getSystemService(BatteryManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            fun prop(id: Int): Int? = bm.getIntProperty(id).takeIf { it != Int.MIN_VALUE && it != 0 }
            // Units vary by vendor (µA on most Qualcomm devices); we assume µA and verify on the device.
            val currentNow = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.div(1000.0)
            val currentAvg = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.div(1000.0)
            val counter = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.div(1000.0)

            val status = when (sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> "unknown"
            }
            val plugged = when (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "no"
                else -> "other"
            }
            val elapsed = SystemClock.elapsedRealtime()
            val awake = SystemClock.uptimeMillis()
            return BatterySnapshot(
                levelPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                currentNowMa = currentNow,
                currentAvgMa = currentAvg,
                chargeCounterMah = counter,
                voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
                tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
                status = status,
                plugged = plugged,
                interactive = pm.isInteractive,
                sinceBootMin = elapsed / 60_000,
                deepSleepPct = if (elapsed > 0) 100.0 * (elapsed - awake) / elapsed else 0.0,
                sysfsCurrentNow = Shell.readFile("/sys/class/power_supply/battery/current_now"),
            )
        }
    }
}
