package com.vortex.view.core

/** The order photos are shown in. Only the viewing order: files are never renamed. */
sealed interface Order {
    val comparator: Comparator<Photo>

    /** By name, the way file managers sort: `IMG_2` before `IMG_10`. */
    data object ByName : Order {
        override val comparator: Comparator<Photo> = compareBy(Names.naturalOrder) { it.name }
    }

    /**
     * A random order. Each photo's place comes from its name and [seed] alone,
     * so moving photos out never reshuffles the rest, a photo added later
     * lands somewhere random, and the same seed gives the same order again
     * next time the folder is opened.
     */
    data class Shuffled(val seed: Long) : Order {
        override val comparator: Comparator<Photo> =
            compareBy<Photo> { key(it.name) }.thenBy(Names.naturalOrder) { it.name }

        /** A 64-bit hash of the name (FNV-1a), mixed with the seed (SplitMix64). */
        fun key(name: String): Long {
            var hash = -0x340d631b7bdddcdbL // FNV offset basis
            for (char in name) {
                hash = (hash xor char.code.toLong()) * 0x100000001b3L
            }
            var z = hash + seed * -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
