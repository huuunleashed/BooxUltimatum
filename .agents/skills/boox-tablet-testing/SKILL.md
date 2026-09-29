---
name: boox-tablet-testing
description: How to test BooxUltimatum's suite apps (the hub and Nib) on a real BOOX tablet over adb without harming the owner's device, and how to test on the emulator and through the test channel when the tablet isn't connected. Use whenever a task involves a connected tablet, the emulator, installing a build, taking screenshots, checking a feature on the device, reading logcat or the logbook, testing portrait and landscape, testing the sleep screen, Instant ink or Nib's pen preview, or restoring the tablet afterwards.
---

# Testing on the BOOX tablet

The tablet is someone's real device, shared with the agent. Treat every write as borrowed. Read [`AGENTS.md`](../../../AGENTS.md) first: its rules win over anything here.

## Before touching it

- `adb devices` must list it. If adb says "cannot connect to daemon", run `adb kill-server` then `adb start-server`.
- **adb over Wi-Fi** (verified 2026-09-28): firmware 4.3 hides the *Wireless debugging* switch, but the older mode works. With the owner's OK, run `adb -s <usb serial> tcpip 5555` once over USB, then `adb connect <tablet ip>:5555` (the IP is in `ip -4 addr show wlan0`); the cable can then come out. It uses the same key approval as USB, lasts until the next reboot, and `adb usb` ends it sooner. With both connections listed, pass `-s` every time.
- **`adb pull` fails silently on this PC** (exit 1, a 0-byte file, no message). Stream files instead, `cmd /c "adb -s <serial> exec-out cat /path > <file>"`, and compare `md5sum` on the tablet with `Get-FileHash -Algorithm MD5`.
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

- The owner's tablet runs the **published release** since 0.5.1 (release key), and updates itself from GitHub Releases. Test builds for it must be release-signed: `.\gradlew.bat assembleRelease -Pbu.signing=$env:USERPROFILE\.booxultimatum\signing.properties`, then `adb install -r`. A debug-signed build won't install over it.
- Before 0.5.1 it ran the debug-signed release build. Moving between the two keys means uninstalling; the steps that keep the owner's data and grants are in the `build-release-and-docs` skill.
- Reinstalling cancels the app's alarms and unbinds its accessibility service, which rebinds within seconds.
- **Leave Nib before `adb install -r` of either app.** The install kills the process without a pause, and the display keeps a pen session going for a dead process: a Nib killed while drawing left the preview drawing over every app, home included. Ask the owner to go to the home screen first. If it happens, `GET_PEN_STATE` (`service call SurfaceFlinger 1048643`) reads 2, and the recovery in AGENTS.md ends it (with the owner's OK). Since 0.6.0, Nib's lease and home's check end it too.
- A build of the same versionCode reinstalls with `install -r` (useful while iterating on an unpublished test build); `dist\` is overwritten by the next `publish.ps1 -DryRun`.
- **Every suite app on one tablet must share a key.** Each declares the signature permission `app.booxultimatum.permission.SUITE` (from `:kit:core`), and Android refuses to install an app that declares it with a different key from one already installed. So a debug-signed Nib won't install next to the release-signed hub; build both release-signed.
- The APKs are `hub\build\outputs\apk\<type>\hub-<type>.apk` and `nib\build\outputs\apk\<type>\nib-<type>.apk`.

## When the tablet isn't connected

The owner may have the tablet with them, away from the computer. Then:

- **Instrumented tests install onto every attached device.** Before `connectedDebugAndroidTest`, make sure `adb devices` lists only the emulator: disconnect a Wi-Fi-connected tablet with `adb disconnect <ip>:5555` (host side only; `adb connect` brings it back without the cable while the tablet stays in TCP mode), and set `$env:ANDROID_SERIAL = 'emulator-5554'` too. Subagents must never run them.
- **Test here first.** Run the JVM tests (`.\gradlew.bat testDebugUnitTest :nib-engine:test`) and the `NoteAir6C` emulator: an Android 36 AVD at 1860 × 2480, 300 dpi. Start it headless with `emulator -avd NoteAir6C -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save` (WHPX works on this PC), then use `adb -e`.
- **What the emulator can't show.** It isn't Boox firmware, so the Sleep and Ink pages are hidden and Nib draws without the display preview. Anything that touches SurfaceFlinger's pen path, the sleep screen or EinkWise needs the tablet.
- **Screenshots.** `adb -e exec-out screencap -p > file.png` works in PowerShell 7. Downscale before viewing.
- **Stylus input on the emulator.** `adb -e shell input stylus swipe x1 y1 x2 y2 ms` draws a stroke Android reports as a stylus. Instrumented tests can inject `MotionEvent`s with `TOOL_TYPE_STYLUS` and pressure.
- **Reaching the tablet.** With the owner's agreement, publish a release-signed test build (`tools/dev/publish.ps1 -Test`). The owner turns on *Offer test builds* (Device page) and installs it from the hub. Then they run the checks listed in the release notes, including Nib's Diagnostics probes, and send back the zip from Device › Logs › *Share logs*. Turn the probe answers and log lines into rows in `knowledge/experiments.md`.

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
- Useful logcat tags: every suite app logs as `BU/<category>`, for example `BU/ink`, `BU/ink.pen`, `BU/ink.display`, `BU/ink.session`, `BU/sleep.live`, `BU/update`, `BU/suite`, `BU/nib.pen`, `BU/exec` (Shizuku calls: failures, unreachable service), `BU/ui` (slow page reads), `BU/storage` and `BU/fonts`. The same entries, and Debug for the pen and update categories, are in the app's logbook (`noBackupFilesDir/logs`, exported from Device › Logs). **Release builds send only Info and above to logcat**; Debug lines (per-stroke `nib.pen` summaries, for instance) go to the logbook file only, and release builds can't be read with `run-as`. So anything to be read over adb during a test is logged at Info, like the `nib.probe` answers, or read from a shared logs zip.
- **To tell a missing preview from a missing session**, mark the device time (`date '+%m-%d %H:%M:%S.000'`), have the owner draw, then count `HANDWRITE update_marker` lines in `logcat -b all -d -T '<time>'`. Updates with nothing visible mean the display drew in a colour it can't show (see the firmware skill). Boox's side logs as `OnyxDaydreamService` and `AlarmManager` (look for `Do not allow` and `clear alarm`); SELinux denials are under `auditd` and `avc` (`logcat -d -b all`).

## Recovering the screen after pen tests

Send these as the shell: `ENABLE_POST` on (`service call SurfaceFlinger 16711692 i32 -1 i32 1 i32 <pid>`), pen state 0 (`16711693 i32 0 i32 0`), auto-sync on (`1048722 i32 1`), then repaint everything (`16711700`). The Ink page's *Recover screen* key does the same.

## Shizuku

It stops on every reboot. Restart it with `.\tools\host\start-shizuku.ps1`. Firmware 4.3 hides Wireless debugging and the Wi-Fi mode above also ends at a reboot, so this needs USB once after each restart.
