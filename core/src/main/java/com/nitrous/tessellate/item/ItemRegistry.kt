package com.nitrous.tessellate.item

import com.nitrous.tessellate.registry.ContentRegistry
import com.nitrous.tessellate.registry.DuplicateIdentifierException
import com.nitrous.tessellate.registry.IContentRegistry
import com.nitrous.tessellate.registry.Identifier
import com.nitrous.tessellate.registry.Namespaces
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.registry.RegistryException
import com.nitrous.tessellate.registry.RegistryFrozenException
import com.nitrous.tessellate.world.BlockType
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized data-driven item registry.
 *
 * Loads items dynamically from JSON definitions via [RegistryBootstrap], resolves associated
 * [BlockType] relationships before freezing, and assigns sequential runtime numeric IDs.
 */
object ItemRegistry {

    private val registry: ContentRegistry<Item> = ContentRegistry(
        registryId = Identifier.of(Namespaces.TESSELLATE, "items")
    )

    private val blockToItemMap = ConcurrentHashMap<BlockType, Item>()

    val isFrozen: Boolean
        get() = registry.isFrozen

    // --- Dynamic compatibility accessors resolved from JSON-loaded items ---
    val AIR: Item get() = getRequired("Tessellate:air")
    val GRASS: Item get() = getRequired("Tessellate:grass")
    val DIRT: Item get() = getRequired("Tessellate:dirt")
    val STONE: Item get() = getRequired("Tessellate:stone")
    val GLASS: Item get() = getRequired("Tessellate:glass")
    val SAND: Item get() = getRequired("Tessellate:sand")
    val COBBLESTONE: Item get() = getRequired("Tessellate:cobblestone")
    val OAK_LOG: Item get() = getRequired("Tessellate:oak_log")
    val OAK_PLANKS: Item get() = getRequired("Tessellate:oak_planks")
    val LEAVES: Item get() = getRequired("Tessellate:leaves")
    val TORCH: Item get() = getRequired("Tessellate:torch")
    val RUBIDIUM_INGOT: Item get() = getRequired("Tessellate:rubidium_ingot")
    val IRON_PICKAXE: Item get() = getRequired("Tessellate:iron_pickaxe")
    val PROTO_STONE: Item get() = getRequired("prototesselate:stone")
    val DEBUGBUG: Item get() = getRequired("Debug:debugbug")
    val DEBUG_WAND: Item get() = getRequired("Debug:debug_wand")
    val THING_THAT_GOES_BRRR: Item get() = getRequired("Developer:thing_that_goes_brrr")

    internal fun clearForBootstrap() {
        registry.clearForBootstrap()
        blockToItemMap.clear()
    }

    internal fun freeze() {
        registry.freeze()
    }

    /**
     * Registers an item into the centralized item registry before startup freeze.
     *
     * @throws RegistryFrozenException if the registry is already frozen.
     * @throws DuplicateIdentifierException if the identifier or a case-insensitive variant is already registered.
     */
    fun register(item: Item): Item {
        registry.register(item.identifier, item)
        if (item.associatedBlock != null) {
            blockToItemMap[item.associatedBlock] = item
        }
        return item
    }

    /**
     * Retrieves an item by its namespaced [Identifier] (case-insensitive).
     */
    fun get(identifier: Identifier): Item? {
        RegistryBootstrap.ensureInitialized()
        return registry.get(identifier)
    }

    /**
     * Retrieves an item by its raw string representation (case-insensitive).
     */
    fun get(identifierString: String): Item? {
        RegistryBootstrap.ensureInitialized()
        return registry.get(identifierString)
    }

    /**
     * Resolves the canonical [Identifier] for a given item identifier (case-insensitive).
     */
    fun getCanonicalIdentifier(identifier: Identifier): Identifier? {
        RegistryBootstrap.ensureInitialized()
        return registry.getCanonicalIdentifier(identifier)
    }

    /**
     * Resolves the canonical [Identifier] for a given string representation (case-insensitive).
     */
    fun getCanonicalIdentifier(identifierString: String): Identifier? {
        RegistryBootstrap.ensureInitialized()
        return registry.getCanonicalIdentifier(identifierString)
    }

    /**
     * Checks if an item with the given identifier is registered.
     */
    fun contains(identifier: Identifier): Boolean {
        RegistryBootstrap.ensureInitialized()
        return registry.contains(identifier)
    }

    /**
     * Checks if an item with the given identifier string is registered.
     */
    fun contains(identifierString: String): Boolean {
        RegistryBootstrap.ensureInitialized()
        return registry.contains(identifierString)
    }

    /**
     * Finds the registered [Item] representing the given [BlockType].
     */
    fun getItemForBlock(block: BlockType): Item? {
        RegistryBootstrap.ensureInitialized()
        return blockToItemMap[block]
    }

    /**
     * Returns all registered items.
     */
    fun getAllItems(): List<Item> {
        RegistryBootstrap.ensureInitialized()
        return registry.getRegisteredEntries().values.toList()
    }

    /**
     * Number of registered items.
     */
    val size: Int
        get() {
            RegistryBootstrap.ensureInitialized()
            return registry.size
        }

    /**
     * Internal content registry backing this item registry.
     */
    val internalRegistry: IContentRegistry<Item>
        get() {
            RegistryBootstrap.ensureInitialized()
            return registry
        }

    private fun getRequired(rawIdentifier: String): Item {
        RegistryBootstrap.ensureInitialized()
        return registry.get(rawIdentifier)
            ?: throw RegistryException("Required item '$rawIdentifier' is missing from ItemRegistry")
    }
}
