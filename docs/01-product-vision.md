# 01 · Product vision

> **BooxUltimatum** is one sideloadable APK that makes a Boox tablet last longer, feel simpler,
> and stay private. Every tweak can be measured, explained and undone.

This vision is anchored on the most credible independent review of the NA6C (eWritable, FW 4.3). Its verdict: an *excellent, versatile device* held back by **poor battery life** and **complex, scattered software**. Those two problems are our north star.

## Pillars (in priority order)

### 1. Battery Doctor: measure → attribute → fix → verify
- **Measure:** an automatic "eWritable-style" drain table per usage state (screen-off standby, awake idle, reading, notes, front light). The on-device low-overhead sampler records level, current, voltage, temperature, suspend count and screen state.
- **Attribute:** wakelocks, alarms, jobs, wakeups per app, CPU time, top offenders (T2 via `dumpsys batterystats/power/alarm/jobscheduler/deviceidle`, and T3 via kernel `wakeup_sources`).
- **Fix:** one-tap, reversible actions: freeze/disable, restrict background (`RUN_ANY_IN_BACKGROUND`), set the standby bucket, trim the Doze whitelist, stricter Doze constants, radios off in standby, a Notes-optimised profile.
- **Verify:** before/after comparisons with the same test protocol, so no placebo tweaks.

### 2. Settings Hub: one searchable place for everything Boox hides
- A searchable index of every Boox and Android setting, with deep links (including hidden activities such as stock Android Settings, the app-freeze manager and usage stats).
- Plain-language explanations ("what does *Smooth Refresh* cost you?").
- Explains where things live when Boox splits them (Dual Notes vs Split Screen, and so on).

### 3. E-Ink Profiles
- Back up, restore and share EInkWise per-app settings (MMKV `/onyxconfig`).
- Curated presets for popular third-party apps (Kindle, KOReader, Obsidian, OneNote, browsers).
- Later (T3): per-*activity* refresh modes (the OnyxTweaks feature, ported to Android 16).

### 4. Privacy & Debloat
- A crowd-sourceable **Onyx package knowledge base** (`knowledge/onyx-packages.json`). UAD has *zero* `com.onyx.*` entries today. Each package gets a purpose, a risk rating and battery impact.
- Debloat profiles (Minimal / Privacy / Aggressive), always done with `pm disable-user` or `uninstall --user 0` so they're reversible.
- Telemetry blocking without root (a local VPN/DNS filter, NetGuard-style), and with iptables (T3).

### 5. Power-user actions & automation
- Extra side-button long-press actions, quick-settings tiles (B/W mode, full refresh, "deep standby").
- Workarounds for known firmware bugs (for example, the stylus freeze after pasting a screenshot in Notes).
- Root tier: CPU governor/cap profiles, kernel wakeup analysis, and an Xposed module.

## Non-goals
- Replacing Boox's Notes or NeoReader apps.
- Anything irreversible without an explicit, scary confirmation and a tested restore path.
- Pirating or unlocking paid or region-locked Boox services.

## Principles
1. **Safety first:** every tweak has an `apply` and a `revert` step, and every change is logged in an on-device journal.
2. **Evidence over folklore:** a tweak ships only after its effect is measured on real hardware.
3. **Graceful tiers:** useful with no privileges (T0), and better with adb grants, Shizuku or root.
4. **E-ink-native UI:** monochrome, high contrast, no animations, large targets, paginated lists.
5. **GPL-3.0, upstream-friendly:** push device support back to KOReader, the decryptBooxUpdateUpx keys, and OnyxTweaks.
