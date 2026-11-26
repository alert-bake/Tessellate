package com.nitrous.tessellate.registry

import java.io.File

/**
 * Platform-independent representation of a cached block definition stored inside `data.cbor`.
 */
data class CachedBlockEntry(
    val id: String,
    val hardness: Float,
    val isOpaque: Boolean,
    val isTransparent: Boolean,
    val isSolid: Boolean,
    val isSimple: Boolean,
    val model: String,
    val lightEmission: Int,
    val topTexture: String,
    val sideTexture: String,
    val bottomTexture: String
)

/**
 * Platform-independent representation of a cached item definition stored inside `data.cbor`.
 */
data class CachedItemEntry(
    val id: String,
    val displayName: String,
    val maxStackSize: Int,
    val itemType: String,
    val blockId: String? = null
)

/**
 * Platform-independent representation of a single ingredient inside a cached crafting recipe.
 */
data class CachedRecipeIngredientEntry(
    val itemId: String,
    val normalizedItemId: String,
    val count: Int
)

/**
 * Platform-independent representation of a cached crafting recipe stored inside `data.cbor`.
 */
data class CachedRecipeEntry(
    val id: String,
    val ingredients: List<CachedRecipeIngredientEntry>,
    val resultItemId: String,
    val resultCount: Int
)

/**
 * Complete CBOR-cacheable snapshot of all JSON-backed registries and content definitions,
 * tagged with the SHA-256 hash of all source JSON files for strict cache invalidation.
 */
data class RegistryCacheSnapshot(
    val version: Int = CURRENT_VERSION,
    val sourceHash: String,
    val blocks: List<CachedBlockEntry>,
    val items: List<CachedItemEntry>,
    val recipes: List<CachedRecipeEntry>,
    val commands: List<CommandDefinitionJson>,
    val worldGenPasses: List<WorldGenPassDefinitionJson>,
    val structoids: List<StructoidDefinitionJson>
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Outcome returned when forcing a full cache clear & CBOR rebuild via `/cbopreload`.
 */
data class CborReloadResult(
    val success: Boolean,
    val parsedJsonCount: Int,
    val cacheFilePath: String?,
    val sourceHash: String?,
    val message: String
)

/**
 * Platform delegate implemented in `:app` for binary CBOR encoding/decoding of [RegistryCacheSnapshot]
 * with atomic temporary-file writes (`data.cbor.tmp` -> `data.cbor`).
 */
interface RegistryCborCacheDelegate {
    fun readCache(cacheFile: File): RegistryCacheSnapshot?
    fun writeCacheAtomic(cacheFile: File, snapshot: RegistryCacheSnapshot): Boolean
}
