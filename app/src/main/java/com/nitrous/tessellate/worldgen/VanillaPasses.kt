package com.nitrous.tessellate.worldgen

import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.World

/**
 * Pass 0: Fills the entire chunk with base `tessellate:stone`.
 */
class BasePass(
    override val id: String = "tessellate:base",
    override val priority: Int = 0
) : WorldGenPass {
    override fun generate(chunk: Chunk, world: World) {
        val stone = BlockType.STONE
        chunk.fill(stone)
    }
}

/**
 * Pass 10: 2D Perlin noise heightmap (Y = 40..80).
 * Top block becomes `tessellate:grass`, top 4 blocks below surface become `tessellate:dirt`,
 * and everything above the surface is cleared to `tessellate:air`.
 */
class SurfacePass(
    override val id: String = "tessellate:surface",
    override val priority: Int = 10
) : WorldGenPass {

    override fun generate(chunk: Chunk, world: World) {
        val air = BlockType.AIR
        val baseY = chunk.chunkY * Chunk.SIZE
        if (baseY > MAX_HEIGHT) {
            chunk.fill(air)
            return
        }
        if (baseY + Chunk.SIZE - 1 < MIN_HEIGHT - 4) {
            return
        }

        val grass = BlockType.GRASS
        val dirt = BlockType.DIRT

        val baseX = chunk.chunkX * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE
        var hasSky = false
        var hasGround = false
        val lightBuf = ByteArray(Chunk.VOLUME)

        for (lx in 0 until Chunk.SIZE) {
            val wx = baseX + lx
            for (lz in 0 until Chunk.SIZE) {
                val wz = baseZ + lz
                val surfaceY = computeSurfaceY(world.seed, wx, wz)
                for (ly in 0 until Chunk.SIZE) {
                    val wy = baseY + ly
                    val idx = (ly shl 8) or (lz shl 4) or lx
                    when {
                        wy > surfaceY -> {
                            chunk.setBlockRaw(lx, ly, lz, air)
                            lightBuf[idx] = Chunk.MAX_LIGHT.toByte()
                            hasSky = true
                        }
                        wy == surfaceY -> {
                            chunk.setBlockRaw(lx, ly, lz, grass)
                            lightBuf[idx] = Chunk.MIN_LIGHT.toByte()
                            hasGround = true
                        }
                        wy >= surfaceY - 4 -> {
                            chunk.setBlockRaw(lx, ly, lz, dirt)
                            lightBuf[idx] = Chunk.MIN_LIGHT.toByte()
                            hasGround = true
                        }
                        else -> {
                            lightBuf[idx] = Chunk.MIN_LIGHT.toByte()
                            hasGround = true
                        }
                    }
                }
            }
        }

        when {
            !hasGround -> chunk.markCompletelyLit()
            !hasSky -> chunk.markCompletelyDark()
            else -> chunk.setLightArrays(lightBuf, lightBuf)
        }
    }

    companion object {
        const val MIN_HEIGHT = 40
        const val MAX_HEIGHT = 80
        private const val SURFACE_SCALE = 0.018f

        fun computeSurfaceY(seed: Long, wx: Int, wz: Int): Int {
            val n = PerlinNoise.fbm2DNormalized(seed, wx * SURFACE_SCALE, wz * SURFACE_SCALE, octaves = 3)
            return (MIN_HEIGHT + (n * (MAX_HEIGHT - MIN_HEIGHT)).toInt()).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        }
    }
}

/**
 * Pass 30: 3D noise cave carving below Y = 60.
 * If 3D noise > 0.6, replaces block with `tessellate:air`.
 */
class CavePass(
    override val id: String = "tessellate:cave",
    override val priority: Int = 30
) : WorldGenPass {

    override fun generate(chunk: Chunk, world: World) {
        val baseY = chunk.chunkY * Chunk.SIZE
        if (baseY >= MAX_CAVE_Y) return

        val air = BlockType.AIR
        val airId = air.id
        val baseX = chunk.chunkX * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        for (ly in 0 until Chunk.SIZE) {
            val wy = baseY + ly
            if (wy >= MAX_CAVE_Y) break
            for (lz in 0 until Chunk.SIZE) {
                val wz = baseZ + lz
                for (lx in 0 until Chunk.SIZE) {
                    if (chunk.getBlockIdUnchecked(lx, ly, lz) == airId) continue
                    val wx = baseX + lx
                    if (isCaveAt(world.seed, wx, wy, wz)) {
                        chunk.setBlockRaw(lx, ly, lz, air)
                        if (chunk.lightIntended != null) {
                            chunk.setIntendedLight(lx, ly, lz, Chunk.MIN_LIGHT)
                            chunk.setRenderedLight(lx, ly, lz, Chunk.MIN_LIGHT)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_CAVE_Y = 60
        const val NOISE_THRESHOLD = 0.6f
        private const val CAVE_FREQ_XZ = 0.055f
        private const val CAVE_FREQ_Y = 0.075f

        fun isCaveAt(seed: Long, wx: Int, wy: Int, wz: Int): Boolean {
            if (wy >= MAX_CAVE_Y) return false
            // Boost amplitude gently around 0.5 so Perlin caves exceed 0.6f cleanly while remaining walkable
            val raw = PerlinNoise.noise3D(seed + 4099L, wx * CAVE_FREQ_XZ, wy * CAVE_FREQ_Y, wz * CAVE_FREQ_XZ)
            val normalized = 0.5f + raw * 0.9f
            return normalized > NOISE_THRESHOLD
        }
    }
}

/**
 * Pass 50: Randomly replaces `tessellate:stone` with `tessellate:cobblestone` as ore placeholder.
 */
class OrePass(
    override val id: String = "tessellate:ore",
    override val priority: Int = 50
) : WorldGenPass {

    override fun generate(chunk: Chunk, world: World) {
        val baseY = chunk.chunkY * Chunk.SIZE
        if (baseY > SurfacePass.MAX_HEIGHT) return

        val stoneId = BlockType.STONE.id
        val ore = BlockType.COBBLESTONE
        val baseX = chunk.chunkX * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        for (ly in 0 until Chunk.SIZE) {
            val wy = baseY + ly
            for (lz in 0 until Chunk.SIZE) {
                val wz = baseZ + lz
                for (lx in 0 until Chunk.SIZE) {
                    if (chunk.getBlockIdUnchecked(lx, ly, lz) == stoneId) {
                        val wx = baseX + lx
                        if (PerlinNoise.hashFloat(world.seed + 8191L, wx, wy, wz) < ORE_CHANCE) {
                            chunk.setBlockRaw(lx, ly, lz, ore)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ORE_CHANCE = 0.045f
    }
}

/**
 * Pass 60: Loops through surface blocks; if `tessellate:grass`, rolls a 2% chance
 * to spawn a 4-6 block tall `tessellate:oak_log` trunk with a 3x3 `tessellate:leaves` canopy.
 * Handles cross-chunk trunks and canopies deterministically so no chunk edges are clipped.
 */
class TreeFeaturePass(
    override val id: String = "tessellate:tree_feature",
    override val priority: Int = 60
) : WorldGenPass {

    override fun generate(chunk: Chunk, world: World) {
        val baseY = chunk.chunkY * Chunk.SIZE
        if (baseY + Chunk.SIZE - 1 < SurfacePass.MIN_HEIGHT + 1 || baseY > SurfacePass.MAX_HEIGHT + 7) {
            return
        }

        val oakLog = BlockType.fromIdentifier("tessellate:oak_log") ?: BlockType.OAK_LOG
        val leaves = BlockType.fromIdentifier("tessellate:leaves") ?: BlockType.LEAVES
        val grass = BlockType.GRASS
        val air = BlockType.AIR

        val baseX = chunk.chunkX * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        // Check tree candidate root columns within horizontal canopy radius (+-1 around chunk)
        for (tx in (baseX - 1)..(baseX + Chunk.SIZE)) {
            for (tz in (baseZ - 1)..(baseZ + Chunk.SIZE)) {
                val rootY = SurfacePass.computeSurfaceY(world.seed, tx, tz)

                // Quick vertical AABB reject: tallest tree reaches rootY + 7
                if (rootY + 7 < baseY || rootY + 1 >= baseY + Chunk.SIZE) continue

                // Roll deterministic 2% chance for this grass column
                if (PerlinNoise.hashFloat(world.seed + 17389L, tx, rootY, tz) >= TREE_CHANCE) continue

                // If root block lies inside this chunk, ensure it actually remained grass (wasn't carved)
                val rootLx = tx - baseX
                val rootLy = rootY - baseY
                val rootLz = tz - baseZ
                if (rootLx in 0 until Chunk.SIZE && rootLy in 0 until Chunk.SIZE && rootLz in 0 until Chunk.SIZE) {
                    if (chunk.getBlock(rootLx, rootLy, rootLz) != grass) continue
                } else {
                    // Deterministic carve check when root sits in a neighboring/lower chunk
                    if (CavePass.isCaveAt(world.seed, tx, rootY, tz)) continue
                }

                val trunkHeight = 4 + PerlinNoise.hashInt(world.seed + 31337L, tx, rootY, tz, 3) // 4..6 blocks
                val trunkTopY = rootY + trunkHeight

                // Place 3x3 canopy layers around top of trunk (trunkTopY - 1 .. trunkTopY + 1)
                for (cy in (trunkTopY - 1)..(trunkTopY + 1)) {
                    val ly = cy - baseY
                    if (ly !in 0 until Chunk.SIZE) continue
                    for (dx in -1..1) {
                        val lx = (tx + dx) - baseX
                        if (lx !in 0 until Chunk.SIZE) continue
                        for (dz in -1..1) {
                            val lz = (tz + dz) - baseZ
                            if (lz !in 0 until Chunk.SIZE) continue
                            // Do not overwrite trunk center up to trunkTopY
                            if (dx == 0 && dz == 0 && cy <= trunkTopY) continue
                            if (chunk.getBlock(lx, ly, lz) == air) {
                                chunk.setBlockRaw(lx, ly, lz, leaves)
                            }
                        }
                    }
                }

                // Place 4-6 block tall oak log trunk
                for (ty in (rootY + 1)..trunkTopY) {
                    val ly = ty - baseY
                    val lx = tx - baseX
                    val lz = tz - baseZ
                    if (lx in 0 until Chunk.SIZE && ly in 0 until Chunk.SIZE && lz in 0 until Chunk.SIZE) {
                        chunk.setBlockRaw(lx, ly, lz, oakLog)
                    }
                }
            }
        }
    }

    companion object {
        const val TREE_CHANCE = 0.02f
    }
}
