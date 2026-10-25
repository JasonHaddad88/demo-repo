package com.vortex.view.core

/**
 * Zoom and pan arithmetic, kept apart from the screen so it can be tested.
 *
 * *Image* coordinates are pixels of the original photo (upright); *view*
 * coordinates are pixels of the screen. The photo is always drawn so that
 * view = viewCentre + (image - centre) * scale.
 */
class Viewport(imageWidth: Int, imageHeight: Int, viewWidth: Float = 1f, viewHeight: Float = 1f) {
    val imageWidth: Double = imageWidth.coerceAtLeast(1).toDouble()
    val imageHeight: Double = imageHeight.coerceAtLeast(1).toDouble()
    var viewWidth: Double = viewWidth.toDouble().coerceAtLeast(1.0)
        private set
    var viewHeight: Double = viewHeight.toDouble().coerceAtLeast(1.0)
        private set

    /** null means "fit to the screen". */
    var zoom: Double? = null
        private set
    var centreX: Double = this.imageWidth / 2
        private set
    var centreY: Double = this.imageHeight / 2
        private set

    /** On a phone a small photo is enlarged to fill the screen, like any gallery. */
    val fit: Double get() = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val scale: Double get() = zoom ?: fit
    val maxZoom: Double get() = maxOf(MAX_ZOOM, fit * 4)
    val zoomed: Boolean get() = zoom != null

    fun reset() {
        zoom = null
        centreX = imageWidth / 2
        centreY = imageHeight / 2
    }

    /** Zooms so the photo point under [anchorX], [anchorY] (view coords) stays put. */
    fun setZoom(value: Double, anchorX: Double = viewWidth / 2, anchorY: Double = viewHeight / 2) {
        val target = value.coerceIn(fit, maxZoom)
        if (target <= fit * 1.0001) {
            reset()
            return
        }
        val pointX = imageX(anchorX)
        val pointY = imageY(anchorY)
        zoom = target
        centreX = pointX - (anchorX - viewWidth / 2) / target
        centreY = pointY - (anchorY - viewHeight / 2) / target
        clamp()
    }

    fun zoomBy(factor: Double, anchorX: Double = viewWidth / 2, anchorY: Double = viewHeight / 2) =
        setZoom(scale * factor, anchorX, anchorY)

    /** Double-tap: from fit, zoom in at the tapped point; when zoomed, back to fit. */
    fun toggle(anchorX: Double, anchorY: Double) {
        if (zoomed) reset() else setZoom(fit * DOUBLE_TAP_ZOOM, anchorX, anchorY)
    }

    /** Drags the photo by ([dx], [dy]) view pixels; nothing happens when fitted. */
    fun pan(dx: Double, dy: Double) {
        if (!zoomed) return
        centreX -= dx / scale
        centreY -= dy / scale
        clamp()
    }

    fun resize(width: Float, height: Float) {
        val w = width.toDouble().coerceAtLeast(1.0)
        val h = height.toDouble().coerceAtLeast(1.0)
        if (w == viewWidth && h == viewHeight) return
        viewWidth = w
        viewHeight = h
        val current = zoom
        if (current != null && current <= fit * 1.0001) reset() else clamp()
    }

    fun imageX(viewX: Double): Double = centreX + (viewX - viewWidth / 2) / scale
    fun imageY(viewY: Double): Double = centreY + (viewY - viewHeight / 2) / scale
    fun viewX(imageX: Double): Double = viewWidth / 2 + (imageX - centreX) * scale
    fun viewY(imageY: Double): Double = viewHeight / 2 + (imageY - centreY) * scale

    /** The part of the photo on screen, in image coordinates. */
    fun visible(): Region {
        if (!zoomed) return Region(0.0, 0.0, imageWidth, imageHeight)
        val halfW = viewWidth / (2 * scale)
        val halfH = viewHeight / (2 * scale)
        return Region(
            maxOf(0.0, centreX - halfW),
            maxOf(0.0, centreY - halfH),
            minOf(imageWidth, centreX + halfW),
            minOf(imageHeight, centreY + halfH),
        )
    }

    /** Keeps the photo filling the screen where it can, centred where it can't. */
    private fun clamp() {
        val halfW = viewWidth / (2 * scale)
        val halfH = viewHeight / (2 * scale)
        centreX = if (imageWidth <= 2 * halfW) imageWidth / 2 else centreX.coerceIn(halfW, imageWidth - halfW)
        centreY = if (imageHeight <= 2 * halfH) imageHeight / 2 else centreY.coerceIn(halfH, imageHeight - halfH)
    }

    companion object {
        /** 800% of the photo's own pixels: far enough to check focus on any phone photo. */
        const val MAX_ZOOM = 8.0
        const val DOUBLE_TAP_ZOOM = 2.5
    }
}

/** A rectangle of the photo, in image coordinates. */
data class Region(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    fun contains(other: Region): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/**
 * Photos from phones are stored sideways with an EXIF note saying which way
 * up they go. A decoder that reads part of a photo works on the stored
 * pixels, so a region of the upright photo has to be found in them first.
 */
object Orientation {
    const val NORMAL = 1
    const val ROTATE_180 = 3
    const val ROTATE_90 = 6
    const val ROTATE_270 = 8

    /** Whether [rawRegion] can map regions for this EXIF orientation (flips are left alone). */
    fun supported(orientation: Int): Boolean = orientation in setOf(0, NORMAL, ROTATE_180, ROTATE_90, ROTATE_270)

    /** Degrees to turn the stored pixels clockwise to make them upright. */
    fun degrees(orientation: Int): Int = when (orientation) {
        ROTATE_90 -> 90
        ROTATE_180 -> 180
        ROTATE_270 -> 270
        else -> 0
    }

    /** The stored-pixel rectangle that becomes [upright] once turned. */
    fun rawRegion(upright: Region, orientation: Int, rawWidth: Int, rawHeight: Int): Region = when (orientation) {
        ROTATE_180 -> Region(rawWidth - upright.right, rawHeight - upright.bottom, rawWidth - upright.left, rawHeight - upright.top)
        ROTATE_90 -> Region(upright.top, rawHeight - upright.right, upright.bottom, rawHeight - upright.left)
        ROTATE_270 -> Region(rawWidth - upright.bottom, upright.left, rawWidth - upright.top, upright.right)
        else -> upright
    }
}
