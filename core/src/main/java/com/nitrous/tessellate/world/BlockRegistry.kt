package com.nitrous.tessellate.world

import com.nitrous.tessellate.registry.ContentRegistry
import com.nitrous.tessellate.registry.Identifier
import com.nitrous.tessellate.registry.Namespaces
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.registry.RegistryException
import com.nitrous.tessellate.registry.RegistryFrozenException

/**
 * Dynamic runtime block registry.
 *
 * Loads all block definitions from JSON via [RegistryBootstrap] before world generation starts,
 * assigning numeric IDs dynamically at runtime based on load order (`Tessellate:air` = 0),
 * and freezes once startup completes.
 */
object BlockRegistry {

    private const val MAX_BYTE_BLOCK_TYPES = 256

    val internalRegistry: ContentRegistry<BlockType> = ContentRegistry(
        registryId = Identifier.of(Namespaces.TESSELLATE, "blocks")
    )

    private val registryLock = Any()

    @Volatile
    private var idLookupTable: Array<BlockType?> = arrayOfNulls(MAX_BYTE_BLOCK_TYPES)

    @Volatile
    private var cachedAir: BlockType? = null

    val isFrozen: Boolean
        get() = internalRegistry.isFrozen

    val size: Int
        get() {
            RegistryBootstrap.ensureInitialized()
            return internalRegistry.size
        }

    // --- Dynamic compatibility accessors resolving from loaded JSON definitions ---
    val AIR: BlockType get() = getRequired(BlockType.AIR_IDENTIFIER_CANONICAL)
    val GRASS: BlockType get() = getRequired("Tessellate:grass")
    val DIRT: BlockType get() = getRequired("Tessellate:dirt")
    val STONE: BlockType get() = getRequired("Tessellate:stone")
    val DEBUGBUG: BlockType get() = getRequired("Debug:debugbug")
    val THING_THAT_GOES_BRRR: BlockType get() = getRequired("Developer:thing_that_goes_brrr")
    val GLASS: BlockType get() = getRequired("Tessellate:glass")
    val PROTO_STONE: BlockType get() = getRequired("prototesselate:stone")
    val SAND: BlockType get() = getRequired("Tessellate:sand")
    val COBBLESTONE: BlockType get() = getRequired("Tessellate:cobblestone")
    val OAK_LOG: BlockType get() = getRequired("Tessellate:oak_log")
    val LEAVES: BlockType get() = getRequired("Tessellate:leaves")
    val TORCH: BlockType get() = getRequired("Tessellate:torch")

    internal fun clearForBootstrap() {
        synchronized(registryLock) {
            internalRegistry.clearForBootstrap()
            idLookupTable = arrayOfNulls(MAX_BYTE_BLOCK_TYPES)
            cachedAir = null
        }
    }

    internal fun freeze() {
        internalRegistry.freeze()
    }

    /**
     * Dynamically registers a block definition with the next runtime numeric ID based on load order.
     *
     * @throws RegistryFrozenException if the registry is already frozen.
     */
    fun registerDynamic(
        identifier: Identifier,
        isOpaque: Boolean,
        topTexture: String,
        sideTexture: String,
        bottomTexture: String,
        hardness: Float = 1.0f,
        isTransparent: Boolean = false,
        isSolid: Boolean = isOpaque,
        isSimple: Boolean = isOpaque,
        model: String = BlockType.MODEL_CUBE,
        lightEmission: Byte = 0
    ): BlockType {
        synchronized(registryLock) {
            if (internalRegistry.isFrozen) {
                throw RegistryFrozenException(
                    "BlockRegistry is frozen; cannot register '${identifier.toCanonicalString()}' after startup."
                )
            }
            val assignedNumericId = internalRegistry.nextNumericId()
            if (assignedNumericId !in 0 until MAX_BYTE_BLOCK_TYPES) {
                throw RegistryException(
                    "Block numeric ID overflow ($assignedNumericId >= $MAX_BYTE_BLOCK_TYPES) for '${identifier.toCanonicalString()}'"
                )
            }

            val block = BlockType(
                id = assignedNumericId.toByte(),
                identifier = identifier,
                isOpaque = isOpaque,
                topTexture = topTexture,
                sideTexture = sideTexture,
                bottomTexture = bottomTexture,
                hardness = hardness,
                isTransparent = isTransparent,
                isSolid = isSolid,
                isSimple = isSimple,
                model = model,
                lightEmission = lightEmission
            )

            internalRegistry.register(identifier, assignedNumericId, block)
            idLookupTable[assignedNumericId] = block
            if (block.isAir) {
                cachedAir = block
            }
            return block
        }
    }

    /**
     * Registers a pre-built [BlockType] before the registry is frozen (assigning a dynamic runtime ID
     * when `block.id` is already taken or preserving/re-syncing via load order).
     */
    fun register(block: BlockType): BlockType {
        return registerDynamic(
            identifier = block.identifier,
            isOpaque = block.isOpaque,
            topTexture = block.topTexture,
            sideTexture = block.sideTexture,
            bottomTexture = block.bottomTexture,
            hardness = block.hardness,
            isTransparent = block.isTransparent,
            isSolid = block.isSolid,
            isSimple = block.isSimple,
            model = block.model,
            lightEmission = block.lightEmission
        )
    }

    fun getById(id: Byte): BlockType {
        var air = cachedAir
        if (air == null) {
            RegistryBootstrap.ensureInitialized()
            air = cachedAir
        }
        val slot = id.toInt() and 0xFF
        return idLookupTable[slot]
            ?: air
            ?: internalRegistry.get(BlockType.AIR_IDENTIFIER_CANONICAL)
            ?: throw RegistryException("BlockRegistry has not been initialized with Tessellate:air")
    }

    fun get(identifier: Identifier): BlockType? {
        RegistryBootstrap.ensureInitialized()
        return internalRegistry.get(identifier)
    }

    fun get(rawIdentifier: String): BlockType? {
        RegistryBootstrap.ensureInitialized()
        return internalRegistry.get(rawIdentifier)
    }

    fun contains(identifier: Identifier): Boolean {
        RegistryBootstrap.ensureInitialized()
        return internalRegistry.contains(identifier)
    }

    fun contains(rawIdentifier: String): Boolean {
        RegistryBootstrap.ensureInitialized()
        return internalRegistry.contains(rawIdentifier)
    }

    fun getAllBlocks(): List<BlockType> {
        RegistryBootstrap.ensureInitialized()
        return internalRegistry.getRegisteredEntries().values.toList()
    }

    private fun getRequired(rawIdentifier: String): BlockType {
        RegistryBootstrap.ensureInitialized()
        return internalRegistry.get(rawIdentifier)
            ?: throw RegistryException("Required block '$rawIdentifier' is missing from BlockRegistry")
    }
}
