# Contributing to BooxUltimatum

Thanks for looking. The project is young and runs on one tablet so far, so the most valuable help is evidence from other devices and careful bug reports. `AGENTS.md` holds the complete rules; this page is the short version.

## Reporting a bug or a finding

- **Say what you have:** the device model, firmware version (Settings › About), Android version, and BooxUltimatum's version (Settings in the app).
- **For a bug:** what you did, what you expected, and what happened. Portrait or landscape matters on this device, so mention it.
- **For a device finding:** what you measured and how, for example the `dumpsys` output, a logcat excerpt or the battery log export. Remove personal data first: network names, account names, notification text, book titles.

## Sending code

1. **Reversible and tiered.** Every tweak needs an `apply` and a `revert` step, a declared minimum tier (T0 app, T1 adb grant, T2 Shizuku, T3 root) and a risk level. Package changes must be reversible (`pm disable-user --user 0` or `pm uninstall -k --user 0`). Never remove packages from system partitions.
2. **Measured.** A tweak ships once its effect is measured with the protocol in `docs/02-reverse-engineering-plan.md`, and recorded in `knowledge/experiments.md`.
3. **E-ink friendly.** Black on white, no animation, big touch targets, and no background polling. Check both orientations.
4. **Checked.** Before a pull request, run `.\gradlew.bat assembleRelease lintRelease` and `python tools\dev\prose_wrap.py`, and add a line under *[Unreleased]* in `CHANGELOG.md`.

## Writing rules

1. **No hard-wrapped prose.** Write each paragraph, list item and blockquote as one logical line and let the editor wrap it. The only exceptions are code comments and places where a line break is actually needed: code blocks, tables, output samples, or an intentional Markdown line break. Check with `python tools/dev/prose_wrap.py` and fix with `--fix`.
2. **Unverified facts are marked.** Mark device facts *[verify]* until a test confirms them, then cite the test in `knowledge/experiments.md`.
3. **Plain words.** Say what a change does for the person holding the tablet before how it works.

## Licensing

By contributing you agree that your contribution is licensed under GPL-3.0-or-later, like the rest of the project. Record code or assets taken from other projects in `THIRD_PARTY.md`, with the source repo, commit, license and files involved.
