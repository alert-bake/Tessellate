package com.nitrous.tessellate.worldgen

import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.World
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parsed chunk dimensions for a Structoid bounding box (e.g., "Ch2x4x2" -> 2x4x2 chunks).
 */
data class StructoidSizeChunks(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int
) {
    companion object {
        private val PATTERN = Regex("^Ch(\\d+)x(\\d+)x(\\d+)$", RegexOption.IGNORE_CASE)

        fun parse(raw: String): StructoidSizeChunks {
            val match = PATTERN.matchEntire(raw.trim())
                ?: throw IllegalArgumentException(
                    "Invalid Structoid size_chunks '$raw'. Expected format 'ChXxYxZ' (e.g., 'Ch2x4x2')."
                )
            return StructoidSizeChunks(
                sizeX = match.groupValues[1].toInt().coerceAtLeast(1),
                sizeY = match.groupValues[2].toInt().coerceAtLeast(1),
                sizeZ = match.groupValues[3].toInt().coerceAtLeast(1)
            )
        }
    }
}

/**
 * Data-driven Structoid macro-carving volume aligned to chunk coordinates.
 */
data class StructoidDefinition(
    val id: String,
    val originChunkX: Int,
    val originChunkY: Int,
    val originChunkZ: Int,
    val sizeChunks: String = "Ch1x1x1",
    val noiseThreshold: Float = 0.35f,
    val noiseFrequency: Float = 0.1f,
    val carveAll: Boolean = false
) {
    val parsedSize: StructoidSizeChunks = StructoidSizeChunks.parse(sizeChunks)

    fun intersectsChunk(cx: Int, cy: Int, cz: Int): Boolean {
        return cx in originChunkX until (originChunkX + parsedSize.sizeX) &&
            cy in originChunkY until (originChunkY + parsedSize.sizeY) &&
            cz in originChunkZ until (originChunkZ + parsedSize.sizeZ)
    }
}

/**
 * Pass 40: Macro-carving pass applying registered [StructoidDefinition] volumes.
 *
 * Includes the NHPHP edge case optimization: if a chunk is entirely air (`isEmpty`),
 * all carving math is skipped immediately to save CPU cycles.
 */
class StructoidPass(
    override val id: String = "tessellate:structoid",
    override val priority: Int = 40
) : WorldGenPass {

    private val structoids = mutableListOf<StructoidDefinition>()
    private val lock = Any()

    val skippedEmptyChunksCount = AtomicInteger(0)
    val carvedChunksCount = AtomicInteger(0)

    fun registerStructoid(definition: StructoidDefinition) {
        synchronized(lock) {
            structoids.removeAll { it.id == definition.id }
            structoids.add(definition)
        }
    }

    fun clearStructoids() {
        synchronized(lock) {
            structoids.clear()
        }
        skippedEmptyChunksCount.set(0)
        carvedChunksCount.set(0)
    }

    fun getStructoids(): List<StructoidDefinition> = synchronized(lock) { structoids.toList() }

    override fun generate(chunk: Chunk, world: World) {
        // NHPHP Edge Case: immediately skip air-only chunks before touching noise math
        chunk.recalculateEmpty()
        if (chunk.isEmpty) {
            skippedEmptyChunksCount.incrementAndGet()
            return
        }

        val matching = synchronized(lock) {
            structoids.filter { it.intersectsChunk(chunk.chunkX, chunk.chunkY, chunk.chunkZ) }
        }
        if (matching.isEmpty()) return

        val air = BlockType.AIR
        val baseX = chunk.chunkX * Chunk.SIZE
        val baseY = chunk.chunkY * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        carvedChunksCount.incrementAndGet()

        for (def in matching) {
            val seedOffset = def.id.hashCode().toLong() * 1315423911L
            for (ly in 0 until Chunk.SIZE) {
                val wy = baseY + ly
                for (lz in 0 until Chunk.SIZE) {
                    val wz = baseZ + lz
                    for (lx in 0 until Chunk.SIZE) {
                        if (chunk.getBlock(lx, ly, lz) == air) continue
                        val wx = baseX + lx
                        val shouldCarve = if (def.carveAll) {
                            true
                        } else {
                            val noise = PerlinNoise.noise3DNormalized(
                                world.seed + seedOffset,
                                wx * def.noiseFrequency,
                                wy * def.noiseFrequency,
                                wz * def.noiseFrequency
                            )
                            noise > def.noiseThreshold
                        }
                        if (shouldCarve) {
                            chunk.setBlockRaw(lx, ly, lz, air)
                        }
                    }
                }
            }
        }
    }
}
