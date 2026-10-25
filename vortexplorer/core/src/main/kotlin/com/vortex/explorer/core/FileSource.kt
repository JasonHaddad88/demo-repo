package com.vortex.explorer.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** What a source lets you do beyond browsing. */
enum class Capability { READ, WRITE, DELETE, RENAME }

/** A failure a person can act on: the message is meant to be shown as is. */
open class SourceException(message: String, cause: Throwable? = null) : IOException(message, cause)

class NameTakenException(name: String) : SourceException("\"$name\" already exists there")

class ReadOnlyException(sourceName: String) :
    SourceException("$sourceName is read-only")

/**
 * Anything VortexPlorer can browse: an Android folder, a USB drive, the photo
 * gallery, an SMB share or a WebDAV server.
 *
 * Calls block and may touch the network, so callers run them off the main
 * thread. Implementations must be safe to use from one thread at a time.
 */
interface FileSource : Closeable {
    val id: String
    val displayName: String
    val rootPath: String
    val capabilities: Set<Capability>

    /** Direct children of [path], folders and files, in no particular order. */
    fun list(path: String): List<Entry>

    fun open(path: String): InputStream

    /**
     * Stores [input] as a new file called [name] inside [parentPath].
     * Throws [NameTakenException] when the name exists and [overwrite] is false.
     */
    fun write(
        parentPath: String,
        name: String,
        input: InputStream,
        length: Long = -1L,
        mime: String? = null,
        overwrite: Boolean = false,
    ): Entry

    fun mkdir(parentPath: String, name: String): Entry

    fun delete(entry: Entry)

    fun rename(entry: Entry, newName: String): Entry

    /** A human-readable location, e.g. `smb://nas/photos/2024`. */
    fun describe(path: String): String = path

    fun can(capability: Capability): Boolean = capability in capabilities

    override fun close() {}
}

/** Shared helpers for sources whose paths are `/`-separated strings. */
object Paths {
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayDeque<String>()
        for (part in parts) {
            if (part == "..") {
                if (stack.isNotEmpty()) stack.removeLast()
            } else {
                stack.addLast(part)
            }
        }
        return "/" + stack.joinToString("/")
    }

    fun join(parent: String, name: String): String {
        requireValidName(name)
        val base = normalize(parent)
        return if (base == "/") "/$name" else "$base/$name"
    }

    fun parentOf(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return "/"
        val parent = normalized.substringBeforeLast('/', "")
        return parent.ifEmpty { "/" }
    }

    fun nameOf(path: String): String = normalize(path).substringAfterLast('/')

    /** Rejects names that would escape their folder or that no filesystem accepts. */
    fun requireValidName(name: String) {
        if (name.isBlank() || name == "." || name == "..") {
            throw SourceException("\"$name\" is not a usable name")
        }
        if (name.any { it == '/' || it == '\\' || it == '\u0000' }) {
            throw SourceException("names cannot contain / or \\")
        }
    }
}
