import unittest

import check_release_notes as crn

VALID = """BooxUltimatum 1.2.0 makes the sleep screen steadier and lets you open frozen apps from home. Nib is unchanged.

## What's new

- **Steadier sleep screen.** It keeps its picture through a charge, and says when it is paused.
- **Frozen apps open from home.** With Shizuku, tapping one opens it, and the hub freezes it again afterwards.

## Fixed

- **Fewer false alarms.** Today no longer reports a stuck display when the tablet was only refreshing.

## Tested

- **Checked:** on a Note Air6 C with firmware 4.3: the sleep screen and frozen apps. Unit tests and lint pass.
- **Not yet seen:** nothing.

## Known limits

- Opening frozen apps needs Shizuku, which stops at every reboot.
- Only the Note Air6 C on firmware 4.3 is tested.

The full history is in [CHANGELOG.md](https://github.com/huuunleashed/BooxUltimatum/blob/main/CHANGELOG.md).

Not affiliated with Onyx or BOOX.
"""

TEST_VALID = """Nib 0.5.0-test.1, a test build: the page zooms in one frame. It is unfinished work published so it can be checked on the tablet, not a release. It installs over 0.4.1 and keeps your drawings.

## What changed

- **Zooming shows the new page in one frame.** The old picture stays, scaled, until the new one is ready.

## Tested

- **Checked:** on the emulator and by unit tests.
- **Not yet seen:** how it looks on the tablet's firmware 4.3 display.

## What to check

1. Pinch to zoom in and out on a page of pencil strokes. The page should change once, not tile by tile.
2. Then send About › Share logs.

## Known limits

- Only the Note Air6 C on firmware 4.3 is tested.

The full history is in [CHANGELOG.md](https://github.com/huuunleashed/BooxUltimatum/blob/main/CHANGELOG.md).

Not affiliated with Onyx or BOOX.
"""


def problems(text: str, test: bool = False) -> str:
    return "\n".join(crn.check(text, test=test))


class ReleaseNotesTest(unittest.TestCase):
    def test_a_conforming_release_note_passes(self):
        self.assertEqual([], crn.check(VALID))

    def test_a_conforming_test_build_note_passes(self):
        self.assertEqual([], crn.check(TEST_VALID, test=True))

    def test_the_templates_are_not_publishable_until_filled_in(self):
        from pathlib import Path
        here = Path(__file__).parent
        self.assertIn("placeholders", problems((here / "release-notes.template.md").read_text(encoding="utf-8")))
        self.assertIn("placeholders", problems((here / "release-notes-test.template.md").read_text(encoding="utf-8"), test=True))

    def test_a_date_in_the_summary_is_refused(self):
        bad = VALID.replace("makes the sleep screen steadier", "follows the night of 2026-10-12 and steadies the sleep screen")
        self.assertIn("a date", problems(bad))

    def test_an_identifier_or_a_time_in_an_item_is_refused(self):
        self.assertIn("identifier", problems(VALID.replace("Steadier sleep screen.", "READ_DROPBOX_DATA read.")))
        self.assertIn("time of day", problems(VALID.replace("when it is paused", "until 07:00")))
        self.assertIn("file name", problems(VALID.replace("through a charge", "through Timelines.kt")))

    def test_more_than_two_measurements_in_an_item_is_refused(self):
        bad = VALID.replace("It keeps its picture through a charge", "It drew 6 mA instead of 300 mA for 29 min")
        self.assertIn("measurements", problems(bad))

    def test_the_sections_are_fixed(self):
        self.assertIn("unknown section", problems(VALID.replace("## Fixed", "## Bug fixes")))
        self.assertIn("missing section '## Tested'", problems(VALID.replace("## Tested", "## Tested on")))
        self.assertIn("out of order", problems(VALID.replace("## Fixed", "## Known limits2").replace("## Known limits2", "## Fixed")
                                                .replace("## Tested", "## TMP").replace("## Known limits", "## Tested").replace("## TMP", "## Known limits")))

    def test_the_summary_is_one_short_paragraph(self):
        self.assertIn("the summary is one paragraph", problems(VALID.replace("Nib is unchanged.", "Nib is unchanged.\nAnd more.")))
        long = "x" * 450
        self.assertIn("summary is", problems(VALID.replace("Nib is unchanged.", long)))

    def test_an_item_starts_with_a_bold_statement_and_the_list_is_short(self):
        self.assertIn("bold statement", problems(VALID.replace("**Steadier sleep screen.**", "Steadier sleep screen.")))
        many = VALID.replace("## Fixed", "\n".join(f"- **Item {i}.** Text." for i in range(8)) + "\n\n## Fixed")
        self.assertIn("items", problems(many))

    def test_tested_says_what_was_and_was_not_seen_and_where(self):
        self.assertIn("Checked", problems(VALID.replace("**Checked:**", "**Did:**")))
        self.assertIn("Not yet seen", problems(VALID.replace("**Not yet seen:**", "**Unsure:**")))
        self.assertIn("firmware", problems(VALID.replace("firmware 4.3: ", "").replace("Only the Note Air6 C on firmware 4.3 is tested.", "Only one tablet.")))

    def test_the_notes_end_with_the_changelog_link_and_the_disclaimer(self):
        self.assertIn("exactly", problems(VALID.replace("Not affiliated with Onyx or BOOX.", "Thanks.")))
        self.assertIn("CHANGELOG.md", problems(VALID.replace("[CHANGELOG.md](https://github.com/huuunleashed/BooxUltimatum/blob/main/CHANGELOG.md)", "the changelog")))

    def test_a_title_hard_wrapping_and_other_links_are_refused(self):
        self.assertIn("title", problems("# BooxUltimatum 1.2.0\n\n" + VALID))
        self.assertIn("hard-wrapped", problems(VALID.replace("and lets you open", "and lets\nyou open")))
        self.assertIn("no links", problems(VALID.replace("Nib is unchanged.", "See [docs](https://example.com).")))

    def test_the_first_item_must_fit_in_what_the_updater_shows(self):
        before = "\n## Before you update\n\n- " + ("Grant the app one more permission from a computer. " * 16).strip() + "\n"
        self.assertIn("first 800 characters", problems(VALID.replace("\n## What's new", before + "\n## What's new")))

    def test_a_code_block_before_the_main_list_is_refused(self):
        before = "\n## Before you update\n\n- Run this.\n\n```\nadb shell pm grant x y\n```\n"
        self.assertIn("code block", problems(VALID.replace("\n## What's new", before + "\n## What's new")))

    def test_a_test_build_says_so_and_has_something_to_check(self):
        self.assertIn("test build", problems(TEST_VALID.replace("a test build", "an early look"), test=True))
        self.assertIn("missing section '## What to check'", problems(TEST_VALID.replace("## What to check", "## Try"), test=True))
        self.assertIn("numbered list", problems(TEST_VALID.replace("1. Pinch", "- Pinch").replace("2. Then", "- Then"), test=True))

    def test_what_the_updater_shows_loses_the_marks_it_drops(self):
        self.assertEqual("A b c\nitem", crn.plain("# A _b_ *c*\n```\ncode\n```\n- item"))


if __name__ == "__main__":
    unittest.main()
