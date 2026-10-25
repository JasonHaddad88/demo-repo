package com.vortex.explorer.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.vortex.explorer.core.Entry
import com.vortex.explorer.core.EntryKind
import com.vortex.explorer.data.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Loads thumbnails and full-screen images from any source.
 *
 * Thumbnails are cached in memory and on disk, so scrolling back through a
 * shuffle of a network share does not fetch every photo again. Full images
 * are decoded at screen size, never larger, and turned the right way up.
 */
class ImageLoader(context: Context, private val registry: SourceRegistry) {
    private val resolver = context.contentResolver
    private val diskCache = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val memory = object : LruCache<String, ImageBitmap>(memoryBudgetKb()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4) / 1024
    }

    // Enough to keep a grid moving, few enough not to swamp a NAS or the heap.
    private val gate = Semaphore(4)

    fun cached(entry: Entry, size: Int): ImageBitmap? = memory.get(thumbKey(entry, size))

    suspend fun thumbnail(entry: Entry, size: Int): ImageBitmap? {
        if (entry.isFolder || entry.kind == EntryKind.AUDIO || entry.kind == EntryKind.OTHER) return null
        val key = thumbKey(entry, size)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val bitmap = fromDisk(key) ?: fromProvider(entry, size) ?: decodeEntry(entry, size)?.also { toDisk(key, it) }
                bitmap?.asImageBitmap()?.also { memory.put(key, it) }
            }
        }
    }

    /** The whole picture, scaled to fit [maxSide] pixels on its longer side. */
    suspend fun full(entry: Entry, maxSide: Int): ImageBitmap? {
        if (entry.kind != EntryKind.IMAGE) return thumbnail(entry, maxSide / 2)
        return withContext(Dispatchers.IO) {
            gate.withPermit { decodeEntry(entry, maxSide)?.asImageBitmap() }
        }
    }

    fun clearMemory() = memory.evictAll()

    // -- sources of pixels --------------------------------------------

    /** Gallery and most document providers can hand back a ready thumbnail. */
    private fun fromProvider(entry: Entry, size: Int): Bitmap? {
        if (!entry.path.startsWith("content://") || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { resolver.loadThumbnail(Uri.parse(entry.path), Size(size, size), null) }.getOrNull()
    }

    private fun decodeEntry(entry: Entry, maxSide: Int): Bitmap? {
        if (entry.kind != EntryKind.IMAGE) return null
        if (entry.size > MAX_IMAGE_BYTES) return null
        val source = registry.get(entry.sourceId) ?: return null
        val bytes = runCatching { source.open(entry.path).use { it.readCapped(MAX_IMAGE_BYTES) } }.getOrNull() ?: return null
        return decode(bytes, maxSide)
    }

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return upright(bitmap, bytes)
    }

    /** Applies the EXIF orientation phones write instead of rotating the pixels. */
    private fun upright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned != bitmap) bitmap.recycle()
        return turned
    }

    // -- disk cache -----------------------------------------------------

    private fun fromDisk(key: String): Bitmap? {
        val file = File(diskCache, key)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.path)
    }

    private fun toDisk(key: String, bitmap: Bitmap) {
        runCatching {
            File(diskCache, key).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
    }

    private fun thumbKey(entry: Entry, size: Int): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${entry.key}|${entry.modified}|${entry.size}|$size".toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    private fun InputStream.readCapped(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        /** Anything bigger is not a photo anyone wants pulled over Wi-Fi to preview. */
        const val MAX_IMAGE_BYTES = 60L * 1024 * 1024

        fun memoryBudgetKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()
    }
}
