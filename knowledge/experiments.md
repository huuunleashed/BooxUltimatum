# Experiments log

Evidence behind every device fact and tweak in BooxUltimatum. Battery A/B runs follow the protocol in `docs/02-reverse-engineering-plan.md`; each run links its raw capture folder in `captures/` (git-ignored). Device: BOOX Note Air6 C, firmware `2026-08-17_12-29_4.3-rel_0817_fd4f8e9fe`, Android 16, bootloader locked.

## Battery A/B runs

| Date | Firmware | Test | Change | Baseline %/h | After %/h | Δ | Capture | Verdict |
|---|---|---|---|---|---|---|---|---|
| (none yet) | | | | | | | | |

The in-app battery log (Battery › Battery log) now records light samples every 30 minutes of awake time plus a deep snapshot every 3 hours, so standby drain can be read from ordinary use. The first overnight unplugged run is still to do.

## Device findings

| Date | Finding | How it was verified | Consequence in the app |
|---|---|---|---|
| 2026-09-25 | Doze is off in firmware (`mDeepEnabled=false`, `mLightEnabled=false`). | `dumpsys deviceidle` over adb. | *Turn on Doze* tweak; Battery shows the state. |
| 2026-09-25 | All Onyx packages sit on the Doze allowlist. | `dumpsys deviceidle whitelist`. | *Let Boox apps sleep* tweak, keeping keyboards, launcher, NaviBall, clock and players exempt. |
| 2026-09-25 | Boox sets `RUN_ANY_IN_BACKGROUND=ignore` on apps the user installs (Apple Music, Discord, YouTube). That is why music stops about 1.5 minutes after leaving the player and Discord screen sharing goes blank. | `cmd appops get <pkg> RUN_ANY_IN_BACKGROUND` on each app, matching the symptoms reported. | *Let your apps keep running* tweak, Apps › Restricted filter and a per-app *Keep running in background* key. |
| 2026-09-25 | Android 16 refuses shell writes to DeviceConfig flags outside its allowlist (`SecurityException`). | `device_config put device_idle …` through Shizuku. | The planned *Enter deep sleep sooner* tweak was dropped rather than shipped broken. |
| 2026-09-25 | `cmd power set-mode 1` does not turn Battery Saver on while the tablet is charging. | Applied on USB power; state stayed off until unplugged. | Note on the *Battery Saver* tweak. |
| 2026-09-25 | `settings put secure icon_blacklist wifi,battery` hides those icons in the real status bar, over the Boox home too. | Screenshots before and after. | Appearance › System status bar, journaled with a one-tap restore. |
| 2026-09-25 | Battery and a few core icons are not in SystemUI's icon-holder dump, although `icon_blacklist` hides them. | `dumpsys activity service …SystemUIService` compared with the bar. | Core slots are always offered in Appearance. |
| 2026-09-25 | Boox blanks status-bar icons over apps it did not make (the bar is visible but empty), per-app via its mmkv config (`forceFullScreen`). | Screenshots of BooxUltimatum and Play Store against Settings. | The launcher header shows time, connectivity and battery itself. |
| 2026-09-25 | Apps without location access cannot read the Wi-Fi SSID; `cmd wifi status` through Shizuku can. | Header test with and without Shizuku. | Network name read through Shizuku only on reconnect. |
| 2026-09-25 | Boox entry points: `com.onyx.action.LIBRARY`, `com.onyx.action.STORAGE`, and `com.onyx/.StartupActivity` for the Boox home, which holds Notes. Notes has no launcher activity of its own. | `cmd package resolve-activity` and launch tests. | Boox shelf widget and Settings › Boox functions. |
| 2026-09-25 | Boox widgets (Library recently read, note grids, quick settings) are provided by `com.onyx` and bind in a third-party host after the standard bind consent. | Widget picker test; Library widget shows recent books. | Widget picker lists them first. |
| 2026-09-25 | The Boox Library widget asks for a 180 dp minimum height but crops its covers and titles there. | Hosted at the stated minimum, then taller. | Hosted widgets default to 1.4 × the stated minimum and have Shorter/Taller keys. |
| 2026-09-26 | The launcher uses 64–132 MB PSS in the foreground (60 MB of which is window buffers right after start) and 29–44 MB in the background, against 455 MB for the Boox home (`com.onyx`). Idle CPU is 0.0 %. | `dumpsys meminfo`, `top -d 15` on home. | Design check for "must not compete with the Boox launcher". |
| 2026-09-26 | The Shizuku user service (`:shell`) costs about 66 MB PSS and, with `daemon(false)`, lives as long as the app process, which as home is forever. | `dumpsys meminfo <pid>`, `ps`. | The service is unbound and its process removed after 45 s without calls. |
| 2026-09-26 | Re-scheduling an inexact repeating alarm restarts its countdown, so scheduling on every app open could keep the log from ever sampling. | `dumpsys alarm` before and after opening the app. | The log alarm is only set when absent (and always after boot or update). |
| 2026-09-26 | USB power from a PC reports as a wall charger (`AC`) on this firmware. | Battery › Readings while plugged into the PC. | The "stays awake while plugged in" note covers every power source. |
| 2026-09-26 | `com.onyx.easytransfer` (BOOXDrop) had used 8 min 27 s of CPU in 4.5 h of uptime, the most of any Onyx process. | `top`, TIME+ column. | Lead for the first A/B run: *Pause idle Boox apps* covers it. |

## Tweak verification

Round trip means applied on the tablet, the state re-read as on, undone, and the original value confirmed. *Preset* means the firmware already ships the tweak's value, so the app shows it as on and offers no undo.

| Tweak id | Tier | Mechanism | Status on NA6C FW 4.3 |
|---|---|---|---|
| `apps.background` | T2 | `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow` for user apps Boox restricted | Round trip 2026-09-25; the effect on playback is still to confirm in daily use. |
| `doze.enable` | T2 | `dumpsys deviceidle enable` / `disable` | Round trip 2026-09-25. Resets on reboot, as noted in the app. |
| `doze.boox_allowlist` | T2 | `dumpsys deviceidle whitelist -<pkg>` for non-essential Onyx apps, journaled list | Implemented; round trip still to record. |
| `bg.boox_restrict` | T2 | `RUN_ANY_IN_BACKGROUND ignore` for optional Onyx apps, previous modes journaled | Round trip 2026-09-25. |
| `power.saver` | T2 | `cmd power set-mode 1` / `0` | Applies only once unplugged (see findings). |
| `power.adaptive` | T1 | `app_standby_enabled`, `adaptive_battery_management_enabled` = 1 | Preset. |
| `power.autosync` | T0 | `ContentResolver.setMasterSyncAutomatically` | Implemented; round trip still to record. |
| `power.timeout` | T2 | `screen_off_timeout` = 120000, previous value journaled | Round trip 2026-09-25. |
| `power.stay_awake` | T1 | `stay_on_while_plugged_in` = 0 | Preset. |
| `radio.wifi_scan` | T1 | `wifi_scan_always_enabled` = 0 | Preset; the setting path was round-tripped 2026-09-25. |
| `radio.ble_scan` | T1 | `ble_scan_always_enabled` = 0 | Round trip 2026-09-25. |
| `radio.wifi_wakeup` | T1 | `wifi_wakeup_enabled` = 0 | Round trip 2026-09-25. |
| `radio.location` | T2 | `cmd location set-location-enabled false` | Preset. |
| `ui.serif_font` | T2 | `cmd overlay enable/disable com.android.theme.font.notoserifsource` | Round trip 2026-09-26. The overlay recreates the activity, so the change runs in an app-wide scope and journals first. |
| `ui.animations` | T1 | Window, transition and animator scales = 0 | Preset; round trip 2026-09-25. |
| `privacy.dns` | T1 | `private_dns_mode` = hostname, `private_dns_specifier` = `dns.adguard-dns.com` | Round trip 2026-09-25. |
| `privacy.ota` | T2 | `pm disable-user com.onyx.android.onyxotaservice` | Implemented; round trip still to record. |
| `privacy.store` | T2 | `pm disable-user` for `com.onyx.appmarket`, `com.onyx.igetshop` | Round trip 2026-09-25. |
| `privacy.factory` | T2 | `pm disable-user com.onyx.android.production.test` | Preset. |

Other reversible changes outside the tweak list, all journaled: status-bar icons (`icon_blacklist`, restore verified 2026-09-25), battery percentage (`status_bar_show_battery_percent`), the default home app (`cmd package set-home-activity`, original restored), and fonts copied to `/sdcard/fonts` for NeoReader (install and removal verified 2026-09-25).
