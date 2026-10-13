# 01 · Product vision

> **BooxUltimatum** is a sideloadable suite (you install it yourself, outside an app store) that makes a Boox tablet last longer, feel simpler, and stay private, with apps like Nib that use the tablet's best hardware. Every tweak can be measured, explained and undone.

This vision rests on the most credible independent review of the Note Air6 C (NA6C), by eWritable on firmware 4.3. Its verdict: an *excellent, versatile device* held back by **poor battery life** and **complex, scattered software**. Those two problems guide everything below.

Tweaks and features are grouped in privilege tiers. T0 needs no special access. T1 uses permissions you grant over adb (Android Debug Bridge, run from a computer). T2 uses Shizuku, a helper app that lets other apps run commands with the shell's privileges once you start it. T3 needs root, which BooxUltimatum doesn't require.

## Pillars (in priority order)

### 1. Battery Doctor: measure → attribute → fix → verify
- **Measure:** an automatic drain table per usage state (screen-off standby, awake idle, reading, notes, front light), in the style of eWritable's review. The low-overhead sampler on the tablet records level, current, voltage, temperature, suspend count and screen state.
- **Attribute:** wake locks (what keeps the tablet awake), alarms, jobs and wakeups per app, CPU time and the top offenders. T2 reads them through `dumpsys batterystats/power/alarm/jobscheduler/deviceidle`, and T3 through the kernel's `wakeup_sources`.
- **Fix:** one-tap, reversible actions. These are freezing or disabling an app, restricting its background use (`RUN_ANY_IN_BACKGROUND`), setting its standby bucket (how often Android lets it run), trimming the list of apps Doze never pauses (Doze is Android's deep idle mode), setting stricter Doze constants, turning radios off in standby, and a profile tuned for Notes.
- **Verify:** before and after comparisons with the same test protocol, so no tweak is a placebo.

### 2. Settings Hub: one searchable place for everything Boox hides
- A searchable index of every Boox and Android setting, with direct links to each page. These include pages Boox hides, such as Android's own Settings, the app freeze manager and usage stats.
- Plain-language explanations ("what does *Smooth Refresh* cost you?").
- Explains where things live when Boox splits them (Dual Notes vs Split Screen, and so on).

### 3. E-ink profiles
- Back up, restore and share EInkWise per-app settings (MMKV `/onyxconfig`). EInkWise is Boox's own per-app refresh setting.
- Curated presets for popular third-party apps (Kindle, KOReader, Obsidian, OneNote, browsers).
- Later (T3): per-*activity* refresh modes (the OnyxTweaks feature, ported to Android 16).

### 4. Privacy & Debloat
- A crowd-sourceable **Onyx package knowledge base** (`knowledge/onyx-packages.json`). UAD (Universal Android Debloater) has *zero* `com.onyx.*` entries today. Each package gets a purpose, a risk rating and its battery impact.
- Debloat profiles (Minimal / Privacy / Aggressive). They always use `pm disable-user` or `uninstall --user 0`, so they can be reversed.
- Telemetry blocking (apps sending data about you to their makers) without root, through a local VPN or DNS filter in the style of NetGuard. With root, it uses iptables (T3).

### 5. Power-user actions & automation
- Extra actions for pressing and holding the side buttons, and Quick Settings tiles (B/W mode, full refresh, "deep standby").
- Workarounds for known firmware bugs (for example, the stylus freeze after pasting a screenshot in Notes).
- With root (T3): CPU governor and cap profiles, kernel wakeup analysis, and an Xposed module (a way to change how the system behaves).

## Non-goals
- Replacing Boox's Notes or NeoReader apps. Nib, the suite's drawing app, is for drawing. It borrows Notes' instant pen but has no notebooks, handwriting recognition or sync, and isn't meant to replace Notes for writing.
- Anything irreversible without an explicit confirmation that spells out the risk, and a tested restore path.
- Pirating or unlocking paid or region-locked Boox services.

## Principles
1. **Safety first:** every tweak has an `apply` and a `revert` step, and every change is logged in a journal on the tablet.
2. **Evidence over folklore:** a tweak ships only after its effect is measured on real hardware.
3. **Graceful tiers:** useful with no privileges (T0), and better with adb grants (T1), Shizuku (T2) or root (T3).
4. **E-ink-native UI:** monochrome, high contrast, no animations, large targets, paginated lists.
5. **GPL-3.0, upstream-friendly:** the project is open source under the GPL-3.0 licence, and pushes device support back to KOReader, the decryptBooxUpdateUpx keys and OnyxTweaks.
