"""Checks the string resources of the suite against the table of terms in AGENTS.md (section Words).

Run: python tools/dev/check_words.py   (prints each problem and exits 1 if there are any)
The unit test test_check_words.py runs it over the repository on every push.
"""
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PATTERNS = (
    'hub/src/main/res/values/strings*.xml',
    'nib/src/main/res/values/strings*.xml',
    'kit/*/src/main/res/values/strings*.xml',
)

# (regex, what to say instead). Case-insensitive unless the flag says otherwise.
BANNED = (
    (r'\bthis app\b', 'this app: say BooxUltimatum or Nib', 0),
    (r'\bthe hub\b', 'the hub: say BooxUltimatum', re.I),
    (r'\bBOOX\b', 'BOOX: write Boox', 0),
    (r'\blauncher', 'launcher: say home app', re.I),
    (r'\btypeface', 'typeface: say font', re.I),
    (r'\blong-?press', 'long-press: say touch and hold', re.I),
    (r'\b(?:the|your) devices?\b', 'the device: say the tablet', re.I),
    (r'\bclick', 'click: say tap', re.I),
    (r'\broll(?:ed|ing)? back\b|\bput (?:it |them )?back\b|\brevert', 'roll back, put back, revert: say Undo or Restore', re.I),
    (r"[A-Za-z]\\?'[a-z]", "straight apostrophe: use \u2019", 0),
    (r'\.\.\.', 'three dots: use \u2026', 0),
)

# Where a banned word is right: the string's name -> the words (the first words of the message above) it may keep.
ALLOWED = {
    'app_bg_restricted_note': ('this app',),      # the other app whose page is open
    'l_frozen_explain': ('this app',),
    'l_frozen_explain_shizuku': ('this app',),
    'update_need_sources': ('this app',),          # shared by the hub and Nib, so it can only say "this app"
    'about_disclaimer': ('BOOX',),                 # the company's own spelling of its brand
}

ELEMENT = re.compile(r'(?s)<(string|item)\b([^>]*)>(.*?)</\1>')


def check_text(xml):
    """Yields (name, message) for each problem in one resource file's text."""
    plural = None
    for m in re.finditer(r'(?s)<plurals\b[^>]*\bname="(\w+)"[^>]*>(.*?)</plurals>|<string\b([^>]*)>(.*?)</string>', xml):
        if m.group(1):
            name, bodies = m.group(1), [i.group(1) for i in re.finditer(r'(?s)<item\b[^>]*>(.*?)</item>', m.group(2))]
        else:
            attrs = m.group(3)
            if 'translatable="false"' in attrs or 'formatted="false"' in attrs:
                continue
            name, bodies = re.search(r'name="(\w+)"', attrs).group(1), [m.group(4)]
        for body in bodies:
            for rx, message, flags in BANNED:
                if re.search(rx, body, flags) and message.split(':')[0] not in ALLOWED.get(name, ()):
                    yield name, message


def problems(root=ROOT):
    found = []
    for pattern in PATTERNS:
        for path in sorted(glob.glob(os.path.join(root, *pattern.split('/')))):
            xml = open(path, encoding='utf-8').read()
            for name, message in check_text(xml):
                found.append('%s: %s: %s' % (os.path.relpath(path, root).replace(os.sep, '/'), name, message))
    return found


if __name__ == '__main__':
    found = problems()
    for line in found:
        print(line)
    if found:
        print('%d problem(s). The terms are in AGENTS.md, section Words.' % len(found))
    sys.exit(1 if found else 0)
