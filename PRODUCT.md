# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The primary user is the project owner: a technical owner of a BOOX Note Air6 C who reads, takes handwritten notes, and runs Android apps on it. They want the device to last longer on a charge, and they want to understand and control what it's doing. Secondary audience (later): the wider Boox community of technically curious owners. They'll sideload the APK from GitHub, some comfortable with adb and many not. Every screen must make sense to someone who hasn't read the docs.

## Product Purpose

BooxUltimatum is one sideloadable APK that makes a Boox tablet last longer, feel simpler, and stay private. It measures where battery goes, explains it in plain language, and offers tweaks that can be reversed. Success means a user can see their standby and active drain, understand the top causes, apply a fix, and verify it actually helped, all without a PC after first setup.

## Positioning

It's the only tool built around evidence on this specific hardware. Every tweak is backed by a before/after measurement taken on a real Note Air6 C, declares the privilege it needs, and can be undone from a journal. Generic debloaters and battery apps offer lists and toggles with no proof; BooxUltimatum offers measured claims.

## Operating Context

- It runs on a 10.3" Kaleido 3 colour e-ink panel: 1860×2480 mono at 300 ppi, 930×1240 colour at 150 ppi, muted colours, slow refresh, and visible ghosting. It's used in portrait and landscape, by finger and by EMR stylus, often with the front light low.
- Sessions are short and deliberate: check drain, apply a change, come back hours later to verify. Long-running measurements happen while the device sleeps.
- Setup happens once at a PC (adb grants, Shizuku start). Shizuku must be restarted after each reboot.
- Firmware in use: BOOX FW 4.3 (Android 16). The locked bootloader means no root.

## Capabilities and Constraints

- Privilege tiers: T0 app, T1 adb-granted permissions, T2 Shizuku (shell uid). Root (T3) is out of scope by decision (2026-09-25).
- Planned pillars: Battery Doctor (measure, attribute, fix, verify), Settings Hub (search and deep links into scattered Boox settings), E-Ink Profiles, Privacy & Debloat, and power-user actions.
- Every tweak has apply/revert, a tier, a risk level, and linked evidence.
- No background polling. Data refreshes on user action or on scheduled inexact alarms, so the app must never cause the drain it measures.
- Strings live in Android resources, English first, structured for later translation.
- Undecided: distribution channel details. The app icon is decided (2026-09-26): an ordered-dither ramp with one lit colour cell (`docs/brand/`).

## Brand Commitments

- Name: BooxUltimatum. License: GPL-3.0-or-later.
- The user's volunteered binding direction: **absolutely beautiful, minimal, but complex.** Concretely, that means a calm surface with serious depth: few things on screen, and each one opens precise data and controls.
- The voice is honest and precise. It states what was measured and what's unverified, and never exaggerates battery claims.

## Evidence on Hand

- A real device capture: `captures/20260925-2118-<serial>-factory-fw43` (git-ignored, personal data).
- Verified device facts in `docs/00-device-research.md` §0.
- Third-party reference measurements (eWritable, FW 4.3-rel): idle 2 %/h, reading 4 %/h, notes 10 %/h, max front light +14 %/h.
- No first-party battery measurements exist yet. The UI must not show invented drain numbers, savings, or user counts.

## Product Principles

1. Measure before claiming. Numbers come from this device, and the UI labels their source and age.
2. Reversible by default. Every change is journaled and can be undone in one step.
3. Depth on demand. The first glance answers "is my battery OK?", and precision is one tap away, never zero taps.
4. The tool must not cost what it saves. No polling, no wakelocks, no animation.
5. Honest tiers. Features show what privilege they need and degrade gracefully without it.

## Accessibility & Inclusion

The e-ink panel is the primary accessibility constraint. Use high contrast (pure black on white at minimum), make meaning never depend on colour alone (colour renders muted and grainy at 150 ppi), use 48 dp minimum touch targets suited to stylus and finger, follow the system font scale in sp, and avoid motion.
