package com.nitrous.tessellate.item

import com.nitrous.tessellate.registry.Identifier
import com.nitrous.tessellate.world.BlockType

/**
 * High-level categorization of item behaviors and types.
 */
enum class ItemType {
    BLOCK,      // Placeable voxel block
    TOOL,       // Harvesting or utility tool
    MATERIAL,   // Crafting or raw resource material
    DEBUG,      // Debug-specific utility
    STANDARD    // General gameplay item
}

/**
 * Registrable item definition mapping a namespaced identifier to display metadata, stack limits,
 * and an optional placeable voxel block.
 *
 * Encapsulates:
 * - Namespaced identifier ("namespace:path")
 * - User-facing display name
 * - Maximum stack size (default 64)
 * - Optional associated voxel block for placeable items
 * - Item behavior category
 */
open class Item(
    val identifier: Identifier,
    val displayName: String,
    val maxStackSize: Int = 64,
    val associatedBlock: BlockType? = null,
    val itemType: ItemType = if (associatedBlock != null) ItemType.BLOCK else ItemType.STANDARD
) {
    /** True if this item can be placed in the world as a voxel block */
    val isPlaceable: Boolean
        get() = associatedBlock != null && associatedBlock != BlockType.AIR

    /** True if this item represents a voxel block */
    val isBlock: Boolean
        get() = associatedBlock != null

    override fun toString(): String = identifier.toCanonicalString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Item) return false
        return identifier == other.identifier
    }

    override fun hashCode(): Int = identifier.hashCode()
}

/**
 * Specialized Item representing a placeable voxel block.
 */
class BlockItem(
    identifier: Identifier,
    displayName: String,
    val block: BlockType,
    maxStackSize: Int = 64
) : Item(
    identifier = identifier,
    displayName = displayName,
    maxStackSize = maxStackSize,
    associatedBlock = block,
    itemType = ItemType.BLOCK
)
