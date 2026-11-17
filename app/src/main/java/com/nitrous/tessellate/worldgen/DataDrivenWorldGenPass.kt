package com.nitrous.tessellate.worldgen

import com.nitrous.tessellate.registry.WorldGenPassDefinitionJson
import com.nitrous.tessellate.world.BlockRegistry
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.World
import java.util.Locale

/**
 * Data-driven [WorldGenPass] loaded from JSON under `assets/content/worldgen/passes/`
 * so mods can inject custom terrain layers, ores, or carvers by priority/offset before freezing.
 */
class DataDrivenWorldGenPass(
    private val definition: WorldGenPassDefinitionJson
) : WorldGenPass {

    override val id: String = definition.id
    override val priority: Int = definition.effectivePriority()

    override fun generate(chunk: Chunk, world: World) {
        val targetBlock = BlockRegistry.get(definition.blockId) ?: BlockType.STONE
        val replaceFilter = definition.replaceBlockId?.takeIf { it.isNotBlank() }?.let { BlockRegistry.get(it) }
        val op = definition.operation.trim().lowercase(Locale.ROOT)
        val baseX = chunk.chunkX * Chunk.SIZE
        val baseY = chunk.chunkY * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        if (baseY + Chunk.SIZE - 1 < definition.minY || baseY > definition.maxY) return

        val passSeed = world.seed + definition.id.hashCode().toLong()
        for (ly in 0 until Chunk.SIZE) {
            val wy = baseY + ly
            if (wy !in definition.minY..definition.maxY) continue
            for (lz in 0 until Chunk.SIZE) {
                val wz = baseZ + lz
                for (lx in 0 until Chunk.SIZE) {
                    val wx = baseX + lx
                    val existing = chunk.getBlock(lx, ly, lz)
                    if (replaceFilter != null && existing != replaceFilter) continue

                    when (op) {
                        "fill_layer" -> {
                            if (definition.chance >= 1f || PerlinNoise.hashFloat(passSeed, wx, wy, wz) < definition.chance) {
                                chunk.setBlockRaw(lx, ly, lz, targetBlock)
                            }
                        }
                        "scatter", "ore" -> {
                            if (existing != BlockType.AIR &&
                                PerlinNoise.hashFloat(passSeed, wx, wy, wz) < definition.chance
                            ) {
                                chunk.setBlockRaw(lx, ly, lz, targetBlock)
                            }
                        }
                        "carve" -> {
                            if (existing != BlockType.AIR &&
                                PerlinNoise.noise3DNormalized(
                                    passSeed,
                                    wx * definition.frequency,
                                    wy * definition.frequency,
                                    wz * definition.frequency
                                ) > definition.threshold
                            ) {
                                chunk.setBlockRaw(lx, ly, lz, BlockType.AIR)
                            }
                        }
                    }
                }
            }
        }
    }
}
