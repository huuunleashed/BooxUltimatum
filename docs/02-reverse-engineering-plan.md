# 02 · Reverse-engineering and testing plan

Goal: find out how the NA6C spends its energy and CPU, map every Onyx component, and work out which tweaks are safe (friendly → advanced). We work in phases, and each phase gates the next. Read-only phases come first, and nothing risky happens without a full backup.

## Ground rules

- **Read before write.** Phases 1–3 change nothing on the device except enabling USB debugging and installing our probe APK.
- **One variable at a time.** Every battery experiment changes exactly one thing and is compared against a baseline taken on the same firmware.
- **Unplugged means unplugged.** USB charges the tablet, so power measurements run on-device (the logger script or the probe app) and data is pulled afterwards. Wireless ADB is allowed for pulling, but not during a measured window, because Wi-Fi would be a confounder.
- **Journal everything.** Each session produces a folder in `captures/<date>-<serial>-<label>/` containing raw outputs, plus a short `NOTES.md` with what we did and saw.
- **Firmware discipline.** Record the firmware build before and after any OTA. Incremental OTAs refuse to install on rooted devices, and full OTAs remove root.

## Phase 0 · Before the device arrives (done / in progress)

- [x] Desk research (`00-device-research.md`).
- [x] Host toolchain: platform-tools (adb/fastboot), Android SDK 36, JDK 21, Gradle.
- [x] Host scripts: `tools/host/recon.ps1` (read-only inventory), `tools/host/power-logger.ps1` (on-device sampler), `tools/host/pull-apks.ps1`.
- [x] Probe APK v0.1 (device info, battery readings, package inventory, privilege detection).

## Phase 1 · Unboxing day (zero risk, about 1 h together)

1. **First boot decision (your call):** optionally let the first boot run on a network we can observe (a PC hotspot with Wireshark or a Pi-hole log) to record what phones home before any account sign-in. Otherwise, skip Wi-Fi during setup.
2. Write down the firmware version from *Settings → About*, **before accepting any OTA**. We capture the factory firmware baseline first, then update. The update may fix battery issues, so we want before/after numbers.
3. Enable Developer options (tap *Build number* 7×), turn on **USB debugging**, plug in, and accept the RSA prompt.
4. Run `tools\host\recon.ps1 -Label factory`. It collects props, the SoC and kernel, CPU/thermal/power-supply sysfs, all packages (with paths, UIDs, installers and disabled state), `dumpsys` of battery/batterystats/power/deviceidle/alarm/jobscheduler/activity services/appops/usagestats, `settings` in all three namespaces, the `/onyxconfig` listing, SELinux mode, verified-boot and bootloader flags, and the partition layout (if readable).
5. Install the probe APK (`adb install app-debug.apk`) and grant the T1 permissions:
   ```
   adb shell pm grant app.booxultimatum android.permission.WRITE_SECURE_SETTINGS
   adb shell pm grant app.booxultimatum android.permission.DUMP
   adb shell pm grant app.booxultimatum android.permission.READ_LOGS
   adb shell appops set app.booxultimatum GET_USAGE_STATS allow
   ```
6. Quick answers we want on day 1: `ro.board.platform`, `ro.soc.model`, kernel version, whether `init_boot` exists, `ro.boot.verifiedbootstate`/`ro.boot.flash.locked`, whether `/onyxconfig/mmkv` is readable by the shell, whether `current_now` is readable, and whether `/sys/power/suspend_stats` is readable.

## Phase 2 · Static analysis (no device risk; runs in parallel with Phase 3)

1. `tools\host\pull-apks.ps1 -Filter onyx` pulls all Onyx APKs and the framework jars (`/system/framework/*onyx*`, `/system_ext/framework`).
2. Decompile them with **jadx** (a host tool). For each Onyx package, record in `knowledge/onyx-packages.json`: purpose; exported components; background services, receivers, alarms and jobs; wakelock tags; network endpoints; MMKV keys it reads; and hidden activities we can deep-link.
3. Special targets:
   - The EPD/refresh stack (the class behind EInkWise, BSR, and the `EpdController` hooks from `onyxsdk-device`).
   - The **Notes** ink pipeline, to explain the 10 %/h drain (recognition, indexing, sync, refresh loop).
   - The doze integration (`OnyxDozeWakeLock` or its successor) and the Onyx freeze manager.
   - The OTA service and firmware URL/model string (for firmware download and decryption, and for extracting the `NoteAir6C` key per the decryptBooxUpdateUpx `CONTRIBUTING.md`).
   - MMKV key schema for `/onyxconfig/mmkv/onyx_config` (per-app e-ink profiles, handwriting-optimised app list).

## Phase 3 · Battery baseline (the core experiment; overnight plus about 1 h slots)

Reproduce eWritable's table on our unit so the numbers are comparable, then add standby. We start each run at 80–90 %, let the device settle for 10 min after unplugging, and use Wi-Fi off unless the test says otherwise.

| ID | State | Duration | Notes |
|---|---|---|---|
| B0 | Screen off, standby, Wi-Fi off | 8 h (overnight) | the real "drain while asleep" number |
| B1 | Screen off, standby, Wi-Fi on | 8 h | background network wakeups |
| B2 | Awake idle, light off, radios off | 1 h | eWritable's 2 %/h floor |
| B3 | NeoReader, page turn every 30 s (auto via `input keyevent`) | 1 h | reading |
| B4 | Native Notes, continuous writing | 1 h | eWritable's 10 %/h |
| B5 | Third-party notes app, same writing | 1 h | isolates the Onyx Notes pipeline from EMR/display |
| B6 | Front light 50 % / 100 % | 1 h each | light cost |

Instrumentation per run:
- Before: `dumpsys batterystats --reset`. Start `power-logger.ps1 -Action Start -Interval 60`, then unplug.
- After: replug, `power-logger.ps1 -Action Pull`, run `dumpsys batterystats --checkin` and `dumpsys batterystats` (text), `dumpsys power`, `dumpsys alarm`, `dumpsys jobscheduler`, `dumpsys deviceidle`, and `adb bugreport` (for **Battery Historian**).
- Look for suspend gaps in the logger timestamps (screen-off runs should show long gaps, meaning deep sleep). If they don't, something holds a wakelock. A rising `suspend_stats/success` count confirms deep sleep entries.
- If needed, capture a **Perfetto** trace (CPU frequency and idle states, sched, power rails if exposed) for B2 and B4 to see what runs while the screen is on and nothing is happening.

## Phase 4 · Attribution and A/B tweak experiments

For each suspect (from Phase 2–3 data), apply one reversible change, repeat the relevant B-test, and record Δ %/h in `knowledge/experiments.md`. Candidate levers, from friendly to advanced:

1. Settings the user can reach: auto-sync, cloud/Push, handwriting recognition/search indexing, Smooth/BSR refresh, freeze-in-background options, screen-off Wi-Fi.
2. App standby and appops: `am set-standby-bucket <pkg> restricted`, `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore`, and removing Onyx packages from the Doze whitelist (`dumpsys deviceidle whitelist -<pkg>`).
3. Doze tuning: `device_idle_constants` (`settings put global device_idle_constants …`), `cmd deviceidle force-idle` tests.
4. Disabling Onyx services one by one (`pm disable-user --user 0`) for OTA, push, analytics, the app market, easy-transfer, and production-test packages.
5. T3 only: CPU policy caps and governor for the idle floor, kernel wakeup_sources analysis, and IRQ affinity.

## Phase 5 · Root feasibility (optional; only after Phases 1–4 and a full backup)

1. Check flags: `ro.boot.flash.locked`, `ro.boot.verifiedbootstate`, `ro.boot.vbmeta.device_state`, and `getprop ro.oem_unlock_supported`. Also check whether *OEM unlocking* appears in Developer options.
2. Fastboot: `adb reboot bootloader` → `fastboot getvar all` (read-only). Save the output and don't run any `flash` or `oem` commands yet.
3. EDL: `adb reboot edl` → confirm USB `05C6:9008`. Use `bkerler/edl` to read Sahara info only (HWID, PK hash, serial). Then search `bkerler/Loaders` and MobileRead for a matching firehose.
4. Firmware route (no EDL needed): get the NA6C `update.upx` via the Onyx firmware API → decrypt it (with the key from Phase 2) → run `payload-dumper-go` → extract `boot.img` / `init_boot.img` → patch with Magisk. Only `fastboot boot`/flash if the bootloader permits it.
5. Decide go/no-go together. Root is **not** required for the core product.

## Phase 6 · Build loop

Each validated finding becomes a tweak (with apply/revert/tier/evidence) in the app, a knowledge-base entry, and, where it fits, an upstream contribution (KOReader device ID, the NoteAir6C decrypt key, OnyxTweaks Android 16 port notes).

## What I need from you on day 1

- About 1 h with the tablet plugged into this PC, plus willingness to leave it unplugged overnight for B0.
- Your decision on the first-boot network capture and on accepting the day-1 OTA (I recommend: capture the factory baseline, then update).
- Your intended daily usage (apps, Wi-Fi habits), so the profiles target your real workload.
