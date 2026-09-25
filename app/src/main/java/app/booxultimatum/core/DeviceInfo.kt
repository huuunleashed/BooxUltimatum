package app.booxultimatum.core

import android.os.Build

data class DeviceInfo(val rows: List<Pair<String, String>>) {
    val isBoox: Boolean = rows.any { (k, v) -> k == "Manufacturer" && v.contains("onyx", ignoreCase = true) }

    companion object {
        private val PROP_KEYS = listOf(
            "ro.product.model", "ro.product.device", "ro.product.board", "ro.board.platform", "ro.hardware",
            "ro.soc.manufacturer", "ro.soc.model", "ro.build.display.id", "ro.build.version.incremental",
            "ro.build.version.security_patch", "ro.boot.verifiedbootstate", "ro.boot.flash.locked",
            "ro.boot.vbmeta.device_state", "ro.oem_unlock_supported", "ro.boot.slot_suffix",
            "ro.boot.dynamic_partitions", "ro.virtual_ab.enabled", "ro.product.first_api_level",
        )

        fun collect(): DeviceInfo {
            val props = Shell.getprops()
            val rows = mutableListOf(
                "Manufacturer" to Build.MANUFACTURER,
                "Model" to Build.MODEL,
                "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                "Fingerprint" to Build.FINGERPRINT,
                "Kernel" to (System.getProperty("os.version") ?: "?"),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rows += "SoC" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
            }
            PROP_KEYS.forEach { key -> props[key]?.takeIf { it.isNotEmpty() }?.let { rows += key to it } }
            Shell.readFile("/sys/devices/soc0/machine")?.let { rows += "soc0/machine" to it }
            Shell.readFile("/sys/devices/soc0/soc_id")?.let { rows += "soc0/soc_id" to it }
            val cpuMax = (0..7).mapNotNull { Shell.readFile("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
            if (cpuMax.isNotEmpty()) rows += "CPU max kHz (cpu0..7)" to cpuMax.joinToString(" ")
            rows += "/onyxconfig readable" to (java.io.File("/onyxconfig").list()?.joinToString() ?: "no")
            return DeviceInfo(rows)
        }
    }
}
