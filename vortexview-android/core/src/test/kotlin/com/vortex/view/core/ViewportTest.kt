package com.vortex.view.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportTest {
    // A 4000x3000 phone photo on a 1080x2340 portrait screen.
    private fun photo() = Viewport(4000, 3000, 1080f, 2340f)

    @Test
    fun `starts fitted to the screen width`() {
        val vp = photo()
        assertFalse(vp.zoomed)
        assertEquals(1080.0 / 4000, vp.scale, 1e-9)
        assertEquals(Region(0.0, 0.0, 4000.0, 3000.0), vp.visible())
        assertEquals((2340 - 810) / 2.0, vp.viewY(0.0), 1e-9, "letterboxed top and bottom")
    }

    @Test
    fun `small photos are enlarged to fill the screen`() {
        val vp = Viewport(540, 400, 1080f, 2340f)
        assertEquals(2.0, vp.fit, 1e-9)
    }

    @Test
    fun `pinching keeps the point under the fingers still`() {
        val vp = photo()
        // Away from the edges, where keeping the photo on screen would win.
        val before = vp.imageX(300.0) to vp.imageY(1200.0)
        vp.zoomBy(6.0, 300.0, 1200.0)
        assertEquals(before.first, vp.imageX(300.0), 1e-6)
        assertEquals(before.second, vp.imageY(1200.0), 1e-6)
    }

    @Test
    fun `zooming out past fit returns to fit and recentres`() {
        val vp = photo()
        vp.zoomBy(3.0, 100.0, 100.0)
        vp.zoomBy(0.1)
        assertFalse(vp.zoomed)
        assertEquals(2000.0, vp.centreX, 1e-9)
    }

    @Test
    fun `zoom is capped`() {
        val vp = photo()
        vp.setZoom(1000.0)
        assertEquals(Viewport.MAX_ZOOM, vp.scale, 1e-9)
    }

    @Test
    fun `panning stops at the edges`() {
        val vp = photo()
        vp.setZoom(1.0)
        vp.pan(1e6, 1e6)
        assertEquals(0.0, vp.visible().left, 1e-9)
        assertEquals(0.0, vp.visible().top, 1e-9)
        vp.pan(-1e6, -1e6)
        assertEquals(4000.0, vp.visible().right, 1e-9)
        assertEquals(3000.0, vp.visible().bottom, 1e-9)
    }

    @Test
    fun `panning a fitted photo does nothing`() {
        val vp = photo()
        vp.pan(200.0, 200.0)
        assertEquals(2000.0, vp.centreX, 1e-9)
    }

    @Test
    fun `a side that fits stays centred`() {
        val vp = photo()
        vp.setZoom(0.5) // 2000x1500 on screen: wider than 1080, shorter than 2340
        vp.pan(0.0, 500.0)
        assertEquals(1500.0, vp.centreY, 1e-9)
        vp.pan(-300.0, 0.0)
        assertEquals(2600.0, vp.centreX, 1e-9)
    }

    @Test
    fun `double tap zooms in where tapped, then back to fit`() {
        val vp = photo()
        vp.toggle(540.0, 1170.0)
        assertEquals(vp.fit * Viewport.DOUBLE_TAP_ZOOM, vp.scale, 1e-9)
        vp.toggle(0.0, 0.0)
        assertFalse(vp.zoomed)
    }

    @Test
    fun `turning the phone keeps the zoom`() {
        val vp = photo()
        vp.setZoom(1.0)
        vp.resize(2340f, 1080f)
        assertEquals(1.0, vp.scale, 1e-9)
        assertTrue(vp.zoomed)
    }
}

class OrientationTest {
    // Where a stored pixel ends up once the photo is turned upright.
    private fun upright(x: Double, y: Double, orientation: Int, rawW: Int, rawH: Int): Pair<Double, Double> =
        when (orientation) {
            Orientation.ROTATE_90 -> (rawH - y) to x
            Orientation.ROTATE_180 -> (rawW - x) to (rawH - y)
            Orientation.ROTATE_270 -> y to (rawW - x)
            else -> x to y
        }

    @Test
    fun `regions of the upright photo are found in the stored pixels`() {
        val rawW = 4000
        val rawH = 3000
        for (orientation in listOf(Orientation.NORMAL, Orientation.ROTATE_90, Orientation.ROTATE_180, Orientation.ROTATE_270)) {
            for ((x, y) in listOf(10.5 to 20.5, 3990.5 to 15.5, 1234.5 to 2900.5)) {
                val (u, v) = upright(x, y, orientation, rawW, rawH)
                val tiny = Region(u - 0.25, v - 0.25, u + 0.25, v + 0.25)
                val raw = Orientation.rawRegion(tiny, orientation, rawW, rawH)
                assertTrue(
                    raw.left <= x && x <= raw.right && raw.top <= y && y <= raw.bottom,
                    "orientation $orientation: ($x, $y) should be in $raw",
                )
                assertEquals(0.5, raw.width, 1e-9)
            }
        }
    }

    @Test
    fun `the whole upright photo is the whole stored photo`() {
        val whole = Region(0.0, 0.0, 3000.0, 4000.0) // upright size of a sideways 4000x3000
        assertEquals(Region(0.0, 0.0, 4000.0, 3000.0), Orientation.rawRegion(whole, Orientation.ROTATE_90, 4000, 3000))
        assertEquals(Region(0.0, 0.0, 4000.0, 3000.0), Orientation.rawRegion(whole, Orientation.ROTATE_270, 4000, 3000))
        assertEquals(90, Orientation.degrees(Orientation.ROTATE_90))
        assertFalse(Orientation.supported(5))
    }
}
