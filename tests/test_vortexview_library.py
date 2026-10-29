import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from vortexview import library
from vortexview.library import Gallery, MoveError, Settings


def touch(path: Path, content: bytes = b"x") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


class LibraryTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.photos = self.root / "Photos"
        self.keep = self.root / "Keepers"
        self.keep.mkdir()
        for name in ("IMG_10.jpg", "IMG_2.jpg", "IMG_1.JPG", "beach.png", "notes.txt", "clip.mp4"):
            touch(self.photos / name, name.encode())
        self.gallery = Gallery()
        self.gallery.open(self.photos)

    def names(self):
        return [p.name for p in self.gallery.images]


class ScanTests(LibraryTestCase):
    def test_only_images_in_natural_order(self):
        self.assertEqual(self.names(), ["beach.png", "IMG_1.JPG", "IMG_2.jpg", "IMG_10.jpg"])

    def test_hidden_files_are_skipped(self):
        touch(self.photos / ".hidden.jpg")
        self.gallery.reload()
        self.assertNotIn(".hidden.jpg", self.names())

    def test_subfolders_are_not_listed(self):
        touch(self.photos / "Sub" / "deep.jpg")
        self.gallery.reload()
        self.assertNotIn("deep.jpg", self.names())

    def test_opening_a_file_starts_at_that_file(self):
        gallery = Gallery()
        gallery.open(self.photos / "IMG_2.jpg")
        self.assertEqual(gallery.current.name, "IMG_2.jpg")
        self.assertEqual(gallery.folder, self.photos)

    def test_missing_path(self):
        with self.assertRaises(MoveError):
            Gallery().open(self.root / "nope")

    def test_natural_key(self):
        self.assertLess(library.natural_key("a2"), library.natural_key("a10"))
        self.assertLess(library.natural_key("B"), library.natural_key("c"))

    def test_heif_only_with_the_plugin(self):
        with mock.patch.object(library, "heif_supported", return_value=False):
            self.assertNotIn(".heic", library.image_extensions())
        with mock.patch.object(library, "heif_supported", return_value=True):
            self.assertIn(".heic", library.image_extensions())


class NavigationTests(LibraryTestCase):
    def test_go_stops_at_the_ends(self):
        self.assertFalse(self.gallery.go(-1))
        self.assertTrue(self.gallery.go(1))
        self.assertEqual(self.gallery.current.name, "IMG_1.JPG")
        self.gallery.go(100)
        self.assertEqual(self.gallery.current.name, "IMG_10.jpg")
        self.assertFalse(self.gallery.go(1))

    def test_neighbours(self):
        self.assertIsNone(self.gallery.neighbour(-1))
        self.assertEqual(self.gallery.neighbour(1).name, "IMG_1.JPG")

    def test_empty_folder(self):
        empty = self.root / "Empty"
        empty.mkdir()
        gallery = Gallery()
        gallery.open(empty)
        self.assertIsNone(gallery.current)
        self.assertFalse(gallery.go(1))

    def test_reload_keeps_the_current_photo(self):
        self.gallery.go_to(2)  # IMG_2.jpg
        touch(self.photos / "IMG_0.jpg")
        self.gallery.reload()
        self.assertEqual(self.gallery.current.name, "IMG_2.jpg")


class MoveTests(LibraryTestCase):
    def test_moves_the_current_photo_and_shows_the_next(self):
        self.gallery.go_to(1)  # IMG_1.JPG
        record = self.gallery.move_current(self.keep)
        self.assertTrue((self.keep / "IMG_1.JPG").exists())
        self.assertFalse((self.photos / "IMG_1.JPG").exists())
        self.assertEqual(self.gallery.current.name, "IMG_2.jpg")
        self.assertEqual(record.destination, self.keep / "IMG_1.JPG")
        self.assertFalse(record.renamed)

    def test_contents_survive_the_move(self):
        self.gallery.move_current(self.keep)
        self.assertEqual((self.keep / "beach.png").read_bytes(), b"beach.png")

    def test_moving_the_last_photo_steps_back(self):
        self.gallery.go_to(3)
        self.gallery.move_current(self.keep)
        self.assertEqual(self.gallery.current.name, "IMG_2.jpg")

    def test_moving_everything_leaves_nothing(self):
        for _ in range(4):
            self.gallery.move_current(self.keep)
        self.assertIsNone(self.gallery.current)
        with self.assertRaises(MoveError):
            self.gallery.move_current(self.keep)

    def test_never_overwrites(self):
        touch(self.keep / "beach.png", b"older beach")
        record = self.gallery.move_current(self.keep)
        self.assertEqual((self.keep / "beach.png").read_bytes(), b"older beach")
        self.assertEqual(record.destination.name, "beach (2).png")
        self.assertTrue(record.renamed)

    def test_counts_up_past_existing_copies(self):
        touch(self.keep / "beach.png")
        touch(self.keep / "beach (2).png")
        self.assertEqual(self.gallery.move_current(self.keep).destination.name, "beach (3).png")

    def test_refuses_its_own_folder(self):
        with self.assertRaises(MoveError) as ctx:
            self.gallery.move_current(self.photos)
        self.assertIn("already in", str(ctx.exception))
        self.assertEqual(len(self.gallery.images), 4)

    def test_refuses_a_missing_target(self):
        with self.assertRaises(MoveError) as ctx:
            self.gallery.move_current(self.root / "gone")
        self.assertIn("doesn't exist", str(ctx.exception))

    def test_locked_file_is_explained(self):
        with mock.patch.object(library.shutil, "move", side_effect=PermissionError(13, "denied")):
            with self.assertRaises(MoveError) as ctx:
                self.gallery.move_current(self.keep)
        self.assertIn("open in another program", str(ctx.exception))
        self.assertEqual(len(self.gallery.images), 4, "a failed move must not drop the photo from the list")
        self.assertEqual(self.gallery.history, [])

    def test_other_files_are_left_alone(self):
        self.gallery.move_current(self.keep)
        self.assertTrue((self.photos / "notes.txt").exists())


class UndoTests(LibraryTestCase):
    def test_undo_puts_it_back_in_place(self):
        self.gallery.go_to(2)  # IMG_2.jpg
        self.gallery.move_current(self.keep)
        self.gallery.go(1)
        undone = self.gallery.undo()
        self.assertTrue((self.photos / "IMG_2.jpg").exists())
        self.assertFalse((self.keep / "IMG_2.jpg").exists())
        self.assertEqual(self.gallery.current.name, "IMG_2.jpg")
        self.assertEqual(self.names(), ["beach.png", "IMG_1.JPG", "IMG_2.jpg", "IMG_10.jpg"])
        self.assertEqual(undone.destination, self.photos / "IMG_2.jpg")

    def test_undo_several_in_reverse_order(self):
        self.gallery.move_current(self.keep)
        self.gallery.move_current(self.keep)
        self.gallery.undo()
        self.gallery.undo()
        self.assertEqual(self.names(), ["beach.png", "IMG_1.JPG", "IMG_2.jpg", "IMG_10.jpg"])
        self.assertEqual(list(self.keep.iterdir()), [])

    def test_nothing_to_undo(self):
        self.assertIsNone(self.gallery.undo())

    def test_undo_when_the_file_was_moved_on(self):
        record = self.gallery.move_current(self.keep)
        record.destination.unlink()
        with self.assertRaises(MoveError):
            self.gallery.undo()
        self.assertEqual(self.gallery.history, [])

    def test_undo_does_not_overwrite_a_new_file_with_the_same_name(self):
        self.gallery.move_current(self.keep)  # beach.png
        touch(self.photos / "beach.png", b"newcomer")
        undone = self.gallery.undo()
        self.assertEqual((self.photos / "beach.png").read_bytes(), b"newcomer")
        self.assertEqual(undone.destination.name, "beach (2).png")

    def test_undo_after_switching_folders_still_restores_the_file(self):
        self.gallery.move_current(self.keep)
        other = self.root / "Other"
        touch(other / "x.jpg")
        self.gallery.open(other)
        self.gallery.undo()
        self.assertTrue((self.photos / "beach.png").exists())
        self.assertEqual(self.names(), ["x.jpg"])


class ShuffleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.photos = Path(self.tmp.name) / "Camera"
        self.keep = Path(self.tmp.name) / "Keepers"
        self.keep.mkdir()
        self.all = [f"IMG_{i}.jpg" for i in range(1, 41)]
        for name in self.all:
            touch(self.photos / name)
        self.gallery = Gallery()
        self.gallery.open(self.photos)

    def names(self):
        return [p.name for p in self.gallery.images]

    def test_shuffle_changes_the_order_and_starts_at_the_top(self):
        self.gallery.go(5)
        self.gallery.shuffle(seed=1)
        self.assertEqual(sorted(self.names()), sorted(self.all))
        self.assertNotEqual(self.names(), self.all)
        self.assertEqual(self.gallery.index, 0)
        self.assertEqual(self.gallery.shuffle_seed, 1)

    def test_same_seed_same_order(self):
        self.gallery.shuffle(seed=7)
        first = self.names()
        other = Gallery()
        other.shuffle_seed = 7
        other.open(self.photos)
        self.assertEqual(first, [p.name for p in other.images])
        other.shuffle(seed=8)
        self.assertNotEqual(first, [p.name for p in other.images])

    def test_moving_and_undo_keep_the_shuffle(self):
        self.gallery.shuffle(seed=3)
        before = self.names()
        self.gallery.go(4)
        moving = self.gallery.current.name
        self.gallery.move_current(self.keep)
        self.assertEqual(self.names(), [n for n in before if n != moving])
        self.assertEqual(self.gallery.current.name, before[5])
        self.gallery.undo()
        self.assertEqual(self.names(), before)
        self.assertEqual(self.gallery.current.name, moving)

    def test_new_photos_land_somewhere_and_the_rest_keep_order(self):
        self.gallery.shuffle(seed=5)
        before = self.names()
        touch(self.photos / "IMG_new.jpg")
        self.gallery.reload()
        self.assertEqual([n for n in self.names() if n != "IMG_new.jpg"], before)
        self.assertIn("IMG_new.jpg", self.names())

    def test_back_to_name_order_stays_on_the_photo(self):
        self.gallery.shuffle(seed=2)
        self.gallery.go(3)
        looking = self.gallery.current.name
        self.gallery.sort_by_name()
        self.assertEqual(self.names(), sorted(self.all, key=library.natural_key))
        self.assertEqual(self.gallery.current.name, looking)
        self.assertIsNone(self.gallery.shuffle_seed)

    def test_every_position_is_reachable(self):
        seen = set()
        for seed in range(2000):
            self.gallery.shuffle(seed=seed)
            seen.add(self.names().index("IMG_1.jpg"))
        self.assertEqual(len(seen), len(self.all))

    def test_a_random_shuffle_gets_a_seed(self):
        seed = self.gallery.shuffle()
        self.assertIsInstance(seed, int)
        self.assertEqual(self.gallery.shuffle_seed, seed)


class SettingsTests(unittest.TestCase):
    def test_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "nested" / "settings.json"
            settings = Settings(
                last_folder="C:/Photos", targets=["C:/Keep", "D:/Rejects"], geometry="900x700+10+10",
                theme="gtx", shuffle_seed=12345,
            )
            settings.save(path)
            loaded = Settings.load(path)
            self.assertEqual(loaded, settings)

    def test_missing_or_broken_file_gives_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(Settings.load(Path(tmp) / "none.json"), Settings())
            broken = Path(tmp) / "broken.json"
            broken.write_text("{not json")
            self.assertEqual(Settings.load(broken), Settings())
            broken.write_text(json.dumps({"targets": [1, "", "C:/Ok"]}))
            self.assertEqual(Settings.load(broken).targets, ["C:/Ok"])
            broken.write_text(json.dumps({"shuffle_seed": "nope", "theme": ""}))
            self.assertIsNone(Settings.load(broken).shuffle_seed)
            self.assertEqual(Settings.load(broken).theme, "vortex")

    def test_add_target_dedupes_and_caps(self):
        settings = Settings()
        self.assertEqual(settings.add_target(Path("/a")), 0)
        self.assertEqual(settings.add_target(Path("/b")), 1)
        self.assertEqual(settings.add_target(Path("/a")), 0)
        for i in range(7):
            settings.add_target(Path(f"/c{i}"))
        with self.assertRaises(MoveError):
            settings.add_target(Path("/one-too-many"))

    def test_settings_location_can_be_overridden(self):
        with mock.patch.dict(os.environ, {"VORTEXVIEW_SETTINGS": "/tmp/x.json"}):
            self.assertEqual(library.settings_path(), Path("/tmp/x.json"))


if __name__ == "__main__":
    unittest.main()
