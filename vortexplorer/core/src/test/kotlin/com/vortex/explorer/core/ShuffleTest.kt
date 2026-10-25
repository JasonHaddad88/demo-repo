package com.vortex.explorer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShuffleTest {
    private fun keys(result: ShuffleResult) = result.items.map { it.key }

    private fun sameFolderPairs(items: List<Entry>) =
        items.zipWithNext().count { (a, b) -> a.parent == b.parent }

    @Test
    fun `every mode returns each file exactly once`() {
        val pool = library(300, folders = 7).mapIndexed { i, e -> e.copy(modified = 1_000L + i) }
        for (mode in ShuffleMode.values()) {
            for (noRepeats in listOf(false, true)) {
                val result = Shuffler.shuffle(
                    pool,
                    ShuffleOptions(mode = mode, seed = "S1", noSameFolderTwice = noRepeats),
                    isSeen = { it.name.hashCode() % 3 == 0 },
                )
                assertEquals(pool.map { it.key }.sorted(), keys(result).sorted(), "$mode lost or duplicated files")
                assertEquals(300, result.total)
            }
        }
    }

    @Test
    fun `a seed replays the same order regardless of listing order`() {
        val pool = library(200, folders = 5)
        for (mode in ShuffleMode.values()) {
            val first = Shuffler.shuffle(pool, ShuffleOptions(mode = mode, seed = "beach"))
            val again = Shuffler.shuffle(pool.reversed(), ShuffleOptions(mode = mode, seed = " BEACH "))
            assertEquals(keys(first), keys(again), "$mode did not replay")
            assertEquals("BEACH", first.seed)
        }
    }

    @Test
    fun `different seeds give different orders`() {
        val pool = library(50)
        val orders = (1..20).map { keys(Shuffler.shuffle(pool, ShuffleOptions(seed = "seed$it"))) }.toSet()
        assertEquals(20, orders.size)
    }

    @Test
    fun `no seed means a fresh one, reported back`() {
        val pool = library(50)
        val a = Shuffler.shuffle(pool)
        val b = Shuffler.shuffle(pool)
        assertEquals(8, a.seed.length)
        assertNotEquals(a.seed, b.seed)
        assertEquals(keys(a), keys(Shuffler.shuffle(pool, ShuffleOptions(seed = a.seed))))
    }

    @Test
    fun `pure shuffle is uniform over all permutations`() {
        // 4 files have 24 orders; each should appear 1/24 of the time.
        // Chi-square with 23 degrees of freedom, p = 0.001 cutoff 49.73.
        val pool = library(4)
        val trials = 48_000
        val counts = HashMap<List<String>, Int>()
        for (i in 0 until trials) {
            val order = keys(Shuffler.shuffle(pool, ShuffleOptions(seed = "u$i")))
            counts[order] = (counts[order] ?: 0) + 1
        }
        assertEquals(24, counts.size, "some orders never appeared")
        val expected = trials / 24.0
        val chiSquare = counts.values.sumOf { (it - expected) * (it - expected) / expected }
        assertTrue(chiSquare < 49.73, "shuffle is biased: chi^2 = $chiSquare")
    }

    @Test
    fun `every file is equally likely to come first`() {
        val pool = library(10)
        val firsts = HashMap<String, Int>()
        val trials = 20_000
        repeat(trials) { i ->
            val first = Shuffler.shuffle(pool, ShuffleOptions(seed = "p$i")).items.first().key
            firsts[first] = (firsts[first] ?: 0) + 1
        }
        // 9 degrees of freedom, p = 0.001 cutoff 27.88.
        val expected = trials / 10.0
        val chiSquare = firsts.values.sumOf { (it - expected) * (it - expected) / expected }
        assertTrue(chiSquare < 27.88, "first position is biased: chi^2 = $chiSquare")
    }

    @Test
    fun `unseen files all come before seen ones`() {
        val pool = library(100, folders = 4)
        val seen = pool.filterIndexed { i, _ -> i % 3 == 0 }.map { it.key }.toSet()
        val result = Shuffler.shuffle(pool, ShuffleOptions(mode = ShuffleMode.UNSEEN_FIRST, seed = "x")) {
            it.key in seen
        }
        val boundary = result.unseenCount
        assertEquals(100 - seen.size, boundary)
        assertTrue(result.items.take(boundary).none { it.key in seen })
        assertTrue(result.items.drop(boundary).all { it.key in seen })
    }

    @Test
    fun `unseen first when everything has been seen is a plain shuffle`() {
        val pool = library(30)
        val result = Shuffler.shuffle(pool, ShuffleOptions(mode = ShuffleMode.UNSEEN_FIRST, seed = "x")) { true }
        assertEquals(0, result.unseenCount)
        assertEquals(30, result.items.size)
    }

    @Test
    fun `folder spread never repeats a folder while another is left`() {
        val pool = library(100, folders = 2) // 50 + 50
        repeat(25) { i ->
            val result = Shuffler.shuffle(pool, ShuffleOptions(mode = ShuffleMode.FOLDER_SPREAD, seed = "s$i"))
            assertEquals(0, sameFolderPairs(result.items))
        }
    }

    @Test
    fun `folder spread with a dominant folder only repeats when it has to`() {
        // 90 from one folder and 10 from another: at most 20 changes of folder are
        // possible, so at least 79 back-to-back pairs are unavoidable — and no more.
        val big = (0 until 90).map { entry("/big/b$it.jpg") }
        val small = (0 until 10).map { entry("/small/s$it.jpg") }
        repeat(25) { i ->
            val result = Shuffler.shuffle(big + small, ShuffleOptions(mode = ShuffleMode.FOLDER_SPREAD, seed = "d$i"))
            assertEquals(79, sameFolderPairs(result.items))
        }
    }

    @Test
    fun `folder spread keeps folder proportions through the order`() {
        // Folder A has three times the files of B; any stretch of the order
        // should hold roughly three A for each B, not all A first.
        val a = (0 until 300).map { entry("/a/$it.jpg") }
        val b = (0 until 100).map { entry("/b/$it.jpg") }
        val c = (0 until 100).map { entry("/c/$it.jpg") }
        val result = Shuffler.shuffle(a + b + c, ShuffleOptions(mode = ShuffleMode.FOLDER_SPREAD, seed = "prop"))
        val firstHalf = result.items.take(250)
        val shareOfA = firstHalf.count { it.parent == "/a" } / 250.0
        assertEquals(0.6, shareOfA, 0.12)
    }

    @Test
    fun `forced repeats are scattered, not piled up at the end`() {
        // 60% of the files share a folder, so some back-to-back pairs must
        // happen. They should be spread through the order: a slideshow should
        // not finish on a long run of one folder.
        val a = (0 until 300).map { entry("/a/$it.jpg") }
        val b = (0 until 100).map { entry("/b/$it.jpg") }
        val c = (0 until 100).map { entry("/c/$it.jpg") }
        repeat(10) { i ->
            val items = Shuffler.shuffle(a + b + c, ShuffleOptions(mode = ShuffleMode.FOLDER_SPREAD, seed = "t$i")).items
            val repeatAt = items.zipWithNext().withIndex()
                .filter { (_, pair) -> pair.first.parent == pair.second.parent }.map { it.index }
            val inSecondHalf = repeatAt.count { it >= items.size / 2 }
            val share = inSecondHalf.toDouble() / repeatAt.size
            assertTrue(share in 0.3..0.7, "repeats bunched: ${(share * 100).toInt()}% in the second half")
            val longestRun = items.fold(Pair("", 0) to 0) { (run, best), entry ->
                val length = if (entry.parent == run.first) run.second + 1 else 1
                (entry.parent to length) to maxOf(best, length)
            }.second
            assertTrue(longestRun <= 4, "a run of $longestRun from one folder")
        }
    }

    @Test
    fun `newer-leaning shuffles put new files earlier on average`() {
        val pool = (0 until 1_000).map { entry("/f/img$it.jpg", modified = 1_000_000L + it) }
        val newest = pool.takeLast(100).map { it.key }.toSet()
        val oldest = pool.take(100).map { it.key }.toSet()

        fun averagePosition(order: List<Entry>, group: Set<String>) =
            order.withIndex().filter { it.value.key in group }.map { it.index }.average()

        val newer = Shuffler.shuffle(pool, ShuffleOptions(mode = ShuffleMode.NEWER_FIRST, seed = "n", bias = 6.0))
        assertTrue(averagePosition(newer.items, newest) + 200 < averagePosition(newer.items, oldest))

        val older = Shuffler.shuffle(pool, ShuffleOptions(mode = ShuffleMode.OLDER_FIRST, seed = "n", bias = 6.0))
        assertTrue(averagePosition(older.items, oldest) + 200 < averagePosition(older.items, newest))
    }

    @Test
    fun `bias of one is no bias`() {
        val pool = (0 until 1_000).map { entry("/f/img$it.jpg", modified = 1_000_000L + it) }
        val newest = pool.takeLast(200).map { it.key }.toSet()
        val result = Shuffler.shuffle(pool, ShuffleOptions(mode = ShuffleMode.NEWER_FIRST, seed = "flat", bias = 1.0))
        val position = result.items.withIndex().filter { it.value.key in newest }.map { it.index }.average()
        assertEquals(499.5, position, 60.0)
    }

    @Test
    fun `undated files still take part in age-leaning shuffles`() {
        val dated = (0 until 50).map { entry("/f/d$it.jpg", modified = 5_000L + it) }
        val undated = (0 until 50).map { entry("/f/u$it.jpg") }
        val result = Shuffler.shuffle(dated + undated, ShuffleOptions(mode = ShuffleMode.NEWER_FIRST, seed = "u"))
        assertEquals(100, result.items.size)
    }

    @Test
    fun `no-same-folder-twice removes avoidable repeats`() {
        val pool = library(400, folders = 8)
        repeat(10) { i ->
            val plain = Shuffler.shuffle(pool, ShuffleOptions(seed = "r$i"))
            val spaced = Shuffler.shuffle(pool, ShuffleOptions(seed = "r$i", noSameFolderTwice = true))
            assertTrue(sameFolderPairs(plain.items) > 0, "test needs repeats to remove")
            assertEquals(0, sameFolderPairs(spaced.items))
        }
    }

    @Test
    fun `no-same-folder-twice leaves a single folder alone`() {
        val pool = library(20, folders = 1)
        val result = Shuffler.shuffle(pool, ShuffleOptions(seed = "one", noSameFolderTwice = true))
        assertEquals(20, result.items.size)
    }

    @Test
    fun `limit keeps the front of the order`() {
        val pool = library(100)
        val full = Shuffler.shuffle(pool, ShuffleOptions(seed = "L"))
        val ten = Shuffler.shuffle(pool, ShuffleOptions(seed = "L", limit = 10))
        assertEquals(keys(full).take(10), keys(ten))
        assertEquals(100, ten.total)
    }

    @Test
    fun `duplicates from merged sources are dropped`() {
        val pool = library(10)
        val result = Shuffler.shuffle(pool + pool, ShuffleOptions(seed = "dup"))
        assertEquals(10, result.items.size)
    }

    @Test
    fun `same path in two sources counts as two files`() {
        val phone = library(5, sourceId = "phone")
        val nas = library(5, sourceId = "nas")
        assertEquals(10, Shuffler.shuffle(phone + nas).items.size)
    }

    @Test
    fun `empty and single inputs are fine in every mode`() {
        for (mode in ShuffleMode.values()) {
            assertEquals(0, Shuffler.shuffle(emptyList(), ShuffleOptions(mode = mode)).items.size)
            assertEquals(1, Shuffler.shuffle(library(1), ShuffleOptions(mode = mode)).items.size)
        }
        assertNull(Shuffler.pickOne(emptyList()))
    }

    @Test
    fun `pick one is the head of the full shuffle`() {
        val pool = library(40)
        val options = ShuffleOptions(seed = "pick")
        assertEquals(Shuffler.shuffle(pool, options).items.first(), Shuffler.pickOne(pool, options))
    }

    @Test
    fun `next round never opens with the file just shown`() {
        val pool = library(2)
        var round = Shuffler.shuffle(pool, ShuffleOptions(seed = "loop"))
        repeat(200) {
            val next = Shuffler.nextRound(pool, round)
            assertNotEquals(round.items.last().key, next.items.first().key)
            assertNotEquals(round.seed, next.seed)
            round = next
        }
    }

    @Test
    fun `next round is replayable`() {
        val pool = library(30)
        val start = Shuffler.shuffle(pool, ShuffleOptions(seed = "chain"))
        assertEquals(keys(Shuffler.nextRound(pool, start)), keys(Shuffler.nextRound(pool, start)))
    }
}
