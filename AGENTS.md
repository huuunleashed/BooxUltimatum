# AGENTS.md

Guidance for AI agents (and humans) working in this repository. `.github/copilot-instructions.md` points here, so this file is the source of truth.

## Project in one line

BooxUltimatum is a GPL-3.0 Android hub app of measured, reversible tweaks for the BOOX Note Air6 C, plus the host-side reverse-engineering tooling and knowledge base behind it.

## Hard rules

1. **No hard-wrapped prose.** In Markdown and other prose, write each paragraph, list item, and blockquote as one line. Hard breaks are fine only in code comments or where they're needed (code blocks, tables, literal output, intentional Markdown line breaks). Run `python tools/dev/prose_wrap.py` before finishing, or `--fix` to repair.
2. **Keep the README current.** `README.md` is the project's living status page. After any meaningful change, update its *Status*, *Next up*, and *Changelog* sections (newest first, dated).
3. **Tweaks are reversible and tiered.** Every tweak implements apply/revert, declares its tier (T0 app, T1 adb-grant, T2 Shizuku, T3 root) and risk, and links evidence in `knowledge/experiments.md` before it ships.
4. **The device is shared and real.** Never run write or flash commands on a connected tablet (`pm uninstall`, `pm disable-user`, `settings put`, `fastboot flash`, `edl w`, `reboot edl/bootloader`) unless the user explicitly asks in the current session. Read-only commands (`getprop`, `dumpsys`, `pm list`, `cat` on sysfs) are fine.
5. **Evidence over folklore.** Mark unverified device facts *[verify]* and cite the `captures/` folder name once they're confirmed. Web and aggregator specs are leads to check, not facts.
6. **Privacy.** `captures/` is git-ignored because it can hold personal data. Only commit curated, anonymised summaries. Never commit firmware images, `.upx` files, or decryption keys that aren't already public.
7. **Licensing.** The project is GPL-3.0-or-later. When you vendor upstream code, keep its headers and record the repo, commit, license, and files in `THIRD_PARTY.md`.

## Layout

- `app/`: Android app, Kotlin + Jetpack Compose, package `app.booxultimatum`, minSdk 30, target 36. `core/` holds platform readers and executors, `ui/` holds e-ink UI primitives.
- `knowledge/`: `onyx-packages.json` (bundled into app assets via `sourceSets`) and `experiments.md`.
- `tools/host/`: PowerShell scripts over adb (`common.ps1` holds shared helpers). `tools/dev/`: repo hygiene tools.
- `docs/`: numbered design docs. Update the relevant one when its topic changes.

## Commands

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot'   # JDK 21 (JDK 25 is also installed but too new for Gradle 8.14)
.\gradlew.bat assembleDebug        # build
.\gradlew.bat lintDebug            # lint (version-upgrade warnings are known and intentional)
python tools\dev\prose_wrap.py     # prose rule check
.\tools\host\recon.ps1 -Label <name> [-Quick]
```

## UI conventions (e-ink)

Pure black on white, no ripples or animations, large touch targets, and no background polling. Data refreshes only on user action or on scheduled, inexact alarms. Prefer pagination over long scrolling lists where it's practical.

## Toolchain pins

AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21, Compose BOM 2025.10.01. These are older stable versions pinned on purpose. Upgrading to AGP 9 / Kotlin 2.4 is a separate, deliberate task: don't mix it with feature work.
