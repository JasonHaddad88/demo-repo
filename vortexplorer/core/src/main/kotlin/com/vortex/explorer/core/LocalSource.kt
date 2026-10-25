package com.vortex.explorer.core

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A folder on a regular filesystem. On Android it serves the app's own
 * storage; on the JVM it is what the tests copy to and from.
 */
class LocalSource(
    override val id: String,
    override val displayName: String,
    root: File,
) : FileSource {
    private val root: File = root.canonicalFile

    override val rootPath: String = "/"
    override val capabilities: Set<Capability> = Capability.values().toSet()

    private fun resolve(path: String): File {
        val normalized = Paths.normalize(path)
        val file = File(root, normalized.removePrefix("/")).canonicalFile
        val inside = file == root || file.path.startsWith(root.path + File.separator)
        if (!inside) throw SourceException("$path is outside $displayName")
        return file
    }

    private fun toEntry(file: File, path: String): Entry = Entry(
        sourceId = id,
        path = path,
        name = file.name,
        isFolder = file.isDirectory,
        size = if (file.isDirectory) -1L else file.length(),
        modified = file.lastModified(),
        parent = Paths.parentOf(path),
    )

    override fun list(path: String): List<Entry> {
        val folder = resolve(path)
        if (!folder.isDirectory) throw SourceException("${describe(path)} is not a folder")
        val children = folder.listFiles() ?: throw SourceException("cannot read ${describe(path)}")
        val base = Paths.normalize(path)
        return children.map { toEntry(it, Paths.join(base, it.name)) }
    }

    override fun open(path: String): InputStream = try {
        FileInputStream(resolve(path))
    } catch (e: FileNotFoundException) {
        throw SourceException("cannot open ${describe(path)}", e)
    }

    override fun write(
        parentPath: String,
        name: String,
        input: InputStream,
        length: Long,
        mime: String?,
        overwrite: Boolean,
    ): Entry {
        val path = Paths.join(parentPath, name)
        val target = resolve(path)
        if (target.exists() && !overwrite) throw NameTakenException(name)
        // Write beside the target and swap it in, so a failed copy never
        // leaves a half-written file under the real name.
        val partial = File(target.parentFile, ".${target.name}.partial")
        try {
            partial.outputStream().use { input.copyTo(it, BUFFER) }
            if (target.exists() && !target.delete()) throw SourceException("cannot replace $name")
            if (!partial.renameTo(target)) throw SourceException("cannot save $name")
        } finally {
            partial.delete()
        }
        return toEntry(target, path)
    }

    override fun mkdir(parentPath: String, name: String): Entry {
        val path = Paths.join(parentPath, name)
        val folder = resolve(path)
        if (folder.exists()) throw NameTakenException(name)
        if (!folder.mkdirs()) throw SourceException("cannot create folder $name")
        return toEntry(folder, path)
    }

    override fun delete(entry: Entry) {
        val file = resolve(entry.path)
        if (file == root) throw SourceException("will not delete the whole of $displayName")
        val gone = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!gone && file.exists()) throw SourceException("cannot delete ${entry.name}")
    }

    override fun rename(entry: Entry, newName: String): Entry {
        val from = resolve(entry.path)
        val path = Paths.join(Paths.parentOf(entry.path), newName)
        val to = resolve(path)
        val caseOnly = from.path.equals(to.path, ignoreCase = true) && from.path != to.path
        if (to.exists() && !caseOnly) throw NameTakenException(newName)
        if (!from.renameTo(to)) throw SourceException("cannot rename ${entry.name}")
        return toEntry(to, path)
    }

    override fun describe(path: String): String = File(root, Paths.normalize(path).removePrefix("/")).path

    private companion object {
        const val BUFFER = 256 * 1024
    }
}
