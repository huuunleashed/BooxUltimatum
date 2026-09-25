package app.booxultimatum.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.exec.ShellResult
import org.json.JSONObject

data class LauncherInfo(val component: ComponentName, val label: String, val isBoox: Boolean, val isDefault: Boolean)

data class BooxShortcut(val pkg: String, val label: String)

/**
 * Safe launcher switching. The Boox home (com.onyx/.StartupActivity, verified on NA6C FW 4.3) is remembered
 * before the first switch so it can always be restored in one tap. Recents live in a separate package
 * (com.android.onyxquickstep), so they are not tied to the home app.
 */
object Launchers {
    private const val JOURNAL_ID = "launcher.default"
    private val COMPONENT = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")
    private val HIDDEN = setOf("com.android.settings")

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun current(context: Context): ComponentName? {
        val ri = context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val ai = ri.activityInfo ?: return null
        // The chooser ("ResolverActivity") means no default is set.
        return if (ai.packageName == "android") null else ComponentName(ai.packageName, ai.name)
    }

    fun list(context: Context): List<LauncherInfo> {
        val pm = context.packageManager
        val def = current(context)
        return pm.queryIntentActivities(homeIntent(), PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName !in HIDDEN }
            .map {
                val c = ComponentName(it.packageName, it.name)
                LauncherInfo(c, it.loadLabel(pm).toString(), isBoox(it.packageName), c == def)
            }
            .sortedWith(compareByDescending<LauncherInfo> { it.isBoox }.thenBy { it.label.lowercase() })
    }

    private fun isBoox(pkg: String) = pkg == "com.onyx" || pkg.startsWith("com.onyx.")

    suspend fun setDefault(context: Context, target: ComponentName): ShellResult {
        val flat = target.flattenToShortString()
        require(COMPONENT.matches(flat)) { "Bad component: $flat" }
        current(context)?.let { Journal.rememberOriginal(context, JOURNAL_ID, JSONObject().put("component", it.flattenToShortString())) }
        val r = Privileged.sh("cmd package set-home-activity --user 0 $flat")
        Journal.log(context, "launcher", target.packageName, if (r.ok) "" else r.message, r.ok)
        return r
    }

    /** Back to the launcher that was default before BooxUltimatum first changed it (normally the Boox home). */
    suspend fun restore(context: Context): ShellResult {
        val saved = Journal.original(context, JOURNAL_ID)?.optString("component")?.let { ComponentName.unflattenFromString(it) }
        val target = saved ?: list(context).firstOrNull { it.isBoox }?.component ?: return ShellResult(-1, "", "No Boox launcher found")
        val r = setDefault(context, target)
        if (r.ok) Journal.forget(context, JOURNAL_ID)
        return r
    }

    fun switched(context: Context) = Journal.original(context, JOURNAL_ID) != null

    /** Every launchable Onyx app, so Boox functions stay one tap away under any launcher. */
    fun booxShortcuts(context: Context): List<BooxShortcut> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo }
            .filter { isBoox(it.packageName) }
            .distinctBy { it.packageName }
            .map { BooxShortcut(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }
}
