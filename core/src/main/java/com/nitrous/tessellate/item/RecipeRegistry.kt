package com.nitrous.tessellate.item

import com.nitrous.tessellate.registry.ContentRegistry
import com.nitrous.tessellate.registry.IContentRegistry
import com.nitrous.tessellate.registry.Identifier
import com.nitrous.tessellate.registry.Namespaces
import com.nitrous.tessellate.registry.RegistryBootstrap
import java.util.Locale

/**
 * Normalized ingredient requirement inside a [CraftingRecipe].
 */
data class RecipeIngredient(
    val itemId: String,
    val normalizedItemId: String,
    val count: Int
)

/**
 * Data-driven shapeless crafting recipe loaded from `assets/content/recipes/`.
 */
data class CraftingRecipe(
    val identifier: Identifier,
    val ingredients: List<RecipeIngredient>,
    val resultItemId: String,
    val resultCount: Int
)

/**
 * Centralized data-driven registry for crafting recipes loaded at startup via [RegistryBootstrap].
 */
object RecipeRegistry {

    private val registry: ContentRegistry<CraftingRecipe> = ContentRegistry(
        registryId = Identifier.of(Namespaces.TESSELLATE, "recipes")
    )

    val isFrozen: Boolean
        get() = registry.isFrozen

    internal fun clearForBootstrap() {
        registry.clearForBootstrap()
    }

    internal fun freeze() {
        registry.freeze()
    }

    fun register(recipe: CraftingRecipe): CraftingRecipe {
        registry.register(recipe.identifier, recipe)
        return recipe
    }

    fun get(identifier: Identifier): CraftingRecipe? {
        RegistryBootstrap.ensureInitialized()
        return registry.get(identifier)
    }

    fun get(identifierString: String): CraftingRecipe? {
        RegistryBootstrap.ensureInitialized()
        return registry.get(identifierString)
    }

    fun getAllRecipes(): List<CraftingRecipe> {
        RegistryBootstrap.ensureInitialized()
        return registry.getRegisteredEntries().values.toList()
    }

    val size: Int
        get() {
            RegistryBootstrap.ensureInitialized()
            return registry.size
        }

    val internalRegistry: IContentRegistry<CraftingRecipe>
        get() {
            RegistryBootstrap.ensureInitialized()
            return registry
        }

    /**
     * Finds the first shapeless recipe whose exact ingredient item types match [craftingGrid]
     * with sufficient item counts and no extraneous item types present.
     */
    fun findMatch(craftingGrid: Array<ItemStack?>): CraftingRecipe? {
        RegistryBootstrap.ensureInitialized()
        val gridCounts = LinkedHashMap<String, Int>()
        for (stack in craftingGrid) {
            if (stack != null && stack.count > 0 && stack.itemId.isNotBlank()) {
                val key = normalizeId(stack.itemId)
                gridCounts[key] = (gridCounts[key] ?: 0) + stack.count
            }
        }
        if (gridCounts.isEmpty()) return null

        for (recipe in registry.getRegisteredEntries().values) {
            if (matchesShapeless(recipe, gridCounts)) {
                return recipe
            }
        }
        return null
    }

    private fun matchesShapeless(recipe: CraftingRecipe, gridCounts: Map<String, Int>): Boolean {
        if (recipe.ingredients.isEmpty()) return false
        val required = LinkedHashMap<String, Int>(recipe.ingredients.size)
        for (ing in recipe.ingredients) {
            required[ing.normalizedItemId] = (required[ing.normalizedItemId] ?: 0) + ing.count
        }
        if (gridCounts.size != required.size) return false
        for ((itemKey, reqCount) in required) {
            val present = gridCounts[itemKey] ?: return false
            if (present < reqCount) return false
        }
        return true
    }

    /**
     * Consumes one craft worth of ingredients of [recipe] from [craftingGrid].
     * Returns true if all required ingredients were present and consumed cleanly.
     */
    fun consumeIngredients(craftingGrid: Array<ItemStack?>, recipe: CraftingRecipe): Boolean {
        if (findMatch(craftingGrid) == null) return false

        val remainingByItem = LinkedHashMap<String, Int>(recipe.ingredients.size)
        for (ing in recipe.ingredients) {
            remainingByItem[ing.normalizedItemId] = (remainingByItem[ing.normalizedItemId] ?: 0) + ing.count
        }

        // Verify all required counts exist
        val available = LinkedHashMap<String, Int>()
        for (stack in craftingGrid) {
            if (stack != null && stack.count > 0) {
                val k = normalizeId(stack.itemId)
                available[k] = (available[k] ?: 0) + stack.count
            }
        }
        for ((k, needed) in remainingByItem) {
            if ((available[k] ?: 0) < needed) return false
        }

        for (i in craftingGrid.indices) {
            val stack = craftingGrid[i] ?: continue
            val key = normalizeId(stack.itemId)
            val needed = remainingByItem[key] ?: 0
            if (needed <= 0) continue

            val taken = minOf(needed, stack.count)
            stack.count -= taken
            remainingByItem[key] = needed - taken
            if (stack.count <= 0) {
                craftingGrid[i] = null
            }
        }
        return true
    }

    private fun normalizeId(id: String): String {
        return Identifier.parseOrNull(id)?.toNormalizedString() ?: id.lowercase(Locale.ROOT)
    }
}
