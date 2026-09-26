# Security

BooxUltimatum can hold powerful permissions on your tablet (the adb grants and Shizuku), so security problems matter.

## Reporting a problem

Please report security issues privately through [GitHub's private vulnerability reporting](https://github.com/huuunleashed/BooxUltimatum/security/advisories/new), not in a public issue. Say what you found, how to reproduce it, and which version you tested. You'll get an answer as soon as the maintainer can; this is a one-person spare-time project, so please allow a few days.

## What counts

- Anything that lets another app use BooxUltimatum's permissions or its Shizuku service.
- Anything that changes the tablet in a way the journal can't undo.
- Personal data leaving the tablet, or being written where other apps can read it, without the owner asking.

## Supported versions

Only the latest release, or the latest commit on `main` before the first release, is supported.
