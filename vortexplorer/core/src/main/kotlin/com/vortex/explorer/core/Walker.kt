package com.vortex.explorer.core

/** What a walk found, and what it could not get into. */
data class WalkResult(
    val items: List<Entry>,
    val foldersScanned: Int,
    /** Folders that could not be listed, with the reason. */
    val problems: List<String>,
    /** True when [Walker.maxItems] stopped the walk early. */
    val truncated: Boolean,
    val cancelled: Boolean,
)

/** Where to start a walk: a folder inside some source. */
data class Origin(val source: FileSource, val path: String = source.rootPath)

/**
 * Collects matching files under one or more folders, across sources, so a
 * shuffle can draw from "everything under here" — or from a phone folder,
 * a USB stick and a NAS share at once.
 */
class Walker(
    private val maxItems: Int = 200_000,
    private val maxDepth: Int = 64,
) {
    fun collect(
        origins: List<Origin>,
        filter: EntryFilter = EntryFilter.MEDIA,
        recursive: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: (foldersScanned: Int, filesFound: Int) -> Unit = { _, _ -> },
    ): WalkResult {
        val found = ArrayList<Entry>()
        val problems = ArrayList<String>()
        var folders = 0
        var truncated = false

        for (origin in origins) {
            val visited = HashSet<String>()
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.addLast(origin.path to 0)

            while (queue.isNotEmpty()) {
                if (isCancelled()) {
                    return WalkResult(found, folders, problems, truncated, cancelled = true)
                }
                val (path, depth) = queue.removeFirst()
                if (!visited.add(path)) continue

                val children = try {
                    origin.source.list(path)
                } catch (e: Exception) {
                    problems += "${origin.source.describe(path)}: ${e.message ?: e.javaClass.simpleName}"
                    continue
                }
                folders++

                for (child in children) {
                    if (child.isFolder) {
                        val enter = recursive && depth < maxDepth &&
                            (filter.includeHidden || !child.isHidden)
                        if (enter) queue.addLast(child.path to depth + 1)
                    } else if (filter.accepts(child)) {
                        found += child
                        if (found.size >= maxItems) {
                            truncated = true
                            onProgress(folders, found.size)
                            return WalkResult(found, folders, problems, truncated, cancelled = false)
                        }
                    }
                }
                onProgress(folders, found.size)
            }
        }
        return WalkResult(found, folders, problems, truncated, cancelled = false)
    }

    fun collect(
        source: FileSource,
        path: String = source.rootPath,
        filter: EntryFilter = EntryFilter.MEDIA,
        recursive: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: (foldersScanned: Int, filesFound: Int) -> Unit = { _, _ -> },
    ): WalkResult = collect(listOf(Origin(source, path)), filter, recursive, isCancelled, onProgress)
}
