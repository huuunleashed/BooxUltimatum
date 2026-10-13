import unittest

import check_words as cw


class CheckWordsTest(unittest.TestCase):
    def test_it_finds_each_banned_term(self):
        xml = '''<resources>
            <string name="a">Open this app and tap the hub.</string>
            <string name="b">Choose your launcher typeface.</string>
            <string name="c">Long-press, or click the BOOX device.</string>
            <string name="d">Don\\'t put it back.</string>
        </resources>'''
        names = sorted({name for name, _ in cw.check_text(xml)})
        self.assertEqual(['a', 'b', 'c', 'd'], names)

    def test_a_clean_string_passes(self):
        xml = '<resources><string name="a">Touch and hold an app, then choose Pin to home.</string></resources>'
        self.assertEqual([], list(cw.check_text(xml)))

    def test_plural_items_are_checked_too(self):
        xml = '<resources><plurals name="p"><item quantity="one">%1$d launcher</item><item quantity="other">%1$d launchers</item></plurals></resources>'
        self.assertEqual(2, len(list(cw.check_text(xml))))

    def test_a_listed_exception_is_allowed(self):
        xml = '<resources><string name="l_frozen_explain">Boox froze this app.</string></resources>'
        self.assertEqual([], list(cw.check_text(xml)))

    def test_the_repository_follows_the_terms(self):
        self.assertEqual([], cw.problems())


if __name__ == '__main__':
    unittest.main()
