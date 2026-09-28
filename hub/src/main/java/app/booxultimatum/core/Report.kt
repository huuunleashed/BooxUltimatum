package app.booxultimatum.core

import android.content.Context

/** Plain-text report for sharing with developers. Deliberately English-only: it is a data export, not UI. */
object Report {
    suspend fun build(context: Context): String {
        val device = DeviceReader.read(context)
        val battery = BatterySnapshot.read(context)
        val access = PrivilegeStatus.check(context)
        val apps = PackageScanner.scan(context)
        return buildString {

        appendLine("# BooxUltimatum report")
        device.groups.forEach { g ->
            appendLine()
            appendLine("## ${g.title}")
            g.lines.forEach { appendLine("${it.label}: ${it.value}") }
        }
        appendLine()
        appendLine("## Battery")
        appendLine("Level: ${battery.levelPct} %  Status: ${battery.status}  Source: ${battery.source}")
        appendLine("Charge counter: ${battery.chargeCounterMah?.let { "%.0f mAh".format(it) } ?: "n/a"}  Est. full: ${battery.estimatedFullMah?.let { "%.0f mAh".format(it) } ?: "n/a"}")
        appendLine("Voltage: ${battery.voltageMv ?: "n/a"} mV  Temperature: ${battery.tempC ?: "n/a"} °C  Health: ${battery.health}  Cycles: ${battery.cycleCount ?: "n/a"}")
        appendLine("Since boot: ${battery.sinceBootMs / 60_000} min  Asleep: ${"%.1f".format(battery.asleepFraction * 100)} %")
        appendLine()
        appendLine("## Access")
        appendLine("Tier: ${access.highestTier}  secure settings=${access.secureSettings} dump=${access.dump} logs=${access.readLogs} usage=${access.usageStats} shizuku=${access.shizukuRunning}/${access.shizukuGranted}")
        appendLine()
        appendLine("## Boox packages (${apps.count(AppFilter.Boox)}), Doze-exempt (${apps.count(AppFilter.DozeExempt)})")
        apps.filter(AppFilter.Boox).forEach { appendLine("${it.id}${if (!it.enabled) " [disabled]" else ""}${if (it.dozeExempt) " [doze-exempt]" else ""}") }
        appendLine()
        appendLine("## Background-restricted user apps (${apps.count(AppFilter.Restricted)})")
        apps.filter(AppFilter.Restricted).forEach { appendLine(it.id) }
        }
    }
}
