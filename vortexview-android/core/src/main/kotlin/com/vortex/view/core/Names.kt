package com.vortex.view.core

object Names {
    private val PHOTO_EXTENSIONS = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp", "webp", "heic", "heif", "avif",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * Photos by name, or by type when a name has no telling extension. Hidden
     * files are skipped, which includes the ".trashed-…" and ".pending-…" files
     * Android leaves in camera folders.
     */
    fun isPhoto(name: String, mime: String? = null): Boolean {
        if (name.startsWith(".")) return false
        val extension = extension(name)
        if (extension in PHOTO_EXTENSIONS) return true
        return extension.isEmpty() && mime != null && mime.startsWith("image/")
    }

    /** Sorts "IMG_2" before "IMG_10", the way file managers do. */
    val naturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numberA = a.substring(startA, i).trimStart('0')
                val numberB = b.substring(startB, j).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length - numberB.length
                val byValue = numberA.compareTo(numberB)
                if (byValue != 0) return byValue
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byLength = (a.length - i) - (b.length - j)
        return if (byLength != 0) byLength else a.compareTo(b)
    }

    /** `photo.jpg`, or `photo (2).jpg` if that is taken — never overwrite. */
    fun unique(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        var number = 2
        while (true) {
            val candidate = "$stem ($number)$suffix"
            if (candidate.lowercase() !in taken) return candidate
            number++
        }
    }
}
