package com.nitrous.tessellate.registry

import com.squareup.moshi.Json

/**
 * JSON schema for face texture mappings inside block definition files (under `content/blocks/`).
 */
data class BlockTexturesJson(
    val all: String? = null,
    val top: String? = null,
    val side: String? = null,
    val bottom: String? = null
) {
    fun resolveTop(): String = top ?: all ?: ""
    fun resolveSide(): String = side ?: all ?: ""
    fun resolveBottom(): String = bottom ?: all ?: ""
}

/**
 * JSON schema for data-driven block definitions (under `content/blocks/`).
 */
data class BlockDefinitionJson(
    val id: String,
    val hardness: Float = 1.0f,
    @Json(name = "is_opaque") val isOpaque: Boolean? = null,
    @Json(name = "is_transparent") val isTransparent: Boolean = false,
    @Json(name = "is_solid") val isSolid: Boolean = true,
    @Json(name = "is_simple") val isSimple: Boolean? = null,
    val model: String = "cube",
    val lightEmission: Int = 0,
    val textures: BlockTexturesJson = BlockTexturesJson()
) {
    fun resolveOpaque(): Boolean = isOpaque ?: (!isTransparent && isSolid)
    fun resolveSimple(): Boolean = isSimple ?: resolveOpaque()
    fun resolveModel(): String = model.trim().lowercase().ifEmpty { "cube" }
    fun resolveLightEmission(): Byte = lightEmission.coerceIn(0, 16).toByte()
}

/**
 * JSON schema for data-driven item definitions (under `content/items/`).
 */
data class ItemDefinitionJson(
    val id: String,
    @Json(name = "display_name") val displayName: String? = null,
    @Json(name = "max_stack_size") val maxStackSize: Int = 64,
    @Json(name = "item_type") val itemType: String? = null,
    @Json(name = "block_id") val blockId: String? = null
)

/**
 * JSON schema for data-driven command definitions (under `content/commands/`).
 */
data class CommandDefinitionJson(
    val name: String,
    val description: String = "",
    val usage: String = "",
    val action: String? = null,
    val response: String? = null
)

/**
 * JSON schema for data-driven mod worldgen passes (under `content/worldgen/passes/`).
 */
data class WorldGenPassDefinitionJson(
    val id: String,
    val priority: Int = 55,
    @Json(name = "priority_offset") val priorityOffset: Int = 0,
    val operation: String = "fill_layer",
    @Json(name = "block_id") val blockId: String = "tessellate:stone",
    @Json(name = "replace_block_id") val replaceBlockId: String? = null,
    @Json(name = "min_y") val minY: Int = 0,
    @Json(name = "max_y") val maxY: Int = 127,
    val chance: Float = 1.0f,
    val frequency: Float = 0.08f,
    val threshold: Float = 0.6f
) {
    fun effectivePriority(): Int = priority + priorityOffset
}

/**
 * JSON schema for Structoid macro-carving definitions (under `content/worldgen/structoids/`).
 */
data class StructoidDefinitionJson(
    val id: String,
    @Json(name = "origin_chunk_x") val originChunkX: Int = 0,
    @Json(name = "origin_chunk_y") val originChunkY: Int = 0,
    @Json(name = "origin_chunk_z") val originChunkZ: Int = 0,
    @Json(name = "size_chunks") val sizeChunks: String = "Ch1x1x1",
    @Json(name = "noise_threshold") val noiseThreshold: Float = 0.35f,
    @Json(name = "noise_frequency") val noiseFrequency: Float = 0.1f,
    @Json(name = "carve_all") val carveAll: Boolean = false
)

/**
 * JSON schema for a single ingredient in a crafting recipe (`assets/content/recipes/`).
 */
data class RecipeIngredientJson(
    val item: String,
    val count: Int = 1
)

/**
 * JSON schema for the crafted output stack in a recipe (`assets/content/recipes/`).
 */
data class RecipeResultJson(
    val item: String,
    val count: Int = 1
)

/**
 * JSON schema for shapeless crafting recipes (`assets/content/recipes/`).
 */
data class RecipeDefinitionJson(
    val id: String,
    val ingredients: List<RecipeIngredientJson> = emptyList(),
    val result: RecipeResultJson
)

