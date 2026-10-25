package com.vortex.explorer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RandomTest {
    @Test
    fun `same seed gives the same stream`() {
        val a = SeededRandom(42)
        val b = SeededRandom(42)
        repeat(1_000) { assertEquals(a.nextLong(), b.nextLong()) }
    }

    @Test
    fun `different seeds diverge`() {
        val a = SeededRandom(1)
        val b = SeededRandom(2)
        assertNotEquals((1..8).map { a.nextLong() }, (1..8).map { b.nextLong() })
    }

    @Test
    fun `stream is pinned so seeds replay across versions`() {
        // If this ever changes, every seed a user has written down stops working.
        // Values come from an independent SplitMix64 + xoshiro256** reference.
        val random = SeededRandom(0)
        assertEquals(
            listOf(-7355399402456485196L, -4652746763540216534L, 1900383378846508768L),
            List(3) { random.nextLong() },
        )
    }

    @Test
    fun `nextInt stays in bounds`() {
        val random = SeededRandom(7)
        for (bound in listOf(1, 2, 3, 10, 1_000, Int.MAX_VALUE)) {
            repeat(2_000) {
                val value = random.nextInt(bound)
                assertTrue(value in 0 until bound, "$value out of [0, $bound)")
            }
        }
        assertFailsWith<IllegalArgumentException> { random.nextInt(0) }
    }

    @Test
    fun `nextInt is uniform`() {
        // Chi-square against a uniform die; 5 degrees of freedom, p = 0.001 cutoff 20.52.
        val random = SeededRandom(99)
        val counts = IntArray(6)
        val rolls = 120_000
        repeat(rolls) { counts[random.nextInt(6)]++ }
        val expected = rolls / 6.0
        val chiSquare = counts.sumOf { (it - expected) * (it - expected) / expected }
        assertTrue(chiSquare < 20.52, "die looks loaded: chi^2 = $chiSquare, counts = ${counts.toList()}")
    }

    @Test
    fun `doubles cover the unit interval`() {
        val random = SeededRandom(5)
        val values = List(50_000) { random.nextDouble() }
        assertTrue(values.all { it >= 0.0 && it < 1.0 })
        assertEquals(0.5, values.average(), 0.01)
        assertTrue(List(10_000) { random.nextPositiveDouble() }.all { it > 0.0 && it <= 1.0 })
    }

    @Test
    fun `fresh seeds are short and unambiguous`() {
        val seeds = List(500) { Seeds.fresh() }
        assertTrue(seeds.all { it.length == 8 })
        assertTrue(seeds.all { seed -> seed.none { it in "ILOU" } })
        assertTrue(seeds.toSet().size > 495, "fresh seeds collide too often")
    }

    @Test
    fun `seeds ignore case and surrounding space`() {
        assertEquals(Seeds.toLong("beach 24"), Seeds.toLong("  BEACH 24 "))
        assertNotEquals(Seeds.toLong("beach 24"), Seeds.toLong("beach 25"))
    }

    @Test
    fun `next seed is deterministic and different`() {
        assertEquals(Seeds.next("ABCD1234"), Seeds.next("abcd1234"))
        assertNotEquals("ABCD1234", Seeds.next("ABCD1234"))
    }
}
