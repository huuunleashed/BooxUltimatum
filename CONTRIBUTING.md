# Contributing to BooxUltimatum

Thanks for looking. BooxUltimatum is a one-person spare-time project. It is best tested on the maintainer's Note Air6 C (firmware 4.3), and owners of other Boox tablets are already reporting how it behaves on theirs. Every careful report and every finding from another tablet moves it forward. This page covers what's most useful, how to set up, and the few rules that keep the app safe. [`AGENTS.md`](AGENTS.md) holds the complete rules.

## Where help is wanted

In rough order of value:

1. **Findings from other Boox tablets and firmware.** Does the sleep screen work on your Note Air 5 C, Tab Ultra or Go 10.3? Does Instant ink start? What does `dumpsys oec_service` print? (`dumpsys` is an adb command that prints what an Android service reports.) Use the *Device finding* issue form.
2. **Bug reports** with tablet, firmware, orientation and steps. Use the *Bug report* form.
3. **Battery measurements:** an overnight run with BooxUltimatum's battery log and its exported zip, before and after a tweak.
4. **Translations:** the hub and the shared kit read in Simplified Chinese, and the maintainer's own Vietnamese is on the way. A new language is a folder and a set of strings: the convention, and what stays in English, are in [`docs/12-languages.md`](docs/12-languages.md). Strings live in `hub/src/main/res/values/strings*.xml` (and each `kit/*/src/main/res` for shared strings; Nib's are in `nib/src/main/res`).
5. **Code:** start with an issue labelled `good first issue`, or open an *Idea* issue first so you and the maintainer can agree on the approach before you spend time on it.

## Reporting a bug or a finding

- **The quickest way:** open Device › Report a problem or idea in BooxUltimatum. It shows exactly what each part of the report adds, and nothing is sent until you tap Open on GitHub or Share.
- **Say what you have:** the tablet model, the firmware version (Settings › About), the Android version, and BooxUltimatum's version (Device › Installed version) or the commit you built.
- **For a bug:** what you did, what you expected, and what happened. Portrait or landscape can matter, so mention which you used.
- **For a finding:** what you measured and how, for example `dumpsys` output, a logcat excerpt or the battery log export.
- **Remove personal data first:** network names, account names, notification text, book titles, and your tablet's serial number.
- **Security problems** go to [private vulnerability reporting](https://github.com/huuunleashed/BooxUltimatum/security/advisories/new), not a public issue. See [`SECURITY.md`](SECURITY.md).

## Development setup

You'll need JDK 21, the Android SDK (platform 36, build-tools 36) and platform-tools, which include `adb`, Android's debugging tool for talking to the tablet from a computer. Android Studio works but isn't required.

```powershell
git clone https://github.com/huuunleashed/BooxUltimatum.git
cd BooxUltimatum
.\gradlew.bat assembleRelease lintRelease   # macOS/Linux: ./gradlew assembleRelease lintRelease
adb install -r hub\build\outputs\apk\release\hub-release.apk
```

- **Signing:** builds from source are debug-signed, so they update an earlier build of your own in place and keep its grants. They can't replace a copy installed from Releases, which is signed with the maintainer's release key. Android refuses that update, and uninstalling the copy loses its data and grants. Only the maintainer's release key signs public releases.
- **Access tiers:** to test T1 and T2 features, grant the permissions listed in the README (T1, adb grants) and start Shizuku (T2). `.\tools\host\start-shizuku.ps1` starts it again after the tablet restarts.
- **Code map:** the module layout is in `AGENTS.md` and `docs/07-suite.md`, and the design docs in `docs/` explain each area.
- **Working with an AI agent:** the `.agents/skills/` folder holds [Agent Skills](https://agentskills.io) that most coding agents load on their own: how to test on the tablet safely, the firmware interfaces found so far, how sleep faces are built, and how to build and release. They save a new session from rediscovering all of it, so please update them when you learn something new.

## The rules for changes

1. **Reversible and tiered.** Every tweak implements `apply` and `revert`, and declares a minimum tier (T0 app, T1 adb grant, T2 Shizuku; root is out of scope) and a risk level. Package changes must be reversible (`pm disable-user --user 0` or `pm uninstall -k --user 0`). Nothing may touch system partitions or the bootloader.
2. **Measured.** A tweak ships once its effect is measured with the protocol in `docs/02-reverse-engineering-plan.md` and recorded in `knowledge/experiments.md`. Unverified device facts are marked *[verify]*.
3. **E-ink friendly.**
   - Black on white with one green accent.
   - No animation or ripples, and touch targets of 48 dp or more.
   - No background polling: refresh on user action, system broadcasts or inexact non-wakeup alarms.
   - Every screen works in portrait and landscape, and keeps its state through a rotation.
   - Counts use `plurals`.
4. **Clean-room.** Don't copy Onyx's code or bundle their SDKs. Call firmware interfaces through small clients of the project's own and document the evidence. Record any other third-party code or assets in `THIRD_PARTY.md`.
5. **Honest copy.** Describe what's verified, and say plainly what isn't. No invented numbers, and say which tablet a measurement came from.

## Pull requests

1. **Branch** from `main` and keep the change focused: one feature or fix per pull request.
2. **Check before pushing:** `.\gradlew.bat assembleRelease lintRelease` passes, and so does `python tools\dev\prose_wrap.py`. The same checks run on every pull request.
3. **Changelog:** add a line under *[Unreleased]* in `CHANGELOG.md`.
4. **Describe it:** fill in the template, saying what it does for the person holding the tablet and which tablet and firmware you tested on. "Not tested on a tablet" is an honest answer; just say so.
5. **Screenshots** of UI changes help. Remove personal data from them.

## Writing rules

1. **No hard-wrapped prose.** Write each paragraph, list item and blockquote as one logical line and let the editor wrap it. The exceptions are code comments and places where a line break is actually needed: code blocks, tables, output samples, and intentional Markdown line breaks. Check with `python tools/dev/prose_wrap.py`; fix with `--fix`.
2. **Plain words.** Say what a change does before how it works. Prefer short sentences and concrete numbers with their source.
3. **The Words section of `AGENTS.md`.** Anyone writing text that people read (the apps' strings, the README, the site, the release notes, or a doc that describes a feature) should read the *Words* section of [`AGENTS.md`](AGENTS.md) first. It says who the words are for, the voice, how to describe a feature and how to name things. Its table gives one term for each thing, for example *tablet* rather than *device*, *Turn on* rather than *enable*, and *home app* rather than *launcher*.

## Adding a language

The convention is in [`docs/12-languages.md`](docs/12-languages.md), and the short version is this.

1. **Make the folder.** `hub/src/main/res/values-<tag>/`, with the same file names as the English folder beside it, and the same keys in the same order. The tag is specific: `vi`, `zh-rCN`, `zh-rTW`. Do not make a generic `values-zh`.
2. **Translate the strings.** Every string the English side has, and no others. The values in a string stay as they are: `%1$s` stays `%1$s`. Leave the brand names, the units, the paths, the log tags and the font samples in English, and leave a string marked `translatable="false"` alone.
3. **Give the plurals the forms the language has.** Chinese and Vietnamese have one, `other`; English and most European languages have two, `one` and `other`.
4. **Keep the glossary.** The terms the language uses for the suite's own things are in `docs/12-languages.md`, one table per language. A language that says a thing in two ways is as hard to read as an English string that uses two names for it, so a new term goes in the table too.
5. **Tell the app about it.** Add the language to `SuiteLanguage` in `kit/core`, with the resource folder, the BCP 47 name Android uses (`zh-CN`) and the language in its own words, and add it to the app's `res/xml/locales_config.xml`. An app offers a language when it has the words for it, so Nib needs its own folder before it can offer it.
6. **Check it.** `python tools/dev/check_translations.py` reports anything that does not line up, and `--report` says what each language covers. The same check runs on every push.

## Conduct

Be kind and assume good faith. Critique the work, not the person. Harassment, insults or personal attacks aren't welcome, and the maintainer may remove comments or block accounts that don't follow this. For anything serious, contact the maintainer privately through GitHub.

## Licensing

By contributing, you agree that your contribution is licensed under GPL-3.0-or-later, like the rest of the project.
