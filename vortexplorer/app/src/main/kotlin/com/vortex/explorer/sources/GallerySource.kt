package com.vortex.explorer.sources

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.vortex.explorer.core.Capability
import com.vortex.explorer.core.Entry
import com.vortex.explorer.core.FileSource
import com.vortex.explorer.core.ReadOnlyException
import com.vortex.explorer.core.SourceException
import java.io.InputStream

/**
 * Every photo and video MediaStore knows about, grouped into the same albums
 * the system gallery shows. Read-only: changing the gallery needs per-file
 * consent from the system, which a shuffle app has no business asking for.
 *
 * Paths: `/` (albums), `/all` (everything), `/album/<bucket id>`; files are
 * their content URIs.
 */
class GallerySource(context: Context) : FileSource {
    private val resolver = context.contentResolver

    override val id: String = "gallery"
    override val displayName: String = "Photos & videos"
    override val rootPath: String = "/"
    override val capabilities: Set<Capability> = setOf(Capability.READ)

    private class Collection(val uri: Uri, val isVideo: Boolean)

    private val collections = listOf(
        Collection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false),
        Collection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true),
    )

    override fun list(path: String): List<Entry> = guarded {
        when {
            path == "/" -> albums()
            path == ALL -> media(selection = null, args = null)
            path.startsWith(ALBUM) -> {
                val bucket = path.removePrefix(ALBUM)
                media("${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(bucket))
            }
            else -> throw SourceException("no such album")
        }
    }

    private fun albums(): List<Entry> {
        val names = LinkedHashMap<String, String>()
        val newest = HashMap<String, Long>()
        for (collection in collections) {
            val projection = arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
            )
            resolver.query(collection.uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val bucket = cursor.getString(0) ?: continue
                    names.putIfAbsent(bucket, cursor.getString(1) ?: "Unnamed")
                    val modified = cursor.getLong(2) * 1000
                    if (modified > (newest[bucket] ?: 0L)) newest[bucket] = modified
                }
            }
        }
        val all = Entry(id, ALL, "All photos & videos", isFolder = true, parent = "/")
        val albums = names.map { (bucket, name) ->
            Entry(id, "$ALBUM$bucket", name, isFolder = true, modified = newest[bucket] ?: 0L, parent = "/")
        }.sortedByDescending { it.modified }
        return listOf(all) + albums
    }

    private fun media(selection: String?, args: Array<String>?): List<Entry> {
        val result = ArrayList<Entry>()
        for (collection in collections) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.BUCKET_ID,
            )
            resolver.query(collection.uri, projection, selection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection.uri, cursor.getLong(0))
                    val taken = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                    val modified = cursor.getLong(3) * 1000
                    result += Entry(
                        sourceId = id,
                        path = uri.toString(),
                        name = cursor.getString(1) ?: if (collection.isVideo) "video" else "photo",
                        isFolder = false,
                        size = if (cursor.isNull(2)) -1L else cursor.getLong(2),
                        // When the photo was taken beats when the file was last touched.
                        modified = if (taken > 0) taken else modified,
                        mime = cursor.getString(5),
                        // Album, so "spread folders" spreads across albums.
                        parent = "$ALBUM${cursor.getString(6) ?: ""}",
                    )
                }
            }
        }
        return result
    }

    override fun open(path: String): InputStream = guarded {
        resolver.openInputStream(Uri.parse(path)) ?: throw SourceException("cannot open that file")
    }

    override fun write(
        parentPath: String,
        name: String,
        input: InputStream,
        length: Long,
        mime: String?,
        overwrite: Boolean,
    ): Entry = throw ReadOnlyException(displayName)

    override fun mkdir(parentPath: String, name: String): Entry = throw ReadOnlyException(displayName)

    override fun delete(entry: Entry) = throw ReadOnlyException(displayName)

    override fun rename(entry: Entry, newName: String): Entry = throw ReadOnlyException(displayName)

    override fun describe(path: String): String = when {
        path == "/" -> displayName
        path == ALL -> "$displayName/All"
        else -> displayName
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw SourceException("VortexPlorer needs permission to see your photos and videos", e)
    }

    companion object {
        const val ALL = "/all"
        const val ALBUM = "/album/"
    }
}
