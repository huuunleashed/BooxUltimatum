# 03 · Architecture

## Shape

One APK (`app.booxultimatum`), Kotlin + Jetpack Compose, minSdk 30, target/compile SDK 36 (Android 16). The UI is e-ink-first: monochrome, no ripples or animations, large targets, and pagination instead of scrolling where practical.

```mermaid
flowchart TB
  UI[Compose UI: Hub · Battery Doctor · E-Ink Profiles · Privacy · Tools]
  UI --> Tweaks[Tweak engine: apply / revert / verify, journal]
  Tweaks --> Exec[Privileged executor]
  Exec --> T0[T0 Android APIs + Onyx SDK]
  Exec --> T1[T1 adb-granted perms]
  Exec --> T2[T2 Shizuku UserService as uid 2000]
  Exec --> T3[T3 root su / Xposed module]
  UI --> KB[(Knowledge base: onyx-packages.json, settings index, presets)]
  UI --> Sampler[Power sampler: low-overhead, alarm-driven]
  Sampler --> Store[(Local DB: samples, runs, experiments)]
```

## Planned modules (single `:app` for now; split when the code grows)

| Module | Responsibility | Upstream it may borrow from |
|---|---|---|
| `:core-exec` | Privilege tier detection; `ShellExecutor` for T1/T2/T3 with a unified result type | Shizuku API (Apache-2.0), Canta's Shizuku usage (LGPL-3.0), Hail's freeze backends (GPL-3.0) |
| `:core-onyx` | Onyx SDK wrapper (EPD refresh, front light), MMKV `/onyxconfig` reader/writer, Onyx intents and hidden activities | onyx-intl/OnyxAndroidDemo (Apache-2.0), OnyxMMKVEditor (GPL-3.0), OnyxTweaks (GPL-3.0) |
| `:feature-battery` | Sampler, drain table, attribution parsers (batterystats checkin, power, alarm, jobs) | Battery Historian formats (reference only) |
| `:feature-debloat` | Knowledge-base driven profiles, reversible disable/uninstall | UAD-ng list schema (GPL-3.0), Canta |
| `:feature-privacy` | Local VPN DNS filter (no root), iptables (root) | NetGuard (GPL-3.0), AFWall+ (GPL-3.0) as references |
| `:feature-hub` | Searchable settings index with deep links | OnyxTweaks' shortcut list |
| `:xposed` (later, T3) | Android 16 port of selected OnyxTweaks hooks (per-activity refresh) | OnyxTweaks (GPL-3.0) |

## The tweak model

```kotlin
interface Tweak {
    val id: String                // "doze.remove-onyx-whitelist"
    val tier: Tier                // T0..T3
    val risk: Risk                // SAFE, CAUTION, EXPERT
    val evidence: String?         // link to knowledge/experiments.md entry
    suspend fun isApplied(): Boolean
    suspend fun apply(): Result<Unit>
    suspend fun revert(): Result<Unit>
}
```

Each apply writes a journal entry with the previous state, so reverting works even after an app reinstall (the journal is exported to shared storage).

## Power sampler design (must not cause the drain it measures)

- No foreground service and no partial wakelock. It uses `AlarmManager.setAndAllowWhileIdle` (inexact) with a 15-minute minimum during standby, and ~1-minute sampling only in an explicit "test run" mode.
- It reads `BatteryManager` (capacity, `CURRENT_NOW`, `CHARGE_COUNTER`), sticky `ACTION_BATTERY_CHANGED` (voltage, temperature, plugged), `PowerManager.isInteractive`, and elapsed realtime vs uptime (the gap is time suspended).
- For lab runs we prefer the host-launched shell logger (`tools/host/power-logger.ps1`) because it involves zero app code.
