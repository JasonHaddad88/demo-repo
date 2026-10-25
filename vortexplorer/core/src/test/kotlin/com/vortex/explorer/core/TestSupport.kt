package com.vortex.explorer.core

import java.io.ByteArrayInputStream
import java.io.InputStream

/** Builds entries without a real source. */
fun entry(
    path: String,
    modified: Long = 0L,
    sourceId: String = "test",
    size: Long = 1L,
): Entry {
    val normalized = Paths.normalize(path)
    return Entry(
        sourceId = sourceId,
        path = normalized,
        name = Paths.nameOf(normalized),
        isFolder = false,
        size = size,
        modified = modified,
        parent = Paths.parentOf(normalized),
    )
}

/** `count` images spread over `folders`, named predictably. */
fun library(count: Int, folders: Int = 1, sourceId: String = "test"): List<Entry> =
    (0 until count).map { index -> entry("/f${index % folders}/img$index.jpg", sourceId = sourceId) }

/** An in-memory tree of folders and files, enough to exercise walks and copies. */
class MemorySource(
    override val id: String = "memory",
    override val displayName: String = "Memory",
    private val readOnly: Boolean = false,
) : FileSource {
    private val folders = linkedSetOf("/")
    private val files = linkedMapOf<String, ByteArray>()
    private val modified = mutableMapOf<String, Long>()
    val failingFolders = mutableSetOf<String>()

    override val rootPath: String = "/"
    override val capabilities: Set<Capability> =
        if (readOnly) setOf(Capability.READ) else Capability.values().toSet()

    fun folder(path: String): MemorySource = apply {
        var current = Paths.normalize(path)
        while (current != "/") {
            folders += current
            current = Paths.parentOf(current)
        }
    }

    fun file(path: String, content: String = path, time: Long = 0L): MemorySource = apply {
        val normalized = Paths.normalize(path)
        folder(Paths.parentOf(normalized))
        files[normalized] = content.toByteArray()
        modified[normalized] = time
    }

    fun read(path: String): String? = files[Paths.normalize(path)]?.toString(Charsets.UTF_8)

    fun exists(path: String): Boolean {
        val normalized = Paths.normalize(path)
        return normalized in folders || normalized in files
    }

    override fun list(path: String): List<Entry> {
        val dir = Paths.normalize(path)
        if (dir in failingFolders) throw SourceException("permission denied")
        if (dir !in folders) throw SourceException("no such folder: $dir")
        val subfolders = folders.filter { it != "/" && Paths.parentOf(it) == dir }.map {
            Entry(id, it, Paths.nameOf(it), isFolder = true, parent = dir)
        }
        val children = files.keys.filter { Paths.parentOf(it) == dir }.map {
            Entry(id, it, Paths.nameOf(it), false, files.getValue(it).size.toLong(), modified[it] ?: 0L, null, dir)
        }
        return subfolders + children
    }

    override fun open(path: String): InputStream =
        ByteArrayInputStream(files[Paths.normalize(path)] ?: throw SourceException("no such file: $path"))

    override fun write(
        parentPath: String,
        name: String,
        input: InputStream,
        length: Long,
        mime: String?,
        overwrite: Boolean,
    ): Entry {
        if (readOnly) throw ReadOnlyException(displayName)
        val target = Paths.join(parentPath, name)
        if (!overwrite && exists(target)) throw NameTakenException(name)
        val bytes = input.readBytes()
        files[target] = bytes
        return Entry(id, target, name, false, bytes.size.toLong(), parent = Paths.normalize(parentPath))
    }

    override fun mkdir(parentPath: String, name: String): Entry {
        if (readOnly) throw ReadOnlyException(displayName)
        val target = Paths.join(parentPath, name)
        if (exists(target)) throw NameTakenException(name)
        folder(target)
        return Entry(id, target, name, true, parent = Paths.normalize(parentPath))
    }

    override fun delete(entry: Entry) {
        if (readOnly) throw ReadOnlyException(displayName)
        files.remove(entry.path)
        folders.removeAll { it == entry.path || it.startsWith(entry.path + "/") }
        files.keys.removeAll { it.startsWith(entry.path + "/") }
    }

    override fun rename(entry: Entry, newName: String): Entry {
        if (readOnly) throw ReadOnlyException(displayName)
        val target = Paths.join(Paths.parentOf(entry.path), newName)
        if (exists(target)) throw NameTakenException(newName)
        files.remove(entry.path)?.let { files[target] = it }
        return entry.copy(path = target, name = newName)
    }
}
