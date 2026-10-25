package com.vortex.explorer.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * xoshiro256** seeded through SplitMix64.
 *
 * Written out rather than borrowed from the platform so that a seed produces
 * exactly the same order on every phone, every Android version and the JVM.
 * That is what makes a shuffle shareable: same seed, same files, same order.
 */
class SeededRandom(seed: Long) {
    private var s0: Long
    private var s1: Long
    private var s2: Long
    private var s3: Long

    init {
        var state = seed
        fun splitMix(): Long {
            state += GOLDEN_GAMMA
            var z = state
            z = (z xor (z ushr 30)) * MIX_1
            z = (z xor (z ushr 27)) * MIX_2
            return z xor (z ushr 31)
        }
        s0 = splitMix()
        s1 = splitMix()
        s2 = splitMix()
        s3 = splitMix()
    }

    fun nextLong(): Long {
        val result = java.lang.Long.rotateLeft(s1 * 5, 7) * 9
        val t = s1 shl 17
        s2 = s2 xor s0
        s3 = s3 xor s1
        s1 = s1 xor s2
        s0 = s0 xor s3
        s2 = s2 xor t
        s3 = java.lang.Long.rotateLeft(s3, 45)
        return result
    }

    /** Uniform in `[0, bound)`, without modulo bias. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, got $bound" }
        while (true) {
            val bits = (nextLong() ushr 33).toInt() // 31 random bits
            val value = bits % bound
            if (bits - value + (bound - 1) >= 0) return value
        }
    }

    /** Uniform in `[0, 1)` with 53 bits of precision. */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    /** Uniform in `(0, 1]`, safe to take the logarithm of. */
    fun nextPositiveDouble(): Double = 1.0 - nextDouble()

    private companion object {
        val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL.toLong()
        val MIX_1 = 0xBF58476D1CE4E5B9uL.toLong()
        val MIX_2 = 0x94D049BB133111EBuL.toLong()
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}

/** Short, speakable seeds: eight characters, no look-alike letters. */
object Seeds {
    // Crockford's base 32: no I, L, O or U, so a seed survives being read aloud.
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 8
    private val secure = SecureRandom()

    fun fresh(): String = buildString {
        repeat(LENGTH) { append(ALPHABET[secure.nextInt(ALPHABET.length)]) }
    }

    /** Case and surrounding space do not matter: "beach 24" and "BEACH 24 " are one seed. */
    fun normalize(seed: String): String = seed.trim().uppercase()

    fun toLong(seed: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalize(seed).toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        return value
    }

    fun random(seed: String): SeededRandom = SeededRandom(toLong(seed))

    /** The seed for the next round of a looping slideshow, derived so it is replayable too. */
    fun next(seed: String): String {
        val random = random("$seed/next")
        return buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }
}
