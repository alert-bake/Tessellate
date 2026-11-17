package com.nitrous.tessellate.worldgen

import com.nitrous.tessellate.registry.DuplicateIdentifierException
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.registry.RegistryFrozenException
import com.nitrous.tessellate.world.BlockRegistry
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldType

/**
 * Sorted Atomic Lasagna pass registry for procedural chunk generation.
 * Passes are sorted in ascending order of [WorldGenPass.priority] and frozen at startup.
 */
object WorldGenRegistry {

    const val FLAT_DIRT_START_Y = 8
    const val FLAT_GRASS_Y = 11

    private val lock = Any()
    private val passes = mutableListOf<WorldGenPass>()

    @Volatile
    private var frozen: Boolean = false

    val structoidPass: StructoidPass = StructoidPass()

    val isFrozen: Boolean
        get() = frozen

    internal fun clearForBootstrap() {
        synchronized(lock) {
            passes.clear()
            structoidPass.clearStructoids()
            frozen = false
        }
    }

    internal fun registerVanillaDefaults() {
        registerInternal(BasePass())
        registerInternal(SurfacePass())
        registerInternal(CavePass())
        registerInternal(structoidPass)
        registerInternal(OrePass())
        registerInternal(TreeFeaturePass())
    }

    internal fun freeze() {
        synchronized(lock) {
            passes.sortWith(compareBy<WorldGenPass> { it.priority }.thenBy { it.id })
            frozen = true
        }
    }

    /**
     * Registers a custom or mod [WorldGenPass] before the registry freezes.
     * Automatically keeps the pass list sorted by [WorldGenPass.priority].
     */
    fun register(pass: WorldGenPass) {
        synchronized(lock) {
            if (frozen) {
                throw RegistryFrozenException(
                    "WorldGenRegistry is frozen; cannot register pass '${pass.id}' after startup."
                )
            }
            registerInternal(pass)
        }
    }

    /**
     * Registers a [StructoidDefinition] onto the active [StructoidPass] prior to freeze.
     */
    fun registerStructoid(definition: StructoidDefinition) {
        synchronized(lock) {
            if (frozen) {
                throw RegistryFrozenException(
                    "WorldGenRegistry is frozen; cannot register structoid '${definition.id}' after startup."
                )
            }
            structoidPass.registerStructoid(definition)
        }
    }

    private fun registerInternal(pass: WorldGenPass) {
        if (passes.any { it.id.equals(pass.id, ignoreCase = true) }) {
            throw DuplicateIdentifierException("WorldGenPass with id '${pass.id}' is already registered.")
        }
        passes.add(pass)
        passes.sortWith(compareBy<WorldGenPass> { it.priority }.thenBy { it.id })
    }

    fun getPasses(): List<WorldGenPass> {
        RegistryBootstrap.ensureInitialized()
        return synchronized(lock) { passes.toList() }
    }

    val size: Int
        get() {
            RegistryBootstrap.ensureInitialized()
            return synchronized(lock) { passes.size }
        }

    /**
     * Generates terrain for [chunk] according to [world]'s [WorldType] and [World.seed]:
     * - [WorldType.FLAT]: bypasses Atomic Lasagna and generates a flat grass/dirt/stone layer.
     * - [WorldType.DEBUG]: generates a flat stone/proto-stone platform with a grid of registered blocks.
     * - [WorldType.DEFAULT]: executes all sorted Atomic Lasagna passes using [World.seed].
     */
    fun generateChunk(chunk: Chunk, world: World) {
        RegistryBootstrap.ensureInitialized()
        when (world.session.worldType) {
            WorldType.FLAT -> generateFlatChunk(chunk)
            WorldType.DEBUG -> generateDebugChunk(chunk)
            WorldType.DEFAULT -> {
                val ordered = synchronized(lock) { passes.toList() }
                for (i in 0 until ordered.size) {
                    ordered[i].generate(chunk, world)
                }
            }
        }
        chunk.recalculateEmpty()
        chunk.markDirty()
    }

    private fun generateFlatChunk(chunk: Chunk) {
        val baseY = chunk.chunkY * Chunk.SIZE
        if (baseY > FLAT_GRASS_Y) {
            return
        }

        val stone = BlockType.STONE
        val dirt = BlockType.DIRT
        val grass = BlockType.GRASS

        for (ly in 0 until Chunk.SIZE) {
            val wy = baseY + ly
            val block = when {
                wy < FLAT_DIRT_START_Y -> stone
                wy < FLAT_GRASS_Y -> dirt
                wy == FLAT_GRASS_Y -> grass
                else -> null
            } ?: continue

            for (lz in 0 until Chunk.SIZE) {
                for (lx in 0 until Chunk.SIZE) {
                    chunk.setBlockRaw(lx, ly, lz, block)
                }
            }
        }
    }

    private fun generateDebugChunk(chunk: Chunk) {
        val baseY = chunk.chunkY * Chunk.SIZE
        if (baseY > FLAT_GRASS_Y) {
            return
        }

        val stone = BlockType.STONE
        val protoStone = BlockType.fromIdentifier("prototesselate:stone") ?: stone
        val palette = BlockRegistry.getAllBlocks().filter { !it.isAir }
        val baseX = chunk.chunkX * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        for (ly in 0 until Chunk.SIZE) {
            val wy = baseY + ly
            if (wy > FLAT_GRASS_Y) continue
            for (lz in 0 until Chunk.SIZE) {
                val wz = baseZ + lz
                for (lx in 0 until Chunk.SIZE) {
                    val wx = baseX + lx
                    when {
                        wy < FLAT_GRASS_Y - 1 -> chunk.setBlockRaw(lx, ly, lz, stone)
                        wy == FLAT_GRASS_Y - 1 -> chunk.setBlockRaw(lx, ly, lz, protoStone)
                        wy == FLAT_GRASS_Y && palette.isNotEmpty() && wx >= 0 && wz >= 0 && wx % 2 == 0 && wz % 2 == 0 -> {
                            val idx = ((wx / 2) + (wz / 2) * 7) % palette.size
                            chunk.setBlockRaw(lx, ly, lz, palette[idx])
                        }
                    }
                }
            }
        }
    }
}
