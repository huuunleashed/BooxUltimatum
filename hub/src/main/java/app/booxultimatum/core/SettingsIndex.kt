package app.booxultimatum.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import app.booxultimatum.R

enum class HubGroup(@StringRes val title: Int) {
    Power(R.string.hub_power), Connections(R.string.hub_connections), Display(R.string.hub_display),
    Apps(R.string.hub_apps), System(R.string.hub_system), Developer(R.string.hub_developer),
}

data class HubEntry(val group: HubGroup, @StringRes val title: Int, @StringRes val summary: Int, val intent: Intent)

/** A searchable index of system settings pages. Only pages that resolve on this device are shown. */
object SettingsIndex {
    private fun action(a: String) = Intent(a)

    private fun candidates(context: Context): List<HubEntry> {
        val pkg = Uri.parse("package:${context.packageName}")
        return listOf(
            HubEntry(HubGroup.Power, R.string.hs_battery_usage, R.string.hs_battery_usage_s, action(Intent.ACTION_POWER_USAGE_SUMMARY)),
            HubEntry(HubGroup.Power, R.string.hs_battery_saver, R.string.hs_battery_saver_s, action(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
            HubEntry(HubGroup.Power, R.string.hs_optimization, R.string.hs_optimization_s, action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            HubEntry(HubGroup.Power, R.string.hs_screensaver, R.string.hs_screensaver_s, action(Settings.ACTION_DREAM_SETTINGS)),
            HubEntry(HubGroup.Connections, R.string.hs_wifi, R.string.hs_wifi_s, action(Settings.ACTION_WIFI_SETTINGS)),
            HubEntry(HubGroup.Connections, R.string.hs_bluetooth, R.string.hs_bluetooth_s, action(Settings.ACTION_BLUETOOTH_SETTINGS)),
            HubEntry(HubGroup.Connections, R.string.hs_network, R.string.hs_network_s, action(Settings.ACTION_WIRELESS_SETTINGS)),
            HubEntry(HubGroup.Connections, R.string.hs_vpn, R.string.hs_vpn_s, action(Settings.ACTION_VPN_SETTINGS)),
            HubEntry(HubGroup.Connections, R.string.hs_cast, R.string.hs_cast_s, action(Settings.ACTION_CAST_SETTINGS)),
            HubEntry(HubGroup.Connections, R.string.hs_location, R.string.hs_location_s, action(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
            HubEntry(HubGroup.Display, R.string.hs_display, R.string.hs_display_s, action(Settings.ACTION_DISPLAY_SETTINGS)),
            HubEntry(HubGroup.Display, R.string.hs_sound, R.string.hs_sound_s, action(Settings.ACTION_SOUND_SETTINGS)),
            HubEntry(HubGroup.Display, R.string.hs_accessibility, R.string.hs_accessibility_s, action(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            HubEntry(HubGroup.Display, R.string.hs_keyboards, R.string.hs_keyboards_s, action(Settings.ACTION_INPUT_METHOD_SETTINGS)),
            HubEntry(HubGroup.Display, R.string.hs_hard_keyboard, R.string.hs_hard_keyboard_s, action(Settings.ACTION_HARD_KEYBOARD_SETTINGS)),
            HubEntry(HubGroup.Display, R.string.hs_language, R.string.hs_language_s, action(Settings.ACTION_LOCALE_SETTINGS)),
            HubEntry(HubGroup.Display, R.string.hs_captions, R.string.hs_captions_s, action(Settings.ACTION_CAPTIONING_SETTINGS)),
            HubEntry(HubGroup.Apps, R.string.hs_all_apps, R.string.hs_all_apps_s, action(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)),
            HubEntry(HubGroup.Apps, R.string.hs_default_apps, R.string.hs_default_apps_s, action(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
            HubEntry(HubGroup.Apps, R.string.hs_notifications, R.string.hs_notifications_s, action("android.settings.ALL_APPS_NOTIFICATION_SETTINGS")),
            HubEntry(HubGroup.Apps, R.string.hs_usage_access, R.string.hs_usage_access_s, action(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            HubEntry(HubGroup.Apps, R.string.hs_overlay, R.string.hs_overlay_s, action(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)),
            HubEntry(HubGroup.Apps, R.string.hs_files, R.string.hs_files_s, action(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)),
            HubEntry(HubGroup.Apps, R.string.hs_unknown_sources, R.string.hs_unknown_sources_s, action(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)),
            HubEntry(HubGroup.Apps, R.string.hs_this_app, R.string.hs_this_app_s, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)),
            // Boox's App Freeze page: on FW 4.3 it's hidden from Boox Settings' menus but still opens by its action.
            HubEntry(HubGroup.Apps, R.string.hs_boox_freeze, R.string.hs_boox_freeze_s, action("onyx.settings.action.APP_FREEZE_MANAGEMENT")),
            HubEntry(HubGroup.System, R.string.hs_stock_settings, R.string.hs_stock_settings_s, Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings"))),
            HubEntry(HubGroup.System, R.string.hs_storage, R.string.hs_storage_s, action(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
            HubEntry(HubGroup.System, R.string.hs_date, R.string.hs_date_s, action(Settings.ACTION_DATE_SETTINGS)),
            HubEntry(HubGroup.System, R.string.hs_accounts, R.string.hs_accounts_s, action(Settings.ACTION_SYNC_SETTINGS)),
            HubEntry(HubGroup.System, R.string.hs_security, R.string.hs_security_s, action(Settings.ACTION_SECURITY_SETTINGS)),
            HubEntry(HubGroup.System, R.string.hs_privacy, R.string.hs_privacy_s, action(Settings.ACTION_PRIVACY_SETTINGS)),
            HubEntry(HubGroup.System, R.string.hs_dnd, R.string.hs_dnd_s, action("android.settings.ZEN_MODE_SETTINGS")),
            HubEntry(HubGroup.System, R.string.hs_print, R.string.hs_print_s, action(Settings.ACTION_PRINT_SETTINGS)),
            HubEntry(HubGroup.System, R.string.hs_about, R.string.hs_about_s, action(Settings.ACTION_DEVICE_INFO_SETTINGS)),
            HubEntry(HubGroup.Developer, R.string.hs_developer, R.string.hs_developer_s, action(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
        )
    }

    fun available(context: Context): List<HubEntry> {
        val pm = context.packageManager
        return candidates(context).filter { e -> runCatching { pm.resolveActivity(e.intent, 0) != null }.getOrDefault(false) }
    }
}
