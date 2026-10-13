# 07 · The suite: hub, kit and apps

BooxUltimatum is no longer one app. It's a suite: the BooxUltimatum app itself, which holds the tools and modules, separate apps that build on the same foundations, and a kit of libraries they all share. This document describes how it's put together. The drawing app, Nib, has its own document ([`08-nib.md`](08-nib.md)).

## The pieces

```mermaid
flowchart TB
  subgraph Apps
    Hub[":hub · app.booxultimatum<br/>tools, modules, suite manager"]
    Nib[":nib · app.booxultimatum.nib<br/>drawing"]
  end
  subgraph Kit
    Core[":kit:core<br/>tablet profile, suite registry, permission"]
    Log[":kit:log<br/>logbook, crash capture, export"]
    UI[":kit:ui<br/>Braun Instrument design system"]
    Ink[":kit:ink<br/>SurfaceFlinger pen path, pen node, PenSession"]
    Update[":kit:update<br/>releases, verification, installs"]
  end
  Engine[":nib-engine<br/>pure Kotlin drawing model"]
  Hub --> Core & Log & UI & Ink & Update
  Nib --> Core & Log & UI & Ink & Update & Engine
  Ink --> Core
  Update --> Core
  Core --> Log
```

| Module | Package | What it holds |
|---|---|---|
| `:hub` | `app.booxultimatum` | BooxUltimatum itself. Its application id and data stayed the same through the split, so installed copies update in place. |
| `:nib` | `app.booxultimatum.nib` | Nib, the drawing app |
| `:nib-engine` | `app.booxultimatum.nib.engine` | Nib's strokes, brushes, layers, undo and file format, with no Android code, so it's tested on the JVM (a Java virtual machine on a computer) |
| `:kit:core` | `app.booxultimatum.kit.core` | `Tablet` (the tablet profile), `SuiteApp` and `Suite` (the registry, certificates, open and uninstall), `AppWork`, `PenNodes`. Its manifest declares the suite permission and package visibility for every app. |
| `:kit:log` | `app.booxultimatum.kit.log` | `Logbook`, `Logger`, crash and exit capture, `Housekeeping` (storage limits and cleanup), the provider other suite apps read logs and storage through, `Redact` |
| `:kit:ui` | `app.booxultimatum.kit.ui` | The theme, components and glyphs, the Archivo font and its licence |
| `:kit:ink` | `app.booxultimatum.kit.ink` | `SurfaceInk` (the display calls), `PenInput` (the pen's kernel node), `PenDisplay` and `PenSession` (a session for an app drawing in its own window) |
| `:kit:update` | `app.booxultimatum.kit.update` | `Version`, `ReleaseFeed`, `Updates` (check, download, verify, install), `UpdateText` |

The kit never depends on an app, and apps never depend on each other. BooxUltimatum's Shizuku support stays in `:hub`. Shizuku is a helper app that lets other apps run commands with the shell's privileges once you start it. `SurfaceInk.connect` takes an optional `ElevatedRoute`, which Instant ink supplies and Nib doesn't need.

## Apps and modules

BooxUltimatum's Suite page shows two kinds of parts.

- **Apps** are separate APKs, such as Nib. BooxUltimatum installs them from GitHub Releases with the same checks as its own updates, opens them, updates them and uninstalls them (Android shows its own confirmation). Each works on its own. BooxUltimatum only adds updates, one log export and the Suite page.
- **Modules** are built into BooxUltimatum: Home screen, Sleep screen, Instant ink and the Battery log. Removing one stops its work and undoes what it changed, through the same steps as its own switches (`core/suite/Modules.kt`), and its pages leave the menu. Adding it back restores the pages. Home screen, Sleep screen and Instant ink stay off until you turn them on, though Instant ink's Quick Settings tiles return. The Battery log starts recording again.

| Module | Removing it |
|---|---|
| Home screen | If BooxUltimatum home is the default home app, switches back to the previous one, normally Boox's. With Shizuku that switch is direct, and the removal stops if it fails. Without Shizuku, Android's Default home app page opens for you to choose, and the removal goes on without waiting for your choice. Then it turns off the home alias. |
| Sleep screen | Turns off live updates (the sleep screen refreshing while asleep) and the refresh alarm, turns its accessibility service off (live updates use it to hold the picture on screen while the tablet is locked), and restores the Boox sleep screen and power-off screen |
| Instant ink | Turns it off, recovers the screen, and turns off its Quick Settings tiles (the Instant ink switch and Clean screen) |
| Battery log | Stops recording; past logs stay |

Instant ink treats suite apps like Boox's own apps: it pauses over them and never ends a session there, since Nib runs the pen path itself. Suite apps can't be picked for Instant ink.

## Navigation

The navigation rail has five sections, and a section with several pages shows them as tabs. Pages keep their names, so `MainActivity.EXTRA_DESTINATION` links from the home screen, the Quick Settings tile and notifications still work.

| Section | Pages |
|---|---|
| Overview | Overview |
| Suite | Apps and modules, Home screen, Sleep, Ink |
| Battery | Today (`Destination.Battery`), History, Causes, Health |
| System | Tweaks, Apps, Appearance, Fonts, Settings |
| Device | Device (this tablet and BooxUltimatum's updates), Access, Logs, Storage |

## Trust between the apps

Every public build of every suite app is signed with the same release key. `:kit:core`'s manifest declares `app.booxultimatum.permission.SUITE` in every app as a signature permission, one that only apps signed with the same key can hold. No other app can claim the name first. The permission guards each app's log provider. A consequence: a debug-signed suite app can't be installed next to a release-signed one, since Android refuses a permission declared by apps with different keys.

An APK is installed only if it passes these checks:

- Its SHA-256 checksum matches the release.
- Its package is the expected one.
- It's newer than the installed copy and signed like it.
- It's signed with the installing app's own key.

Development builds are signed differently, so their updater stays off.

## Releases and the test channel

Each app has its own version and tag. `tools/dev/publish.ps1` builds, checks the signing certificate, writes the checksum and publishes.

| Kind | Tag | Asset |
|---|---|---|
| BooxUltimatum release | `vX.Y.Z` | `BooxUltimatum-X.Y.Z.apk` |
| Nib release | `nib-vX.Y.Z` | `Nib-X.Y.Z.apk` |
| Test build | `test-<hub or nib>-X.Y.Z-test.N` | `test-BooxUltimatum-…apk` or `test-Nib-…apk` |

Each APK also gets a `.sha256` file and the R8 mapping file it was built with (zipped), so a crash from a release can be traced back to the source.

- **Test builds** are unfinished work, published so the maintainer can check them on a real tablet while away from the computer. They're offered only when *Offer test builds* is on (Device page), and a release always outranks its own test build (0.6.0-test.3 comes before 0.6.0).
- **BooxUltimatum versions from before the suite** (0.5.x) look only for `BooxUltimatum-<tag without v>.apk`, and read only the latest 10 releases. So they never see a test build or a Nib release. But a run of more than ten such releases in a row could hide a BooxUltimatum release from them. The current updater reads 50.

## Logging

Every suite app writes a logbook in every build, release or debug (`:kit:log`).

- **What's recorded.**
  - Each entry is one JSON line: time, level, category, message, fields, thread, process and a per-launch session id. Each file starts with a header that names the app, its version and the tablet.
  - Crashes are written at once, with the last 200 entries.
  - At the next start, Android's record of why the process ended (a crash, an ANR (app not responding), low memory, a force-stop) is logged. The ANR trace or native tombstone (a crash dump from native code) is saved beside it.
- **What it costs.**
  - Calls never wait for the disk. One writer thread batches the entries, and the queue drops routine entries first if it ever fills.
  - The pen path logs one summary per stroke, never per sample.
- **Levels.** Info and above always reach the file, and so does Debug for `ink`, `nib.pen`, `nib.render`, `update` and `suite`. *Detailed logging* (Device › Logs) raises every suite app to Verbose for 24 hours.
- **Where it's kept.** `noBackupFilesDir/logs/`, eight files of 1 MB at most, and nothing older than 14 days.
- **Privacy.** No drawing content, typed text, or account or network names. File names are hashed, and package names are kept only for suite and Boox apps.
- **Sharing.** Device › Logs › *Share logs* makes one zip with BooxUltimatum's logs, every other suite app's logs (read through its provider) and a summary of the tablet. Problem reports quote the latest entries.

## Storage

No suite app may fill the tablet over time. `Housekeeping` in `:kit:log` runs by itself at most every 12 hours, when the logbook starts:

| Area | Limit |
|---|---|
| Cache (`cacheDir`, `externalCacheDir`) | Files older than three days go, then the oldest until it's under 64 MB |
| Downloaded updates (`cache/updates`) | Older than a day |
| Shared exports (log zips, shared pictures, pen recordings) | Older than a week, and all but the newest five |
| Logs and reports | At most 8 files of 1 MB, nothing older than 14 days, the newest 20 crash and 10 exit reports |

Your own data is registered with `Housekeeping.registerData` so it's counted, and it's never cleared. That is Nib's drawings, and BooxUltimatum's battery log, sleep screen pictures and installed fonts. Device › Storage shows each app's areas (other apps answer through their provider, which only apps signed with the suite key may call). It can clear temporary files, or logs and reports, per app.

## Testing without the tablet

- **Unit tests** run on the JVM: `.\gradlew.bat testDebugUnitTest :nib-engine:test`. They cover the logbook, release parsing and version order (including what a 0.5 version of BooxUltimatum would see), the pen session's display calls against a fake display, and the whole drawing engine.
- **An emulator** shaped like the Note Air6 C runs the UI in both orientations: 1860 × 2480 at 300 dpi, Android 36, created as the `NoteAir6C` AVD (Android Virtual Device). It has no Boox firmware, so Sleep and Ink are hidden there and Nib draws without the display preview.
- **The display's pen path needs the real tablet.** Test builds go out on the test channel, and the maintainer sends back the log export.
