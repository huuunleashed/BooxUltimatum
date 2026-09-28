package app.booxultimatum.core.suite

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.content.edit
import app.booxultimatum.R
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.Journal
import app.booxultimatum.core.Launchers
import app.booxultimatum.core.ink.InkPrefs
import app.booxultimatum.core.ink.InkTileService
import app.booxultimatum.core.ink.InstantInk
import app.booxultimatum.core.sleep.LivePrefs
import app.booxultimatum.core.sleep.LiveSleep
import app.booxultimatum.core.sleep.SleepStore
import app.booxultimatum.core.sleep.SleepStudio
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The parts of BooxUltimatum that run on their own and can be taken out. Removing one stops its work and undoes what
 * it changed, and its pages leave the navigation until it's added back. They live inside this APK; separate apps such
 * as Nib are installed and uninstalled instead (see `SuiteApp`).
 */
enum class Module(@StringRes val title: Int, @StringRes val summary: Int, val booxOnly: Boolean) {
    Home(R.string.module_home, R.string.module_home_summary, booxOnly = false),
    Sleep(R.string.module_sleep, R.string.module_sleep_summary, booxOnly = true),
    Ink(R.string.module_ink, R.string.module_ink_summary, booxOnly = true),
    BatteryLog(R.string.module_battery_log, R.string.module_battery_log_summary, booxOnly = false),
}

/** Why a module couldn't be removed, in words for the owner. */
class ModuleBlocked(@StringRes val reason: Int) : Exception()

object Modules {
    private const val PREFS = "modules"
    const val HOME_ALIAS = "app.booxultimatum.launcher.Home"
    private val log = Logbook.logger("suite")

    fun available(context: Context): List<Module> {
        val boox = Tablet.current(context).isBoox
        return Module.entries.filter { boox || !it.booxOnly }
    }

    /** Whether the module is part of the app right now. Every module starts added. */
    fun added(context: Context, module: Module): Boolean =
        module in available(context) && prefs(context).getBoolean(module.name, true)

    /** Whether the module is doing something at this moment, for the lamp next to it. */
    fun running(context: Context, module: Module): Boolean = when (module) {
        Module.Home -> homeAliasEnabled(context)
        Module.Sleep -> SleepStore.load(context).active || LivePrefs.load(context).enabled
        Module.Ink -> InkPrefs.load(context).enabled
        Module.BatteryLog -> BatteryLog.enabled(context)
    }

    fun add(context: Context, module: Module) {
        prefs(context).edit { putBoolean(module.name, true) }
        when (module) {
            Module.Ink -> setComponent(context, InkTileService::class.java, true)
            Module.BatteryLog -> BatteryLog.setEnabled(context, true)
            Module.Home, Module.Sleep -> Unit
        }
        log.i("module added", "module" to module.name)
        Journal.log(context, "suite", context.getString(R.string.module_journal_added, context.getString(module.title)), "", true)
    }

    /** Stops the module and undoes what it changed, then takes it out of the app. */
    suspend fun remove(context: Context, module: Module): Result<Unit> = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        val r = runCatching {
            when (module) {
                Module.Home -> {
                    if (Launchers.list(app).any { it.isDefault && it.component.packageName == app.packageName }) {
                        // Never leave the tablet without a home: Boox's home comes back first, or nothing changes.
                        if (!Launchers.restore(app).ok) throw ModuleBlocked(R.string.module_home_blocked)
                    }
                    setAlias(app, false)
                }
                Module.Sleep -> {
                    LivePrefs.save(app, LivePrefs.load(app).copy(enabled = false))
                    LiveSleep.cancel(app)
                    if (LiveSleep.accessibilityOn(app)) LiveSleep.disableAccessibility(app)
                    if (SleepStudio.changed(app) || SleepStore.load(app).active) SleepStudio.restore(app).getOrThrow()
                    if (SleepStudio.powerOffSet(app)) SleepStudio.restorePowerOff(app).getOrThrow()
                }
                Module.Ink -> {
                    InstantInk.apply(app, InkPrefs.load(app).copy(enabled = false))
                    InstantInk.recoverScreen(app)
                    setComponent(app, InkTileService::class.java, false)
                }
                Module.BatteryLog -> BatteryLog.setEnabled(app, false)
            }
            prefs(app).edit { putBoolean(module.name, false) }
        }
        r.onSuccess { log.i("module removed", "module" to module.name) }
            .onFailure { log.w("module not removed", "module" to module.name, error = it) }
        Journal.log(app, "suite", app.getString(R.string.module_journal_removed, app.getString(module.title)), r.exceptionOrNull()?.message.orEmpty(), r.isSuccess)
        r
    }

    private fun homeAliasEnabled(context: Context) =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, HOME_ALIAS)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setAlias(context: Context, on: Boolean) = context.packageManager.setComponentEnabledSetting(
        ComponentName(context, HOME_ALIAS),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )

    private fun setComponent(context: Context, cls: Class<*>, on: Boolean) = context.packageManager.setComponentEnabledSetting(
        ComponentName(context, cls),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
