# Contributing to BooxUltimatum

## Writing rules

1. **No hard-wrapped prose.** Write each paragraph, list item, and blockquote as one logical line and let the editor soft-wrap it. The only exceptions are code comments and places where a line break is actually needed, such as code blocks, tables, poetry-like output samples, or an intentional Markdown line break (two trailing spaces or `\`). Check with `python tools/dev/prose_wrap.py` and fix with `python tools/dev/prose_wrap.py --fix`.
2. Mark unverified device facts with *[verify]* until a capture in `captures/` confirms them, then cite the capture.

## Safety rules for tweaks

1. Every tweak needs an `apply` and a `revert` step, plus a declared minimum privilege tier (T0 app, T1 adb-grant, T2 Shizuku, T3 root).
2. Package removal must be reversible (`pm disable-user --user 0` or `pm uninstall -k --user 0`). Never remove packages from system partitions.
3. A tweak only ships once its effect has been measured with the protocol in `docs/02-reverse-engineering-plan.md`.

## Licensing

The project is GPL-3.0-or-later. Record code taken from upstream projects in `THIRD_PARTY.md`, including the source repo, commit, license, and the files involved.
