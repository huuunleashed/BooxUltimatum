package app.booxultimatum.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONObject

data class PackageEntry(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val system: Boolean,
    val kb: KbEntry?,
)

data class KbEntry(val purpose: String, val risk: String)

object PackageScanner {
    private val VENDOR = Regex("onyx|boox|qualcomm|qti|simplemobiletools|chromium", RegexOption.IGNORE_CASE)

    fun loadKnowledge(context: Context): Map<String, KbEntry> = runCatching {
        val json = context.assets.open("onyx-packages.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("packages")
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            o.getString("id") to KbEntry(o.optString("purpose"), o.optString("risk", "unknown"))
        }
    }.getOrDefault(emptyMap())

    fun scan(context: Context, vendorOnly: Boolean): List<PackageEntry> {
        val pm = context.packageManager
        val kb = loadKnowledge(context)
        val apps = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        }
        return apps.asSequence()
            .filter { !vendorOnly || VENDOR.containsMatchIn(it.packageName) }
            .map {
                PackageEntry(
                    id = it.packageName,
                    label = it.loadLabel(pm).toString(),
                    enabled = it.enabled,
                    system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    kb = kb[it.packageName],
                )
            }
            .sortedBy { it.id }
            .toList()
    }
}
