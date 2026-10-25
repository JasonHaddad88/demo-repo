package com.vortex.explorer.core

import kotlin.math.ln

enum class ShuffleMode(val label: String, val summary: String) {
    RANDOM(
        "Pure shuffle",
        "Every order is equally likely.",
    ),
    UNSEEN_FIRST(
        "Unseen first",
        "Files you have not opened yet come first, shuffled; then everything else.",
    ),
    FOLDER_SPREAD(
        "Spread folders",
        "Mixes folders evenly so the same folder rarely comes up twice in a row.",
    ),
    NEWER_FIRST(
        "Lean newer",
        "Newer files tend to turn up earlier; older ones still appear.",
    ),
    OLDER_FIRST(
        "Rediscover",
        "Older files tend to turn up earlier — good for forgotten photos.",
    ),
}

data class ShuffleOptions(
    val mode: ShuffleMode = ShuffleMode.RANDOM,
    /** Replays an earlier order. Null or blank means a fresh seed. */
    val seed: String? = null,
    /** Soft rule: avoid two files from the same folder back to back. */
    val noSameFolderTwice: Boolean = false,
    /** How strongly the newer/older modes lean, from 1 (not at all) to 100. */
    val bias: Double = 4.0,
    /** Keep only the first [limit] results; 0 keeps everything. */
    val limit: Int = 0,
)

data class ShuffleResult(
    val items: List<Entry>,
    /** Enter this seed with the same mode and files to get this exact order again. */
    val seed: String,
    val mode: ShuffleMode,
    /** How many distinct files were shuffled, before any limit. */
    val total: Int,
    /** How many of those had not been seen yet. */
    val unseenCount: Int,
)

/**
 * Turns a pile of files into an order.
 *
 * Every mode is deterministic given its seed and its input *set*: the input is
 * put into a canonical order first, so a network share that lists files in a
 * different order tomorrow still replays the same shuffle.
 */
object Shuffler {
    /** How far ahead [avoidRepeats] looks for a file from another folder. */
    private const val REPEAT_WINDOW = 256

    fun shuffle(
        items: Collection<Entry>,
        options: ShuffleOptions = ShuffleOptions(),
        isSeen: (Entry) -> Boolean = { false },
    ): ShuffleResult {
        val seed = options.seed?.takeIf { it.isNotBlank() }?.let(Seeds::normalize) ?: Seeds.fresh()
        val random = Seeds.random("$seed|${options.mode.name}")
        val canonical = items.distinctBy { it.key }.sortedBy { it.key }
        val unseenCount = canonical.count { !isSeen(it) }

        val ordered = when (options.mode) {
            ShuffleMode.RANDOM -> fisherYates(canonical, random).let {
                if (options.noSameFolderTwice) avoidRepeats(it, random) else it
            }
            ShuffleMode.UNSEEN_FIRST -> {
                val (unseen, seen) = canonical.partition { !isSeen(it) }
                val first = fisherYates(unseen, random)
                val second = fisherYates(seen, random)
                if (options.noSameFolderTwice) {
                    avoidRepeats(first, random) + avoidRepeats(second, random)
                } else {
                    first + second
                }
            }
            ShuffleMode.FOLDER_SPREAD -> folderSpread(canonical, random)
            ShuffleMode.NEWER_FIRST, ShuffleMode.OLDER_FIRST -> weightedByAge(
                canonical,
                random,
                options.bias,
                newerFirst = options.mode == ShuffleMode.NEWER_FIRST,
            ).let { if (options.noSameFolderTwice) avoidRepeats(it, random) else it }
        }

        val limited = if (options.limit > 0) ordered.take(options.limit) else ordered
        return ShuffleResult(limited, seed, options.mode, canonical.size, unseenCount)
    }

    /** One file at random under [options], or null when [items] is empty. */
    fun pickOne(
        items: Collection<Entry>,
        options: ShuffleOptions = ShuffleOptions(),
        isSeen: (Entry) -> Boolean = { false },
    ): Entry? = shuffle(items, options.copy(limit = 1), isSeen).items.firstOrNull()

    /**
     * The next lap of a looping slideshow: a new (still replayable) seed, and
     * never the file that was just on screen as the first one.
     */
    fun nextRound(
        pool: Collection<Entry>,
        previous: ShuffleResult,
        options: ShuffleOptions = ShuffleOptions(mode = previous.mode),
        isSeen: (Entry) -> Boolean = { false },
    ): ShuffleResult {
        val result = shuffle(pool, options.copy(seed = Seeds.next(previous.seed)), isSeen)
        val lastShown = previous.items.lastOrNull()?.key ?: return result
        val items = result.items
        if (items.size < 2 || items.first().key != lastShown) return result
        val swapped = items.toMutableList()
        swapped[0] = items[1]
        swapped[1] = items[0]
        return result.copy(items = swapped)
    }

    internal fun <T> fisherYates(list: List<T>, random: SeededRandom): List<T> {
        val result = list.toMutableList()
        for (i in result.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            if (i != j) {
                val held = result[i]
                result[i] = result[j]
                result[j] = held
            }
        }
        return result
    }

    /**
     * Efraimidis–Spirakis weighted sampling without replacement: each file
     * gets key ln(u)/w and the order is by key, so heavier files tend to come
     * first but any order remains possible.
     */
    private fun weightedByAge(
        items: List<Entry>,
        random: SeededRandom,
        bias: Double,
        newerFirst: Boolean,
    ): List<Entry> {
        if (items.size < 2) return items.toList()
        val strength = bias.coerceIn(1.0, 100.0)
        val dated = items.filter { it.modified > 0 }
            .sortedWith(compareBy<Entry> { it.modified }.thenBy { it.key })
        val rank = HashMap<String, Int>(dated.size * 2)
        dated.forEachIndexed { index, entry -> rank[entry.key] = index }
        val span = (dated.size - 1).coerceAtLeast(1)

        val keyed = items.map { entry ->
            // 0 is the oldest file, 1 the newest; undated files sit in the middle.
            val position = rank[entry.key]?.let { it.toDouble() / span } ?: 0.5
            val freshness = if (newerFirst) position else 1.0 - position
            val weight = 1.0 + (strength - 1.0) * freshness
            entry to ln(random.nextPositiveDouble()) / weight
        }
        return keyed.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Spreads every folder evenly through the order: each folder's files are
     * shuffled, then given evenly spaced target positions with a random offset
     * and a little jitter, and everything is merged by position. A folder with
     * 60% of the files fills about 60% of any stretch of the order, so the
     * repeats it forces are scattered instead of piling up at the end.
     */
    private fun folderSpread(items: List<Entry>, random: SeededRandom): List<Entry> {
        if (items.size < 2) return items.toList()
        val groups = items.groupBy { it.parent }.toSortedMap().values.map { fisherYates(it, random) }
        val placed = ArrayList<Pair<Double, Entry>>(items.size)
        for (group in groups) {
            val spacing = 1.0 / group.size
            val offset = random.nextDouble() * spacing
            group.forEachIndexed { index, entry ->
                val jitter = (random.nextDouble() - 0.5) * spacing * JITTER
                placed += (offset + index * spacing + jitter) to entry
            }
        }
        val ordered = placed.sortedBy { it.first }.map { it.second }.toMutableList()
        relocateRepeats(ordered, random)
        return ordered
    }

    /**
     * Breaks up back-to-back files from one folder where that is possible.
     *
     * First pass: swap a repeat with the next file from another folder, but
     * only while that folder is not a majority of what is left; past that
     * point its repeats are unavoidable, and pulling other folders forward
     * would only pile them all up at the end. Second pass: move any repeat
     * still standing into a gap where it touches no file from its folder.
     */
    internal fun avoidRepeats(items: List<Entry>, random: SeededRandom): List<Entry> {
        val result = items.toMutableList()
        val left = HashMap<String, Int>()
        for (entry in result) left[entry.parent] = (left[entry.parent] ?: 0) + 1

        for (i in result.indices) {
            if (i > 0) {
                val folder = result[i - 1].parent
                val sameFolderLeft = left[folder] ?: 0
                if (result[i].parent == folder && sameFolderLeft * 2 <= result.size - i) {
                    val limit = minOf(result.size, i + 1 + REPEAT_WINDOW)
                    var j = i + 1
                    while (j < limit && result[j].parent == folder) j++
                    if (j < limit) {
                        val held = result[i]
                        result[i] = result[j]
                        result[j] = held
                    }
                }
            }
            val here = result[i].parent
            left[here] = (left[here] ?: 1) - 1
        }
        relocateRepeats(result, random)
        return result
    }

    /**
     * Moves the second file of each repeat to the nearest gap where it has no
     * neighbour from its own folder. Nearest, not anywhere: grabbing gaps from
     * the whole list would fix the early repeats first and leave the late ones
     * stranded, bunching what cannot be fixed at the end.
     */
    private fun relocateRepeats(items: MutableList<Entry>, random: SeededRandom) {
        var i = 1
        while (i < items.size) {
            val folder = items[i].parent
            if (items[i - 1].parent != folder) {
                i++
                continue
            }
            val moved = items.removeAt(i)
            val slot = nearestSlot(items, folder, around = i, random = random)
            if (slot < 0) {
                items.add(i, moved)
                i++
            } else {
                items.add(slot, moved)
                // Everything before the removal point is settled; re-check from there.
                if (slot <= i) i++
            }
        }
    }

    /** The insert position nearest [around] whose neighbours are both from other folders, or -1. */
    private fun nearestSlot(items: List<Entry>, folder: String, around: Int, random: SeededRandom): Int {
        fun fits(slot: Int): Boolean {
            if (slot < 0 || slot > items.size) return false
            val before = if (slot > 0) items[slot - 1].parent else null
            val after = if (slot < items.size) items[slot].parent else null
            return before != folder && after != folder
        }
        for (distance in 1..RELOCATE_RADIUS) {
            val earlier = fits(around - distance)
            val later = fits(around + distance)
            when {
                earlier && later -> return if (random.nextInt(2) == 0) around - distance else around + distance
                earlier -> return around - distance
                later -> return around + distance
            }
            if (around - distance < 0 && around + distance > items.size) break
        }
        return -1
    }

    /** How far a repeat may be moved to break it up. */
    private const val RELOCATE_RADIUS = 64

    /** How far apart a folder's files may wander from their even spacing. */
    private const val JITTER = 0.2
}
