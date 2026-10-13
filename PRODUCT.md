# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The users are owners of a Boox tablet who read, take handwritten notes and run Android apps on it. They want the tablet to last longer on a charge, and they want to understand and control what it's doing. The best-tested tablet is the maintainer's Note Air6 C, but it isn't the only one: owners of a Go 10.3 and an Air 4C are already reporting issues. Many are technically curious members of the wider Boox community. They sideload the APK from GitHub, some comfortable with adb (Android's debugging tool) and many not. Every screen must make sense to someone who hasn't read the docs.

## Product Purpose

BooxUltimatum is a sideloadable suite for Boox tablets. The app of that name makes the tablet last longer, feel simpler and stay private, and manages the suite's other apps, which build on the same kit. The first of those is Nib, a drawing app that uses Boox's instant pen preview in its own window. BooxUltimatum measures where the battery goes, explains it in plain language, and offers tweaks that can be undone. Success means a user can see their standby and active drain, understand the top causes, apply a fix, and verify it actually helped, all without a computer after first setup.

## Positioning

BooxUltimatum is built around evidence from real Boox hardware. Every tweak is backed by a before/after measurement taken on a real Note Air6 C, declares the access it needs, and can be undone from a journal. Generic debloaters and battery apps offer lists and switches with no proof; BooxUltimatum offers measured claims.

## Operating Context

- The best-tested tablet, the maintainer's Note Air6 C, has a 10.3" Kaleido 3 colour e-ink screen: 1860×2480 mono at 300 ppi, 930×1240 colour at 150 ppi, muted colours, slow refresh, and visible ghosting. Other Boox models differ. BooxUltimatum is used in portrait and landscape, by finger and by EMR stylus (the electromagnetic pen), often with the front light low.
- Sessions are short and deliberate: check drain, apply a change, come back hours later to verify. Long-running measurements happen while the tablet sleeps.
- Setup happens once at a computer (adb grants, Shizuku start). Shizuku must be restarted each time the tablet restarts.
- Firmware: Boox firmware 4.3 (Android 16) on the best-tested Note Air6 C. The Go 10.3 reports so far come from firmware 4.2 (Android 12). The locked bootloader means no root.

## Capabilities and Constraints

- Privilege tiers: T0 app, T1 adb-granted permissions, T2 Shizuku (a separate app that lets BooxUltimatum act with adb's rights, the shell uid). Root (T3) is out of scope by decision (2026-09-25), so BooxUltimatum works without it.
- Planned pillars: the battery doctor (measure, attribute, fix, verify), a settings hub (search and deep links into scattered Boox settings), e-ink profiles, privacy and debloat, power-user actions, and the suite's apps (Nib for drawing). BooxUltimatum installs and removes apps, and its own modules can be removed and added back.
- Every tweak can be applied and undone, and has a tier, a risk level and linked evidence.
- No background polling. Data refreshes on user action or on scheduled inexact alarms, which Android may deliver a little late, so the app must never cause the drain it measures.
- Strings live in Android resources, English first, structured for later translation.
- Undecided: distribution channel details. The app icon is decided (2026-09-26): an ordered-dither ramp with one lit colour cell (`docs/brand/`).

## Brand Commitments

- Name: BooxUltimatum. License: GPL-3.0-or-later.
- The maintainer's binding direction: **absolutely beautiful, minimal, but complex.** Concretely, that means a calm surface with serious depth: few things on screen, and each one opens precise data and controls.
- The voice is honest and precise. It states what was measured and what's unverified, and never exaggerates battery claims. Every word the suite shows follows the Words section of `AGENTS.md`.

## Evidence on Hand

- A real device capture: `captures/20260925-2118-<serial>-factory-fw43` (git-ignored, personal data).
- Verified device facts in `docs/00-device-research.md` §0.
- First-party measurements now exist, taken on the maintainer's Note Air6 C, and are on the Battery page: standby 0–12 mA, screen-on 430–640 mA in the cases measured, and the 1-minute live sleep step at about 1 % of battery per one to two hours. Third-party reference measurements (eWritable, FW 4.3-rel): idle 2 %/h, reading 4 %/h, notes 10 %/h, max front light +14 %/h.
- The overnight A/B runs in the protocol in `docs/02-reverse-engineering-plan.md` (Phases 3 and 4) are still to do. Until then the UI shows measured samples with their source and age, never invented drain, savings or user counts.

## Product Principles

1. Measure before claiming. Numbers come from real measurements, and the UI labels their source and age.
2. Reversible by default. Every change is journaled and can be undone in one step.
3. Depth on demand. The first glance answers "is my battery OK?", and precision is one tap away, never zero taps.
4. The tool must not cost what it saves. No polling, no wake locks (which keep the tablet awake), no animation.
5. Honest tiers. Features show what access they need and say what happens without it.

## Accessibility & Inclusion

The e-ink screen is the primary accessibility constraint. Use high contrast (pure black on white at minimum), make meaning never depend on colour alone (colour renders muted and grainy at 150 ppi), use 48 dp minimum touch targets suited to stylus and finger, follow the tablet's font scale in sp, and avoid motion.
