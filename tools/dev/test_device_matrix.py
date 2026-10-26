import unittest

import device_matrix as dm


def data(cells):
    return {
        'devices': [
            {'id': 'a', 'name': 'Tablet A', 'detail': 'Firmware 1', 'reference': True, 'source': 'Here', 'links': [], 'about': 'A.'},
            {'id': 'b', 'name': 'Tablet B', 'detail': 'Firmware 2', 'source': 'Its owner', 'links': [['#1', 'https://example.org/1']], 'about': 'B.'},
        ],
        'groups': [{'name': 'Group', 'features': [{'name': f'Feature {i}', 'tier': 'T0', 'cells': c} for i, c in enumerate(cells)]}],
    }


class DeviceMatrixTest(unittest.TestCase):
    def test_a_shared_note_is_numbered_once_in_reading_order(self):
        d = data([{'a': ['v', 'First.'], 'b': ['r', 'Second.']}, {'b': ['r', 'First.']}])
        self.assertEqual({'First.': 1, 'Second.': 2}, dm.numbered_notes(d))

    def test_seen_working_counts_checked_reported_and_partly_checked(self):
        d = data([{'a': 'v', 'b': 'r'}, {'a': 'p', 'b': 'x'}, {'a': 'b', 'b': 'n'}, {}])
        self.assertEqual(({'a': 2, 'b': 1}, 4), dm.tallies(d))

    def test_an_unknown_status_or_tablet_is_refused(self):
        with self.assertRaises(ValueError):
            dm.validate(data([{'a': 'yes'}]))
        with self.assertRaises(ValueError):
            dm.validate(data([{'c': 'v'}]))

    def test_both_outputs_carry_each_cell_and_note(self):
        d = data([{'a': 'v', 'b': ['x', 'No album.']}])
        md, page = dm.markdown(d), dm.site(d)
        self.assertIn('| Feature 0 | T0 | ● | ✕ <sup>1</sup> |', md)
        self.assertIn('1. No album.', md)
        self.assertIn('class="ev ev-x"', page)
        self.assertIn('<li id="dn-1">No album.</li>', page)
        self.assertIn('title="Tablet B · Feature 0: Doesn&#x27;t work. No album."', page)

    def test_the_generated_part_is_swapped_in_and_the_rest_kept(self):
        text = f'before\n  {dm.START}\n  old\n  {dm.END}\nafter\n'
        self.assertEqual(f'before\n  new\nafter\n', dm.replaced(text, '  new', 'x'))

    def test_the_repository_is_up_to_date(self):
        self.assertEqual([], dm.problems())


if __name__ == '__main__':
    unittest.main()
