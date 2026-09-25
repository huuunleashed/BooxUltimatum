# 00 · Device research: BOOX Note Air6 C (NA6C)

Status: **pre-device desk research** (2026-09-25). Anything marked *[verify]* must be confirmed with `tools/host/recon.ps1` once the tablet is plugged in. Treat blog and aggregator specs as leads to check, not as facts.

## 1. Hardware at a glance

| Item | Note Air6 C (2026) | Note Air5 C (2025) | Source / confidence |
|---|---|---|---|
| SoC | **Qualcomm Dragonwing Q‑6690**, 4 nm, octa-core | **Snapdragon 750G** (SM7225), 8 nm | heise, eWritable review · high |
| Geekbench (1T / nT) | 961 / 2329 | 796 / 1930 (+21 %) | eWritable · high |
| RAM / storage | 6 GB / 64 GB (eWritable unit reports 128 GB) *[verify SKU]* | 6 GB / 64 GB | Boox shop, eWritable |
| Android / FW | **Android 16**, FW **4.3** (review unit: `v4.3-rel`) | Android 15, FW 4.2 | heise, eWritable |
| Display | 10.3" Carta 1200 + Kaleido 3, 2480×1860 mono (300 ppi) / 1240×930 colour (150 ppi); new, smoother writing film | same panel | eWritable |
| Front light | 32 levels + warm/cold (CTM) | same | eWritable |
| Input | Capacitive touch + **EMR** digitiser (Pen3), G-sensor, 2 side buttons (long-press customisable), fingerprint in power key | same | eWritable |
| Battery | 3700 mAh | 3700 mAh | all sources |
| Radio | Wi-Fi 2.4/5 GHz (80 MHz channels, DFS), **BT 6.0**, no cellular | BT 5.1 | FCC XR3‑NA6C, heise |
| I/O | USB‑C OTG, microSD ≤ 2 TB, speakers, mic, pogo pins (keyboard folio) | same | eWritable |
| Chassis | 225×192×5.8 mm, ~440 g, **same chassis as NA5C** | same | eWritable |
| Regulatory | FCC ID **XR3‑NA6C**, granted 2026‑09‑02. Six variants filed: NA6 C / C Plus / C Pro and the mono NA6 / Plus / Pro | n/a | readwithpro.com, via fccid.io |

The FCC internal photos show the SoC under a shield, so the exact silicon, including any SM-number equivalent of the Q‑6690, has to come from `getprop` / `/proc/cpuinfo` / `/sys/devices/soc0/*`.

## 2. How similar is it to the Note Air5 C?

**Probably the same (so NA5C/FW 4.x knowledge should mostly transfer):**
- Chassis, panel, EMR stack, battery, buttons, folio/keyboard.
- The Onyx userland: launcher, EInkWise Center (per-app refresh/DPI/contrast), NeoReader, Notes, Control Center, NaviBall, app-freeze manager, BOOXDrop, Onyx cloud.
- The config storage model. Since FW 4.0, system config lives in **`/onyxconfig/mmkv/onyx_config`** (Tencent MMKV, partly binary). The old plain `/onyxconfig/eac_config` is gone. OnyxTweaks and OnyxMMKVEditor can already read and edit it.

**Different (so NA5C root and low-level knowledge does *not* transfer as-is):**
- **The SoC family changes** from SM7225 to Q‑6690. That means a different kernel, device tree, cpufreq tables, thermal zones, GPU and DSP, and PMIC fuel-gauge paths.
- **EDL/firehose loader.** Earlier Boox root guides (Palma 2, NA4C) used a public generic firehose. No public loader is known for the Q‑6690 yet. *[verify: Sahara HWID/PK-hash via `edl`]*
- **Android 16 security.** Expect a separate `init_boot` partition (GKI), stricter hidden-API and background rules, and possibly new Onyx restrictions on `/onyxconfig` access.
- **Firmware decryption key.** `NoteAir5C` is in `Hagb/decryptBooxUpdateUpx/BooxKeys.csv`, but **`NoteAir6C` is not**. Extracting and contributing that key is an early community win.
- **KOReader's Android launcher** knows `noteair5c`, not `noteair6c`. That's a small upstream PR (device ID → e-ink refresh support).

## 3. Known problems (and whether software can address them)

### 3.1 Battery (the headline issue)
eWritable measured this on FW `v4.3-rel`, just before launch:

| Test (1 h) | NA6C battery used |
|---|---|
| Idle, screen on, nothing happening | **2 %** (the same with Wi-Fi/BT/front light **off or on**) |
| Reading (NeoReader) | 4 % |
| Note-taking (native Notes) | **10 %** |
| Front light max | +14 % |

His scenario (3 h notes + 3 h reading per day) comes to about 42 %/day, roughly 2.4 days per charge. He calls this "amongst the weakest" he has tested. Boox fixed launch-time battery bugs on NA4C and NA5C with firmware updates.

Interpretation (hypotheses to test):
1. **The 2 %/h awake floor doesn't depend on the radios or the light.** Likely causes are CPU/GPU/BSR pipeline activity, a busy EPD/refresh service, or a background Onyx service holding a wakelock or running work. This is a strong candidate for attribution with `dumpsys` / batterystats / Perfetto.
2. **Notes at 10 %/h** suggests the stylus/low-latency ink path (EMR sampling rate, BSR/GPU, "fast refresh" overlay, handwriting recognition or indexing running live). Candidates to test: disable live handwriting search/recognition and cloud sync while writing, change the pen refresh mode, and run the same test in a third-party notes app for comparison.
3. **Standby (screen off)** wasn't reported. Historically Boox devices lose battery in standby through `OnyxDozeWakeLock` and `AnyMotionDetector` wakelocks (XDA, older models), background sync, and Onyx push/OTA services. *[verify overnight]*

### 3.2 Software UX (from the eWritable firmware 4.3 review)
- Settings are powerful but **scattered and inconsistent**. For example, Dual Notes is inside Notes while Split Screen is in the Control Center. There are "toolbars within toolbars". → **A hub/search layer over Boox settings is a real value-add.**
- You **can't choose between the two launchers** (the toolbar launcher or the widget launcher).
- EInkWise Center per-app profiles are excellent but you can't back them up, share them or bulk-edit them. → **Export/import them and ship curated presets.**
- FW 4.3 bug: after pasting a screenshot into a notebook, **the stylus stops responding** until you reopen the notebook. → Document it and look for a workaround (for example, auto re-open).
- Smooth Refresh Mode ghosts more. → Offer per-activity refresh profiles (the OnyxTweaks idea).
- Talk Insights is region-locked (UK/EU). → Out of scope unless it turns out to be a simple flag *[investigate]*.
- The side buttons' long-press actions are customisable, but only to a fixed list. → Extend them (accessibility-service or key-remap actions).

### 3.3 Privacy / trust
- Boox devices phone home (`*.onyx-international.cn`, `boox.com`, `codekk.com`, `effect.snssdk.com`, …), per fardjad's NA3C analysis. They ship re-signed forks of Simple Mobile Tools and their own Chromium fork. There are long-standing GPL-compliance complaints. → Offer optional debloat profiles plus a no-root firewall or DNS blocking.

### 3.4 Hardware (not fixable in software, but worth documenting)
- The stylus magnet is weak, the folio flap is fiddly, and the USB‑C port is on the spine edge.
- A magnetic flap stuck to the back **creates EMR dead zones**. That's a useful tip for the hub.

## 4. Privilege landscape (what each tweak tier needs)

| Tier | How obtained | Unlocks |
|---|---|---|
| T0 app | install APK | Battery APIs, UsageStats (user grant), accessibility service, QS tiles, notification listener, Onyx SDK (`onyxsdk-device`: EPD refresh, front light) |
| T1 adb-grant | one-off `adb shell pm grant … WRITE_SECURE_SETTINGS / DUMP / PACKAGE_USAGE_STATS / READ_LOGS` | `settings put`, `dumpsys` from inside the app, and logcat reading |
| T2 Shizuku | Shizuku via Wireless debugging (re-start after reboot) | runs as shell uid 2000: `pm disable-user`, `cmd appops`, `am set-standby-bucket`, `cmd deviceidle`, `dumpsys batterystats`, `/onyxconfig` writes, and more |
| T3 root | Magisk/KernelSU (**needs a boot/init_boot image and a flashing path**, not yet known for NA6C) | sysfs (`wakeup_sources`, cpufreq/governor), private Onyx MMKV stores, LSPosed/Xposed module (OnyxTweaks-style), firewall (iptables) |

**Design rule:** every feature declares its minimum tier, degrades gracefully, and is **reversible**.

## 5. Sources
- heise: <https://www.heise.de/en/news/Boox-Note-Air6-C-Note-Mini-C-and-Palma-3-come-with-Android-16-11455073.html>
- eWritable NA6C review: <https://ewritable.net/brands/boox/tablets/boox-note-air6-c/>
- eWritable FW 4.3 review: <https://ewritable.net/brands/boox/firmware/4-3/>
- FCC leak (zh-TW): <https://www.readwithpro.com/blog/boox-note-air-6-fcc-six-models-leak>
- Root precedent (Palma 2 / NA4C): <https://github.com/luisliz/BooxNoteAir4CRootGuide>, <https://www.temblast.com/edl.htm>
- Firmware decrypt: <https://github.com/Hagb/decryptBooxUpdateUpx> (no NoteAir6C key yet)
- Privacy/debloat (NA3C): <https://gist.github.com/fardjad/97baf36de97d1c4ae3953b3d359bb918>
- OnyxTweaks: <https://github.com/timschneeb/OnyxTweaks> · OnyxMMKVEditor: <https://github.com/l-althueser/OnyxMMKVEditor>
- Wakelock precedent: <https://xdaforums.com/t/standard-battery-drain-onyx-doze-wakelock.3385915/>
