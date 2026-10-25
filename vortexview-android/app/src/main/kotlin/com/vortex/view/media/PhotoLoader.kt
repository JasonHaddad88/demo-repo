package com.vortex.view.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.vortex.view.core.Orientation
import com.vortex.view.core.Photo
import com.vortex.view.core.Region
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A photo decoded at screen size; [width] and [height] are the original's, upright. */
class Decoded(val bitmap: ImageBitmap, val width: Int, val height: Int)

/** Part of a photo at more detail than [Decoded] has, for zooming in. */
class Detail(val bitmap: ImageBitmap, val region: Region, val sample: Int)

/**
 * Decodes photos at screen size on background threads and keeps the last
 * few, so the next swipe is instant. For zooming in, decodes just the part
 * on screen at full resolution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoLoader(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val regionThread = Dispatchers.IO.limitedParallelism(1)
    private val cache = object : LruCache<String, Decoded>(cacheBudget()) {
        override fun sizeOf(key: String, value: Decoded): Int = value.bitmap.width * value.bitmap.height * 4
    }
    private val running = ConcurrentHashMap<String, Deferred<Decoded>>()

    fun cached(photo: Photo): Decoded? = cache.get(photo.id)

    /** The photo fitted within [maxSide] pixels. Throws if it can't be read. */
    suspend fun load(photo: Photo, maxSide: Int): Decoded {
        cache.get(photo.id)?.let { return it }
        return start(photo, maxSide).await()
    }

    /** Starts decoding the photos you are likely to look at next. */
    fun prefetch(photos: List<Photo>, maxSide: Int) {
        photos.forEach { if (cache.get(it.id) == null) start(it, maxSide) }
    }

    fun forget(photo: Photo) {
        cache.remove(photo.id)
    }

    private fun start(photo: Photo, maxSide: Int): Deferred<Decoded> {
        // Started only once it is in [running], so its own clean-up always finds it there.
        val job = running.computeIfAbsent(photo.id) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    decode(Uri.parse(photo.id), maxSide).also { cache.put(photo.id, it) }
                } finally {
                    running.remove(photo.id)
                }
            }
        }
        job.start()
        return job
    }

    private fun decode(uri: Uri, maxSide: Int): Decoded {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var width = 0
            var height = 0
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                // ImageDecoder turns photos the right way up itself, and reports the upright size.
                width = info.size.width
                height = info.size.height
                val longest = maxOf(width, height)
                if (longest > maxSide) {
                    val scale = maxSide.toDouble() / longest
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                // Software bitmaps can carry mipmaps, which keep a shrunk photo crisp.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { true } // show what there is of a damaged file
            }
            bitmap.setHasMipMap(true)
            return Decoded(bitmap.asImageBitmap(), width, height)
        }

        // Android 8: BitmapFactory, then turn the pixels by the EXIF note.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("not a picture Android can read")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("not a picture Android can read")
        val orientation = orientationOf(uri)
        val upright = turn(raw, Orientation.degrees(orientation))
        val sideways = Orientation.degrees(orientation) % 180 != 0
        val width = if (sideways) bounds.outHeight else bounds.outWidth
        val height = if (sideways) bounds.outWidth else bounds.outHeight
        upright.setHasMipMap(true)
        return Decoded(upright.asImageBitmap(), width, height)
    }

    // -- zoomed-in detail ---------------------------------------------------

    private class RegionSource(val key: String, val decoder: BitmapRegionDecoder, val orientation: Int)

    private var regions: RegionSource? = null

    /**
     * The part [wanted] of the upright photo, shrunk by [sample] (1, 2, 4…).
     * Null when this kind of file can't be read in parts.
     */
    suspend fun detail(photo: Photo, decoded: Decoded, wanted: Region, sample: Int): Detail? = withContext(regionThread) {
        runCatching {
            val source = regionSource(photo) ?: return@runCatching null
            val decoder = source.decoder
            val sideways = Orientation.degrees(source.orientation) % 180 != 0
            val uprightW = if (sideways) decoder.height else decoder.width
            val uprightH = if (sideways) decoder.width else decoder.height
            if (uprightW != decoded.width || uprightH != decoded.height) return@runCatching null

            // Whole pixels, so the stored-pixel rectangle is whole pixels too.
            val region = Region(
                floor(wanted.left).coerceAtLeast(0.0),
                floor(wanted.top).coerceAtLeast(0.0),
                ceil(wanted.right).coerceAtMost(uprightW.toDouble()),
                ceil(wanted.bottom).coerceAtMost(uprightH.toDouble()),
            )
            if (region.width < 1 || region.height < 1) return@runCatching null
            val raw = Orientation.rawRegion(region, source.orientation, decoder.width, decoder.height)
            val rect = Rect(raw.left.toInt(), raw.top.toInt(), raw.right.toInt(), raw.bottom.toInt())
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = decoder.decodeRegion(rect, options) ?: return@runCatching null
            Detail(turn(bitmap, Orientation.degrees(source.orientation)).asImageBitmap(), region, sample)
        }.getOrNull()
    }

    /** Lets go of the full-resolution reader once you move on from a photo. */
    suspend fun releaseDetail() = withContext(regionThread) {
        regions?.decoder?.recycle()
        regions = null
    }

    private fun regionSource(photo: Photo): RegionSource? {
        regions?.let { if (it.key == photo.id) return it }
        regions?.decoder?.recycle()
        regions = null
        val uri = Uri.parse(photo.id)
        val orientation = orientationOf(uri)
        if (!Orientation.supported(orientation)) return null
        val decoder = resolver.openInputStream(uri)?.use { stream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }
        } ?: return null
        return RegionSource(photo.id, decoder, orientation).also { regions = it }
    }

    // -- helpers ------------------------------------------------------------

    private fun orientationOf(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private fun turn(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned != bitmap) bitmap.recycle()
        return turned
    }

    fun close() {
        scope.cancel()
        cache.evictAll()
        regions?.decoder?.recycle()
        regions = null
    }

    private companion object {
        /** A sixth of the app's memory: about five full-screen photos on most phones. */
        fun cacheBudget(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
