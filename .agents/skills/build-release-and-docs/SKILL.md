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

## Kotlin pitfalls seen here

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
3. Build release-signed with `.\gradlew.bat :<module>:assembleRelease -Pbu.signing=$env:USERPROFILE\.booxultimatum\signing.properties`. Check the signer with apksigner: the certificate SHA-256 is `75dbdea9807374ce0a269432528187798d9f662e4fbf8953129f2993d99a2121`.
4. Commit only when the owner asks, and push before publishing: `gh release create` tags the pushed commit.
5. Name tags and assets exactly:

   | Kind | Tag | Asset |
   |---|---|---|
   | Hub | `vX.Y.Z` | `BooxUltimatum-X.Y.Z.apk` |
   | Nib | `nib-vX.Y.Z` | `Nib-X.Y.Z.apk` |
   | Test build | `test-<hub or nib>-X.Y.Z-test.N` | `test-BooxUltimatum-…apk` or `test-Nib-…apk` |

   Publish with `--prerelease`, and don't combine it with `--latest` (HTTP 422).
6. Download the asset anonymously, compare its SHA-256 with the local file, and check that the CI and Pages runs pass.
7. Never publish a debug-signed build, and never install a release-signed build over the development tablet's debug-signed copy.

The in-app updater (`kit/update`: `ReleaseFeed`, `Updates`) reads the latest 50 releases and knows every tag above. It takes the SHA-256 from GitHub's asset `digest`, from a `.sha256` asset, or from a line of the notes. It refuses another package name, an older version or another signing key, and it's off in debug-signed builds. Test builds are offered only when the owner turns on *Offer test builds*, and a release always outranks its own test build. Hubs from before the suite (0.5.x) read only the latest 10 releases and match only `BooxUltimatum-<tag without v>.apk`, so they never see test builds or Nib. But more than ten such releases in a row could hide a hub release from them. The 0.5.0 to 0.5.1 update was verified end to end on 2026-09-27: Android asks once to allow installs from the app, then updates in place.

## Moving a tablet from the debug-signed copy to a release

Android refuses a release-signed APK over a debug-signed install, and uninstalling loses the data and grants. With the owner's agreement, this keeps both:

1. Record the grants first (read-only): `dumpsys package app.booxultimatum` (the `granted=true` lines), `cmd appops get app.booxultimatum`, `settings get secure enabled_accessibility_services`, `cmd role get-role-holders android.app.role.HOME`, and any widgets the launcher hosts (`dumpsys appwidget`).
2. `assembleDebug` (debuggable, same debug key) and `adb install -r` it over the development copy, then `run-as app.booxultimatum sh -c "cd /data/data/app.booxultimatum && tar -cf - files no_backup shared_prefs" > /data/local/tmp/bu.tar` and pull the tar into the session folder (it holds personal data; never into the repository).
3. `adb uninstall app.booxultimatum`, then install a restore helper: `assembleDebug` signed with the release key through `-Pandroid.injected.signing.store.file=… -Pandroid.injected.signing.store.password=… -Pandroid.injected.signing.key.alias=… -Pandroid.injected.signing.key.password=…` (read from `signing.properties`, never printed). `android.injected.version.code` is ignored by AGP 8.13, so to match an older release set `versionCode` in `hub/build.gradle.kts` for that one build and revert it at once.
4. `run-as … tar -xf /data/local/tmp/bu.tar` in the data folder, delete the tar, then `adb install -r` the published APK of the same versionCode over the helper: same key, data kept, no longer debuggable.
5. Re-apply the grants: `pm grant` (DUMP, READ_LOGS, WRITE_SECURE_SETTINGS, READ_CALENDAR), `appops set … GET_USAGE_STATS allow`, `RUN_ANY_IN_BACKGROUND` `ignore` then `allow` (Boox's full-access list), and the accessibility string as it was. The shell can't enable the home alias of a non-debuggable app (`Shell cannot change component state`), so the owner makes it the home screen again from the Hub. Hosted widgets must be added again, and Shizuku asks once more.

## Documentation after every meaningful change

- `CHANGELOG.md` *[Unreleased]*, grouped as Added, Changed, Fixed and Research, written for users: what they'll see.
- `README.md`: features, the tiers table, known limits, the roadmap and the install line. Keep every claim honest and verified.
- `docs/0x-*.md`: the design doc for the area (`07-suite.md` for the hub, kit and releases; `08-nib.md` for Nib).
- `knowledge/experiments.md`: a dated row (the finding, how it was verified, what it means for the app) for every device fact, with unverified claims marked *[verify]*.
- `site/index.html` and `site/docs.html`, kept in step with the README.
- These skills in `.agents/skills/`: update them whenever you learn something that the next session would otherwise have to rediscover.
