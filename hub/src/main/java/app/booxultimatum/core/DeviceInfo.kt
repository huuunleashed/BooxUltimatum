package app.booxultimatum.core

import android.content.Context
import android.os.Build
import app.booxultimatum.R
import java.io.File
import java.util.Locale

data class SpecLine(val label: String, val value: String)
data class SpecGroup(val title: String, val lines: List<SpecLine>)

data class DeviceIdentity(val manufacturer: String, val model: String, val firmwareVersion: String?, val androidRelease: String)

data class DeviceProfile(
    val identity: DeviceIdentity,
    val groups: List<SpecGroup>,
    val properties: List<Pair<String, String>>,
)

object DeviceReader {
    private val FW_VERSION = Regex("""_(\d+\.\d+)-""")

    fun identity(props: Map<String, String> = Shell.getprops()): DeviceIdentity {
        val display = props["ro.build.display.id"]
        return DeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            firmwareVersion = display?.let { FW_VERSION.find(it)?.groupValues?.get(1) },
            androidRelease = Build.VERSION.RELEASE,
        )
    }

    fun read(context: Context): DeviceProfile {
        val props = Shell.getprops()
        fun s(id: Int) = context.getString(id)
        fun p(key: String) = props[key]?.takeIf { it.isNotBlank() }
        fun MutableList<SpecLine>.put(label: Int, value: String?) { if (!value.isNullOrBlank()) add(SpecLine(s(label), value)) }

        val identity = mutableListOf<SpecLine>().apply {
            put(R.string.spec_model, "${Build.MANUFACTURER} ${Build.MODEL}")
            put(R.string.spec_firmware, p("ro.build.display.id"))
            put(R.string.spec_android, context.getString(R.string.value_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
            put(R.string.spec_security_patch, Build.VERSION.SECURITY_PATCH)
        }
        val silicon = mutableListOf<SpecLine>().apply {
            if (Build.VERSION.SDK_INT >= 31) put(R.string.spec_soc, "${Build.SOC_MODEL} · ${Build.SOC_MANUFACTURER}")
            put(R.string.spec_platform, p("ro.board.platform"))
            val cores = Runtime.getRuntime().availableProcessors()
            put(R.string.spec_cpu, cpuClusters() ?: context.resources.getQuantityString(R.plurals.value_cores, cores, cores))
            put(R.string.spec_governor, readFile("/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"))
            put(R.string.spec_kernel, System.getProperty("os.version"))
        }
        val boot = mutableListOf<SpecLine>().apply {
            put(R.string.spec_bootloader, when (p("ro.boot.flash.locked")) {
                "1" -> s(R.string.value_locked)
                "0" -> s(R.string.value_unlocked)
                else -> null
            })
            put(R.string.spec_verified_boot, p("ro.boot.verifiedbootstate"))
            put(R.string.spec_slot, p("ro.boot.slot_suffix"))
            put(R.string.spec_build_type, p("ro.build.type"))
        }
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration
        val display = mutableListOf<SpecLine>().apply {
            put(R.string.spec_resolution, context.getString(R.string.value_resolution, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi))
            put(R.string.spec_window, context.getString(R.string.value_window, config.screenWidthDp, config.screenHeightDp))
            put(R.string.spec_font_scale, String.format(Locale.getDefault(), "%.2f×", config.fontScale))
        }
        return DeviceProfile(
            identity = identity(props),
            groups = listOf(
                SpecGroup(s(R.string.group_identity), identity),
                SpecGroup(s(R.string.group_silicon), silicon),
                SpecGroup(s(R.string.group_boot), boot),
                SpecGroup(s(R.string.group_display), display),
            ).filter { it.lines.isNotEmpty() },
            properties = props.toSortedMap().toList(),
        )
    }

    private fun readFile(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun cpuClusters(): String? {
        val policies = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 } ?: return null
        val parts = policies.mapNotNull { p ->
            val cpus = readFile("${p.path}/related_cpus")?.split(" ")?.count { it.isNotBlank() } ?: return@mapNotNull null
            val khz = readFile("${p.path}/cpuinfo_max_freq")?.toLongOrNull() ?: return@mapNotNull null
            String.format(Locale.getDefault(), "%d × %.2f GHz", cpus, khz / 1_000_000.0)
        }
        return parts.joinToString("  ·  ").ifEmpty { null }
    }
}
