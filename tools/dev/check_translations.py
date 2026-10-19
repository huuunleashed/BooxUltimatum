"""Checks the translated string resources of the suite against the English ones.

Run: python tools/dev/check_translations.py            (prints each problem and exits 1 if there are any)
     python tools/dev/check_translations.py --report   (what each language covers, and what it does not)
The unit test test_check_translations.py runs it over the repository on every push.

A locale is either complete or absent: a language folder that is missing a string, or that carries one
the English side has not got, is a problem. The English side may have strings marked
translatable="false"; those are never translated and must not appear in a locale.
"""
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))

# The modules that carry strings. Each has res/values/ (English) and, for a language, res/values-<tag>/.
MODULES = ('hub', 'nib', 'kit/ui', 'kit/update')

# The plural categories each language uses (CLDR). A language with only "other" must not carry a "one"
# item; a language that uses "one" and "other" must carry both. A language not listed keeps the
# English side's categories.
PLURAL_CATEGORIES = {
    'de': ('one', 'other'),
    'es': ('one', 'other'),
    'fr': ('one', 'other'),
    'it': ('one', 'other'),
    'nl': ('one', 'other'),
    'pt': ('one', 'other'),
    'ja': ('other',),
    'ko': ('other',),
    'th': ('other',),
    'vi': ('other',),
    'zh': ('other',),
}

ITEM = re.compile(r'(?s)<item\b([^>]*?)>(.*?)</item>')
ELEMENT = re.compile(r'(?s)<(string|plurals)\b([^>]*?)(?:/>|>(.*?)</\1>)')
NAME = re.compile(r'name="([^"]+)"')
QUANTITY = re.compile(r'quantity="([^"]+)"')
SPEC = re.compile(r'%(\d+\$)?([sd])')


def parse(text):
    """The entries of one resource file, in the order they are written: name -> (kind, plain, translatable, body).

    A string's body is its text; a plural's body is a list of (quantity, text). The second flag says the
    string is not formatted (`formatted="false"`), the third that it is not translated.
    """
    entries = {}
    for m in ELEMENT.finditer(text):
        kind, attrs, body = m.group(1), m.group(2), m.group(3) or ''
        name = NAME.search(attrs).group(1)
        plain = 'formatted="false"' in attrs
        translatable = 'translatable="false"' in attrs
        if kind == 'plurals':
            items = [(QUANTITY.search(i.group(1)).group(1), i.group(2)) for i in ITEM.finditer(body)]
            entries[name] = ('plurals', plain, translatable, items)
        else:
            entries[name] = ('string', plain, translatable, body)
    return entries


def format_tokens(text):
    """The format specifiers in [text], in order. A literal percent is written %%."""
    tokens = []
    i = 0
    while i < len(text):
        if text[i] != '%':
            i += 1
            continue
        m = SPEC.match(text, i)
        if m:
            tokens.append('%' + (m.group(1) or '') + m.group(2))
            i = m.end()
        elif text.startswith('%%', i):
            tokens.append('%%')
            i += 2
        else:
            tokens.append('%')
            i += 1
    return tokens


def specifiers(formatted, text):
    """What to compare between the English text and the translation.

    A string that is formatted carries its values as specifiers, and the translation must carry the
    same ones, with the same index and the same type, in any order. A string that is not formatted
    carries no values, so only the number of percent signs matters: aapt does not unescape them.
    """
    if formatted:
        return sorted(format_tokens(text))
    return ['%'] * text.count('%')


def language_of(tag):
    """The language of a resource folder: 'zh-rCN' -> 'zh', 'vi' -> 'vi'."""
    return tag.split('-')[0].split('_')[0].lower()


def check_pair(path, base, locale, tag, problems):
    """Compares one locale file with its English file. Adds to [problems]."""
    where = '%s (%s)' % (os.path.relpath(path, ROOT).replace(os.sep, '/'), tag)
    for name, entry in base.items():
        kind, plain, translatable, body = entry
        if translatable:
            if name in locale:
                problems.append('%s: %s is marked translatable="false" in English and must not be translated' % (where, name))
            continue
        if name not in locale:
            problems.append('%s: %s is missing' % (where, name))
    for name, entry in locale.items():
        if name not in base:
            problems.append('%s: %s is not in the English file' % (where, name))

    # The keys are in the same order, so a translator reads the two files side by side.
    expected = [name for name, entry in base.items() if not entry[2]]
    actual = [name for name in locale]
    if actual != expected:
        for index, (want, got) in enumerate(zip(expected, actual)):
            if want != got:
                problems.append('%s: the keys are in a different order; %s should come where %s is' % (where, want, got))
                break
        else:
            problems.append('%s: the keys are in a different order (%d keys here and %d in English)' % (
                where, len(actual), len(expected)))

    categories = PLURAL_CATEGORIES.get(language_of(tag))
    for name, (kind, plain, translatable, body) in base.items():
        if translatable or name not in locale:
            continue
        lkind, lplain, ltranslatable, lbody = locale[name]
        if kind != lkind:
            problems.append('%s: %s is a %s in English and a %s here' % (where, name, kind, lkind))
            continue
        if plain != lplain:
            problems.append('%s: %s must keep formatted="false" (or drop it) like the English one' % (where, name))
        if kind == 'string':
            if not lbody.strip() and body.strip():
                problems.append('%s: %s is empty' % (where, name))
            if specifiers(not plain, body) != specifiers(not lplain, lbody):
                problems.append('%s: %s has different values from the English one (%s and %s)' % (
                    where, name, ' '.join(specifiers(not plain, body)) or 'none', ' '.join(specifiers(not lplain, lbody)) or 'none'))
        else:
            quantities = [q for q, _ in lbody]
            if categories is not None:
                wanted = set(q for q, _ in body) & set(categories)
                if not wanted:
                    wanted = {'other'}
                if set(quantities) != wanted:
                    problems.append('%s: %s needs the %s form only, and has %s' % (
                        where, name, ' and '.join(sorted(wanted)), ' and '.join(sorted(quantities)) or 'none'))
            elif set(quantities) != set(q for q, _ in body):
                problems.append('%s: %s has the forms %s and the English one has %s' % (
                    where, name, ' and '.join(sorted(quantities)), ' and '.join(sorted(q for q, _ in body))))
            for quantity, text in lbody:
                english = dict(body).get(quantity)
                if english is None:
                    continue
                if specifiers(not plain, english) != specifiers(not lplain, text):
                    problems.append('%s: %s (%s) has different values from the English one' % (where, name, quantity))


def check_module(module, problems):
    """Compares every locale of one module with its English files."""
    base_dir = os.path.join(ROOT, module, 'src', 'main', 'res')
    english_dir = os.path.join(base_dir, 'values')
    if not os.path.isdir(english_dir):
        return
    english = {}
    for path in sorted(glob.glob(os.path.join(english_dir, 'strings*.xml'))):
        english[os.path.basename(path)] = parse(open(path, encoding='utf-8').read())
    for locale_dir in sorted(glob.glob(os.path.join(base_dir, 'values-*'))):
        if not os.path.isdir(locale_dir):
            continue
        tag = os.path.basename(locale_dir)[len('values-'):]
        for path in sorted(glob.glob(os.path.join(locale_dir, 'strings*.xml'))):
            name = os.path.basename(path)
            if name not in english:
                problems.append('%s: there is no %s in the English folder' % (
                    os.path.relpath(path, ROOT).replace(os.sep, '/'), name))
                continue
            check_pair(path, english[name], parse(open(path, encoding='utf-8').read()), tag, problems)
        for name in english:
            if not os.path.exists(os.path.join(locale_dir, name)):
                problems.append('%s/%s: %s is missing' % (module, tag, name))


# The apps of the suite, and the modules whose words belong to each of them.
APPS = {
    'hub': ('hub', 'kit/ui', 'kit/update'),
    'nib': ('nib',),
}

ENUM = re.compile(r'^\s*\w+\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\),', re.M)
LOCALE = re.compile(r'<locale\s+android:name="([^"]+)"')
DECLARED = re.compile(r'<string\s+name="([^"]+)"')


def suite_languages():
    """The languages the app knows: the folder, the BCP 47 name and the name in the language itself."""
    path = os.path.join(ROOT, 'kit', 'core', 'src', 'main', 'java', 'app', 'booxultimatum', 'kit', 'core', 'Language.kt')
    if not os.path.exists(path):
        return {}
    found = {}
    for folder, locale, endonym in ENUM.findall(open(path, encoding='utf-8').read()):
        found[locale] = (folder, endonym)
    return found


def declared_locales(app):
    """The BCP 47 names the app declares in its locales_config.xml."""
    path = os.path.join(ROOT, app, 'src', 'main', 'res', 'xml', 'locales_config.xml')
    if not os.path.exists(path):
        return None
    return LOCALE.findall(open(path, encoding='utf-8').read())


def check_declarations(problems):
    """The languages an app offers, the folders it has and the list the app knows must agree."""
    known = suite_languages()
    for app, modules in APPS.items():
        declared = declared_locales(app)
        if declared is None:
            problems.append('%s: there is no res/xml/locales_config.xml' % app)
            continue
        folders = set()
        for module in modules:
            base_dir = os.path.join(ROOT, module, 'src', 'main', 'res')
            for locale_dir in glob.glob(os.path.join(base_dir, 'values-*')):
                if os.path.isdir(locale_dir):
                    folders.add(os.path.basename(locale_dir)[len('values-'):])
        for locale in declared:
            if locale not in known:
                problems.append('%s: %s is not in SuiteLanguage, so the app cannot name it' % (app, locale))
                continue
            folder = known[locale][0]
            if folder != 'en' and folder not in folders:
                problems.append('%s: %s is offered but there is no translation of it' % (app, locale))
        for folder in folders:
            locale = next((loc for loc, (fold, _) in known.items() if fold == folder), None)
            if locale is None:
                problems.append('%s: there is a translation for %s, which is not in SuiteLanguage' % (app, folder))
            elif locale not in declared:
                problems.append('%s: %s is translated but not offered' % (app, folder))


def problems():
    """Every problem in the repository, as a list of sentences."""
    found = []
    for module in MODULES:
        check_module(module, found)
    check_declarations(found)
    return found


def report():
    """What each language covers, and what it does not."""
    lines = []
    for module in MODULES:
        english_dir = os.path.join(ROOT, module, 'src', 'main', 'res', 'values')
        if not os.path.isdir(english_dir):
            continue
        total = 0
        for path in sorted(glob.glob(os.path.join(english_dir, 'strings*.xml'))):
            for entry in parse(open(path, encoding='utf-8').read()).values():
                if not entry[2]:
                    total += 1
        tags = []
        for locale_dir in sorted(glob.glob(os.path.join(english_dir, '..', 'values-*'))):
            if not os.path.isdir(locale_dir):
                continue
            tag = os.path.basename(locale_dir)[len('values-'):]
            count = 0
            for path in sorted(glob.glob(os.path.join(locale_dir, 'strings*.xml'))):
                for entry in parse(open(path, encoding='utf-8').read()).values():
                    if not entry[2]:
                        count += 1
            tags.append((tag, count))
        lines.append('%s: %d strings in English' % (module, total))
        if tags:
            for tag, count in tags:
                lines.append('    %-8s %d strings%s' % (tag, count, '' if count == total else '  (incomplete)'))
        else:
            lines.append('    no translation yet')
    return lines


if __name__ == '__main__':
    if '--report' in sys.argv:
        for line in report():
            print(line)
        sys.exit(0)
    found = problems()
    for line in found:
        print(line)
    if found:
        print('%d problem(s). A language folder holds every string, or none.' % len(found))
    sys.exit(1 if found else 0)
