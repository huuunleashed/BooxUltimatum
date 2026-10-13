#!/usr/bin/env python3
"""Check a release-notes file against the repo's release-notes convention (AGENTS.md, Releases).

Usage:
    python tools/dev/check_release_notes.py NOTES.md             # notes for a release
    python tools/dev/check_release_notes.py --test NOTES.md      # notes for a test build
    python tools/dev/check_release_notes.py --preview NOTES.md   # also print what the hub's updater will show

Prints each problem and exits 1 when the file doesn't follow the convention. `publish.ps1` runs it before building.

The notes are the GitHub release body, and the hub's updater shows the first 800 characters of it with Markdown marks
and code blocks removed (`ReleaseFeed.plainNotes`), so the opening has to make sense on its own, in plain words.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prose_wrap import unwrap  # noqa: E402

UPDATER_CHARS = 800
DISCLAIMER = "Not affiliated with Onyx or BOOX."

# (heading, required), in the order they must appear.
RELEASE_SECTIONS = [("Before you update", False), ("What's new", True), ("Fixed", False), ("Tested", True), ("Known limits", True)]
TEST_SECTIONS = [("What changed", True), ("Tested", True), ("What to check", True), ("Known limits", True)]

# The sections that describe the change itself: where dates, identifiers and measurements don't belong.
DESCRIBING = {"What's new", "Fixed", "What changed"}

LIMITS = {
    "release": {"summary": 400, "total": 3500, "items": 8, "item_chars": 450, "fixed_chars": 300, "before_items": 3},
    "test": {"summary": 600, "total": 5000, "items": 10, "item_chars": 600, "fixed_chars": 600, "before_items": 3},
}

ISO_DATE = re.compile(r"\b20\d\d-\d\d-\d\d\b")
CLOCK_TIME = re.compile(r"\b\d{1,2}:\d{2}\b")
FILE_NAME = re.compile(r"\b[\w-]+\.(?:kt|java|ps1|py|xml|kts|gradle|json)\b")
CONSTANT = re.compile(r"\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b")
COMMIT = re.compile(r"\b(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}\b")
MEASURE = re.compile(r"\b\d+(?:[.,]\d+)?\s?(?:mAh|mA|ms|px|dp|MB|KB|%|h|min|s)(?![A-Za-z])")


def plain(raw: str) -> str:
    """What the hub's updater makes of the notes, before it cuts them at 800 characters."""
    text = re.sub(r"```[\s\S]*?```", "", raw)
    text = re.sub(r"[*_`>#\[\]]", "", text)
    lines = (line.strip().lstrip("-•").strip() for line in text.splitlines())
    return "\n".join(line for line in lines if line)


def norm(heading: str) -> str:
    return heading.replace("\u2019", "'").strip()


def split_sections(text: str) -> tuple[list[str], list[tuple[str, list[str]]]]:
    """The lines before the first heading, and each `##` section as (heading, lines)."""
    preamble: list[str] = []
    sections: list[tuple[str, list[str]]] = []
    in_fence = False
    for line in text.split("\n"):
        if line.lstrip().startswith(("```", "~~~")):
            in_fence = not in_fence
        match = None if in_fence else re.match(r"^##\s+(.+?)\s*$", line)
        if match:
            sections.append((norm(match.group(1)), []))
        elif sections:
            sections[-1][1].append(line)
        else:
            preamble.append(line)
    return preamble, sections


def items_of(lines: list[str], ordered: bool = False) -> list[str]:
    pattern = r"^\s*\d+[.)]\s+(.*)$" if ordered else r"^\s*[-*]\s+(.*)$"
    return [m.group(1) for line in lines if (m := re.match(pattern, line))]


def non_item_lines(lines: list[str]) -> list[str]:
    return [line.strip() for line in lines if line.strip() and not re.match(r"^\s*(?:[-*]|\d+[.)])\s+", line)]


def describing_problems(where: str, text: str) -> list[str]:
    """Things that make notes read as a log of one occasion instead of a description of the release."""
    out = []
    for pattern, what in (
        (ISO_DATE, "a date"),
        (CLOCK_TIME, "a time of day"),
        (FILE_NAME, "a file name"),
        (CONSTANT, "an identifier such as a permission or constant (the updater drops its underscores)"),
        (COMMIT, "a commit hash"),
    ):
        found = pattern.search(text)
        if found:
            out.append(f"{where}: contains {what} ({found.group(0)!r}); that belongs in CHANGELOG.md or knowledge/experiments.md")
    if len(MEASURE.findall(text)) > 2:
        out.append(f"{where}: more than two measurements; describe what the feature does and leave the figures to CHANGELOG.md")
    return out


def check(raw: str, test: bool = False) -> list[str]:
    limits = LIMITS["test" if test else "release"]
    expected = TEST_SECTIONS if test else RELEASE_SECTIONS
    problems: list[str] = []
    text = raw.replace("\r\n", "\n").strip("\n")

    if unwrap(text)[1]:
        problems.append("hard-wrapped prose: write each paragraph and list item as one line")
    if re.search(r"\{\{|\}\}|\bTODO\b", text):
        problems.append("the template's placeholders ({{...}}) or a TODO are still in the notes")
    if re.search(r"^#\s", text, re.M):
        problems.append("no `#` title: the GitHub release title is the title, and the body starts with the summary")
    if re.search(r"^#{3,}\s", text, re.M):
        problems.append("only `##` sections are used; no `###` subsections")
    if len(text) > limits["total"]:
        problems.append(f"{len(text)} characters; keep release notes under {limits['total']} and leave the detail to CHANGELOG.md")

    preamble, sections = split_sections(text)
    summary_lines = [line for line in preamble if line.strip()]
    summary = " ".join(summary_lines)
    if not summary_lines:
        problems.append("the notes must start with a summary paragraph")
    elif re.match(r"^\s*(#|[-*]\s|\d+[.)]\s)", summary_lines[0]):
        problems.append("the first line must be the summary paragraph, not a heading or a list")
    elif len(summary_lines) > 1:
        problems.append("the summary is one paragraph")
    if len(summary) > limits["summary"]:
        problems.append(f"the summary is {len(summary)} characters; keep it under {limits['summary']}: say what the release is about, in general terms")
    problems += describing_problems("summary", summary)
    if test and "test build" not in summary.lower():
        problems.append("the summary of a test build says it is a test build, and not a release")

    names = [name for name, _ in sections]
    known = {name for name, _ in expected}
    for name in names:
        if name not in known:
            problems.append(f"unknown section '## {name}'; the sections are: " + ", ".join(n for n, _ in expected))
    for name in {n for n in names if names.count(n) > 1}:
        problems.append(f"section '## {name}' appears more than once")
    for name, required in expected:
        if required and name not in names:
            problems.append(f"missing section '## {name}'")
    order = [name for name in names if name in known]
    wanted = [name for name, _ in expected if name in order]
    if order != wanted and len(set(order)) == len(order):
        problems.append("sections are out of order; they go: " + ", ".join(n for n, _ in expected))

    body = {name: lines for name, lines in sections}
    for name, lines in sections:
        if name in DESCRIBING:
            for item in items_of(lines):
                problems += describing_problems(f"'{name}' item {item[:40]!r}", item)

    main_name = "What changed" if test else "What's new"
    main_items = items_of(body.get(main_name, []))
    if main_name in body:
        if not main_items:
            problems.append(f"'## {main_name}' needs at least one item")
        if len(main_items) > limits["items"]:
            problems.append(f"'## {main_name}' has {len(main_items)} items; group related changes into at most {limits['items']}")
        for stray in non_item_lines(body[main_name]):
            problems.append(f"'## {main_name}' holds only list items; found the line {stray[:50]!r}")
    for item in main_items:
        if not re.match(r"^\*\*[^*]+\*\*", item):
            problems.append(f"'{main_name}' item {item[:40]!r} must start with a bold statement of what the user gets")
        if len(item) > limits["item_chars"]:
            problems.append(f"'{main_name}' item {item[:40]!r} is {len(item)} characters; keep it under {limits['item_chars']}")

    for item in items_of(body.get("Fixed", [])):
        if not re.match(r"^\*\*[^*]+\*\*", item):
            problems.append(f"'Fixed' item {item[:40]!r} must start with a bold statement of what no longer goes wrong")
        if len(item) > limits["fixed_chars"]:
            problems.append(f"'Fixed' item {item[:40]!r} is {len(item)} characters; keep it under {limits['fixed_chars']}")

    if "Before you update" in body:
        steps = items_of(body["Before you update"])
        if not steps:
            problems.append("'## Before you update' needs at least one step; leave the section out when the update needs no action")
        if len(steps) > limits["before_items"]:
            problems.append(f"'## Before you update' has {len(steps)} steps; keep it to {limits['before_items']}")
    if "What to check" in body and not items_of(body["What to check"], ordered=True):
        problems.append("'## What to check' is a numbered list of things to do, each with what a good result looks like")

    if "Tested" in body:
        tested = items_of(body["Tested"])
        starts = [re.sub(r"[*_]", "", item).lower() for item in tested]
        if not any(s.startswith("checked:") for s in starts):
            problems.append("'## Tested' needs an item starting '**Checked:**'")
        if not any(s.startswith("not yet seen:") for s in starts):
            problems.append("'## Tested' needs an item starting '**Not yet seen:**' (write 'nothing' if it all was)")
        if "firmware" not in " ".join(body["Tested"]).lower():
            problems.append("'## Tested' names the device and the firmware it was checked on")
    if "Known limits" in body and not items_of(body["Known limits"]):
        problems.append("'## Known limits' needs at least one item")

    footer = non_item_lines(body["Known limits"]) if "Known limits" in body else []
    last = [line for line in text.split("\n") if line.strip()][-1].strip() if text.strip() else ""
    if last != DISCLAIMER:
        problems.append(f"the last line is exactly: {DISCLAIMER}")
    if not any("CHANGELOG.md" in line and "](" in line for line in footer):
        problems.append("before the disclaimer, link the full history: a line with a link to CHANGELOG.md")
    for line in footer:
        if line != DISCLAIMER and not ("CHANGELOG.md" in line and "](" in line):
            problems.append(f"after the list, '## Known limits' ends with the CHANGELOG link and the disclaimer only; found {line[:50]!r}")

    # What the updater shows: the first 800 characters, so the first item has to be inside them, and links and code
    # blocks (which it drops or garbles) stay out of everything above the footer.
    shown = plain(text)
    if main_items:
        lead = re.match(r"^\*\*([^*]+)\*\*", main_items[0])
        if lead:
            lead_plain = plain(lead.group(1))
            at = shown.find(lead_plain)
            if at < 0 or at + len(lead_plain) > UPDATER_CHARS:
                problems.append(
                    f"the hub's updater shows only the first {UPDATER_CHARS} characters, and the first '{main_name}' item doesn't fit in them; "
                    "shorten the summary and 'Before you update'",
                )
    before_main = "\n".join(preamble)
    for name, lines in sections:
        if name == main_name:
            break
        before_main += "\n" + "\n".join(lines)
    if "```" in before_main:
        problems.append("no code block before the main list: the updater removes code blocks; write a command inline in words, or point to the Access page")
    for line in text.split("\n"):
        if "](" in line and "CHANGELOG.md" not in line:
            problems.append(f"no links in the notes except the CHANGELOG link (the updater garbles them); found one in {line[:50]!r}")
    return problems


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    if len(args) != 1 or not flags <= {"--test", "--preview"}:
        print(__doc__)
        return 2
    raw = Path(args[0]).read_text(encoding="utf-8")
    problems = check(raw, test="--test" in flags)
    for problem in problems:
        print(f"release notes: {problem}")
    if "--preview" in flags:
        print("--- what the hub's updater shows (first 800 characters) ---")
        print(plain(raw.replace("\r\n", "\n"))[:UPDATER_CHARS])
        print("--- end ---")
    if problems:
        print(f"{len(problems)} problem(s). The convention is in AGENTS.md (Releases) and the build-release-and-docs skill.")
        return 1
    print("release notes: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
