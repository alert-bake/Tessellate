package com.nitrous.tessellate.worldgen

import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.World

/**
 * Atomic Lasagna terrain generation pass contract.
 * Passes execute in ascending [priority] order on each generating 16x16x16 [Chunk].
 */
interface WorldGenPass {
    val id: String
    val priority: Int
    fun generate(chunk: Chunk, world: World)
}
