package com.vortex.explorer.core

/** File names that survive the trip to any filesystem, FAT32 USB sticks included. */
object Names {
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** Most filesystems cap a name at 255 bytes; leave room for a numbering prefix. */
    private const val MAX_BYTES = 200

    fun safe(name: String, fallback: String = "file"): String {
        var cleaned = FORBIDDEN.replace(name, "_").trim().trimEnd('.', ' ')
        if (cleaned.substringBefore('.').uppercase() in RESERVED) cleaned = "_$cleaned"
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") cleaned = fallback
        return truncate(cleaned)
    }

    /** `photo.jpg` -> `photo (2).jpg`, the way most file managers do it. */
    fun numbered(name: String, copy: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($copy)${name.substring(dot)}"
        } else {
            "$name ($copy)"
        }
    }

    /** The first of `name`, `name (2)`, `name (3)`... that is not in [taken]. */
    fun unique(name: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        var copy = 2
        while (numbered(name, copy).lowercase() in lower) copy++
        return numbered(name, copy)
    }

    private fun truncate(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
        var stem = name.removeSuffix(extension)
        while ((stem + extension).toByteArray(Charsets.UTF_8).size > MAX_BYTES && stem.isNotEmpty()) {
            stem = stem.dropLast(1)
        }
        return stem.trimEnd('.', ' ') + extension
    }
}
