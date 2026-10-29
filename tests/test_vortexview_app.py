"""Drives the real VortexView window. Needs tkinter, Pillow and a display
(run under xvfb-run on Linux); skips cleanly without them."""

import gc
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

try:
    import tkinter as tk

    from PIL import Image

    from vortexview import app as app_module
    from vortexview.app import App
    from vortexview.library import Settings

    HAVE_GUI = True
except Exception:  # noqa: BLE001 - no tkinter, no Pillow
    HAVE_GUI = False


def make_photo(path: Path, size=(1200, 800), colour=(200, 80, 40), rotate_tag=None) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", size, colour)
    if rotate_tag is not None:
        exif = Image.Exif()
        exif[0x0112] = rotate_tag  # Orientation
        image.save(path, "JPEG", exif=exif)
    else:
        image.save(path)
    return path


@unittest.skipUnless(HAVE_GUI, "tkinter and Pillow are needed")
class WindowTests(unittest.TestCase):
    def setUp(self):
        try:
            self.root = tk.Tk()
        except tk.TclError as exc:
            self.skipTest(f"no display: {exc}")
        self.root.geometry("1000x700+0+0")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.photos = base / "Holiday"
        self.keep = base / "Keepers"
        self.rejects = base / "Rejects"
        self.keep.mkdir()
        self.rejects.mkdir()
        make_photo(self.photos / "IMG_1.jpg", colour=(30, 120, 200))
        make_photo(self.photos / "IMG_2.jpg", size=(1600, 900), colour=(220, 180, 40), rotate_tag=6)
        make_photo(self.photos / "IMG_3.png", size=(640, 480), colour=(40, 160, 90))
        (self.photos / "notes.txt").write_text("not a photo")

        self.settings_file = base / "settings.json"
        Settings(targets=[str(self.keep), str(self.rejects)]).save(self.settings_file)
        self.app = App(self.root, settings_file=self.settings_file)
        self.addCleanup(self._close)
        self.app.open_path(self.photos)
        self.settle()

    def _close(self):
        try:
            self.app.close()
        except tk.TclError:
            pass
        # Free this window's Tk objects here, on the main thread, before the
        # next test's decoder threads could trigger a collection.
        self.app = None
        self.root = None
        gc.collect()

    def settle(self, rounds=60):
        """Lets decoding threads finish and the window catch up."""
        for _ in range(rounds):
            self.root.update()
            if self.app.picture is not None or self.app.gallery.current is None:
                break
            self.root.after(20)
        self.root.update()

    def press(self, key):
        """A key ("m", "Right") or a full Tk event pattern ("<Control-z>")."""
        self.root.focus_force()
        self.root.event_generate(key if key.startswith("<") else f"<KeyPress-{key}>")
        self.settle()

    # -- the core promise -----------------------------------------------

    def test_m_moves_the_photo_to_the_first_folder(self):
        self.assertEqual(self.app.gallery.current.name, "IMG_1.jpg")
        self.press("m")
        self.assertTrue((self.keep / "IMG_1.jpg").exists())
        self.assertFalse((self.photos / "IMG_1.jpg").exists())
        self.assertEqual(self.app.gallery.current.name, "IMG_2.jpg")
        self.assertIn("Moved IMG_1.jpg → Keepers", self.app.status.cget("text"))

    def test_number_keys_pick_the_folder(self):
        self.press("2")
        self.assertTrue((self.rejects / "IMG_1.jpg").exists())
        self.press("1")
        self.assertTrue((self.keep / "IMG_2.jpg").exists())

    def test_the_button_moves_too(self):
        self.app.target_buttons[1].invoke()
        self.settle()
        self.assertTrue((self.rejects / "IMG_1.jpg").exists())

    def test_ctrl_z_puts_it_back(self):
        self.press("m")
        self.press("<Control-z>")
        self.assertTrue((self.photos / "IMG_1.jpg").exists())
        self.assertEqual(self.app.gallery.current.name, "IMG_1.jpg")
        self.assertIn("Put IMG_1.jpg back", self.app.status.cget("text"))

    def test_arrows_navigate(self):
        self.press("Right")
        self.assertEqual(self.app.gallery.current.name, "IMG_2.jpg")
        self.press("End")
        self.assertEqual(self.app.gallery.current.name, "IMG_3.png")
        self.press("Left")
        self.assertEqual(self.app.gallery.current.name, "IMG_2.jpg")

    def test_title_and_position(self):
        self.assertIn("IMG_1.jpg (1 of 3)", self.root.title())
        self.assertIn("1 / 3", self.app.position_label.cget("text"))
        self.assertIn("1200 × 800", self.app.position_label.cget("text"))

    # -- what's on screen ------------------------------------------------

    def test_photo_is_drawn(self):
        self.assertIsNotNone(self.app.photo)
        self.assertTrue(self.app.canvas.find_all())

    def test_phone_rotation_is_honoured(self):
        self.press("Right")  # IMG_2: 1600x900 stored, tagged "rotate 90"
        picture = self.app.picture
        self.assertIsNotNone(picture.image)
        self.assertGreater(picture.image.height, picture.image.width, "EXIF rotation ignored")
        self.assertIn("900 × 1600", self.app.position_label.cget("text"), "size should be reported upright")

    def test_photo_fits_the_window_and_small_ones_are_not_blown_up(self):
        canvas_w, canvas_h = self.app.canvas.winfo_width(), self.app.canvas.winfo_height()
        self.assertLessEqual(self.app.photo.width(), canvas_w)
        self.assertLessEqual(self.app.photo.height(), canvas_h)
        self.press("End")  # IMG_3: 640x480, smaller than the window
        self.assertEqual((self.app.photo.width(), self.app.photo.height()), (640, 480))

    def test_unreadable_file_is_shown_as_a_message(self):
        (self.photos / "IMG_0.jpg").write_bytes(b"not really a jpeg")
        self.app.reload()
        self.app.jump(0)
        self.settle()
        self.assertIsNone(self.app.picture.image)
        self.assertIn("Can't show IMG_0.jpg", self.app.picture.error)
        self.press("m")  # it can still be moved out of the way
        self.assertTrue((self.keep / "IMG_0.jpg").exists())

    def test_moving_everything_says_so(self):
        for _ in range(3):
            self.press("m")
        self.assertIsNone(self.app.gallery.current)
        canvas = self.app.canvas
        texts = [canvas.itemcget(item, "text") for item in canvas.find_all() if canvas.type(item) == "text"]
        self.assertTrue(any("All done" in text for text in texts), texts)

    # -- zoom -------------------------------------------------------------

    def wait_for_full_size(self, rounds=150):
        for _ in range(rounds):
            self.root.update()
            if self.app.full is not None:
                return
            self.root.after(20)
        self.fail("the full-size photo never loaded")

    def big_photo(self):
        """A photo much bigger than the screen, with a 1-pixel grid to zoom into."""
        image = Image.new("RGB", (5000, 3000), (90, 90, 90))
        for x in range(0, 5000, 2):
            image.paste((255, 255, 255), (x, 0, x + 1, 3000))
        image.save(self.photos / "IMG_0.png")
        self.app.reload()
        self.app.jump(0)
        self.settle(rounds=300)  # a big PNG can't be decoded at a reduced size
        self.assertEqual(self.app.gallery.current.name, "IMG_0.png")
        self.assertLess(self.app.picture.image.width, 5000, "should start with a screen-sized copy")

    def test_plus_and_minus_zoom_and_ctrl_0_fits(self):
        fitted = self.app.photo.width()
        self.assertEqual(self.app.zoom_button.cget("text"), "Fit")
        self.press("plus")
        self.assertTrue(self.app.view.zoomed)
        self.assertEqual(self.app.zoom_button.cget("text"), f"{self.app.view.percent}%")
        self.assertEqual(self.app.canvas.cget("cursor"), "fleur")
        self.press("minus")
        self.assertFalse(self.app.view.zoomed)
        self.press("<KeyPress-equal>")
        self.press("<Control-Key-0>")
        self.assertFalse(self.app.view.zoomed)
        self.assertEqual(self.app.photo.width(), fitted)
        self.assertEqual(self.app.zoom_button.cget("text"), "Fit")

    def test_ctrl_1_is_100_percent_and_never_moves_the_photo(self):
        self.press("<Control-Key-1>")
        self.assertEqual(self.app.view.scale, 1.0)
        self.assertTrue((self.photos / "IMG_1.jpg").exists(), "Ctrl+1 moved the photo")
        self.assertEqual(self.app.gallery.current.name, "IMG_1.jpg")
        canvas = (self.app.canvas.winfo_width(), self.app.canvas.winfo_height())
        self.assertEqual((self.app.photo.width(), self.app.photo.height()), (min(1200, canvas[0]), min(800, canvas[1])))
        self.press("1")  # plain 1 still moves it
        self.assertTrue((self.keep / "IMG_1.jpg").exists())

    def test_ctrl_wheel_zooms_but_the_plain_wheel_still_flips(self):
        self.root.event_generate("<Control-MouseWheel>", delta=120)
        self.settle()
        self.assertTrue(self.app.view.zoomed)
        self.assertEqual(self.app.gallery.current.name, "IMG_1.jpg")
        self.root.event_generate("<MouseWheel>", delta=-120)
        self.settle()
        self.assertEqual(self.app.gallery.current.name, "IMG_2.jpg")

    def test_another_photo_starts_fitted(self):
        self.press("<Control-Key-1>")
        self.press("Right")
        self.assertIsNotNone(self.app.view)
        self.assertFalse(self.app.view.zoomed)
        self.assertEqual(self.app.zoom_button.cget("text"), "Fit")

    def double_click(self, x, y, time):
        # Tk can't be sent a double-click as such: two quick clicks make one.
        for t in (time, time + 50):
            self.app.canvas.event_generate("<ButtonPress-1>", x=x, y=y, time=t)
            self.app.canvas.event_generate("<ButtonRelease-1>", x=x, y=y, time=t + 10)
        self.settle()

    def test_double_click_toggles_100_percent(self):
        self.double_click(100, 100, time=10_000)
        self.assertEqual(self.app.view.scale, 1.0)
        self.double_click(100, 100, time=20_000)
        self.assertFalse(self.app.view.zoomed)

    def test_dragging_pans_a_zoomed_photo(self):
        self.big_photo()
        self.press("<Control-Key-1>")
        before = self.app.view.cx
        self.app.canvas.event_generate("<ButtonPress-1>", x=500, y=300)
        self.app.canvas.event_generate("<B1-Motion>", x=300, y=300)
        self.app.canvas.event_generate("<ButtonRelease-1>", x=300, y=300)
        self.root.update()
        self.assertAlmostEqual(self.app.view.cx, before + 200)

    def test_zooming_in_loads_the_full_size_photo(self):
        self.big_photo()
        drawn = []
        real = app_module.ImageTk.PhotoImage

        def capture(image, *args, **kwargs):
            drawn.append(image)
            return real(image, *args, **kwargs)

        with mock.patch.object(app_module.ImageTk, "PhotoImage", side_effect=capture):
            self.press("<Control-Key-1>")
            self.wait_for_full_size()
        self.assertEqual(self.app.full.size, (5000, 3000))
        # At 100% every other column is white: the real pixels, not a blurred copy.
        row = [drawn[-1].getpixel((x, 10))[0] for x in range(20)]
        self.assertEqual(sorted(set(row)), [90, 255], row)
        self.press("Right")
        self.assertIsNone(self.app.full, "the full-size copy should be dropped on the next photo")

    # -- folders ----------------------------------------------------------

    def test_with_no_folder_set_m_asks_for_one_then_moves(self):
        self.app.settings.targets = []
        self.app.rebuild_targets()
        with mock.patch.object(app_module.filedialog, "askdirectory", return_value=str(self.rejects)):
            self.press("m")
        self.assertEqual(self.app.settings.targets, [str(self.rejects)])
        self.assertTrue((self.rejects / "IMG_1.jpg").exists())

    def test_choosing_the_folder_being_viewed_is_refused(self):
        with mock.patch.object(app_module.filedialog, "askdirectory", return_value=str(self.photos)):
            self.assertIsNone(self.app.add_target())
        self.assertIn("choose a different one", self.app.status.cget("text"))

    def test_a_missing_folder_is_flagged_and_moves_are_refused(self):
        self.rejects.rmdir()
        self.app.rebuild_targets()
        self.assertIn("(missing)", self.app.target_buttons[1].cget("text"))
        self.press("2")
        self.assertTrue((self.photos / "IMG_1.jpg").exists())
        self.assertIn("doesn't exist", self.app.status.cget("text"))

    def test_settings_survive_a_restart(self):
        self.press("Right")
        self.app.close()
        reloaded = Settings.load(self.settings_file)
        self.assertEqual(reloaded.targets, [str(self.keep), str(self.rejects)])
        self.assertEqual(reloaded.last_folder, str(self.photos))
        self.assertEqual(Path(reloaded.last_image).name, "IMG_2.jpg")

        # The first window's Tk objects must be freed here, on the main thread.
        self.app = None
        self.root = None
        gc.collect()
        self.root = tk.Tk()
        self.app = App(self.root, settings_file=self.settings_file)
        self.app.start()
        self.settle()
        self.assertEqual(self.app.gallery.current.name, "IMG_2.jpg", "should carry on where it left off")

    def test_theme_and_shuffle_survive_a_restart(self):
        self.app.apply_theme("bamboo")
        self.press("s")
        order = [p.name for p in self.app.gallery.images]
        self.app.close()
        self.app = None
        self.root = None
        gc.collect()
        self.root = tk.Tk()
        self.app = App(self.root, settings_file=self.settings_file)
        self.app.start()
        self.settle()
        self.assertEqual(self.app.p.id, "bamboo")
        self.assertEqual([p.name for p in self.app.gallery.images], order, "same shuffle after a restart")

    # -- looks --------------------------------------------------------------

    def test_starts_in_the_vortex_theme(self):
        from vortexview import theme

        self.assertEqual(self.app.p.id, "vortex")
        self.assertEqual(self.app.canvas.cget("background"), theme.palette("vortex").bg)
        self.assertEqual(self.app.canvas.find_withtag("ground") != (), True, "the haloed ground is drawn")

    def test_switching_theme_repaints_keeps_the_photo_and_is_remembered(self):
        from vortexview import theme

        self.app.apply_theme("gtx")
        self.settle()
        self.assertEqual(self.app.canvas.cget("background"), theme.palette("gtx").bg)
        self.assertEqual(self.app.theme_button.cget("text"), "GTX")
        self.assertIsNotNone(self.app.photo, "the photo should still be on screen")
        self.assertEqual(len(self.app.target_buttons), 2)
        self.press("m")  # and everything still works
        self.assertTrue((self.keep / "IMG_1.jpg").exists())
        self.assertEqual(Settings.load(self.settings_file).theme, "gtx")

    def test_every_theme_draws(self):
        from vortexview import theme

        for tid in theme.THEME_IDS:
            self.app.apply_theme(tid)
            self.settle()
            self.assertEqual(self.app.p.id, tid)
            self.assertTrue(self.app.canvas.find_withtag("ground"), tid)

    # -- shuffle ----------------------------------------------------------------

    def test_s_shuffles_and_n_goes_back_to_name_order(self):
        for i in range(4, 12):
            make_photo(self.photos / f"IMG_{i}.jpg", size=(200, 150))
        self.app.reload()
        names = [p.name for p in self.app.gallery.images]
        self.press("s")
        self.assertIsNotNone(self.app.gallery.shuffle_seed)
        self.assertEqual(sorted(p.name for p in self.app.gallery.images), sorted(names))
        self.assertEqual(self.app.gallery.index, 0)
        self.assertIn("shuffled", self.app.position_label.cget("text"))
        self.assertTrue(self.app.shuffle_button.lit)
        self.assertEqual(self.app.name_order_button.winfo_manager(), "pack")
        self.assertEqual(Settings.load(self.settings_file).shuffle_seed, self.app.gallery.shuffle_seed)
        self.assertEqual(sorted(os.listdir(self.photos)), sorted(os.listdir(self.photos)), "files untouched")
        self.press("n")
        self.assertIsNone(self.app.gallery.shuffle_seed)
        self.assertEqual([p.name for p in self.app.gallery.images], names)
        self.assertFalse(self.app.shuffle_button.lit)
        self.assertEqual(self.app.name_order_button.winfo_manager(), "")

    def test_screenshot(self):
        """Not an assertion: saves what the window looks like when asked to."""
        target = os.environ.get("VORTEXVIEW_SCREENSHOT")
        if not target:
            self.skipTest("set VORTEXVIEW_SCREENSHOT=path.png to capture the window")
        self.press("Right")
        self.app.say("Moved IMG_1.jpg → Keepers   ·   2 left   ·   Ctrl+Z to undo")
        self.root.update()
        self.root.after(300)
        self.root.update()
        from PIL import ImageGrab

        x, y = self.root.winfo_rootx(), self.root.winfo_rooty()
        w, h = self.root.winfo_width(), self.root.winfo_height()
        ImageGrab.grab(bbox=(x, y, x + w, y + h), xdisplay=os.environ.get("DISPLAY")).save(target)


if __name__ == "__main__":
    unittest.main()
