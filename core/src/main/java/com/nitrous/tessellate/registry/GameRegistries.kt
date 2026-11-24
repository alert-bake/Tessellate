package com.nitrous.tessellate.registry

import com.nitrous.tessellate.item.Item
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.world.BlockRegistry
import com.nitrous.tessellate.world.BlockType

/**
 * Type alias for backward compatibility.
 */
typealias ItemDefinition = Item

/**
 * Central registry repository for Tessellate runtime.
 *
 * Guarantees that built-in namespaces:
 * - "Debug" (Debug-related content)
 * - "Tessellate" (Vanilla game content)
 * - "prototesselate" (Migrated prototype content)
 * - "Developer" (Developer-specific content)
 * are always registered and available in all builds, including release builds.
 */
object GameRegistries {
    /** Global Block Registry delegating to [BlockRegistry] */
    val BLOCKS: IContentRegistry<BlockType>
        get() {
            RegistryBootstrap.ensureInitialized()
            return BlockRegistry.internalRegistry
        }

    /** Global Item Registry delegating to [ItemRegistry] */
    val ITEMS: IContentRegistry<Item>
        get() = ItemRegistry.internalRegistry

    /** Global Recipe Registry delegating to [RecipeRegistry] */
    val RECIPES: IContentRegistry<com.nitrous.tessellate.item.CraftingRecipe>
        get() = com.nitrous.tessellate.item.RecipeRegistry.internalRegistry

    /**
     * Freezes all core game registries.
     */
    fun freezeAll() {
        BlockRegistry.freeze()
        ItemRegistry.internalRegistry.freeze()
        com.nitrous.tessellate.item.RecipeRegistry.freeze()
        com.nitrous.tessellate.worldgen.WorldGenRegistry.freeze()
        RegistryBootstrap.freezeCommandDelegate()
    }

    /**
     * Resolves an identifier across known content registries.
     */
    fun findBlock(identifierOrString: String): BlockType? {
        val id = Identifier.parseOrNull(identifierOrString) ?: return null
        return BLOCKS.get(id)
    }

    /**
     * Resolves an item across known content registries.
     */
    fun findItem(identifierOrString: String): Item? {
        val id = Identifier.parseOrNull(identifierOrString) ?: return null
        return ITEMS.get(id)
    }
}
