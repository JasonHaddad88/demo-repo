package com.vortex.explorer.core

import java.io.FilterInputStream
import java.io.InputStream

/** What to do when the destination already has a file by that name. */
enum class Conflict { SKIP, RENAME, OVERWRITE }

data class TransferProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    /** -1 while some sizes are still unknown. */
    val bytesTotal: Long,
    val current: String,
)

data class TransferReport(
    /** What now exists at the destination because of this transfer. */
    val written: List<Entry>,
    /** Source entries left alone because of [Conflict.SKIP]. */
    val skipped: List<Entry>,
    val failed: List<Pair<Entry, String>>,
    val cancelled: Boolean,
) {
    val ok: Boolean get() = failed.isEmpty() && !cancelled
}

class TransferCancelled : SourceException("cancelled")

/**
 * Copies or moves entries from any source to any other: phone to NAS,
 * share to USB stick, WebDAV to phone. Folders are copied with their contents.
 */
class Copier(private val sourceFor: (String) -> FileSource?) {
    private var filesDone = 0
    private var filesTotal = 0
    private var bytesDone = 0L
    private var bytesTotal = 0L
    private var sizesKnown = true
    private var lastReport = 0L

    fun copy(
        items: List<Entry>,
        to: FileSource,
        folder: String,
        conflict: Conflict = Conflict.RENAME,
        move: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferReport {
        if (!to.can(Capability.WRITE)) throw ReadOnlyException(to.displayName)
        reset(items)
        val written = ArrayList<Entry>()
        val skipped = ArrayList<Entry>()
        val failed = ArrayList<Pair<Entry, String>>()
        val job = Job(to, conflict, move, isCancelled, onProgress, written, skipped, failed)

        val taken = namesIn(to, folder)
        try {
            for (item in items) {
                if (isCancelled()) throw TransferCancelled()
                job.copyEntry(item, folder, taken)
            }
        } catch (e: TransferCancelled) {
            return TransferReport(written, skipped, failed, cancelled = true)
        }
        onProgress(progress(""))
        return TransferReport(written, skipped, failed, cancelled = false)
    }

    /** Copies one file to an exact name; the building block for exports. */
    internal fun copyFile(
        item: Entry,
        to: FileSource,
        folder: String,
        name: String,
        overwrite: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): Entry {
        val from = sourceFor(item.sourceId) ?: throw SourceException("the source of ${item.name} is no longer connected")
        var created = false
        try {
            return from.open(item.path).use { raw ->
                val counted = CountingStream(raw) { read ->
                    bytesDone += read
                    if (isCancelled()) throw TransferCancelled()
                    val now = System.nanoTime()
                    if (now - lastReport > REPORT_INTERVAL_NANOS) {
                        lastReport = now
                        onProgress(progress(item.name))
                    }
                }
                created = true
                to.write(folder, name, counted, item.size, item.mime ?: MediaTypes.mimeFor(name), overwrite)
            }
        } catch (e: Exception) {
            // Never leave a half-copied file under the real name — unless it was
            // there before us, in which case it is not ours to remove.
            if (created && !overwrite && e !is NameTakenException) {
                runCatching {
                    to.list(folder).firstOrNull { it.name == name && !it.isFolder }?.let(to::delete)
                }
            }
            throw e
        }
    }

    private inner class Job(
        val to: FileSource,
        val conflict: Conflict,
        val move: Boolean,
        val isCancelled: () -> Boolean,
        val onProgress: (TransferProgress) -> Unit,
        val written: MutableList<Entry>,
        val skipped: MutableList<Entry>,
        val failed: MutableList<Pair<Entry, String>>,
    ) {
        fun copyEntry(item: Entry, folder: String, taken: MutableSet<String>) {
            val from = sourceFor(item.sourceId)
            if (from == null) {
                failed += item to "its source is no longer connected"
                return
            }
            if (item.isFolder) copyFolder(item, from, folder, taken) else copyOne(item, from, folder, taken)
        }

        private fun copyOne(item: Entry, from: FileSource, folder: String, taken: MutableSet<String>) {
            val wanted = Names.safe(item.name)
            val exists = taken.any { it.equals(wanted, ignoreCase = true) }
            val name = when {
                !exists -> wanted
                conflict == Conflict.SKIP -> {
                    skipped += item
                    filesDone++
                    bytesDone += item.size.coerceAtLeast(0)
                    return
                }
                conflict == Conflict.RENAME -> Names.unique(wanted, taken)
                else -> wanted
            }
            try {
                val result = copyFile(item, to, folder, name, overwrite = exists && conflict == Conflict.OVERWRITE, isCancelled, onProgress)
                taken += name
                written += result
                if (move && from.can(Capability.DELETE)) from.delete(item)
            } catch (e: TransferCancelled) {
                throw e
            } catch (e: Exception) {
                failed += item to (e.message ?: e.javaClass.simpleName)
            }
            filesDone++
            onProgress(progress(item.name))
        }

        private fun copyFolder(item: Entry, from: FileSource, folder: String, taken: MutableSet<String>) {
            val wanted = Names.safe(item.name)
            val exists = taken.any { it.equals(wanted, ignoreCase = true) }
            val target: Entry
            val inside: MutableSet<String>
            try {
                if (exists && conflict != Conflict.RENAME) {
                    // Merge into the folder that is already there.
                    val existing = to.list(folder).firstOrNull { it.isFolder && it.name.equals(wanted, ignoreCase = true) }
                        ?: throw SourceException("\"$wanted\" exists there and is not a folder")
                    target = existing
                    inside = namesIn(to, existing.path)
                } else {
                    val name = if (exists) Names.unique(wanted, taken) else wanted
                    target = to.mkdir(folder, name)
                    taken += name
                    written += target
                    inside = HashSet()
                }
                val children = from.list(item.path)
                for (child in children) {
                    filesTotal += if (child.isFolder) 0 else 1
                    if (child.size >= 0) bytesTotal += child.size else sizesKnown = false
                }
                for (child in children) {
                    if (isCancelled()) throw TransferCancelled()
                    if (child.isFolder) copyFolder(child, from, target.path, inside) else copyOne(child, from, target.path, inside)
                }
                if (move && from.can(Capability.DELETE) && failed.none { it.first.path.startsWith(item.path + "/") }) {
                    from.delete(item)
                }
            } catch (e: TransferCancelled) {
                throw e
            } catch (e: Exception) {
                failed += item to (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun reset(items: List<Entry>) {
        filesDone = 0
        filesTotal = items.count { !it.isFolder }
        bytesDone = 0
        bytesTotal = items.filter { !it.isFolder }.sumOf { it.size.coerceAtLeast(0) }
        sizesKnown = items.none { it.isFolder || it.size < 0 }
        lastReport = 0
    }

    private fun progress(current: String) = TransferProgress(
        filesDone,
        filesTotal,
        bytesDone,
        if (sizesKnown) bytesTotal else -1L,
        current,
    )

    private class CountingStream(input: InputStream, private val onRead: (Int) -> Unit) : FilterInputStream(input) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) onRead(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) onRead(count)
            return count
        }
    }

    internal companion object {
        const val REPORT_INTERVAL_NANOS = 150_000_000L

        fun namesIn(source: FileSource, folder: String): MutableSet<String> =
            source.list(folder).mapTo(HashSet()) { it.name }
    }
}

data class ExportOptions(
    /** Digits in the number prefix; 0 picks enough for the count (at least 3). */
    val numberWidth: Int = 0,
    val separator: String = " - ",
    /** False gives bare numbers: `001.jpg`. */
    val keepNames: Boolean = true,
    /** Also write an .m3u playlist in the shuffled order when there is audio or video. */
    val playlist: Boolean = true,
    val playlistName: String = "shuffle.m3u",
)

data class ExportPlan(val targets: List<Pair<Entry, String>>, val playlistName: String?)

/**
 * Writes a shuffle out so that dumb players keep it: car stereos, TVs and
 * photo frames play files in name order, so the names carry the order.
 */
object Exporter {
    /**
     * Removes a number prefix in the exact form this exporter writes (at least
     * three digits, then [separator]), so re-shuffling never stacks prefixes.
     * Anything looser would eat real names: "2024 holiday.jpg" keeps its year.
     */
    fun stripNumber(name: String, separator: String = " - "): String =
        Regex("^\\d{3,6}" + Regex.escape(separator)).replaceFirst(name, "").ifEmpty { name }

    fun plan(order: List<Entry>, options: ExportOptions = ExportOptions()): ExportPlan {
        val files = order.filter { !it.isFolder }
        val width = if (options.numberWidth > 0) options.numberWidth else maxOf(3, files.size.toString().length)
        val targets = files.mapIndexed { index, entry ->
            val number = (index + 1).toString().padStart(width, '0')
            val name = if (options.keepNames) {
                number + options.separator + Names.safe(stripNumber(entry.name, options.separator))
            } else {
                number + entry.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
            }
            entry to Names.safe(name)
        }
        val playable = files.any { it.kind == EntryKind.AUDIO || it.kind == EntryKind.VIDEO }
        return ExportPlan(targets, if (options.playlist && playable) options.playlistName else null)
    }

    /** Copies [order] into [folder] of [to], numbered in that order. */
    fun export(
        order: List<Entry>,
        sourceFor: (String) -> FileSource?,
        to: FileSource,
        folder: String,
        options: ExportOptions = ExportOptions(),
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferReport {
        if (!to.can(Capability.WRITE)) throw ReadOnlyException(to.displayName)
        val plan = plan(order, options)
        val copier = Copier(sourceFor)
        val written = ArrayList<Entry>()
        val failed = ArrayList<Pair<Entry, String>>()
        val taken = Copier.namesIn(to, folder)
        var bytesDone = 0L
        val bytesTotal = plan.targets.sumOf { it.first.size.coerceAtLeast(0) }

        plan.targets.forEachIndexed { index, (entry, wanted) ->
            if (isCancelled()) return TransferReport(written, emptyList(), failed, cancelled = true)
            val name = Names.unique(wanted, taken)
            try {
                val result = copier.copyFile(entry, to, folder, name, overwrite = false, isCancelled) { inner ->
                    onProgress(TransferProgress(index, plan.targets.size, bytesDone + inner.bytesDone, bytesTotal, entry.name))
                }
                taken += name
                written += result
            } catch (e: TransferCancelled) {
                return TransferReport(written, emptyList(), failed, cancelled = true)
            } catch (e: Exception) {
                failed += entry to (e.message ?: e.javaClass.simpleName)
            }
            bytesDone += entry.size.coerceAtLeast(0)
            onProgress(TransferProgress(index + 1, plan.targets.size, bytesDone, bytesTotal, entry.name))
        }

        // `written` is already in shuffled order and holds the names actually
        // used, so the playlist matches the files on disk even after renames.
        plan.playlistName?.let { playlistName ->
            writePlaylist(to, folder, playlistName, written.map { it.name })?.let { written += it }
        }
        return TransferReport(written, emptyList(), failed, cancelled = false)
    }

    /**
     * Renames the files of one folder so their names follow [order] — shuffles
     * a USB stick in place, without copying anything. Two passes through
     * temporary names, so swapping `001 - a` and `002 - b` never collides.
     */
    fun reshuffleInPlace(
        order: List<Entry>,
        source: FileSource,
        options: ExportOptions = ExportOptions(),
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferReport {
        if (!source.can(Capability.RENAME)) throw SourceException("${source.displayName} cannot rename files")
        val plan = plan(order, options.copy(playlist = false))
        val total = plan.targets.size
        val stamp = Seeds.fresh().lowercase()
        val temporary = ArrayList<Pair<Entry, Entry>>() // original -> renamed to temporary name

        // Pass 1: everything to a temporary name; undo it all if anything fails.
        for ((index, target) in plan.targets.withIndex()) {
            val (entry, _) = target
            if (isCancelled()) {
                undo(source, temporary)
                return TransferReport(emptyList(), emptyList(), emptyList(), cancelled = true)
            }
            try {
                temporary += entry to source.rename(entry, "vx-$stamp-$index.tmp")
            } catch (e: Exception) {
                undo(source, temporary)
                return TransferReport(emptyList(), emptyList(), listOf(entry to (e.message ?: "rename failed")), cancelled = false)
            }
            onProgress(TransferProgress(index + 1, total * 2, 0, -1, entry.name))
        }

        // Pass 2: temporary names to the numbered ones.
        val written = ArrayList<Entry>()
        val failed = ArrayList<Pair<Entry, String>>()
        for ((index, pair) in temporary.withIndex()) {
            val (original, temp) = pair
            val finalName = plan.targets[index].second
            try {
                written += source.rename(temp, finalName)
            } catch (e: Exception) {
                failed += original to (e.message ?: "rename failed")
                runCatching { source.rename(temp, original.name) }
            }
            onProgress(TransferProgress(total + index + 1, total * 2, 0, -1, finalName))
        }
        return TransferReport(written, emptyList(), failed, cancelled = false)
    }

    private fun undo(source: FileSource, renamed: List<Pair<Entry, Entry>>) {
        for ((original, temp) in renamed.asReversed()) {
            runCatching { source.rename(temp, original.name) }
        }
    }

    private fun writePlaylist(to: FileSource, folder: String, name: String, files: List<String>): Entry? {
        if (files.isEmpty()) return null
        val text = buildString {
            append("#EXTM3U\r\n")
            files.forEach { append(it).append("\r\n") }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        return to.write(folder, name, bytes.inputStream(), bytes.size.toLong(), "audio/x-mpegurl", overwrite = true)
    }
}
