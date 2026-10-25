package com.vortex.explorer.core

/** What a file is, as far as browsing and shuffling care. */
enum class EntryKind { FOLDER, IMAGE, VIDEO, AUDIO, OTHER }

/**
 * One file or folder in some [FileSource].
 *
 * [path] is opaque: each source decides what it looks like (a share path for
 * SMB, a document URI for Android folders). Callers never build paths
 * themselves; they pass back paths a source gave them.
 */
data class Entry(
    val sourceId: String,
    val path: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long = -1L,
    /** Last modified time in epoch milliseconds, or 0 when unknown. */
    val modified: Long = 0L,
    val mime: String? = null,
    /** Path of the folder holding this entry; lets shuffles spread across folders. */
    val parent: String = "",
) {
    val kind: EntryKind
        get() = if (isFolder) EntryKind.FOLDER else MediaTypes.kindOf(name, mime)

    /** Identity that is stable across listings and unique across sources. */
    val key: String
        get() = "$sourceId|$path"

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isHidden: Boolean
        get() = name.startsWith(".")
}

object MediaTypes {
    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
    )
    val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "ts", "m2ts", "mts",
        "wmv", "flv", "mpg", "mpeg",
    )
    val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mid", "midi",
    )

    private val MIME_BY_EXTENSION = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg",
        "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "mov" to "video/quicktime", "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp", "ts" to "video/mp2t", "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "opus" to "audio/opus", "wav" to "audio/wav",
        "txt" to "text/plain", "pdf" to "application/pdf", "zip" to "application/zip",
        "m3u" to "audio/x-mpegurl", "m3u8" to "audio/x-mpegurl",
    )

    fun kindOf(name: String, mime: String? = null): EntryKind {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension in IMAGE_EXTENSIONS -> EntryKind.IMAGE
            extension in VIDEO_EXTENSIONS -> EntryKind.VIDEO
            extension in AUDIO_EXTENSIONS -> EntryKind.AUDIO
            mime == null -> EntryKind.OTHER
            mime.startsWith("image/") -> EntryKind.IMAGE
            mime.startsWith("video/") -> EntryKind.VIDEO
            mime.startsWith("audio/") -> EntryKind.AUDIO
            else -> EntryKind.OTHER
        }
    }

    fun mimeFor(name: String): String =
        MIME_BY_EXTENSION[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}

/** Which entries a walk or a shuffle should keep. */
data class EntryFilter(
    val kinds: Set<EntryKind> = setOf(EntryKind.IMAGE, EntryKind.VIDEO),
    val includeHidden: Boolean = false,
) {
    fun accepts(entry: Entry): Boolean {
        if (!includeHidden && entry.isHidden) return false
        return entry.kind in kinds
    }

    companion object {
        val IMAGES = EntryFilter(setOf(EntryKind.IMAGE))
        val VIDEOS = EntryFilter(setOf(EntryKind.VIDEO))
        val AUDIO = EntryFilter(setOf(EntryKind.AUDIO))
        val MEDIA = EntryFilter(setOf(EntryKind.IMAGE, EntryKind.VIDEO))
        val EVERYTHING = EntryFilter(
            setOf(EntryKind.IMAGE, EntryKind.VIDEO, EntryKind.AUDIO, EntryKind.OTHER),
        )
    }
}
