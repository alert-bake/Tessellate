package com.nitrous.tessellate.world

import kotlin.random.Random

/**
 * Player gameplay mode for a world session.
 */
enum class GameMode(val displayName: String) {
    SURVIVAL("Survival"),
    CREATIVE("Creative");

    fun next(): GameMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

/**
 * Terrain generation preset for a world session.
 */
enum class WorldType(val displayName: String) {
    DEFAULT("Default"),
    FLAT("Flat"),
    DEBUG("Debug");

    fun next(): WorldType {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

/**
 * Immutable session configuration passed into [World] upon creation.
 */
data class WorldSession(
    val name: String,
    val gamemode: GameMode,
    val worldType: WorldType,
    val seed: Long
) {
    companion object {
        const val DEFAULT_WORLD_NAME = "New World"

        /**
         * Parses user-entered seed text:
         * - Blank/whitespace -> random [Long] from [randomProvider]
         * - Integer string -> exact [Long] value
         * - Arbitrary text -> deterministic hash [Long]
         */
        fun parseSeed(rawSeed: String, randomProvider: () -> Long = { Random.nextLong() }): Long {
            val trimmed = rawSeed.trim()
            if (trimmed.isEmpty()) {
                return randomProvider()
            }
            return trimmed.toLongOrNull() ?: trimmed.hashCode().toLong()
        }
    }
}
