# AGENTS.md

Guidance for AI agents and humans working in this repository. `.github/copilot-instructions.md` points here, so this file is the source of truth.

## The project in one line

BooxUltimatum is a GPL-3.0 Android app for the BOOX Note Air6 C: a home screen, sleep-screen designer, Instant ink layer, battery doctor and hub of measured, reversible tweaks. It also holds the reverse-engineering tooling and evidence behind them.

## Hard rules

1. **No hard-wrapped prose.** In Markdown and other prose, write each paragraph, list item and blockquote as one line. Line breaks are fine only in code comments and where they're needed: code blocks, tables, literal output, intentional Markdown line breaks. Run `python tools/dev/prose_wrap.py` before finishing, or `--fix` to repair.
2. **Keep the status pages current.** After any meaningful change:
   - Add an entry under *[Unreleased]* in `CHANGELOG.md`, grouped as Added, Changed, Fixed or Research.
   - Update `README.md` when a feature, limit, requirement or the roadmap changes.
   - Update the design doc in `docs/` that covers the topic.
3. **Tweaks are reversible and tiered.** Every tweak implements apply and revert, declares its tier (T0 app, T1 adb grant, T2 Shizuku, T3 root) and risk, and links its evidence in `knowledge/experiments.md` before it ships.
4. **The device is shared and real.**
   - Never run write or flash commands on a connected tablet unless the owner explicitly asks in the current session. That includes `pm uninstall`, `pm disable-user`, `settings put`, `service call` that changes state, `fastboot flash`, `edl w` and `reboot edl/bootloader`.
   - Read-only commands (`getprop`, `dumpsys`, `pm list`, `cat` on sysfs) are fine.
   - After any experiment, put the tablet back as it was: rotation, the Boox sleep style, EinkWise configs, and the display state (see *Recovering the screen* below).
5. **Evidence over folklore.** Mark unverified device facts *[verify]* until a test confirms them, then record the test in `knowledge/experiments.md`. Web specs and aggregator sites are leads to check, not facts.
6. **Privacy.**
   - `captures/` is git-ignored because it can hold personal data; commit only curated, anonymised summaries.
   - Never commit firmware images, `.upx` files, keystores or keys, or decryption keys that aren't already public.
   - Screenshots follow *Screenshots* below.
7. **Licensing.** The project is GPL-3.0-or-later. When you vendor upstream code or assets, keep their headers and record the repo, commit, license and files in `THIRD_PARTY.md`. Don't bundle binary-only SDKs, such as Onyx's obfuscated AARs; write a small clean-room client for the few calls needed instead.
8. **Honest copy.** The README and in-app text describe what is verified on the tablet, say plainly what isn't, and never claim results that weren't measured. The project isn't affiliated with Onyx/BOOX; keep that disclaimer.

## Layout

- `app/`: the Android app. Kotlin and Jetpack Compose, package `app.booxultimatum`, minSdk 30, target 36.
  - `core/`: platform readers, the journal, the battery log (`BatteryLog.kt`), the tablet font (`SystemFont.kt`, `UiFonts.kt`), `exec/` (the Shizuku executor), `tweaks/` (the framework and catalogue), `sleep/` (the sleep screen studio: spec, renderer, faces, publisher, scheduler) and `ink/` (Instant ink: SurfaceFlinger client, pen input, service).
  - `launcher/`: the home screen.
  - `ui/`: the e-ink theme, components, glyphs and screens.
- `docs/`: numbered design docs (`00` device research to `06` sleep screen) and `screenshots/` for the README.
- `knowledge/`: `experiments.md` (the evidence log) and `onyx-packages.json` (bundled into app assets via `sourceSets`).
- `tools/host/`: PowerShell scripts over adb (`common.ps1` holds shared helpers). `tools/dev/`: repository hygiene.
- **The GitHub side:**
  - Contribution rules are in `CONTRIBUTING.md` and the forms in `.github/ISSUE_TEMPLATE/`.
  - CI (`.github/workflows/build.yml`) builds, lints and checks prose on every push and pull request.
  - The landing page in `site/` deploys to GitHub Pages through `.github/workflows/pages.yml`, together with `docs/screenshots/` and `docs/brand/`.
  - Keep the landing page's claims in step with the README.
- `README.md` (the front page), `CHANGELOG.md` (the history), `CONTRIBUTING.md`, `SECURITY.md`, `THIRD_PARTY.md` and `PRODUCT.md` (design context).

## Commands

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot'   # JDK 21 (JDK 25 is too new for Gradle 8.14)
.\gradlew.bat assembleDebug        # debug build
.\gradlew.bat assembleRelease      # what the tablet runs: R8-minified, debug-signed so it updates in place
.\gradlew.bat lintRelease          # lint (version-upgrade warnings are known and intentional)
python tools\dev\prose_wrap.py     # prose rule check
.\tools\host\recon.ps1 -Label <name> [-Quick]
.\tools\host\start-shizuku.ps1     # after every tablet reboot (Shizuku doesn't survive reboots)
```

## Testing on the tablet

- Validate UI in **portrait and landscape**, and check that state survives a rotation. When auto-rotate is on, `settings put system user_rotation` is ignored; use `cmd window user-rotation lock <0|1>`, then `cmd window user-rotation free` afterwards.
- Screenshots come from `adb exec-out screencap -p`. The sleep screen can be captured while the tablet sleeps, but waking it lands on the owner's PIN lock screen, so capture it last.
- **Instant ink needs a person drawing.** Both SurfaceFlinger's pen reader and our service read the pen's kernel node (`/dev/input/event5`). Events injected with `input` never reach it, and the node is read-only for apps and the shell. Script the display calls, and ask the owner to draw and report.
- **Recovering the screen.** If a pen session leaves preview ink or holds frames, send these as the shell: `ENABLE_POST` on (`service call SurfaceFlinger 16711692 i32 -1 i32 1 i32 <pid>`), then pen state 0 (`16711693 i32 0 i32 0`), then auto-sync on (`1048722 i32 1`), then repaint everything (`16711700`). The app's *Recover screen* key does the same.
- **Never assume the tablet is unlocked.** Check `dumpsys window | grep isKeyguardShowing` first.

## Screenshots

`docs/screenshots/` holds the README images: PNGs taken on the tablet, scaled to 620 px wide (1000 px for landscape). Before committing one, remove anything personal:

- network names;
- account names;
- notification contents;
- book covers and titles (the Library widget is pixelated);
- notes and calendar events.

Retake them when a screen changes noticeably, and never edit one to show a feature that doesn't exist.

## Releases

- **Versions:** `versionName` follows semantic versioning (pre-1.0: minor for features, patch for fixes), and `versionCode` goes up by one with every release. Both live in `app/build.gradle.kts`.
- **Cutting a release:**
  1. Move *[Unreleased]* in `CHANGELOG.md` under the new version with its date.
  2. Update the README roadmap.
  3. Run lint and build.
  4. Tag `vX.Y.Z` and attach the APK to a GitHub Release.
- **Signing:** public releases are signed with a release key kept outside the repository. Build them with `.\gradlew.bat assembleRelease -Pbu.signing=<path to signing.properties>`, where that file holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; the maintainer's lives in `%USERPROFILE%\.booxultimatum\`. Without `-Pbu.signing` the build is debug-signed, which is what the development tablet runs. Never publish a debug-signed build, and never install a release-signed build over the development copy: the signatures differ, so Android refuses the update, and uninstalling loses the app's data and grants.
- **Release notes** state which device and firmware were tested, and repeat the known limits.

## UI conventions (e-ink)

- **Look:** pure black on white, with one green lamp as the accent. No ripples or animations, and large touch targets (48 dp or more).
- **Refresh:** no background polling; data refreshes only on user action, system broadcasts, or inexact non-wakeup alarms. Prefer pages to long scrolling lists where practical.
- **Text:** text entry on the home screen goes in a panel at the top, where the keyboard can't cover it. Strings that carry a count use `plurals`. Typographic apostrophes (’) go in string resources.

## Toolchain pins

AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21 and Compose BOM 2025.10.01. These older stable versions are pinned on purpose. Upgrading to AGP 9 or Kotlin 2.4 is a separate, deliberate task: don't mix it with feature work.
