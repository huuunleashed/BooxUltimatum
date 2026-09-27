#!/usr/bin/env python3
"""Detect (and optionally fix) hard-wrapped prose in Markdown files.

Repo rule: prose paragraphs and list items are written as one logical line each.
Code blocks, tables, headings and front matter are left untouched.

Usage:
    python tools/dev/prose_wrap.py            # check all *.md, exit 1 on violations
    python tools/dev/prose_wrap.py --fix      # rewrite files in place
    python tools/dev/prose_wrap.py FILE ...   # limit to given files
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {".git", ".gradle", "build", "node_modules", "captures", ".idea"}

BLOCK_START = re.compile(r"^\s*(#{1,6}\s|[-*+]\s|\d+[.)]\s|>|\||```|~~~|<|!\[|\[\^|---\s*$|\*\*\*\s*$)")


def is_continuation(prev: str, line: str) -> bool:
    if not prev.strip() or not line.strip():
        return False
    if BLOCK_START.match(line):
        return False
    stripped_prev = prev.strip()
    if stripped_prev.startswith(("#", "|", "```", "~~~", "<")) or re.match(r"^(---|\*\*\*)\s*$", stripped_prev):
        return False
    if prev.endswith("  ") or prev.endswith("\\"):
        return False  # explicit Markdown line break is intentional
    return True


def unwrap(text: str) -> tuple[str, int]:
    out: list[str] = []
    joins = 0
    in_fence = False
    lines = text.split("\n")
    # YAML front matter (Agent Skills' SKILL.md, for example) is data, one key per line, not prose.
    start = 0
    if lines and lines[0].strip() == "---":
        end = next((i for i in range(1, len(lines)) if lines[i].strip() == "---"), None)
        if end is not None:
            out.extend(lines[: end + 1])
            start = end + 1
    for line in lines[start:]:
        fence = line.lstrip().startswith(("```", "~~~"))
        if in_fence:
            out.append(line)
            if fence:
                in_fence = False
            continue
        if fence:
            in_fence = True
            out.append(line)
            continue
        if out and is_continuation(out[-1], line):
            out[-1] = out[-1].rstrip() + " " + line.strip()
            joins += 1
        else:
            out.append(line)
    return "\n".join(out), joins


def md_files(args: list[str]) -> list[Path]:
    if args:
        return [Path(a).resolve() for a in args]
    return [p for p in ROOT.rglob("*.md") if not SKIP_DIRS.intersection(p.relative_to(ROOT).parts)]


def main() -> int:
    fix = "--fix" in sys.argv
    files = md_files([a for a in sys.argv[1:] if a != "--fix"])
    bad = 0
    for f in files:
        fixed, joins = unwrap(f.read_text(encoding="utf-8"))
        if joins:
            bad += 1
            rel = f.relative_to(ROOT) if f.is_relative_to(ROOT) else f
            print(f"{'fixed' if fix else 'hard-wrapped'}: {rel} ({joins} wrapped line(s))")
            if fix:
                f.write_text(fixed, encoding="utf-8", newline="\n")
    if bad and not fix:
        print("Run with --fix to unwrap.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
