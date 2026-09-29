---
name: boox-firmware-interfaces
description: The verified BOOX (Onyx) firmware interfaces BooxUltimatum relies on, how they were found and how to find more. Use whenever working on the sleep screen, live updates, the power-off screen, Instant ink, Nib's pen preview, the tablet font, App Freeze, background restrictions, alarms, the charge limit, device detection or pen input, or when reverse-engineering anything new on Boox firmware.
---

# BOOX firmware interfaces

Everything here was verified on a Note Air6 C with firmware 4.3 (Android 16) unless it is marked *[verify]*. Each finding has a dated row in [`knowledge/experiments.md`](../../../knowledge/experiments.md). Add a row for anything new before shipping it, and mark unverified claims *[verify]*. Other models may differ: check `Tablet.current(context)` and probe each interface before use.

## Finding things

- Decompiled sources stay outside the repository; never commit firmware or APKs. Pull read-only with `tools/host/pull-apks.ps1` and decompile with jadx. The owner's machine keeps them in `%TEMP%\kcb\ss\{kcb,fw,svc}\sources`: `kcb` is `com.onyx` (Boox Settings and the dream), `fw` the framework with `android.onyx.*`, and `svc` services.jar.
- Search with ripgrep, scoped to a subfolder such as `...\sources\com\onyx\common\dream`. PowerShell's `Select-String -Recurse` over the whole tree takes minutes, and a broad ripgrep over all of it can time out.
- Confirm on the tablet with logcat before trusting decompiled code; jadx sometimes gives up on a method (`Method not decompiled`).
- **The 2026-09-28 study** decompiled BOOX Notes 46037 (`/system/app/knote2-release`), NeoReader 39009 (`/system/app/kreader2-release`) and `framework.jar` into `captures\notes-46037\jadx`, `captures\notes-46037\framework\jadx` and `captures\neoreader-39009\jadx`. jadx 1.5.6 is in `%USERPROFILE%\Tools\jadx` (run it with `JADX_OPTS=-Xmx6g`, `--no-res -j 6`; it can sit on its last two classes for many minutes, and by then everything else is written, so stop it). Onyx's SDK is bundled inside both apps under `com\onyx\android\sdk\` (`pen\TouchHelper`, `pen\RawInputReader`, `api\device\epd\EpdController`, `device\SDMDevice`). `docs/09-ink.md` has the full call table, the native choreography and the findings ledger.
- **Trace the native apps live** rather than guessing: mark the time (`date '+%m-%d %H:%M:%S.000'`), have the owner write, then read `logcat -b all -d -v threadtime -T '<time>'` and follow `onyx_android_refreash_enable` (the frame hold), `onyx_tcon_set_upd_scheme` (the controller's scheme), `HANDWRITE update_marker` (preview updates), `HandlePenTrigger` and `Pid N State change`.
- **Measure BOOX's pens instead of guessing: penlab** (`tools/host/penlab`, see its README). It runs the firmware's pen-ink library (`libneopen_jni.so`) from the shell under `app_process` and prints BOOX's ink for any stroke and configuration. It found the fountain's p^(2s) law and the charcoal's tilt curve in minutes (`docs/09-ink.md` › *Measured pens*). Keep its outputs out of the repository; only numbers go in.
- **Pen input can't be injected**: SELinux refuses the shell's writes to the pen node (`sendevent` permission denied), and `input` events never reach the display's pen reader. The display's preview of a stroke is only seen by a person drawing. The fed-stroke calls (16711697 to 16711699) return 0 from the shell and draw nothing.
- Onyx's public docs (onyx-intl/OnyxAndroidDemo, `doc/`) cover a small part: `Onyx-Pen-SDK`, `Scribble-TouchHelper-API`, `EPD-Update-Mode`, `EPD-Touch` (finger touch off in regions).

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
  | 16711693 | Pen state (0 stop, 1 start, 2 draw, 3 pause); `GET_PEN_STATE` reads a paused session back as 4 |
  | 16711694 | Region |
  | 16711688 | Stroke style |
  | 16711687 | Stroke width |
  | 16711686 | Stroke colour |
  | 16711692 | `ENABLE_POST` (int −1, int enable, int pid) |
  | 1048643 | `GET_PEN_STATE` |
  | 1048618 | `GET_MAX_TOUCH_PRESSURE` (returns 4096.0; used as a probe) |
  | 1048722 | Auto-sync |
  | 16711700 | Repaint everything |

- Send the stroke settings after START, in the SDK's order: style, width, colour. Hold frames from hover (`BTN_TOOL_BRUSH` on this pen), not from pen-down.
- **The native apps keep one hold for the whole writing session** (traced 2026-09-28): the frames are held from the first touch, and let through only at a break (a menu, undo, a pan, the eraser, leaving). Every release switches the e-ink controller's update scheme (`onyx_tcon_set_upd_scheme` 3 at the touch, 2 or 4 at the release), which is what delays the next stroke if an app releases after each stroke. Don't release between strokes.
- **The full call table is in `docs/09-ink.md`**, and `kit:ink`'s `Epd` implements it: region mode (1048620, 0 multi, 1 single; multi keeps every excluded rectangle, verified), region pen config per pen part (1049090), style parameters (1049088/9), handwriting-layer bitmaps (1049092), refresh with an update mode (16711681, width and height, not right and bottom), fast mode (16711782/3), geometry (16711723 to 16711727, 1049344), finger touch off in regions (the input manager, AIDL codes 82 to 85).
- SurfaceFlinger reads the region in the panel's landscape frame, so use a square with the long side (2480). It then covers both orientations, so rotating needs no new session.
- **The session has to exist before the touch.** A session started after the touch has begun misses that stroke, and one started about 35 ms before it can too. A paused session resumes instantly with DRAW, even at the touch. On a quick stroke this pen hovers only about 45 ms before touching, and a quick first touch arrives in the same batch as the hover, `BTN_TOUCH` first. So Instant ink opens its session as soon as it's on and keeps it paused (quiet: no preview, no held frames) whenever the pen is away or no chosen app is in front.
- **The firmware holds app frames from every touch** (`HandlePenTrigger, sid: -1 value is: 0` in logcat, `onyx_android_refreash_enable() enable[0]`) until `ENABLE_POST` 1 lets them through, which is also the swap that replaces the preview. A pulse (0, then 1) swaps even if nothing held the frames. Letting frames through while the pen still draws ends the preview for the rest of that stroke, so the preview and the app's live stroke can't be shown together.
- **Boox's apps run sessions of their own.** `com.onyx` stopped the session when Boox Notes opened; Notes starts its own and leaves it paused when you switch away. Never end a session over a `com.onyx.*` app; pause, and take over a paused one (region, stroke, DRAW) in a chosen app afterwards. `Pid N State change to: …` in logcat names who changed it.
- `android.onyx.ViewUpdateHelper` and `VMRuntime.setHiddenApiExemptions` are blocked as hidden APIs for a targetSdk 36 app, so the app uses the Direct route.
- **Region exclude (16711714) is verified** (2026-09-28): the payload is an int flag, then an int array of left, top, right, bottom. Flag 1 takes screen coordinates, which SurfaceFlinger maps to the panel; flag 0 takes the panel's own frame, which is turned against the screen (at rotation 0, panel = (y, 1860 − x); rotation 1 is the identity; rotation 3 is 180°; `dumpsys SurfaceFlinger` shows the turn as `framebufferSpace` orientation, with rotation 0 reading `ROTATION_270`; the display's own matrix, 1049344, says the same). In single-region mode, the default, only the last rectangle counts; in multi-region mode every rectangle does. An empty array clears them. Any pid may call it.
- **A session outlives the process that opened it.** SurfaceFlinger doesn't watch the owner's pid: a Nib killed by `adb install -r` mid-session left the preview drawing over every app (`GET_PEN_STATE` 2) until pen state 0 was sent. `service call SurfaceFlinger 16711693 i32 0 i32 0` from the shell stops it for everyone (`Pid 0 State change to: stop`). `kit:ink`'s `InkGuard` (which also covers fast mode, finger touch and style parameters) and the hub's `StrayInk` exist for this.
- **More calls in the same class, decompiled from FW 4.3 but not yet tried on the tablet** (all *[verify]* unless `docs/09-ink.md` marks them verified; payloads as `ViewUpdateHelper` writes them after the interface token):

  | Code | What it does | Payload |
  |---|---|---|
  | 16711681 | `REFRESH_SCREEN` | left, top, width, height, mode (`HAND_WRITING_REPAINT_MODE` is 524290, per CalliPlus's notes) |
  | 16711715 | Repaint everything with a mode | mode |
  | 16711718 | `APPLY_GC_ONCE`, a full refresh | none |
  | 16711690, 16711691, 16711696 | `MOVE_TO`, `LINE_TO`, `QUAD_TO`: strokes fed by the app | hasView, x, y, width or mode, pressure |
  | 16711697 to 16711699 | Start, add to and finish a fed stroke | baseWidth, x, y, pressure, size, time |
  | 1048833 | Eraser raw drawing | boolean enabled, int painter |
  | 1048834 | Brush raw drawing | boolean |
  | 1049088, 1049089 | Get and set a style's stroke parameters | style (and float[]) |
  | 1049344 | EPD-to-view matrix | none; replies with floats |

  The codes are plain statics a firmware can renumber, so probe each before use and log the result.
- **Stroke styles:** 0 pencil, 1 fountain, 2 marker, 3 neo brush, 4 charcoal, 5 dash, 6 charcoal v2, 7 square pen. Three independent sources agree: Onyx's `TouchHelper` constants as reconstructed by hbmartin/onyx-android-sdk, steffest/Boox-EinkDraw's literals, and CalliPlus's notes. Only 0, 1 and 2 have been seen on this tablet, but the native app uses 0 to 4, 6 and 7 for its pens and 5 for the lasso, so all are in production use on FW 4.3; their parameters are in `docs/09-ink.md`. `ViewUpdateHelper.setStrokeWidth` takes a raw float with no clamp; the smallest width the panel draws cleanly is *[verify]*. Other developers report that a preview colour that isn't opaque breaks the pencil's texture *[verify]*.
- **Colour in the preview** (tested 2026-09-28): opaque colours show, red as red and blue as grey, in the fountain and marker styles. The marker style at half alpha shows every grey from black to #BBBBBB and yellow, but nothing in red, blue, green or teal, even though the display sends its `HANDWRITE` updates for them; a pale opaque colour (#E89194) doesn't show either. So a missing preview can be the colour, not the session: count `HANDWRITE update_marker` lines to tell them apart.
- **Nib uses the path in its own window.** 0.2.0 released after every stroke (verified to work, and to feel slow between quick strokes); from 0.3 `kit:ink`'s `InkCanvasController` keeps one hold per writing session like the native apps, excludes every card in multi-region mode, and pushes its controls as bitmaps during a hold. Its Diagnostics page probes the open questions above.
- **SELinux closes `/sys/class/input` to apps**: both listing it and reading `device/name` are denied. The `/dev/input/eventN` nodes are readable, so `PenInput` opens every readable node and takes the first one that reports `BTN_TOOL_PEN`, `BTN_TOOL_BRUSH` or `BTN_TOOL_RUBBER`, then re-reads that batch so a touch reported just before the tool isn't lost. `InputManager` says whether a stylus exists at all.
- Instant ink needs usage access to know which app is in front. Without it the usage history is silently empty. A tap with the pen can open another app while the pen stays in range, so read the app in front again shortly after taps.

## Fonts, status bar, charging

- Tablet font: broadcast `onyx.action.font.replace.system` (a SystemUI receiver, no permission) with the file in shared storage; `font_lang` 0 = all, 1 = CJK, 2 = EN.
- Status bar: `icon_blacklist` (secure) hides icons at T1. The battery percentage is drawn inside the battery icon, so hiding the icon hides the figure. Replacing icon artwork needs root (`cmd overlay fabricate` refuses).
- The charge limit is fixed at 80 %. `SDMDevice.enableChargingControl` writes 80 and 79 to `/sys/class/power_supply/battery/charge_control_{end,start}_threshold`, which even the shell can't read, so other levels need root.

## Device detection

`kit/core`'s `TabletProfile.kt` (`Tablet.current`, package `app.booxultimatum.kit.core`) is the single answer, shared by every suite app. It recognises Boox from build fields that contain "onyx" or "boox", or from the `com.onyx` package, and reads the series from the model name. Gate Boox-only UI on it, and still probe each interface before use.
