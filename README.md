# BooxUltimatum

A single sideloadable Android app that is meant to become the go-to hub of tweaks and tricks for the **BOOX Note Air6 C** (and, eventually, other Boox tablets). Its goals are longer battery life, simpler access to Boox's scattered settings, and privacy. Every tweak will be measured, explained, and reversible. The project is licensed GPL-3.0-or-later so we can build on the open-source Boox community's work.

> This README changes as the project moves. It's the single place to see where things stand. Agents and contributors update it with every meaningful change (see `AGENTS.md`).

## Status

| Area | State |
|---|---|
| Phase | **0 → 1:** desk research done, waiting for the device (arriving 2026-09-25 afternoon) |
| Device facts | Pre-device research only. Items marked *[verify]* in `docs/00-device-research.md` are pending the first recon. |
| App | `0.1.0-probe` builds. It has Device, Battery, Packages and Privileges tabs and a "Share report" button. No tweaks yet. |
| Host tooling | `recon.ps1`, `power-logger.ps1` and `pull-apks.ps1` are written but haven't been run on a real device yet |
| Root | Not attempted. Feasibility is Phase 5 (no public Q‑6690 loader, no NoteAir6C firmware key yet). |

### Next up
1. Unboxing session: capture the factory-firmware baseline (`recon.ps1 -Label factory`) before any OTA.
2. Install the probe APK, grant the T1 permissions, and check the day-1 questions in `docs/02-reverse-engineering-plan.md` §Phase 1.
3. Overnight B0 standby run with `power-logger.ps1`.

## Why this exists

The most credible independent review ([eWritable](https://ewritable.net/brands/boox/tablets/boox-note-air6-c/)) calls the NA6C an excellent, versatile tablet held back by **poor battery life** (≈2 %/h awake idle, ≈10 %/h in native Notes on FW 4.3) and by **complex, scattered software**. BooxUltimatum goes after exactly those two problems, plus privacy. See `docs/01-product-vision.md`.

## Repository map

| Path | What |
|---|---|
| `docs/00-device-research.md` | NA6C hardware/software research, the NA5C comparison, known issues, and privilege tiers |
| `docs/01-product-vision.md` | Pillars: Battery Doctor, Settings Hub, E-Ink Profiles, Privacy & Debloat, Power-user |
| `docs/02-reverse-engineering-plan.md` | The phased plan for testing together, and the battery test protocol |
| `docs/03-architecture.md` | App architecture, tweak model, and sampler design |
| `docs/04-upstream-projects.md` | Open-source projects we fork or borrow from, with their licenses |
| `app/` | Android app (Kotlin, Jetpack Compose, e-ink-first UI) |
| `knowledge/` | Onyx package knowledge base (also bundled as app assets) and the experiments log |
| `tools/host/` | PowerShell scripts run from the PC over adb |
| `tools/dev/` | Repo hygiene tools (for example `prose_wrap.py`) |
| `captures/` | Raw device captures (git-ignored, may contain personal data) |

## Quick start

Prerequisites (already installed on the dev PC): JDK 21, Android SDK (platform 36, build-tools 36) in `%LOCALAPPDATA%\Android\Sdk`, and platform-tools on `PATH`.

```powershell
# Build the probe APK
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot'
.\gradlew.bat assembleDebug          # -> app\build\outputs\apk\debug\app-debug.apk

# With the tablet plugged in (USB debugging on)
.\tools\host\recon.ps1 -Label factory
adb install -r app\build\outputs\apk\debug\app-debug.apk
.\tools\host\power-logger.ps1 -Action Start -Interval 60   # then unplug
```

## Privilege tiers

Every feature declares the minimum tier it needs and degrades gracefully without it. **T0** is the plain app. **T1** is permissions granted once over adb. **T2** is Shizuku (shell uid). **T3** is root (Magisk/KernelSU and LSPosed). The core product must stay useful at T0–T2.

## Changelog

- **2026-09-25:** Project initialised. Device research, product vision, reverse-engineering plan, architecture, and upstream survey written. Android toolchain installed. Probe app `0.1.0-probe` scaffolded and building. Host recon, power-logger and APK-pull scripts added. Package knowledge base seeded from the NA3C community analysis. Repo rules added (no hard-wrapped prose, reversible tweaks, GPL-3.0).

## License

GPL-3.0-or-later (see `LICENSE`). Third-party code and attributions are listed in `THIRD_PARTY.md`.
