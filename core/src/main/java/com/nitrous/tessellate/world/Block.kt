package com.nitrous.tessellate.world

import com.nitrous.tessellate.registry.ContentRegistry
import com.nitrous.tessellate.registry.Identifier
import com.nitrous.tessellate.registry.Namespaces

/**
 * Direction enum representing the 6 primary voxel faces with outward normal vectors.
 */
enum class Direction(
    val dx: Int,
    val dy: Int,
    val dz: Int,
    val normalX: Float,
    val normalY: Float,
    val normalZ: Float,
    val faceShade: Float // Ambient occlusion / directional light factor
) {
    UP(0, 1, 0, 0f, 1f, 0f, 1.0f),
    DOWN(0, -1, 0, 0f, -1f, 0f, 0.55f),
    NORTH(0, 0, -1, 0f, 0f, -1f, 0.8f),
    SOUTH(0, 0, 1, 0f, 0f, 1f, 0.85f),
    WEST(-1, 0, 0, -1f, 0f, 0f, 0.7f),
    EAST(1, 0, 0, 1f, 0f, 0f, 0.75f);

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            NORTH -> SOUTH
            SOUTH -> NORTH
            WEST -> EAST
            EAST -> WEST
        }
}

/**
 * Data-driven block definition loaded at startup.
 * Numeric byte IDs are assigned dynamically at runtime by [BlockRegistry] from JSON load order.
 */
data class BlockType(
    val id: Byte,
    val identifier: Identifier,
    val isOpaque: Boolean,
    val topTexture: String,
    val sideTexture: String,
    val bottomTexture: String,
    val hardness: Float = 1.0f,
    val isTransparent: Boolean = false,
    val isSolid: Boolean = isOpaque,
    val isSimple: Boolean = isOpaque,
    val model: String = MODEL_CUBE,
    val lightEmission: Byte = 0
) {
    /** Canonical serialized string representation (e.g. "Tessellate:stone") */
    val stringId: String
        get() = identifier.toCanonicalString()

    /** True if this block represents air */
    val isAir: Boolean
        get() = identifier.normalizedString == AIR_IDENTIFIER_NORMALIZED

    /** Whether this block can be mined by a player */
    val isMineable: Boolean
        get() = !isAir && hardness >= 0f

    val isCrossModel: Boolean
        get() = model == MODEL_CROSS

    fun getTextureForFace(direction: Direction): String {
        return when (direction) {
            Direction.UP -> topTexture
            Direction.DOWN -> bottomTexture
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST -> sideTexture
        }
    }

    companion object {
        const val AIR_IDENTIFIER_CANONICAL = "Tessellate:air"
        const val AIR_IDENTIFIER_NORMALIZED = "tessellate:air"

        const val MODEL_CUBE = "cube"
        const val MODEL_CROSS = "cross"

        /**
         * Global block registry accessor preserved for legacy callers (delegates to [BlockRegistry.internalRegistry]).
         */
        val REGISTRY: ContentRegistry<BlockType>
            get() {
                com.nitrous.tessellate.registry.RegistryBootstrap.ensureInitialized()
                return BlockRegistry.internalRegistry
            }

        // Compatibility accessors resolved dynamically from JSON-loaded registry entries
        val AIR: BlockType get() = BlockRegistry.AIR
        val GRASS: BlockType get() = BlockRegistry.GRASS
        val DIRT: BlockType get() = BlockRegistry.DIRT
        val STONE: BlockType get() = BlockRegistry.STONE
        val DEBUGBUG: BlockType get() = BlockRegistry.DEBUGBUG
        val THING_THAT_GOES_BRRR: BlockType get() = BlockRegistry.THING_THAT_GOES_BRRR
        val GLASS: BlockType get() = BlockRegistry.GLASS
        val PROTO_STONE: BlockType get() = BlockRegistry.PROTO_STONE
        val SAND: BlockType get() = BlockRegistry.SAND
        val COBBLESTONE: BlockType get() = BlockRegistry.COBBLESTONE
        val OAK_LOG: BlockType get() = BlockRegistry.OAK_LOG
        val LEAVES: BlockType get() = BlockRegistry.LEAVES
        val TORCH: BlockType get() = BlockRegistry.TORCH

        fun values(): Array<BlockType> = BlockRegistry.getAllBlocks().toTypedArray()

        fun fromId(id: Byte): BlockType = BlockRegistry.getById(id)

        /**
         * Resolves block type by namespaced identifier (case-insensitive).
         */
        fun fromIdentifier(identifier: Identifier): BlockType? = BlockRegistry.get(identifier)

        /**
         * Resolves block type by identifier string (case-insensitive).
         */
        fun fromIdentifier(rawIdentifier: String): BlockType? = BlockRegistry.get(rawIdentifier)

        /**
         * Compatibility method resolving legacy un-namespaced IDs (e.g., "AIR", "STONE")
         * or standardized namespaced strings (e.g. "Tessellate:stone").
         */
        fun fromStringId(raw: String): BlockType {
            val identifier = Identifier.parseWithLegacyFallback(raw, defaultNamespace = Namespaces.TESSELLATE)
            return fromIdentifier(identifier) ?: AIR
        }

        /**
         * Legacy world-load migration mapping old prototype stone references ("Tessellate:stone" / "STONE")
         * to [PROTO_STONE] ("prototesselate:stone").
         */
        fun migrateLegacyWorldBlockId(raw: String): BlockType {
            val normalized = raw.trim().lowercase()
            if (normalized == "tessellate:stone" ||
                normalized == "tesselate:stone" ||
                normalized == "stone" ||
                normalized == "prototesselate:stone" ||
                normalized == "prototessellate:stone"
            ) {
                return PROTO_STONE
            }
            return fromStringId(raw)
        }
    }
}
