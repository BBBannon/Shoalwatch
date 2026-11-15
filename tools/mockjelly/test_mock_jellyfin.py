"""Run from this folder: python -m unittest test_mock_jellyfin"""
import unittest

import mock_jellyfin as m

m.build_catalogue()
m.seed_state()

HALF_WATCHED = m.guid("episode-0-2-2")  # Harbour Lights S2 E2, seeded part way through


def names(q):
    return [i["Id"] for i in m.next_up(q)["Items"]]


class NextUp(unittest.TestCase):
    def test_a_part_watched_episode_is_next_up_by_default(self):
        self.assertIn(HALF_WATCHED, names({}))

    def test_enable_resumable_false_leaves_part_watched_episodes_out(self):
        self.assertNotIn(HALF_WATCHED, names({"enableresumable": ["false"]}))

    def test_enable_resumable_false_keeps_series_with_a_clean_next_episode(self):
        before = names({})
        later = m.guid("episode-1-1-2")
        # The Long Orbit: S1E1 watched, nothing started, so S1E2 is next up whether or not resumable ones are allowed.
        m.user_state(m.guid("episode-1-1-1")).update(played=True)
        try:
            self.assertIn(later, names({"enableresumable": ["false"]}))
        finally:
            m.user_state(m.guid("episode-1-1-1")).update(played=False)
        self.assertEqual(before, names({}))


if __name__ == "__main__":
    unittest.main()
