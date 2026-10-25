package com.vortex.explorer.core

import java.io.File

/**
 * Remembers which files have been viewed, for the "unseen first" shuffle.
 *
 * Keys are stored as 64-bit hashes, one per line, so a library of a million
 * files costs a few megabytes and no file names are written to disk. A false
 * "seen" needs a 64-bit collision, which is negligible at these sizes.
 */
class SeenStore(private val file: File? = null) {
    private val hashes = HashSet<Long>()
    private val lock = Any()

    init {
        file?.takeIf { it.isFile }?.useLines { lines ->
            for (line in lines) {
                line.trim().toULongOrNull(16)?.let { hashes.add(it.toLong()) }
            }
        }
    }

    val size: Int
        get() = synchronized(lock) { hashes.size }

    fun isSeen(key: String): Boolean = synchronized(lock) { hash(key) in hashes }

    fun isSeen(entry: Entry): Boolean = isSeen(entry.key)

    fun markSeen(keys: Collection<String>) {
        val added = synchronized(lock) { keys.map(::hash).filter { hashes.add(it) } }
        val target = file
        if (added.isEmpty() || target == null) return
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText(added.joinToString("\n", postfix = "\n") { it.toULong().toString(16) })
        }
    }

    fun markSeen(entry: Entry) = markSeen(listOf(entry.key))

    fun forget(keys: Collection<String>) {
        synchronized(lock) {
            keys.map(::hash).forEach { hashes.remove(it) }
            rewrite()
        }
    }

    fun clear() {
        synchronized(lock) {
            hashes.clear()
            runCatching { file?.delete() }
        }
    }

    private fun rewrite() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".tmp")
            temp.writeText(hashes.joinToString("\n", postfix = if (hashes.isEmpty()) "" else "\n") {
                it.toULong().toString(16)
            })
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    internal companion object {
        /** 64-bit FNV-1a over the UTF-8 bytes. */
        fun hash(key: String): Long {
            var value = 0xCBF29CE484222325uL.toLong()
            for (byte in key.toByteArray(Charsets.UTF_8)) {
                value = value xor (byte.toLong() and 0xFF)
                value *= 0x100000001B3L
            }
            return value
        }
    }
}
