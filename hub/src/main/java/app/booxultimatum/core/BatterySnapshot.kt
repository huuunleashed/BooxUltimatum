package app.booxultimatum.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

enum class ChargeStatus { Charging, Discharging, Full, NotCharging, Unknown }
enum class PowerSource { AC, USB, Wireless, Dock, None }
enum class BatteryHealth { Good, Overheat, Dead, OverVoltage, Cold, Failure, Unknown }

/** One reading of Android's battery service. Nothing here polls; callers decide when to read. */
data class BatterySnapshot(
    val levelPct: Int,
    val chargeCounterMah: Double?,
    val estimatedFullMah: Double?,
    val currentNowMa: Double?,
    val voltageMv: Int?,
    val tempC: Double?,
    val status: ChargeStatus,
    val source: PowerSource,
    val health: BatteryHealth,
    val technology: String?,
    val cycleCount: Int?,
    val interactive: Boolean,
    val sinceBootMs: Long,
    val asleepFraction: Double,
    val readAtMillis: Long,
) {
    val charging get() = status == ChargeStatus.Charging

    companion object {
        fun read(context: Context): BatterySnapshot {
            val bm = context.getSystemService(BatteryManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            fun prop(id: Int): Int? = bm.getIntProperty(id).takeIf { it != Int.MIN_VALUE && it != 0 }
            fun extra(name: String): Int? = sticky?.getIntExtra(name, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }

            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            // Charge counter is reported in µAh on this firmware (verified: 2,000,875 at 53 %).
            val counterMah = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.div(1000.0)
            val elapsed = SystemClock.elapsedRealtime()
            val awake = SystemClock.uptimeMillis()

            return BatterySnapshot(
                levelPct = level,
                chargeCounterMah = counterMah,
                estimatedFullMah = if (counterMah != null && level in 5..100) counterMah * 100.0 / level else null,
                currentNowMa = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.div(1000.0),
                voltageMv = extra(BatteryManager.EXTRA_VOLTAGE)?.takeIf { it > 0 },
                tempC = extra(BatteryManager.EXTRA_TEMPERATURE)?.div(10.0),
                status = when (extra(BatteryManager.EXTRA_STATUS)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.Charging
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.Discharging
                    BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.Full
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NotCharging
                    else -> ChargeStatus.Unknown
                },
                source = when (extra(BatteryManager.EXTRA_PLUGGED)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
                    BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.Wireless
                    BatteryManager.BATTERY_PLUGGED_DOCK -> PowerSource.Dock
                    else -> PowerSource.None
                },
                health = when (extra(BatteryManager.EXTRA_HEALTH)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.Good
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.Overheat
                    BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.Dead
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OverVoltage
                    BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.Cold
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.Failure
                    else -> BatteryHealth.Unknown
                },
                technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
                cycleCount = if (Build.VERSION.SDK_INT >= 34) extra(BatteryManager.EXTRA_CYCLE_COUNT)?.takeIf { it >= 0 } else null,
                interactive = pm.isInteractive,
                sinceBootMs = elapsed,
                asleepFraction = if (elapsed > 0) (elapsed - awake).toDouble() / elapsed else 0.0,
                readAtMillis = System.currentTimeMillis(),
            )
        }
    }
}
