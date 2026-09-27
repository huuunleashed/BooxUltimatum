---
name: boox-tablet-testing
description: How to test BooxUltimatum on a real BOOX tablet over adb without harming the owner's device. Use whenever a task involves a connected tablet, installing a build, taking screenshots, checking a feature on the device, reading logcat, testing portrait and landscape, testing the sleep screen or Instant ink, or restoring the tablet afterwards.
---

# Testing on the BOOX tablet

The tablet is someone's real device, shared with the agent. Treat every write as borrowed. Read [`AGENTS.md`](../../../AGENTS.md) first: its rules win over anything here.

## Before touching it

- `adb devices` must list it. If adb says "cannot connect to daemon", run `adb kill-server` then `adb start-server`.
- Never assume it's unlocked: `adb shell dumpsys window | grep isKeyguardShowing`. If it shows the lock screen, ask the owner.
- Note what you'll change and put it back afterwards: rotation (`cmd window user-rotation`), the Boox sleep style, EinkWise configs, accessibility services (`settings get secure enabled_accessibility_services`) and the display state.
- Read-only commands are always fine: `getprop`, `dumpsys`, `pm list`, `cat` on sysfs, `settings get`, `logcat -d`.
- Write commands (`settings put`, `pm disable-user`, `pm uninstall`, state-changing `service call`, `appops set`) need the owner's permission in the current session. A throwaway test app is fine once the owner agrees to the test; uninstall it afterwards.

## Paths and tools (Windows host)

- adb: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`. apksigner: `$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat`.
- UI driving: `. .\tools\host\ui.ps1` gives `Get-UiNodes`, `Tap-Text`, `Has-Text`, `Save-Shot`, `Sh`, `Go-Rail` and `Swipe-Up/Down`. Screenshots land in `captures\shots` (git-ignored) or in `$env:BU_SHOTS`.
- The app's pages sit behind a left rail; `Go-Rail 'Sleep'` taps it. Two-pane pages (Sleep in landscape) scroll per pane: swipe on the right half (`input swipe 1700 1500 1700 400 400`).
- Each PowerShell call starts fresh, so dot-source `ui.ps1` in every call.
- Screenshots are 2480 × 1860 or 1860 × 2480. Downscale them before viewing (System.Drawing) and crop to what you need. Never view an image over 4.9 MB.

## Installing

- The development tablet runs the **debug-signed** release build: `.\gradlew.bat assembleRelease` without `-Pbu.signing`, then `adb install -r app\build\outputs\apk\release\app-release.apk`.
- Never install a release-signed APK over it: the signatures differ, Android refuses, and uninstalling loses the app's data and grants.
- Reinstalling cancels the app's alarms and unbinds its accessibility service, which rebinds within seconds.

## Both orientations

- Test portrait and landscape, and check that state survives a turn.
- With auto-rotate on, `settings put system user_rotation` is ignored. Use `cmd window user-rotation lock 0` (portrait) or `lock 1` / `lock 3` (landscape). Read the owner's setting first with `cmd window user-rotation` (`free` means auto) and restore it afterwards.

## What only a person can test

- **Pen input can't be simulated.** `input` injects through InputDispatcher, which neither SurfaceFlinger's pen reader nor the app's reader sees. Ask the owner to hover, touch and lift. The Note Air6 C's EMR pen has no side button.
- **Watch the pen and the display while the owner draws.** The shell can read the pen node: `getevent -lt /dev/input/event5` gives each `BTN_TOOL_BRUSH` (hover) and `BTN_TOUCH` with kernel timestamps. Run it on the tablet from a pushed script (`nohup sh /data/local/tmp/x.sh &`) rather than through a PowerShell pipe, and kill it and delete the files afterwards. In logcat (`-b all`), `ebc_ioctl() HANDWRITE update_marker` lines mean the preview drew for that stroke, `HandlePenTrigger` marks a touch the display caught, and `Pid N State change to: …` shows who changed the pen session (match N with `ps`). A device-side script can send `service call SurfaceFlinger …` at exact pen moments, which is how session timing was measured.
- **Rule out the drawing app first.** In Sketchbook, *Lock transparency* on an empty layer and the eraser brush both look exactly like Instant ink failing: the black preview shows, then goes, leaving nothing. Check a screenshot of the layer panel and brush before debugging.
- **What the sleeping panel shows.** `screencap` works while asleep but proves nothing about the e-ink panel, because frames drawn while dozing are held until the display turns on. Ask the owner what the panel shows.
- **On battery.** Unplugging ends adb. Arm the test, ask the owner to unplug, and read `logcat -d` once they plug back in (the log survives).
- Ask with short, concrete instructions that name the exact time to watch, and leave a generous window.

## Sleep and wake from adb

- `input keyevent 223` puts the tablet to sleep and `224` wakes it (onto the PIN lock screen if there is one, so capture the sleep screen last).
- `dumpsys power | grep mWakefulness` says Awake or Dozing; `dumpsys dreams` shows the Onyx dream.
- Useful logcat tags: `LiveSleep`, `OnyxDaydreamService`, `InstantInk`, `PenInput`, `AlarmManager` (look for `Do not allow` and `clear alarm`), and `auditd` / `avc` for SELinux denials (`logcat -d -b all`).

## Recovering the screen after pen tests

Send these as the shell: `ENABLE_POST` on (`service call SurfaceFlinger 16711692 i32 -1 i32 1 i32 <pid>`), pen state 0 (`16711693 i32 0 i32 0`), auto-sync on (`1048722 i32 1`), then repaint everything (`16711700`). The Ink page's *Recover screen* key does the same.

## Shizuku

It stops on every reboot. Restart it with `.\tools\host\start-shizuku.ps1`. Firmware 4.3 hides Wireless debugging, so this needs USB.
