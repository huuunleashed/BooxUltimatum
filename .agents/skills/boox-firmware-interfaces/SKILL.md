---
name: boox-firmware-interfaces
description: The verified BOOX (Onyx) firmware interfaces BooxUltimatum relies on, how they were found and how to find more. Use whenever working on the sleep screen, live updates, the power-off screen, Instant ink, the tablet font, App Freeze, background restrictions, alarms, the charge limit, device detection or pen input, or when reverse-engineering anything new on Boox firmware.
---

# BOOX firmware interfaces

Everything here was verified on a Note Air6 C with firmware 4.3 (Android 16) unless it is marked *[verify]*. Each finding has a dated row in [`knowledge/experiments.md`](../../../knowledge/experiments.md). Add a row for anything new before shipping it, and mark unverified claims *[verify]*. Other models may differ: check `Tablet.current(context)` and probe each interface before use.

## Finding things

- Decompiled sources stay outside the repository; never commit firmware or APKs. Pull read-only with `tools/host/pull-apks.ps1` and decompile with jadx. The owner's machine keeps them in `%TEMP%\kcb\ss\{kcb,fw,svc}\sources`: `kcb` is `com.onyx` (Boox Settings and the dream), `fw` the framework with `android.onyx.*`, and `svc` services.jar.
- Search with ripgrep, scoped to a subfolder such as `...\sources\com\onyx\common\dream`. PowerShell's `Select-String -Recurse` over the whole tree takes minutes, and a broad ripgrep over all of it can time out.
- Confirm on the tablet with logcat before trusting decompiled code; jadx sometimes gives up on a method (`Method not decompiled`).

## Sleep screen

- It's Android's doze dream `com.onyx/com.onyx.common.dream.OnyxDaydreamService`, set by `config_dozeComponent`. Another app's DreamService can't replace it.
- The `onyx.action.SCREENSAVER` broadcast (Onyx SDK `ScreenSaverUtils`) takes `type` (16 sleep image, 17 power-off image, 1 wallpaper), `file` (an absolute path) and `show_result_hint` (false), and needs no permission. Write the picture straight into `/sdcard/Pictures/` through MediaStore; a file elsewhere is copied once and later changes are ignored.
- Boox reads the image at sleep and never again while asleep. Don't send the style switch while `!isInteractive`: it blanks the screen.
- **Live updates.** The dream registers an exported receiver for `onyx_dream_refresh`. Any app can send it: the dream wakes the display for about 1.2 s and reports `onyx.action.DISPLAY_CHANGED_STATE` with `state` 2 (on) and then 3 (dozing). Frames drawn while it dozes are held until then.
- Only a `TYPE_ACCESSIBILITY_OVERLAY` window shows over the lock screen; app overlays stay hidden (`DisplayPolicy.shouldBeHiddenByKeyguard`). Draw after state 2, not before: drawing first worked while plugged in but not on battery.
- Boox always draws its bottom bar while charging (`BaseStyleDream.getStatusBarVisibility`); the overlay covers it.
- `com.onyx.action.ONYX_SYSTEM_GOING_TO_SLEEP` arrives tens of milliseconds before the dream decodes the picture.

## Power manager, alarms and background use

- `android.onyx.pm.OnyxAlarmHelper`, called from `AlarmManagerService`, refuses and clears wake-up alarms during sleep unless the package is on its full-access list. The log says `***** Do not allow [...]` and `clear alarm, packageName`.
- A package joins that list when AppOps op 70, `RUN_ANY_IN_BACKGROUND`, *changes* to allowed (`OnyxAppOpsHelper` → `UpdateFullPMPkgsAction`). Setting `allow` over the default does nothing; `ignore` then `allow` works. Whether the list survives a reboot is *[verify]*.
- Wake locks from restricted apps are refused (`WakeLock ... is forbidden`), and so are foreground services that a new, restricted app tries to start from a broadcast.
- `com.onyx.action.disable_restriction` with a `package` extra, sent from the shell, allows the package's alarms while awake and adds it to `OnyxSystemConfig.KEY_HIDDEN_API_THIRD_PARTY_APP_WHITE_LIST`. Whether that opens `android.onyx.ViewUpdateHelper` for Instant ink is *[verify]*.
- The per-app switch is Android's *Allow background usage* (`android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL` with data `package:<pkg>`). Boox's App Freeze page is hidden from the 4.3 menus but opens with `onyx.settings.action.APP_FREEZE_MANAGEMENT`.

## Instant ink and the pen

- The app talks to SurfaceFlinger directly (token `android.ui.ISurfaceComposer`):

  | Code | What it does |
  |---|---|
  | 16711693 | Pen state (0 stop, 1 start, 2 draw, 3 pause) |
  | 16711694 | Region |
  | 16711688 | Stroke style |
  | 16711687 | Stroke width |
  | 16711686 | Stroke colour |
  | 16711692 | `ENABLE_POST` |
  | 1048643 | `GET_PEN_STATE` |
  | 1048618 | `GET_MAX_TOUCH_PRESSURE` (returns 4096.0; used as a probe) |
  | 1048722 | Auto-sync |
  | 16711700 | Repaint everything |

- Send the stroke settings after START, in the SDK's order: style, width, colour. Hold frames from hover (`BTN_TOOL_BRUSH` on this pen), not from pen-down.
- SurfaceFlinger reads the region in the panel's landscape frame, so use a square with the long side (2480).
- `android.onyx.ViewUpdateHelper` and `VMRuntime.setHiddenApiExemptions` are blocked as hidden APIs for a targetSdk 36 app, so the app uses the Direct route.
- **SELinux closes `/sys/class/input` to apps**: both listing it and reading `device/name` are denied. The `/dev/input/eventN` nodes are readable, so `PenInput` opens every readable node and takes the first one that reports `BTN_TOOL_PEN`, `BTN_TOOL_BRUSH` or `BTN_TOOL_RUBBER`. `InputManager` says whether a stylus exists at all.
- Instant ink needs usage access to know which app is in front. Without it the usage history is silently empty.

## Fonts, status bar, charging

- Tablet font: broadcast `onyx.action.font.replace.system` (a SystemUI receiver, no permission) with the file in shared storage; `font_lang` 0 = all, 1 = CJK, 2 = EN.
- Status bar: `icon_blacklist` (secure) hides icons at T1. The battery percentage is drawn inside the battery icon, so hiding the icon hides the figure. Replacing icon artwork needs root (`cmd overlay fabricate` refuses).
- The charge limit is fixed at 80 %. `SDMDevice.enableChargingControl` writes 80 and 79 to `/sys/class/power_supply/battery/charge_control_{end,start}_threshold`, which even the shell can't read, so other levels need root.

## Device detection

`core/TabletProfile.kt` (`Tablet.current`) is the single answer. It recognises Boox from build fields that contain "onyx" or "boox", or from the `com.onyx` package, and reads the series from the model name. Gate Boox-only UI on it, and still probe each interface before use.
