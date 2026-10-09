package app.booxultimatum.ui.screens

import app.booxultimatum.R
import app.booxultimatum.core.BatteryHealth
import app.booxultimatum.core.PowerSource

fun sourceName(s: PowerSource) = when (s) {
    PowerSource.AC -> R.string.source_ac
    PowerSource.USB -> R.string.source_usb
    PowerSource.Wireless -> R.string.source_wireless
    PowerSource.Dock -> R.string.source_dock
    PowerSource.None -> R.string.source_none
}

fun healthName(h: BatteryHealth) = when (h) {
    BatteryHealth.Good -> R.string.health_good
    BatteryHealth.Overheat -> R.string.health_overheat
    BatteryHealth.Dead -> R.string.health_dead
    BatteryHealth.OverVoltage -> R.string.health_over_voltage
    BatteryHealth.Cold -> R.string.health_cold
    BatteryHealth.Failure -> R.string.health_failure
    BatteryHealth.Unknown -> R.string.health_unknown
}
