package com.vortex.view.data

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.vortex.view.core.Folder
import com.vortex.view.core.MoveException
import com.vortex.view.core.Names
import com.vortex.view.core.Photo
import com.vortex.view.core.Storage
import java.io.FileNotFoundException

/**
 * Folders chosen through the system folder picker: internal storage, an SD
 * card, or a USB drive. A [Folder.id] is the tree URI the picker handed back;
 * a [Photo.id] is a document URI inside it.
 *
 * Moving is a real move where Android can do one (same storage), and
 * otherwise a copy that is checked before the original is removed.
 */
class SafStorage(context: Context) : Storage {
    private val resolver = context.applicationContext.contentResolver

    /** Keeps access to a picked folder across restarts. */
    fun remember(tree: Uri) {
        resolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun forget(tree: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun hasAccess(folder: Folder): Boolean {
        val tree = Uri.parse(folder.id)
        return resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    }

    fun folder(tree: Uri): Folder = Folder(tree.toString(), displayName(tree))

    /** Where the folder picker should start: inside [folder]. */
    fun startingPoint(folder: Folder): Uri = root(folder)

    private fun displayName(tree: Uri): String {
        val name = runCatching {
            query(root(tree), arrayOf(Document.COLUMN_DISPLAY_NAME)) { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        if (!name.isNullOrBlank()) return name
        // "primary:DCIM/Camera" → "Camera"; the root of a drive → its volume name.
        val id = DocumentsContract.getTreeDocumentId(tree)
        return id.substringAfter(':').substringAfterLast('/').ifEmpty { id.substringBefore(':') }
    }

    override fun list(folder: Folder): List<Photo> = guarded(folder) {
        val tree = Uri.parse(folder.id)
        val result = ArrayList<Photo>()
        children(folder) { cursor ->
            val documentId = cursor.getString(0) ?: return@children
            val name = cursor.getString(1) ?: return@children
            val mime = cursor.getString(2)
            if (mime == Document.MIME_TYPE_DIR || !Names.isPhoto(name, mime)) return@children
            result += Photo(
                id = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
                name = name,
                size = if (cursor.isNull(3)) -1L else cursor.getLong(3),
                modified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
            )
        }
        result
    }

    override fun names(folder: Folder): Set<String> = guarded(folder) {
        val names = HashSet<String>()
        children(folder) { cursor -> cursor.getString(1)?.let { names += it.lowercase() } }
        names
    }

    override fun exists(folder: Folder): Boolean = runCatching {
        query(root(folder), arrayOf(Document.COLUMN_DOCUMENT_ID)) { it.moveToFirst() } == true
    }.getOrDefault(false)

    override fun exists(photo: Photo): Boolean = runCatching {
        query(Uri.parse(photo.id), arrayOf(Document.COLUMN_DOCUMENT_ID)) { it.moveToFirst() } == true
    }.getOrDefault(false)

    override fun sameFolder(a: Folder, b: Folder): Boolean {
        val treeA = Uri.parse(a.id)
        val treeB = Uri.parse(b.id)
        return treeA.authority == treeB.authority &&
            DocumentsContract.getTreeDocumentId(treeA) == DocumentsContract.getTreeDocumentId(treeB)
    }

    override fun move(photo: Photo, from: Folder, to: Folder, name: String): Photo = guarded(to) {
        val source = Uri.parse(photo.id)
        if (name == photo.name) {
            // A true move: instant, and keeps the file's date. Only some storage
            // offers it, and never between internal storage and an SD card.
            val moved = runCatching { DocumentsContract.moveDocument(resolver, source, root(from), root(to)) }.getOrNull()
            if (moved != null) {
                // The provider answers with a URI under the old folder's grant; use the new one's.
                val inTarget = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(to.id), DocumentsContract.getDocumentId(moved))
                return@guarded photo.copy(id = inTarget.toString(), name = name)
            }
            if (!exists(photo)) {
                // Moved, but the provider didn't say where: find it by name.
                list(to).firstOrNull { it.name == name }?.let { return@guarded it }
                throw MoveException("${photo.name} has disappeared from ${from.name}")
            }
        }
        copyThenDelete(photo, source, from, to, name)
    }

    private fun copyThenDelete(photo: Photo, source: Uri, from: Folder, to: Folder, name: String): Photo {
        // "application/octet-stream" keeps the name exactly as given: with an image
        // type, some storage adds an extension of its own choosing.
        val created = DocumentsContract.createDocument(resolver, root(to), "application/octet-stream", name)
            ?: throw MoveException("Couldn't create $name in ${to.name}")
        var copied = 0L
        var finished = false
        try {
            val input = resolver.openInputStream(source) ?: throw MoveException("Couldn't read ${photo.name}")
            input.use {
                val output = resolver.openOutputStream(created, "w") ?: throw MoveException("Couldn't write to ${to.name}")
                output.use { copied = input.copyTo(output, BUFFER) }
            }
            if (photo.size >= 0 && copied != photo.size) {
                throw MoveException("Couldn't move ${photo.name}: only $copied of ${photo.size} bytes were copied")
            }
            finished = true
        } finally {
            if (!finished) runCatching { DocumentsContract.deleteDocument(resolver, created) }
        }

        val removed = runCatching { DocumentsContract.deleteDocument(resolver, source) }.getOrDefault(false)
        if (!removed) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw MoveException("Couldn't move ${photo.name}: ${from.name} doesn't allow removing it")
        }
        val actual = runCatching {
            query(created, arrayOf(Document.COLUMN_DISPLAY_NAME)) { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: name
        return Photo(created.toString(), actual, copied, System.currentTimeMillis())
    }

    // -- plumbing --------------------------------------------------------

    private fun root(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun root(folder: Folder): Uri = root(Uri.parse(folder.id))

    private fun children(folder: Folder, each: (Cursor) -> Unit) {
        val tree = Uri.parse(folder.id)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cursor = resolver.query(uri, COLUMNS, null, null, null) ?: throw MoveException("Can't read ${folder.name}")
        cursor.use { while (it.moveToNext()) each(it) }
    }

    private fun <T> query(uri: Uri, columns: Array<String>, read: (Cursor) -> T): T? =
        resolver.query(uri, columns, null, null, null)?.use(read)

    private inline fun <T> guarded(folder: Folder, block: () -> T): T = try {
        block()
    } catch (e: MoveException) {
        throw e
    } catch (e: SecurityException) {
        throw MoveException("VortexView no longer has access to ${folder.name} — choose it again", e)
    } catch (e: FileNotFoundException) {
        throw MoveException("${folder.name} isn't there any more (was the card or drive removed?)", e)
    } catch (e: java.io.IOException) {
        throw MoveException("Couldn't finish in ${folder.name}: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw MoveException("Couldn't finish in ${folder.name}: ${e.message}", e)
    } catch (e: IllegalStateException) {
        throw MoveException("Couldn't finish in ${folder.name}: ${e.message}", e)
    } catch (e: UnsupportedOperationException) {
        throw MoveException("${folder.name} can't be changed: ${e.message}", e)
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
