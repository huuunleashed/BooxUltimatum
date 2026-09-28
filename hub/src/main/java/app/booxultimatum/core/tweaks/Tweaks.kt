package app.booxultimatum.core.tweaks

import android.content.ContentResolver
import android.content.Context
import android.location.LocationManager
import android.os.PowerManager
import androidx.annotation.StringRes
import app.booxultimatum.R
import app.booxultimatum.core.BgMode
import app.booxultimatum.core.Journal
import app.booxultimatum.core.Namespace
import app.booxultimatum.core.PrivilegeStatus
import app.booxultimatum.core.SystemSettings
import app.booxultimatum.core.SystemState
import app.booxultimatum.core.Tier
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.exec.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class TweakGroup(@StringRes val title: Int, @StringRes val explain: Int) {
    Apps(R.string.tgroup_apps, R.string.tgroup_apps_explain),
    Sleep(R.string.tgroup_sleep, R.string.tgroup_sleep_explain),
    Power(R.string.tgroup_power, R.string.tgroup_power_explain),
    Radios(R.string.tgroup_radios, R.string.tgroup_radios_explain),
    Interface(R.string.tgroup_interface, R.string.tgroup_interface_explain),
    Privacy(R.string.tgroup_privacy, R.string.tgroup_privacy_explain),
}

enum class TweakRisk(@StringRes val label: Int) { Safe(R.string.trisk_safe), Caution(R.string.trisk_caution) }

enum class TweakState { On, Off, Unknown, NotApplicable }

/** A reversible change. Implementations never throw; failures come back as [Result.failure] with a readable message. */
abstract class Tweak(
    val id: String,
    val group: TweakGroup,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val tier: Tier,
    val risk: TweakRisk,
    @StringRes val note: Int? = null,
) {
    abstract suspend fun state(context: Context): TweakState
    protected abstract suspend fun doApply(context: Context): Result<Unit>
    protected abstract suspend fun doRevert(context: Context): Result<Unit>

    /**
     * True when undo restores a value recorded at apply time. Such tweaks only offer Undo when BooxUltimatum made
     * the change; if the tablet already had the value, undoing would invent a state the owner never had.
     */
    open val undoNeedsRecord: Boolean = false

    fun hasRecord(context: Context) = Journal.original(context, id) != null

    fun available(status: PrivilegeStatus, shizukuReady: Boolean) = when (tier) {
        Tier.T0 -> true
        Tier.T1 -> shizukuReady || status.secureSettings
        Tier.T2 -> shizukuReady
        Tier.T3 -> false
    }

    suspend fun apply(context: Context) = guarded { doApply(context) }.also { log(context, "apply", it) }
    suspend fun revert(context: Context) = guarded { doRevert(context) }.also { log(context, "undo", it) }

    private inline fun guarded(block: () -> Result<Unit>): Result<Unit> = runCatching { block() }.getOrElse { Result.failure(it) }

    private fun log(context: Context, action: String, r: Result<Unit>) =
        Journal.log(context, action, context.getString(title), r.exceptionOrNull()?.message.orEmpty(), r.isSuccess)
}

private fun ShellResult.toResult(): Result<Unit> = if (ok) Result.success(Unit) else Result.failure(IllegalStateException(message))

private fun Boolean?.toState() = when (this) {
    true -> TweakState.On
    false -> TweakState.Off
    null -> TweakState.Unknown
}

/** One or more Android settings set to fixed values; the originals are journaled and restored on undo. */
class SettingTweak(
    id: String, group: TweakGroup, title: Int, summary: Int, risk: TweakRisk,
    private val entries: List<Triple<Namespace, String, String>>,
    private val appliedWhenMissing: Boolean = false,
    tier: Tier = Tier.T1,
    note: Int? = null,
) : Tweak(id, group, title, summary, tier, risk, note) {
    override val undoNeedsRecord = true

    override suspend fun state(context: Context): TweakState {
        val values = entries.map { (ns, key, v) -> SystemSettings.get(context, ns, key) to v }
        return values.all { (cur, v) -> same(cur, v) || (cur == null && appliedWhenMissing) }.toState()
    }

    /** "0.0" and "0" are the same setting value; firmware writes scales as floats. */
    private fun same(cur: String?, v: String): Boolean {
        if (cur == v) return true
        val a = cur?.toDoubleOrNull() ?: return false
        val b = v.toDoubleOrNull() ?: return false
        return a == b
    }

    override suspend fun doApply(context: Context): Result<Unit> {
        val original = JSONObject()
        entries.forEach { (ns, key, _) -> original.put("${ns.cli}:$key", SystemSettings.get(context, ns, key) ?: JSONObject.NULL) }
        Journal.rememberOriginal(context, id, original)
        entries.forEach { (ns, key, v) -> SystemSettings.put(context, ns, key, v).onFailure { return Result.failure(it) } }
        return Result.success(Unit)
    }

    override suspend fun doRevert(context: Context): Result<Unit> {
        val original = Journal.original(context, id)
        entries.forEach { (ns, key, _) ->
            val k = "${ns.cli}:$key"
            val prev = if (original != null && original.has(k) && !original.isNull(k)) original.getString(k) else null
            SystemSettings.put(context, ns, key, prev).onFailure { return Result.failure(it) }
        }
        Journal.forget(context, id)
        return Result.success(Unit)
    }
}

/** Disables packages for user 0 (reversible, nothing is uninstalled). Missing packages are skipped. */
class DisablePackagesTweak(
    id: String, group: TweakGroup, title: Int, summary: Int, risk: TweakRisk,
    private val packages: List<String>,
    note: Int? = null,
) : Tweak(id, group, title, summary, Tier.T2, risk, note) {
    override val undoNeedsRecord = true

    private fun present(c: Context) = packages.filter { SystemState.isEnabled(c, it) != null }

    override suspend fun state(context: Context): TweakState {
        val p = present(context)
        if (p.isEmpty()) return TweakState.NotApplicable
        return p.all { SystemState.isEnabled(context, it) == false }.toState()
    }

    override suspend fun doApply(context: Context): Result<Unit> {
        val p = present(context)
        Journal.rememberOriginal(context, id, JSONObject().apply { p.forEach { put(it, SystemState.isEnabled(context, it) ?: true) } })
        p.forEach { SystemState.setEnabled(it, false).toResult().onFailure { e -> return Result.failure(e) } }
        return Result.success(Unit)
    }

    override suspend fun doRevert(context: Context): Result<Unit> {
        val original = Journal.original(context, id)
        present(context).forEach { pkg ->
            val wasEnabled = original?.optBoolean(pkg, true) ?: true
            if (wasEnabled) SystemState.setEnabled(pkg, true).toResult().onFailure { return Result.failure(it) }
        }
        Journal.forget(context, id)
        return Result.success(Unit)
    }
}

/** A tweak described by three small functions. */
class CommandTweak(
    id: String, group: TweakGroup, title: Int, summary: Int, tier: Tier, risk: TweakRisk,
    note: Int? = null,
    private val read: suspend (Context) -> TweakState,
    private val on: suspend (Context) -> Result<Unit>,
    private val off: suspend (Context) -> Result<Unit>,
) : Tweak(id, group, title, summary, tier, risk, note) {
    override val undoNeedsRecord = true
    override suspend fun state(context: Context) = read(context)
    override suspend fun doApply(context: Context): Result<Unit> {
        // Record first: some commands (the font overlay) restart this process before they return.
        Journal.rememberOriginal(context, id, JSONObject().put("applied", true))
        return on(context).onFailure { Journal.forget(context, id) }
    }
    override suspend fun doRevert(context: Context) = off(context).onSuccess { Journal.forget(context, id) }
}

object Catalog {
    /** Onyx apps that must stay reachable in deep sleep: keyboards, launcher, NaviBall, and alarm or playback apps. */
    private val BOOX_KEEP_AWAKE = setOf(
        "com.onyx", "com.onyx.kime", "com.onyx.latinime", "com.onyx.floatingbutton", "com.android.onyxquickstep",
        "com.onyx.clock", "com.onyx.musicplayer", "com.onyx.voicerecorder",
    )

    /** Onyx apps that have no reason to run in the background (store, shop, transfer, factory test, extras). */
    private val BOOX_BACKGROUND_OPTIONAL = listOf(
        "com.onyx.appmarket", "com.onyx.igetshop", "com.onyx.easytransfer", "com.onyx.android.production.test",
        "com.onyx.aiassistant", "com.onyx.calculator", "com.onyx.gallery", "com.onyx.dict", "com.onyx.tscalibration",
    )

    val all: List<Tweak> = listOf(
        // Apps
        object : Tweak("apps.background", TweakGroup.Apps, R.string.tw_user_bg_title, R.string.tw_user_bg_summary, Tier.T2, TweakRisk.Safe, R.string.tw_note_user_bg) {
            private fun userApps(c: Context): List<String> {
                val pm = c.packageManager
                return pm.getInstalledApplications(0)
                    .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != c.packageName }
                    .map { it.packageName }
            }

            override suspend fun state(context: Context): TweakState {
                if (!Privileged.ready()) return TweakState.Unknown
                val restricted = userApps(context).filter { SystemState.backgroundMode(it) == BgMode.Ignore }
                return when {
                    restricted.isNotEmpty() -> TweakState.Off
                    Journal.original(context, id) != null -> TweakState.On
                    else -> TweakState.NotApplicable
                }
            }

            override suspend fun doApply(context: Context): Result<Unit> {
                val restricted = userApps(context).filter { SystemState.backgroundMode(it) == BgMode.Ignore }
                val prior = Journal.original(context, id)?.optJSONArray("released") ?: org.json.JSONArray()
                val known = (0 until prior.length()).map { prior.getString(it) }.toMutableSet()
                restricted.forEach { pkg -> SystemState.setBackgroundMode(pkg, BgMode.Allow).toResult().onFailure { return Result.failure(it) }; known += pkg }
                Journal.forget(context, id)
                Journal.rememberOriginal(context, id, JSONObject().put("released", org.json.JSONArray(known.toList())))
                return Result.success(Unit)
            }

            override suspend fun doRevert(context: Context): Result<Unit> {
                val released = Journal.original(context, id)?.optJSONArray("released") ?: org.json.JSONArray()
                for (i in 0 until released.length()) {
                    val pkg = released.getString(i)
                    if (SystemState.isEnabled(context, pkg) != null) SystemState.setBackgroundMode(pkg, BgMode.Ignore).toResult().onFailure { return Result.failure(it) }
                }
                Journal.forget(context, id)
                return Result.success(Unit)
            }
        },

        // Sleep
        CommandTweak(
            "doze.enable", TweakGroup.Sleep, R.string.tw_doze_title, R.string.tw_doze_summary, Tier.T2, TweakRisk.Caution,
            note = R.string.tw_note_resets,
            read = { SystemState.doze()?.let { d -> (d.deepEnabled && d.lightEnabled).toState() } ?: TweakState.Unknown },
            on = { SystemState.setDozeEnabled(true).toResult() },
            off = { SystemState.setDozeEnabled(false).toResult() },
        ),
        // "Enter deep sleep sooner" (device_config device_idle timings) was removed: Android 16 refuses shell writes
        // to non-allowlisted DeviceConfig flags (verified on NA6C FW 4.3, SecurityException). Kept out rather than broken.
        object : Tweak("doze.boox_allowlist", TweakGroup.Sleep, R.string.tw_allowlist_title, R.string.tw_allowlist_summary, Tier.T2, TweakRisk.Caution, R.string.tw_note_allowlist) {
            private suspend fun targets(): List<String>? = SystemState.dozeAllowlist()?.first
                ?.filter { (it.startsWith("com.onyx") || it.contains("onyx")) && it !in BOOX_KEEP_AWAKE }

            override suspend fun state(context: Context): TweakState {
                val t = targets() ?: return TweakState.Unknown
                return if (t.isEmpty()) {
                    if (Journal.original(context, id) != null) TweakState.On else TweakState.NotApplicable
                } else TweakState.Off
            }

            override suspend fun doApply(context: Context): Result<Unit> {
                val t = targets() ?: return Result.failure(IllegalStateException(context.getString(R.string.err_no_diagnostics)))
                val removed = org.json.JSONArray()
                t.forEach { pkg -> SystemState.setDozeAllowed(pkg, false).toResult().onFailure { return Result.failure(it) }; removed.put(pkg) }
                Journal.rememberOriginal(context, id, JSONObject().put("removed", removed))
                return Result.success(Unit)
            }

            override suspend fun doRevert(context: Context): Result<Unit> {
                val removed = Journal.original(context, id)?.optJSONArray("removed") ?: org.json.JSONArray()
                for (i in 0 until removed.length()) SystemState.setDozeAllowed(removed.getString(i), true).toResult().onFailure { return Result.failure(it) }
                Journal.forget(context, id)
                return Result.success(Unit)
            }
        },
        object : Tweak("bg.boox_restrict", TweakGroup.Sleep, R.string.tw_background_title, R.string.tw_background_summary, Tier.T2, TweakRisk.Safe) {
            override val undoNeedsRecord = true
            private fun present(c: Context) = BOOX_BACKGROUND_OPTIONAL.filter { SystemState.isEnabled(c, it) != null }

            override suspend fun state(context: Context): TweakState {
                if (!Privileged.ready()) return TweakState.Unknown
                val p = present(context)
                if (p.isEmpty()) return TweakState.NotApplicable
                return p.all { SystemState.backgroundMode(it) == BgMode.Ignore }.toState()
            }

            override suspend fun doApply(context: Context): Result<Unit> {
                val p = present(context)
                Journal.rememberOriginal(context, id, JSONObject().apply { p.forEach { put(it, SystemState.backgroundMode(it).name) } })
                p.forEach { SystemState.setBackgroundMode(it, BgMode.Ignore).toResult().onFailure { e -> return Result.failure(e) } }
                return Result.success(Unit)
            }

            override suspend fun doRevert(context: Context): Result<Unit> {
                val original = Journal.original(context, id)
                present(context).forEach { pkg ->
                    val prev = original?.optString(pkg)?.let { runCatching { BgMode.valueOf(it) }.getOrNull() } ?: BgMode.Default
                    SystemState.setBackgroundMode(pkg, if (prev == BgMode.Unknown) BgMode.Default else prev).toResult().onFailure { return Result.failure(it) }
                }
                Journal.forget(context, id)
                return Result.success(Unit)
            }
        },

        // Power
        CommandTweak(
            "power.saver", TweakGroup.Power, R.string.tw_saver_title, R.string.tw_saver_summary, Tier.T2, TweakRisk.Safe,
            note = R.string.tw_note_saver,
            read = { c -> c.getSystemService(PowerManager::class.java).isPowerSaveMode.toState() },
            on = { Privileged.sh("cmd power set-mode 1").toResult() },
            off = { Privileged.sh("cmd power set-mode 0").toResult() },
        ),
        SettingTweak(
            "power.adaptive", TweakGroup.Power, R.string.tw_adaptive_title, R.string.tw_adaptive_summary, TweakRisk.Safe,
            listOf(Triple(Namespace.Global, "app_standby_enabled", "1"), Triple(Namespace.Global, "adaptive_battery_management_enabled", "1")),
            appliedWhenMissing = true,
        ),
        CommandTweak(
            "power.autosync", TweakGroup.Power, R.string.tw_sync_title, R.string.tw_sync_summary, Tier.T0, TweakRisk.Caution,
            read = { (!ContentResolver.getMasterSyncAutomatically()).toState() },
            on = { withContext(Dispatchers.IO) { runCatching { ContentResolver.setMasterSyncAutomatically(false) } } },
            off = { withContext(Dispatchers.IO) { runCatching { ContentResolver.setMasterSyncAutomatically(true) } } },
        ),
        SettingTweak(
            "power.timeout", TweakGroup.Power, R.string.tw_timeout_title, R.string.tw_timeout_summary, TweakRisk.Safe,
            listOf(Triple(Namespace.System, "screen_off_timeout", "120000")),
            tier = Tier.T2,
        ),
        SettingTweak(
            "power.stay_awake", TweakGroup.Power, R.string.tw_stay_awake_title, R.string.tw_stay_awake_summary, TweakRisk.Safe,
            listOf(Triple(Namespace.Global, "stay_on_while_plugged_in", "0")),
            appliedWhenMissing = true,
        ),

        // Radios
        SettingTweak(
            "radio.wifi_scan", TweakGroup.Radios, R.string.tw_wifi_scan_title, R.string.tw_wifi_scan_summary, TweakRisk.Safe,
            listOf(Triple(Namespace.Global, "wifi_scan_always_enabled", "0")),
        ),
        SettingTweak(
            "radio.ble_scan", TweakGroup.Radios, R.string.tw_ble_scan_title, R.string.tw_ble_scan_summary, TweakRisk.Safe,
            listOf(Triple(Namespace.Global, "ble_scan_always_enabled", "0")),
        ),
        SettingTweak(
            "radio.wifi_wakeup", TweakGroup.Radios, R.string.tw_wifi_wakeup_title, R.string.tw_wifi_wakeup_summary, TweakRisk.Safe,
            listOf(Triple(Namespace.Global, "wifi_wakeup_enabled", "0")),
        ),
        CommandTweak(
            "radio.location", TweakGroup.Radios, R.string.tw_location_title, R.string.tw_location_summary, Tier.T2, TweakRisk.Caution,
            read = { c -> (!c.getSystemService(LocationManager::class.java).isLocationEnabled).toState() },
            on = { Privileged.sh("cmd location set-location-enabled false").toResult() },
            off = { Privileged.sh("cmd location set-location-enabled true").toResult() },
        ),

        // Interface
        CommandTweak(
            "ui.serif_font", TweakGroup.Interface, R.string.tw_serif_title, R.string.tw_serif_summary, Tier.T2, TweakRisk.Safe,
            note = R.string.tw_note_serif,
            read = {
                val r = Privileged.sh("cmd overlay dump com.android.theme.font.notoserifsource")
                when {
                    !r.ok || r.out.isBlank() -> TweakState.NotApplicable
                    else -> (Regex("""mIsEnabled\.*:\s*(\w+)""").find(r.out)?.groupValues?.get(1) == "true").toState()
                }
            },
            on = { Privileged.sh("cmd overlay enable --user 0 com.android.theme.font.notoserifsource").toResult() },
            off = { Privileged.sh("cmd overlay disable --user 0 com.android.theme.font.notoserifsource").toResult() },
        ),
        SettingTweak(
            "ui.animations", TweakGroup.Interface, R.string.tw_animations_title, R.string.tw_animations_summary, TweakRisk.Safe,
            listOf(
                Triple(Namespace.Global, "window_animation_scale", "0"),
                Triple(Namespace.Global, "transition_animation_scale", "0"),
                Triple(Namespace.Global, "animator_duration_scale", "0"),
            ),
        ),

        // Privacy
        SettingTweak(
            "privacy.dns", TweakGroup.Privacy, R.string.tw_dns_title, R.string.tw_dns_summary, TweakRisk.Caution,
            listOf(Triple(Namespace.Global, "private_dns_mode", "hostname"), Triple(Namespace.Global, "private_dns_specifier", "dns.adguard-dns.com")),
            note = R.string.tw_note_dns,
        ),
        DisablePackagesTweak(
            "privacy.ota", TweakGroup.Privacy, R.string.tw_ota_title, R.string.tw_ota_summary, TweakRisk.Caution,
            listOf("com.onyx.android.onyxotaservice"),
            note = R.string.tw_note_ota,
        ),
        DisablePackagesTweak(
            "privacy.store", TweakGroup.Privacy, R.string.tw_store_title, R.string.tw_store_summary, TweakRisk.Safe,
            listOf("com.onyx.appmarket", "com.onyx.igetshop"),
        ),
        DisablePackagesTweak(
            "privacy.factory", TweakGroup.Privacy, R.string.tw_factory_title, R.string.tw_factory_summary, TweakRisk.Safe,
            listOf("com.onyx.android.production.test"),
        ),
    )

    fun byGroup() = all.groupBy { it.group }
}
