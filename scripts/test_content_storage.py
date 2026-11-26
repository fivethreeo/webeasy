from pathlib import Path
import sqlite3
import unittest


class ContentStorageTests(unittest.TestCase):
    def setUp(self):
        self.database = sqlite3.connect(":memory:")
        self.database.execute("PRAGMA foreign_keys = ON")
        schema = Path(__file__).resolve().parents[1] / "shared" / "storage.sql"
        self.database.executescript(schema.read_text())
        self.database.execute("INSERT INTO pages (id, parent_id, title, menu_title, slug, content, date, position) VALUES ('parent', NULL, 'Title', 'Menu', 'about', '', '2026-11-24', 0)")
        self.database.execute("INSERT INTO pages (id, parent_id, title, menu_title, slug, content, date, position) VALUES ('child', 'parent', 'Child', 'Child', 'team', '', '2026-11-24', 0)")
        self.database.execute("INSERT INTO news (id, title, slug, date, short_content) VALUES ('story', 'Title', 'story', '2026-11-24', '')")

    def tearDown(self):
        self.database.close()

    def test_owners_are_exclusive_and_foreign_keys_are_required(self):
        for page, news in [(None, None), ("parent", "story"), ("missing", None)]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.database.execute(
                    "INSERT INTO sections (id, page_id, news_id, heading, content, position) VALUES ('bad', ?, ?, 'Heading', '', 0)", (page, news))
        for owners in [(None, None, None), ("parent", "story", None), (None, None, "missing")]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.database.execute(
                    "INSERT INTO asides VALUES ('bad', ?, ?, ?, '', '', 0)", owners)

    def test_deleting_parent_cascades_to_subpages_sections_and_asides_only(self):
        self.database.execute("INSERT INTO sections (id, page_id, news_id, heading, content, position) VALUES ('section', 'child', NULL, 'Heading', '', 0)")
        self.database.execute("INSERT INTO asides VALUES ('note', NULL, NULL, 'section', '', '', 0)")
        self.database.execute("INSERT INTO asides VALUES ('news-note', NULL, 'story', NULL, '', '', 0)")
        self.database.execute("DELETE FROM pages WHERE id = 'parent'")
        self.assertEqual(self.database.execute("SELECT COUNT(*) FROM pages").fetchone()[0], 0)
        self.assertEqual(self.database.execute("SELECT COUNT(*) FROM sections").fetchone()[0], 0)
        self.assertEqual(self.database.execute("SELECT id FROM asides").fetchall(), [("news-note",)])

    def test_duplicate_sibling_routes_are_rejected_but_different_parents_and_news_dates_are_allowed(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.database.execute("INSERT INTO pages (id, parent_id, title, menu_title, slug, content, date, position) VALUES ('duplicate', NULL, 'Title', 'Menu', 'about', '', '2026-11-24', 1)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.database.execute("INSERT INTO pages (id, parent_id, title, menu_title, slug, content, date, position) VALUES ('duplicate-child', 'parent', 'Title', 'Menu', 'team', '', '2026-11-24', 1)")
        self.database.execute("INSERT INTO pages (id, parent_id, title, menu_title, slug, content, date, position) VALUES ('grandchild', 'child', 'Title', 'Menu', 'team', '', '2026-11-24', 0)")
        self.database.execute("INSERT INTO news (id, title, slug, date, short_content) VALUES ('older', 'Title', 'story', '2026-11-23', '')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.database.execute("INSERT INTO news (id, title, slug, date, short_content) VALUES ('duplicate', 'Title', 'story', '2026-11-24', '')")


if __name__ == "__main__":
    unittest.main()
