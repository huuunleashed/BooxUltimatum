package app.booxultimatum.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

enum class Risk { Safe, Caution, Expert, Never, Unknown }

data class KbEntry(val purpose: String, val risk: Risk)

data class PackageEntry(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val system: Boolean,
    val dozeExempt: Boolean,
    val kb: KbEntry?,
    /** True when Android is told not to run this app in the background (null = not checked or unknown). */
    val bgRestricted: Boolean? = null,
) {
    val isBoox get() = BOOX.containsMatchIn(id)

    private companion object {
        val BOOX = Regex("onyx|boox", RegexOption.IGNORE_CASE)
    }
}

enum class AppFilter { Boox, Restricted, DozeExempt, All }

data class AppInventory(val all: List<PackageEntry>) {
    fun filter(f: AppFilter): List<PackageEntry> = when (f) {
        AppFilter.Boox -> all.filter { it.isBoox }
        AppFilter.Restricted -> all.filter { it.bgRestricted == true }
        AppFilter.DozeExempt -> all.filter { it.dozeExempt }
        AppFilter.All -> all
    }

    fun count(f: AppFilter) = filter(f).size
}

object PackageScanner {
    fun loadKnowledge(context: Context): Map<String, KbEntry> = runCatching {
        val json = context.assets.open("onyx-packages.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("packages")
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            val risk = when (o.optString("risk")) {
                "safe" -> Risk.Safe
                "caution" -> Risk.Caution
                "expert" -> Risk.Expert
                "never" -> Risk.Never
                else -> Risk.Unknown
            }
            val purpose = o.optString("purpose").takeUnless { it.startsWith("unknown") }.orEmpty()
            o.getString("id") to KbEntry(purpose, risk)
        }
    }.getOrDefault(emptyMap())

    suspend fun scan(context: Context): AppInventory {
        val pm = context.packageManager
        val power = context.getSystemService(PowerManager::class.java)
        val kb = loadKnowledge(context)
        val shell = app.booxultimatum.core.exec.Privileged.ready()
        val restricted = if (shell) SystemState.restrictedInBackground() else null
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        }
        val entries = apps.map {
            val system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0
            PackageEntry(
                id = it.packageName,
                label = it.loadLabel(pm).toString(),
                enabled = it.enabled,
                system = system,
                dozeExempt = power.isIgnoringBatteryOptimizations(it.packageName),
                kb = kb[it.packageName],
                // Boox restricts apps you install, so only those are checked: one shell call each.
                bgRestricted = if (shell && !system) restricted?.contains(it.packageName) ?: (SystemState.backgroundMode(it.packageName) == BgMode.Ignore) else null,
            )
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.id }))
        return AppInventory(entries)
    }
}
