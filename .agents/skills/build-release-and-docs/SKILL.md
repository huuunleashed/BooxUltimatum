---
name: build-release-and-docs
description: How to build, lint, version, sign, release and document BooxUltimatum, including the pitfalls met on Windows and PowerShell. Use whenever building the app, fixing build errors, cutting a release, publishing an APK, changing what the in-app updater expects, or updating the CHANGELOG, README, design docs, site, evidence log or these skills.
---

# Build, release and docs

[`AGENTS.md`](../../../AGENTS.md) holds the rules (prose, tiers, device, privacy, licensing); this skill is the practical side.

## Build

- Use JDK 21: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot'` (JDK 25 is too new for Gradle 8.14). The toolchain pins are deliberate: AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21. Platform levels live in `gradle/libs.versions.toml` (`compileSdk`, `minSdk`, `targetSdk`), and each module reads them from there.
- The build has apps (`:hub`, `:nib`), a pure-Kotlin module (`:nib-engine`) and the kit libraries (`:kit:core`, `:kit:log`, `:kit:ui`, `:kit:ink`, `:kit:update`). See `docs/07-suite.md` for what goes where: the kit never depends on an app.
- A quick check is `.\gradlew.bat :hub:compileDebugKotlin -q`; unit tests are `.\gradlew.bat testDebugUnitTest :nib-engine:test`; the full check is `.\gradlew.bat assembleRelease lintRelease`. Lint must report no errors. The version-upgrade, `UseKtx`, `PluralsCandidate` and `SdCardPath` warnings are known.
- Read lint as XML from `<module>\build\reports\lint-results-release.xml`, grouped by `id` and `severity`.
- `python tools\dev\prose_wrap.py` must print nothing (`--fix` repairs hard-wrapped prose).
- **Resources and the R class.** `android.nonTransitiveRClass` is on, so a resource moved into a kit module is referenced through that module's R, for example `app.booxultimatum.kit.ui.R.string.action_next` from the hub. Strings shared by several apps belong in the kit module that uses them.
- **Line endings.** The repository is LF (`.gitattributes`), but the file-creation tool on this machine writes CRLF. Normalise new files to LF before finishing, and remember that a `.Replace()` containing `` `n `` won't match a CRLF file.
- **Typographic apostrophes in an edit's anchor can come back plain.** Anchoring an edit on a string resource that contains `’` once turned it into `'`, and aapt then failed with "Invalid unicode escape sequence" for that resource. After editing `strings.xml`, check `git diff` shows only the lines you meant.
- **Scripts written with PowerShell's `>` are UTF-16**, which penlab and other tools read as garbage (`error unknown command`). Write them with `cmd /c "python … > file"` or `Set-Content -Encoding ascii`.

- **Renaming a resource folder needs `--rerun-tasks`.** After `git mv hub/src/main/res/mipmap-anydpi-v26 mipmap-anydpi`, a plain `:hub:clean :hub:assembleDebug` failed twice with `AAPT: error: resource mipmap/ic_launcher not found`, and `--rerun-tasks` then built it: the incremental resource merger and the build cache keep the old folder's entries. If a resource suddenly "doesn't exist" after a move, that is the reason, not the qualifier.
- **The hub's instrumented test** (`hub/src/androidTest/.../SleepOverlayTest.kt`) renders the sleep plates at panel size on a device or emulator (`.\gradlew.bat :hub:connectedDebugAndroidTest`) and drops PNGs in the app's external files. `connectedAndroidTest` uninstalls the app afterwards, so to keep those files, install both APKs by hand and run `adb shell am instrument -w -e class app.booxultimatum.core.sleep.SleepOverlayTest#writePlatePreviews app.booxultimatum.test/androidx.test.runner.AndroidJUnitRunner`, then `adb pull /sdcard/Android/data/app.booxultimatum/files/plates`. The Sleep page itself is hidden on the emulator (the module is `booxOnly` and the profile isn't Boox), which is why the render test exists.

## Building with several agents at once (2026-10-09)

One checkout can't take parallel Gradle builds, so the Battery revision used one git worktree per agent (`git worktree add E:\bu-wt\<id> -b bat-<id> <integration branch>`, outside the repository path with its spaces) and one machine-wide lock, `tools\dev\gradle-locked.ps1 <tasks> *> build.log`, which every agent builds through (a named mutex, 40 minutes' wait, JDK 21 set for it). The method that worked:

- Commit the shared contract first (the data classes in `core/battery/Model.kt`, and stub files with `TODO("<owner>")` bodies and the final signatures), fast-forward every worktree to it, and give each agent a disjoint list of files it owns and a resource file of its own for strings, with its own id prefix, so merges never conflict.
- Write the rules once in a file every agent reads (`E:\bu-wt\AGENT-RULES.md`): own files only, build only through the lock, never touch a device, normalise to LF, commit and report in a few lines.
- Merge each finished branch into the integration branch as it reports (`git merge --no-edit bat-<id>`), then compile and run the whole test suite before the next wave. Small, specific prompts finish; one agent given a very large task came back with nothing, so split by file.
- A stub's call sites compile before the real code exists, so pages that only call a plate can be written while the plate is still being built.
- `build.log` and `lint.log` are git-ignored; an agent that ran `git add -A` with one lying around once committed it.
- Lint's `MissingPermission` for `DropBoxManager.getNextEntry` (it wants `READ_DROPBOX_DATA`, a system-only permission) is a suppressed warning, not a false alarm, and the hub declares both permissions. Android 16's dropbox service checks `READ_DROPBOX_DATA` instead of `READ_LOGS` for an app that targets API 35 or later (`ENFORCE_READ_DROPBOX_DATA`), and `READ_DROPBOX_DATA` is `signature|privileged|development`, so the owner grants it over adb like `READ_LOGS`. For nine days the hub asked for `READ_LOGS` only and every read was a silent `SecurityException` (2026-10-12). A lint warning about a permission is worth reading in the platform source before it is called false. The two call sites carry `@SuppressLint("MissingPermission")` with that reason.
## Kotlin pitfalls seen here

- **A killed Gradle run poisons the next one.** Piping Gradle into `Select-Object -First N` stops the pipeline and kills the client mid-compile; the builds that follow then failed with a bogus `Unresolved reference` for a function that exists unchanged in `HEAD`, and another failed with no error line at all. Fix: `.\gradlew.bat --stop`, then `.\gradlew.bat :hub:clean`, then build again — don't go hunting for a code fault. Redirect long runs to a file (`*> build.log`) and read that.
- A local variable named `app` (for `applicationContext`) shadows the `app.booxultimatum` package, so fully qualified names fail with "Unresolved reference 'booxultimatum'". Import instead.
- The hub's `core/DeviceInfo.kt` already has a `DeviceProfile`; the tablet profile is `TabletProfile` in `:kit:core` (`app.booxultimatum.kit.core`), read with `Tablet.current`.
- minSdk is 30. Guard calls from API 31 up (`canScheduleExactAlarms`, `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`), and don't guard ones from API 30 or lower (lint flags `ObsoleteSdkInt`).

## PowerShell pitfalls

- Files in this repository mix CRLF and LF endings. A multi-line `.Replace()` matches only if the newlines match, so normalise the search text to the file's newline, or use an editing tool.
- `R` is a built-in alias (`Invoke-History`), so don't name a helper function `R`.
- Typographic apostrophes (’) inside single-quoted PowerShell strings break parsing; edit those files with an editing tool.
- Write files as UTF-8 without a BOM: `[IO.File]::WriteAllText($p, $text, (New-Object Text.UTF8Encoding $false))`.
- Don't pipe a long Gradle run into `Select-Object -First N`: it stops the pipeline and with it the build, so reports go missing. Redirect to a file (`*> build.log`) and read that instead.
- The first test build of the hub reaches a 0.5.x tablet only by hand (download the `test-BooxUltimatum-…apk` from its release page), since 0.5.x updaters can't see test builds. From then on, *Offer test builds* lets the hub find the next ones.

## Releases

Each suite app has its own version (`versionName` and `versionCode` in its module's `build.gradle.kts`) and its own tag. `tools/dev/publish.ps1 -App hub|nib -Notes <file> [-Test] [-DryRun]` does steps 3 to 6 below: it builds release-signed, checks the certificate, writes `dist\<asset>.apk`, its `.sha256` and the zipped R8 mapping (`-mapping.zip`), publishes a pre-release and checks the download against the local hash.

1. Bump `versionCode` by one and set `versionName`. Before 1.0, the minor number is for features and the patch number for fixes. A test build carries a pre-release suffix, such as `0.6.0-test.1`, and still needs a new `versionCode`.
2. Move *[Unreleased]* in `CHANGELOG.md` under the new version with its date, and update the README's install line (the APK name), roadmap and status. Test builds don't move the changelog; their notes list what to check on the tablet.
3. Write the release notes from the template and check them (see *Release notes* below): `python tools\dev\check_release_notes.py --preview <file>` (add `--test` for a test build). `publish.ps1` runs the same check and refuses notes that fail it.
4. Build release-signed with `.\gradlew.bat :<module>:assembleRelease -Pbu.signing=$env:USERPROFILE\.booxultimatum\signing.properties`. Check the signer with apksigner: the certificate SHA-256 is `75dbdea9807374ce0a269432528187798d9f662e4fbf8953129f2993d99a2121`.
5. Commit only when the owner asks, and push before publishing: `publish.ps1` refuses a dirty tree or an unpushed commit, and tags that exact commit with `--target` (since 2026-10-09; before, `gh release create` tagged the default branch's tip, which is wrong for a test build made from a branch). A test build can be published from a pushed branch without merging it.
6. Name tags and assets exactly:

   | Kind | Tag | Asset |
   |---|---|---|
   | Hub | `vX.Y.Z` | `BooxUltimatum-X.Y.Z.apk` |
   | Nib | `nib-vX.Y.Z` | `Nib-X.Y.Z.apk` |
   | Test build | `test-<hub or nib>-X.Y.Z-test.N` | `test-BooxUltimatum-…apk` or `test-Nib-…apk` |

   Publish with `--prerelease`, and don't combine it with `--latest` (HTTP 422).
6. Download the asset anonymously, compare its SHA-256 with the local file, and check that the CI and Pages runs pass.
7. Never publish a debug-signed build, and never install a release-signed build over the development tablet's debug-signed copy.

## Release notes

The convention is in [`AGENTS.md`](../../../AGENTS.md) (Releases); this is how to follow it. The notes are the GitHub release body, they are what someone reads before deciding to update, and the hub's updater shows their first 800 characters with Markdown marks and code blocks removed (`ReleaseFeed.plainNotes`; the marks it strips include `_`, so `SOME_PERMISSION` reads `SOMEPERMISSION`). They are not a log of the work: `CHANGELOG.md` and `knowledge/experiments.md` are, and they hold the dates, the figures and the identifiers.

**Method.** Write the changelog section first. Then copy `tools/dev/release-notes.template.md` (or `release-notes-test.template.md` for a test build) to a file outside the repository, and condense: group the changelog's entries by what a user can do or will notice, order them by how much that matters to them, and drop the story of how each was found. Run `python tools\dev\check_release_notes.py --preview <file>` until it says OK, and read the preview: that is all the updater shows.

**What each part is for.**
- **The summary** answers "what is this release about?" for someone who has never seen the bug or the incident. Name the areas or abilities (*makes the sleep screen and battery reporting more dependable, and lets apps Boox froze open from home*), not the occasion (*follows the night when the battery went from 62 % to 18 %*).
- **An item** is a capability or a behaviour, said from the user's side: a bold lead of a few words, then what it does and what it needs. Group related changes into one item, and keep to eight.
- **Tested** is the honest part: *Checked* names the device, the firmware and how (on the tablet, on the emulator, by unit tests); *Not yet seen* names every change that has not been seen working, or says "nothing".
- **Known limits** are what the app can't do or doesn't know yet, carried forward while they are still true.

**The same facts, written two ways** (from 0.10.0, whose first notes were rewritten to this form):

| Instead of | Write |
|---|---|
| *BooxUltimatum 0.10.0 follows the night of 2026-10-12, when the display stayed on while the tablet slept and the battery went from 62 % to 18 %…* | *BooxUltimatum 0.10.0 makes the sleep screen and the battery reporting more dependable, and lets apps Boox froze open from home and freeze again.* |
| *Today no longer lists a sleeping half hour as a stuck display. A battery sample that landed in the sleep screen's 1.3 second refresh made the whole next segment a Problem, even at 6 mA with the processor asleep…* | *Fewer false battery alarms. Today no longer reports a stuck display when the tablet was only refreshing its sleep screen.* |
| *The dropbox fix behind it. The hub could never read Android's dropbox on this firmware, because Android 16 asks for `READ_DROPBOX_DATA`…* | An item under *Before you update*: *On Android 16, grant the app one more permission once from a computer… Device › Access shows the command.* The cause goes in the changelog and `experiments.md`. |
| *Quiet hours: Pause from and Resume at steppers, and "Paused until 07:00" on the sleep screen.* | *Quiet hours you choose. Not at night on Sleep pauses live updates between two hours you set, and the sleep screen says when it is paused.* |

**Test builds** keep the same skeleton with *What changed* in place of *What's new*, and add *What to check*: numbered things to do on the tablet, each with the result that counts as good, ending with what to send back. They may say a little more than a release, but the summary still says what the build is about and that it is a test build, not a release.

**The checker** (`tools/dev/check_release_notes.py`, tested by `tools/dev/test_check_release_notes.py`) enforces the structure: the sections and their order, one summary paragraph under 400 characters, at most eight items each starting with a bold lead and under 450 characters, a *Tested* section with *Checked* and *Not yet seen* and a firmware, the CHANGELOG link and the disclaimer as the last lines, no title, no hard-wrapped prose, no links or code blocks the updater would garble, and the first item inside the 800 characters. It also refuses what makes notes read as a log: a date, a time of day, a file name, a constant such as a permission, a commit hash, or more than two measurements in one item. When it is wrong about a sentence, reword the sentence; the rules are meant to push detail into the changelog, not to be argued with.

The in-app updater (`kit/update`: `ReleaseFeed`, `Updates`) reads the latest 50 releases and knows every tag above. It takes the SHA-256 from GitHub's asset `digest`, from a `.sha256` asset, or from a line of the notes. It refuses another package name, an older version or another signing key, and it's off in debug-signed builds. Test builds are offered only when the owner turns on *Offer test builds*, and a release always outranks its own test build. Hubs from before the suite (0.5.x) read only the latest 10 releases and match only `BooxUltimatum-<tag without v>.apk`, so they never see test builds or Nib. But more than ten such releases in a row could hide a hub release from them. The 0.5.0 to 0.5.1 update was verified end to end on 2026-09-27: Android asks once to allow installs from the app, then updates in place.

## Moving a tablet from the debug-signed copy to a release

Android refuses a release-signed APK over a debug-signed install, and uninstalling loses the data and grants. With the owner's agreement, this keeps both:

1. Record the grants first (read-only): `dumpsys package app.booxultimatum` (the `granted=true` lines), `cmd appops get app.booxultimatum`, `settings get secure enabled_accessibility_services`, `cmd role get-role-holders android.app.role.HOME`, and any widgets the launcher hosts (`dumpsys appwidget`).
2. `assembleDebug` (debuggable, same debug key) and `adb install -r` it over the development copy, then `run-as app.booxultimatum sh -c "cd /data/data/app.booxultimatum && tar -cf - files no_backup shared_prefs" > /data/local/tmp/bu.tar` and pull the tar into the session folder (it holds personal data; never into the repository).
3. `adb uninstall app.booxultimatum`, then install a restore helper: `assembleDebug` signed with the release key through `-Pandroid.injected.signing.store.file=… -Pandroid.injected.signing.store.password=… -Pandroid.injected.signing.key.alias=… -Pandroid.injected.signing.key.password=…` (read from `signing.properties`, never printed). `android.injected.version.code` is ignored by AGP 8.13, so to match an older release set `versionCode` in `hub/build.gradle.kts` for that one build and revert it at once.
4. `run-as … tar -xf /data/local/tmp/bu.tar` in the data folder, delete the tar, then `adb install -r` the published APK of the same versionCode over the helper: same key, data kept, no longer debuggable.
5. Re-apply the grants: `pm grant` (DUMP, READ_LOGS, READ_DROPBOX_DATA, WRITE_SECURE_SETTINGS, READ_CALENDAR), `appops set … GET_USAGE_STATS allow`, `RUN_ANY_IN_BACKGROUND` `ignore` then `allow` (Boox's full-access list), and the accessibility string as it was. The shell can't enable the home alias of a non-debuggable app (`Shell cannot change component state`), so the owner makes it the home screen again from the Hub. Hosted widgets must be added again, and Shizuku asks once more.

## Documentation after every meaningful change

- `CHANGELOG.md` *[Unreleased]*, grouped as Added, Changed, Fixed and Research, written for users: what they'll see.
- `README.md`: features, the tiers table, known limits, the roadmap and the install line. Keep every claim honest and verified.
- `docs/0x-*.md`: the design doc for the area (`07-suite.md` for the hub, kit and releases; `08-nib.md` for Nib).
- `knowledge/experiments.md`: a dated row (the finding, how it was verified, what it means for the app) for every device fact, with unverified claims marked *[verify]*.
- `site/index.html` and `site/docs.html`, kept in step with the README.
- These skills in `.agents/skills/`: update them whenever you learn something that the next session would otherwise have to rediscover.
