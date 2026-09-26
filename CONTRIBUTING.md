# Contributing to BooxUltimatum

Thanks for looking. BooxUltimatum is a one-person spare-time project that runs on one tablet so far (a Note Air6 C on firmware 4.3), so every careful report and every finding from another device moves it forward. This page covers what's most useful, how to set up, and the few rules that keep the app safe. [`AGENTS.md`](AGENTS.md) holds the complete rules.

## Where help is wanted

In rough order of value:

1. **Findings from other Boox devices and firmware.** Does the sleep screen broadcast work on your Note Air 5 C, Tab Ultra or Go 10.3? Does Instant ink arm? What does `dumpsys oec_service` show? Use the *Device finding* issue form.
2. **Bug reports** with device, firmware, orientation and steps. Use the *Bug report* form.
3. **Battery measurements:** an overnight run with the in-app battery log and its exported zip, before and after a tweak.
4. **Translations:** a Vietnamese translation is planned first. Strings live in `app/src/main/res/values/strings*.xml`.
5. **Code:** start with an issue labelled `good first issue`, or open an *Idea* issue first so we can agree on the approach before you spend time on it.

## Reporting a bug or a finding

- **Say what you have:** device model, firmware version (Settings › About), Android version, and BooxUltimatum's version (Settings in the app) or the commit you built.
- **For a bug:** what you did, what you expected, and what happened. Portrait or landscape matters on this device, so mention it.
- **For a finding:** what you measured and how, for example `dumpsys` output, a logcat excerpt or the battery log export.
- **Remove personal data first:** network names, account names, notification text, book titles, and your device serial.
- **Security problems** go to [private vulnerability reporting](https://github.com/huuunleashed/BooxUltimatum/security/advisories/new), not a public issue. See [`SECURITY.md`](SECURITY.md).

## Development setup

You'll need JDK 21, the Android SDK (platform 36, build-tools 36) and platform-tools (`adb`). Android Studio works but isn't required.

```powershell
git clone https://github.com/huuunleashed/BooxUltimatum.git
cd BooxUltimatum
.\gradlew.bat assembleRelease lintRelease   # macOS/Linux: ./gradlew assembleRelease lintRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

- **Signing:** builds from source are debug-signed, so they update your own copy in place and keep its grants. Only the maintainer's release key signs public releases.
- **Access tiers:** to test T1 and T2 features, grant the permissions listed in the README and start Shizuku (`.\tools\host\start-shizuku.ps1` restarts it after a reboot).
- **Code map:** the `app/` layout is in `AGENTS.md`; the design docs in `docs/` explain each area.

## The rules for changes

1. **Reversible and tiered.** Every tweak implements `apply` and `revert`, and declares a minimum tier (T0 app, T1 adb grant, T2 Shizuku; root is out of scope) and a risk level. Package changes must be reversible (`pm disable-user --user 0` or `pm uninstall -k --user 0`). Nothing may touch system partitions or the bootloader.
2. **Measured.** A tweak ships once its effect is measured with the protocol in `docs/02-reverse-engineering-plan.md` and recorded in `knowledge/experiments.md`. Unverified device facts are marked *[verify]*.
3. **E-ink friendly.**
   - Black on white with one green accent.
   - No animation or ripples, and touch targets of 48 dp or more.
   - No background polling: refresh on user action, system broadcasts or inexact non-wakeup alarms.
   - Every screen works in portrait and landscape, and keeps its state through a rotation.
   - Counts use `plurals`.
4. **Clean-room.** Don't copy Onyx/BOOX code or bundle their SDKs. Call firmware interfaces through small clients of our own and document the evidence. Record any other third-party code or assets in `THIRD_PARTY.md`.
5. **Honest copy.** Describe what's verified, and say plainly what isn't. No invented numbers.

## Pull requests

1. **Branch** from `main` and keep the change focused: one feature or fix per pull request.
2. **Check before pushing:** `.\gradlew.bat assembleRelease lintRelease` passes, and so does `python tools\dev\prose_wrap.py`. The same checks run on every pull request.
3. **Changelog:** add a line under *[Unreleased]* in `CHANGELOG.md`.
4. **Describe it:** fill in the template, saying what it does for the person holding the tablet and which device and firmware you tested on. "Not tested on a device" is an honest answer; just say so.
5. **Screenshots** of UI changes help. Remove personal data from them.

## Writing rules

1. **No hard-wrapped prose.** Write each paragraph, list item and blockquote as one logical line and let the editor wrap it. The exceptions are code comments and places where a line break is actually needed: code blocks, tables, output samples, and intentional Markdown line breaks. Check with `python tools/dev/prose_wrap.py`; fix with `--fix`.
2. **Plain words.** Say what a change does before how it works. Prefer short sentences and concrete numbers with their source.

## Conduct

Be kind and assume good faith. Critique the work, not the person. Harassment, insults or personal attacks aren't welcome, and the maintainer may remove comments or block accounts that don't follow this. For anything serious, contact the maintainer privately through GitHub.

## Licensing

By contributing, you agree that your contribution is licensed under GPL-3.0-or-later, like the rest of the project.
