import unittest

from vortexview.viewport import Viewport


class ViewportTests(unittest.TestCase):
    def photo(self):
        # A 6000x4000 photo in a 1000x800 window.
        return Viewport(6000, 4000, 1000, 800)

    def test_starts_fitted(self):
        vp = self.photo()
        self.assertFalse(vp.zoomed)
        self.assertAlmostEqual(vp.scale, 1000 / 6000)
        box, size, origin = vp.layout()
        self.assertEqual(box, (0, 0, 6000, 4000))
        self.assertEqual(size, (1000, 667))
        self.assertEqual(origin, (0, 67))  # letterboxed top and bottom

    def test_small_photos_are_not_enlarged_to_fit(self):
        vp = Viewport(400, 300, 1000, 800)
        self.assertEqual(vp.fit, 1.0)
        self.assertEqual(vp.layout()[1], (400, 300))

    def test_zoom_keeps_the_point_under_the_cursor(self):
        vp = self.photo()
        anchor = (250.0, 300.0)
        before = vp.image_point(*anchor)
        vp.set_zoom(1.0, anchor)
        after = vp.image_point(*anchor)
        self.assertAlmostEqual(before[0], after[0], places=6)
        self.assertAlmostEqual(before[1], after[1], places=6)

    def test_zoom_steps_compound(self):
        vp = self.photo()
        vp.zoom_by(1)
        self.assertAlmostEqual(vp.scale, vp.fit * 1.25)
        vp.zoom_by(2)
        self.assertAlmostEqual(vp.scale, vp.fit * 1.25 ** 3)

    def test_zooming_out_past_fit_returns_to_fit(self):
        vp = self.photo()
        vp.zoom_by(2)
        vp.zoom_by(-5)
        self.assertFalse(vp.zoomed)
        self.assertEqual((vp.cx, vp.cy), (3000, 2000))

    def test_zoom_is_capped(self):
        vp = self.photo()
        vp.set_zoom(100)
        self.assertEqual(vp.scale, Viewport.MAX_ZOOM)

    def test_at_100_percent_the_visible_region_is_the_window_size(self):
        vp = self.photo()
        vp.set_zoom(1.0)
        box, size, origin = vp.layout()
        self.assertEqual(size, (1000, 800))
        self.assertEqual(origin, (0, 0))
        self.assertAlmostEqual(box[2] - box[0], 1000)
        self.assertAlmostEqual(box[3] - box[1], 800)

    def test_panning_moves_the_view_and_stops_at_the_edges(self):
        vp = self.photo()
        vp.set_zoom(1.0)
        vp.pan(-200, 0)  # drag left: see more of the right side
        self.assertAlmostEqual(vp.cx, 3200)
        vp.pan(100000, 100000)  # far past the top-left corner
        box = vp.layout()[0]
        self.assertEqual((box[0], box[1]), (0, 0))
        vp.pan(-100000, -100000)
        box = vp.layout()[0]
        self.assertEqual((box[2], box[3]), (6000, 4000))

    def test_panning_does_nothing_when_fitted(self):
        vp = self.photo()
        vp.pan(300, 300)
        self.assertEqual((vp.cx, vp.cy), (3000, 2000))

    def test_a_dimension_that_fits_stays_centred(self):
        # Wide panorama: zoomed so its height still fits the window.
        vp = Viewport(8000, 1000, 1000, 800)
        vp.set_zoom(0.5)
        vp.pan(0, 500)
        self.assertEqual(vp.cy, 500)
        _box, size, origin = vp.layout()
        self.assertEqual(size[1], 500)
        self.assertEqual(origin[1], 150)

    def test_toggle_actual_size(self):
        vp = self.photo()
        vp.toggle_actual_size((500, 400))
        self.assertEqual(vp.scale, 1.0)
        vp.toggle_actual_size()
        self.assertFalse(vp.zoomed)
        small = Viewport(400, 300, 1000, 800)
        small.toggle_actual_size()
        self.assertEqual(small.scale, 2.0)

    def test_resizing_the_window_keeps_the_zoom(self):
        vp = self.photo()
        vp.set_zoom(1.0)
        vp.resize(1600, 1000)
        self.assertEqual(vp.scale, 1.0)
        self.assertEqual(vp.layout()[1], (1600, 1000))

    def test_growing_the_window_past_a_small_zoom_falls_back_to_fit(self):
        vp = self.photo()
        vp.set_zoom(0.2)
        vp.resize(4000, 3000)  # now fit is 0.667, above the old zoom
        self.assertFalse(vp.zoomed)

    def test_percent(self):
        vp = self.photo()
        self.assertEqual(vp.percent, 17)
        vp.set_zoom(1.5)
        self.assertEqual(vp.percent, 150)


if __name__ == "__main__":
    unittest.main()
