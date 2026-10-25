package com.vortex.explorer.sources

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.vortex.explorer.core.Capability
import com.vortex.explorer.core.Entry
import com.vortex.explorer.core.FileSource
import com.vortex.explorer.core.MediaTypes
import com.vortex.explorer.core.NameTakenException
import com.vortex.explorer.core.Paths
import com.vortex.explorer.core.SourceException
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A folder the user picked through the system picker — internal storage, an
 * SD card, or a USB drive plugged in over OTG. Paths are document URIs.
 */
class SafSource(
    context: Context,
    override val id: String,
    override val displayName: String,
    private val tree: Uri,
) : FileSource {
    private val resolver = context.contentResolver

    override val rootPath: String =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()

    override val capabilities: Set<Capability> = Capability.values().toSet()

    override fun list(path: String): List<Entry> = guarded("open ${describe(path)}") {
        val folderId = DocumentsContract.getDocumentId(Uri.parse(path))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cursor = resolver.query(children, COLUMNS, null, null, null)
            ?: throw SourceException("cannot read ${describe(path)}")
        cursor.use {
            val result = ArrayList<Entry>(it.count)
            while (it.moveToNext()) {
                val documentId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                val isFolder = mime == Document.MIME_TYPE_DIR
                result += Entry(
                    sourceId = id,
                    path = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
                    name = name,
                    isFolder = isFolder,
                    size = if (isFolder || it.isNull(3)) -1L else it.getLong(3),
                    modified = if (it.isNull(4)) 0L else it.getLong(4),
                    mime = if (isFolder) null else mime,
                    parent = path,
                )
            }
            result
        }
    }

    override fun open(path: String): InputStream = guarded("open ${nameOf(path)}") {
        resolver.openInputStream(Uri.parse(path)) ?: throw SourceException("cannot open ${nameOf(path)}")
    }

    override fun write(
        parentPath: String,
        name: String,
        input: InputStream,
        length: Long,
        mime: String?,
        overwrite: Boolean,
    ): Entry = guarded("save $name") {
        Paths.requireValidName(name)
        val existing = list(parentPath).firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null && !overwrite) throw NameTakenException(name)
        val type = mime ?: MediaTypes.mimeFor(name)
        val target = if (existing != null && !existing.isFolder) {
            Uri.parse(existing.path)
        } else {
            DocumentsContract.createDocument(resolver, Uri.parse(parentPath), type, name)
                ?: throw SourceException("could not create $name here")
        }
        var finished = false
        try {
            // "wt" truncates when replacing; providers that lack it fall back to "w".
            val output = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
                ?: resolver.openOutputStream(target, "w")
                ?: throw SourceException("could not write $name")
            val written = output.use { input.copyTo(it, BUFFER) }
            finished = true
            Entry(id, target.toString(), name, false, written, System.currentTimeMillis(), type, parentPath)
        } finally {
            if (!finished && existing == null) {
                runCatching { DocumentsContract.deleteDocument(resolver, target) }
            }
        }
    }

    override fun mkdir(parentPath: String, name: String): Entry = guarded("create folder $name") {
        Paths.requireValidName(name)
        // Providers quietly pick "name (1)" for a taken name; refuse instead.
        if (list(parentPath).any { it.name.equals(name, ignoreCase = true) }) throw NameTakenException(name)
        val created = DocumentsContract.createDocument(resolver, Uri.parse(parentPath), Document.MIME_TYPE_DIR, name)
            ?: throw SourceException("could not create folder $name")
        Entry(id, created.toString(), name, true, parent = parentPath)
    }

    override fun delete(entry: Entry) = guarded("delete ${entry.name}") {
        if (entry.path == rootPath) throw SourceException("will not delete the whole of $displayName")
        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(entry.path))) {
            throw SourceException("could not delete ${entry.name}")
        }
    }

    override fun rename(entry: Entry, newName: String): Entry = guarded("rename ${entry.name}") {
        Paths.requireValidName(newName)
        val clash = list(entry.parent).any {
            it.name.equals(newName, ignoreCase = true) && it.path != entry.path
        }
        if (clash) throw NameTakenException(newName)
        val renamed = DocumentsContract.renameDocument(resolver, Uri.parse(entry.path), newName)
        entry.copy(path = (renamed ?: Uri.parse(entry.path)).toString(), name = newName)
    }

    /** "primary:DCIM/Camera" becomes "USB drive/DCIM/Camera". */
    override fun describe(path: String): String {
        val documentId = runCatching { DocumentsContract.getDocumentId(Uri.parse(path)) }.getOrNull()
            ?: return displayName
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val relative = documentId.removePrefix(rootId).trimStart('/')
        return if (relative.isEmpty()) displayName else "$displayName/$relative"
    }

    private fun nameOf(path: String): String = describe(path).substringAfterLast('/')

    private inline fun <T> guarded(what: String, block: () -> T): T = try {
        block()
    } catch (e: SourceException) {
        throw e
    } catch (e: SecurityException) {
        throw SourceException("access to $displayName was withdrawn — remove it and add it again", e)
    } catch (e: FileNotFoundException) {
        throw SourceException("could not $what: not found (was the drive unplugged?)", e)
    } catch (e: IllegalArgumentException) {
        throw SourceException("could not $what: ${e.message}", e)
    } catch (e: IllegalStateException) {
        throw SourceException("could not $what: ${e.message}", e)
    }

    private companion object {
        const val BUFFER = 256 * 1024
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
