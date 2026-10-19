import unittest

import check_translations as ct


BASE = '''<resources>
    <string name="plain">Tap to open</string>
    <string name="value">%1$s left, %2$d in all</string>
    <string name="pct">%1$d%% used</string>
    <string name="literal" formatted="false">about 2 % per hour</string>
    <string name="fixed" translatable="false">BooxUltimatum</string>
    <plurals name="cores">
        <item quantity="one">%1$d core</item>
        <item quantity="other">%1$d cores</item>
    </plurals>
</resources>'''


def check(base_xml, locale_xml, tag='vi'):
    found = []
    ct.check_pair('x.xml', ct.parse(base_xml), ct.parse(locale_xml), tag, found)
    return found


class CheckTranslationsTest(unittest.TestCase):
    def test_a_faithful_translation_passes(self):
        locale = '''<resources>
            <string name="plain">Chạm để mở</string>
            <string name="value">Còn %2$d, tổng %1$s</string>
            <string name="pct">Đã dùng %1$d%%</string>
            <string name="literal" formatted="false">khoảng 2 % mỗi giờ</string>
            <plurals name="cores">
                <item quantity="other">%1$d lõi</item>
            </plurals>
        </resources>'''
        self.assertEqual([], check(BASE, locale))

    def test_a_missing_string_is_a_problem(self):
        locale = '<resources><string name="plain">Chạm để mở</string></resources>'
        found = check(BASE, locale)
        self.assertTrue(any('value is missing' in f for f in found), found)

    def test_a_string_the_english_side_has_not_got_is_a_problem(self):
        locale = '''<resources>
            <string name="plain">Chạm để mở</string>
            <string name="value">%1$s, %2$d</string>
            <string name="pct">%1$d%%</string>
            <string name="literal" formatted="false">2 %</string>
            <string name="extra">Thêm</string>
            <plurals name="cores"><item quantity="other">%1$d</item></plurals>
        </resources>'''
        found = check(BASE, locale)
        self.assertTrue(any('extra is not in the English file' in f for f in found), found)

    def test_a_wrong_value_index_is_a_problem(self):
        locale = '''<resources>
            <string name="plain">Chạm để mở</string>
            <string name="value">%1$s còn, %1$d nói chung</string>
            <string name="pct">%1$d%%</string>
            <string name="literal" formatted="false">2 %</string>
            <plurals name="cores"><item quantity="other">%1$d lõi</item></plurals>
        </resources>'''
        found = check(BASE, locale)
        self.assertTrue(any('value has different values' in f for f in found), found)

    def test_a_doubled_percent_in_a_literal_string_is_a_problem(self):
        # A string that is not formatted is shown as it is, so %% reads as two percent signs.
        locale = '''<resources>
            <string name="plain">Chạm để mở</string>
            <string name="value">%1$s, %2$d</string>
            <string name="pct">%1$d%%</string>
            <string name="literal" formatted="false">khoảng 2 %% mỗi giờ</string>
            <plurals name="cores"><item quantity="other">%1$d lõi</item></plurals>
        </resources>'''
        found = check(BASE, locale)
        self.assertTrue(any('literal has different values' in f for f in found), found)

    def test_dropping_formatted_false_is_a_problem(self):
        locale = '''<resources>
            <string name="plain">Chạm để mở</string>
            <string name="value">%1$s, %2$d</string>
            <string name="pct">%1$d%%</string>
            <string name="literal">khoảng 2 % mỗi giờ</string>
            <plurals name="cores"><item quantity="other">%1$d lõi</item></plurals>
        </resources>'''
        found = check(BASE, locale)
        self.assertTrue(any('formatted="false"' in f for f in found), found)

    def test_a_string_marked_not_translatable_must_not_be_translated(self):
        locale = '''<resources>
            <string name="plain">Chạm để mở</string>
            <string name="value">%1$s, %2$d</string>
            <string name="pct">%1$d%%</string>
            <string name="literal" formatted="false">2 %</string>
            <string name="fixed">BooxUltimatum</string>
            <plurals name="cores"><item quantity="other">%1$d lõi</item></plurals>
        </resources>'''
        found = check(BASE, locale)
        self.assertTrue(any('must not be translated' in f for f in found), found)

    def test_a_language_with_one_form_needs_only_other(self):
        locale = '''<resources>
            <string name="plain">Chạm để mở</string>
            <string name="value">%1$s, %2$d</string>
            <string name="pct">%1$d%%</string>
            <string name="literal" formatted="false">2 %</string>
            <plurals name="cores">
                <item quantity="one">%1$d lõi</item>
                <item quantity="other">%1$d lõi</item>
            </plurals>
        </resources>'''
        found = check(BASE, locale, 'vi')
        self.assertTrue(any('needs the other form only' in f for f in found), found)

    def test_a_language_with_two_forms_needs_both(self):
        locale = '''<resources>
            <string name="plain">Tap to open</string>
            <string name="value">%1$s, %2$d</string>
            <string name="pct">%1$d%%</string>
            <string name="literal" formatted="false">2 %</string>
            <plurals name="cores"><item quantity="other">%1$d cores</item></plurals>
        </resources>'''
        found = check(BASE, locale, 'en')
        self.assertTrue(any('has the forms other' in f for f in found), found)

    def test_an_empty_translation_is_a_problem(self):
        locale = '''<resources>
            <string name="plain"></string>
            <string name="value">%1$s, %2$d</string>
            <string name="pct">%1$d%%</string>
            <string name="literal" formatted="false">2 %</string>
            <plurals name="cores"><item quantity="other">%1$d lõi</item></plurals>
        </resources>'''
        found = check(BASE, locale)
        self.assertTrue(any('plain is empty' in f for f in found), found)

    def test_the_repository_is_consistent(self):
        self.assertEqual([], ct.problems())


if __name__ == '__main__':
    unittest.main()
