package com.nitrous.tessellate.item

import com.nitrous.tessellate.registry.Identifier
import java.util.Locale

/**
 * Single stack of items stored in an [Inventory] slot.
 */
data class ItemStack(
    val itemId: String,
    var count: Int
)

/**
 * Player inventory managing stacked [ItemStack] entries (slots 0..8 = hotbar, 9..35 = 27-slot main inventory)
 * and active hotbar slot selection. Default size remains [HOTBAR_SIZE] (9) for standalone hotbar usage.
 */
class Inventory(
    val size: Int = HOTBAR_SIZE
) {
    private val lock = Any()
    private val slots = arrayOfNulls<ItemStack>(size)
    private val maxHotbarIndex: Int
        get() = (minOf(HOTBAR_SIZE, size) - 1).coerceAtLeast(0)

    @Volatile
    var selectedSlot: Int = 0
        set(value) {
            field = value.coerceIn(0, maxHotbarIndex)
        }

    fun selectSlot(index: Int): Boolean {
        if (index !in 0..maxHotbarIndex) return false
        selectedSlot = index
        return true
    }

    fun getSlot(index: Int): ItemStack? = synchronized(lock) {
        if (index !in 0 until size) return null
        return slots[index]
    }

    fun setSlot(index: Int, stack: ItemStack?) = synchronized(lock) {
        if (index !in 0 until size) return
        slots[index] = if (stack != null && stack.count > 0) stack else null
    }

    fun getSelectedStack(): ItemStack? = synchronized(lock) {
        getSlot(selectedSlot)
    }

    /**
     * Checks whether [amount] of [itemId] can fit cleanly into existing matching stacks or empty slots
     * without modifying the inventory.
     */
    fun canFit(itemId: String, amount: Int = 1): Boolean = synchronized(lock) {
        if (amount <= 0) return true
        if (itemId.isBlank()) return false

        val registered = ItemRegistry.get(itemId)
        val canonicalId = registered?.identifier?.toCanonicalString() ?: itemId
        val maxStack = (registered?.maxStackSize ?: DEFAULT_MAX_STACK).coerceAtLeast(1)
        val normalizedTarget = normalizeId(canonicalId)

        var remaining = amount
        for (i in 0 until size) {
            val existing = slots[i] ?: continue
            if (normalizeId(existing.itemId) == normalizedTarget && existing.count < maxStack) {
                remaining -= (maxStack - existing.count)
                if (remaining <= 0) return true
            }
        }
        for (i in 0 until size) {
            if (slots[i] == null) {
                remaining -= maxStack
                if (remaining <= 0) return true
            }
        }
        return remaining <= 0
    }

    /**
     * Adds [amount] of [itemId] into the inventory, stacking into matching existing slots first
     * and overflowing into the earliest empty slot(s).
     * Returns leftover count that could not fit (0 if all items were added).
     */
    fun addItem(itemId: String, amount: Int = 1): Int = synchronized(lock) {
        if (amount <= 0 || itemId.isBlank()) return 0

        val registered = ItemRegistry.get(itemId)
        val canonicalId = registered?.identifier?.toCanonicalString() ?: itemId
        val maxStack = (registered?.maxStackSize ?: DEFAULT_MAX_STACK).coerceAtLeast(1)
        val normalizedTarget = normalizeId(canonicalId)

        var remaining = amount

        // Pass 1: stack onto existing matching slots with available capacity
        for (i in 0 until size) {
            if (remaining <= 0) break
            val existing = slots[i] ?: continue
            if (normalizeId(existing.itemId) == normalizedTarget && existing.count < maxStack) {
                val space = maxStack - existing.count
                val added = minOf(remaining, space)
                existing.count += added
                remaining -= added
            }
        }

        // Pass 2: place remaining items into empty slots
        for (i in 0 until size) {
            if (remaining <= 0) break
            if (slots[i] == null) {
                val placed = minOf(remaining, maxStack)
                slots[i] = ItemStack(itemId = canonicalId, count = placed)
                remaining -= placed
            }
        }

        return remaining
    }

    fun getTotalCount(itemId: String): Int = synchronized(lock) {
        val target = normalizeId(itemId)
        var total = 0
        for (i in 0 until size) {
            val stack = slots[i] ?: continue
            if (normalizeId(stack.itemId) == target) {
                total += stack.count
            }
        }
        return total
    }

    fun clear() = synchronized(lock) {
        for (i in 0 until size) {
            slots[i] = null
        }
        selectedSlot = 0
    }

    private fun normalizeId(id: String): String {
        return Identifier.parseOrNull(id)?.toNormalizedString() ?: id.lowercase(Locale.ROOT)
    }

    companion object {
        const val HOTBAR_SIZE = 9
        const val MAIN_INVENTORY_SIZE = 27
        const val TOTAL_SIZE = HOTBAR_SIZE + MAIN_INVENTORY_SIZE
        const val DEFAULT_MAX_STACK = 64
    }
}
