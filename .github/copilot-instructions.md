# Copilot instructions

Read and follow `AGENTS.md` at the repository root. It's the single source of truth for this repo's rules. The key ones:

- Never introduce hard-wrapped prose (check with `python tools/dev/prose_wrap.py`).
- Record every meaningful change under *[Unreleased]* in `CHANGELOG.md`, and keep `README.md` current.
- Make every tweak reversible, with a declared privilege tier.
- Never run write or flash commands on a connected tablet unless the owner explicitly asks.
- Before a task, load the matching Agent Skill in `.agents/skills/` (tablet testing, firmware interfaces, sleep faces, build and release), and update it with anything new you learn.
