package com.nitrous.tessellate.registry

import android.content.Context
import com.nitrous.tessellate.command.CommandRegistry
import com.nitrous.tessellate.command.JsonCommand
import com.nitrous.tessellate.item.BlockItem
import com.nitrous.tessellate.item.CraftingRecipe
import com.nitrous.tessellate.item.Item
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemType
import com.nitrous.tessellate.item.RecipeIngredient
import com.nitrous.tessellate.item.RecipeRegistry
import com.nitrous.tessellate.world.BlockRegistry
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.worldgen.DataDrivenWorldGenPass
import com.nitrous.tessellate.worldgen.StructoidDefinition
import com.nitrous.tessellate.worldgen.WorldGenRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.Locale

/**
 * Executes the deterministic 5-step data-driven startup pipeline for Tessellate:
 * 1. Initialize registries
 * 2. Load JSON content definitions from `assets/content/` (blocks, items, commands, worldgen passes, structoids)
 * 3. Resolve relationships & register vanilla + mod worldgen passes
 * 4. Freeze registries
 * 5. Ready for World initialization
 */
object RegistryBootstrap {

    private enum class State {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED
    }

    private const val ASSETS_BLOCKS_DIR = "content/blocks"
    private const val ASSETS_ITEMS_DIR = "content/items"
    private const val ASSETS_RECIPES_DIR = "content/recipes"
    private const val ASSETS_COMMANDS_DIR = "content/commands"
    private const val ASSETS_WORLDGEN_PASSES_DIR = "content/worldgen/passes"
    private const val ASSETS_STRUCTOIDS_DIR = "content/worldgen/structoids"

    private val bootstrapLock = Any()

    @Volatile
    private var state: State = State.UNINITIALIZED

    @Volatile
    private var lastLoadedModSignature: String = ""

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val blockAdapter = moshi.adapter(BlockDefinitionJson::class.java)
    private val itemAdapter = moshi.adapter(ItemDefinitionJson::class.java)
    private val recipeAdapter = moshi.adapter(RecipeDefinitionJson::class.java)
    private val commandAdapter = moshi.adapter(CommandDefinitionJson::class.java)
    private val worldGenPassAdapter = moshi.adapter(WorldGenPassDefinitionJson::class.java)
    private val structoidAdapter = moshi.adapter(StructoidDefinitionJson::class.java)

    val isInitialized: Boolean
        get() = state == State.INITIALIZED

    /**
     * Ensures the bootstrap pipeline has run once cleanly.
     * Safe for standalone unit tests or code accessing registries before `TessellateEngine`.
     */
    fun ensureInitialized(context: Context? = null) {
        if (state != State.UNINITIALIZED) return
        synchronized(bootstrapLock) {
            if (state == State.UNINITIALIZED) {
                initializeInternal(
                    context = context,
                    modContentDirs = emptyList(),
                    extraBlockJsons = emptyList(),
                    extraItemJsons = emptyList(),
                    extraRecipeJsons = emptyList(),
                    extraCommandJsons = emptyList(),
                    extraWorldGenPassJsons = emptyList(),
                    extraStructoidJsons = emptyList(),
                    preFreezeHook = null
                )
            }
        }
    }

    /**
     * Executes the complete startup registration pipeline:
     * 1. Initialize registries
     * 2. Load JSON content definitions (base assets + optional mod directories / extra JSONs)
     * 3. Resolve relationships & wire vanilla + mod recipes + worldgen passes + commands
     * 4. Run optional pre-freeze hook & freeze registries
     */
    fun initialize(
        context: Context? = null,
        modContentDirs: List<File> = emptyList(),
        extraBlockJsons: List<String> = emptyList(),
        extraItemJsons: List<String> = emptyList(),
        extraRecipeJsons: List<String> = emptyList(),
        extraCommandJsons: List<String> = emptyList(),
        extraWorldGenPassJsons: List<String> = emptyList(),
        extraStructoidJsons: List<String> = emptyList(),
        forceReinitialize: Boolean = false,
        preFreezeHook: (() -> Unit)? = null
    ) {
        synchronized(bootstrapLock) {
            val contextModSignature = computeContextModSignature(context)
            val modsChanged = context != null && contextModSignature != lastLoadedModSignature
            if (state == State.INITIALIZED && !forceReinitialize && !modsChanged &&
                modContentDirs.isEmpty() && extraBlockJsons.isEmpty() &&
                extraItemJsons.isEmpty() && extraRecipeJsons.isEmpty() &&
                extraCommandJsons.isEmpty() && extraWorldGenPassJsons.isEmpty() &&
                extraStructoidJsons.isEmpty() && preFreezeHook == null
            ) {
                return
            }
            initializeInternal(
                context = context,
                modContentDirs = modContentDirs,
                extraBlockJsons = extraBlockJsons,
                extraItemJsons = extraItemJsons,
                extraRecipeJsons = extraRecipeJsons,
                extraCommandJsons = extraCommandJsons,
                extraWorldGenPassJsons = extraWorldGenPassJsons,
                extraStructoidJsons = extraStructoidJsons,
                preFreezeHook = preFreezeHook
            )
        }
    }

    private fun computeContextModSignature(context: Context?): String {
        if (context == null) return ""
        val userModDir = File(context.filesDir, "content")
        if (!userModDir.isDirectory) return ""
        val subDirs = listOf(
            "B" to File(userModDir, "blocks"),
            "I" to File(userModDir, "items"),
            "R" to File(userModDir, "recipes"),
            "C" to File(userModDir, "commands"),
            "P" to File(userModDir, "worldgen/passes"),
            "S" to File(userModDir, "worldgen/structoids")
        )
        return buildString {
            for ((prefix, dir) in subDirs) {
                val files = dir.listFiles { f ->
                    f.isFile && f.name.endsWith(".json", ignoreCase = true)
                }?.sortedBy { it.name }.orEmpty()
                for (f in files) {
                    append(prefix).append(':').append(f.name).append(':').append(f.length()).append(';')
                }
            }
        }
    }

    private fun initializeInternal(
        context: Context?,
        modContentDirs: List<File>,
        extraBlockJsons: List<String>,
        extraItemJsons: List<String>,
        extraRecipeJsons: List<String>,
        extraCommandJsons: List<String>,
        extraWorldGenPassJsons: List<String>,
        extraStructoidJsons: List<String>,
        preFreezeHook: (() -> Unit)?
    ) {
        state = State.INITIALIZING
        try {
            // 1. Initialize registries
            BlockRegistry.clearForBootstrap()
            ItemRegistry.clearForBootstrap()
            RecipeRegistry.clearForBootstrap()
            WorldGenRegistry.clearForBootstrap()
            CommandRegistry.clearForBootstrap()

            // Collect mod directories (app filesDir/content if present + caller-supplied dirs)
            val effectiveModDirs = buildList {
                if (context != null) {
                    val userModDir = File(context.filesDir, "content")
                    if (userModDir.isDirectory) {
                        add(userModDir)
                    }
                }
                addAll(modContentDirs)
            }

            // 2. Load JSON content definitions
            val blockDefs = loadBlockDefinitions(context, effectiveModDirs, extraBlockJsons)
            val itemDefs = loadItemDefinitions(context, effectiveModDirs, extraItemJsons)
            val recipeDefs = loadRecipeDefinitions(context, effectiveModDirs, extraRecipeJsons)
            val commandDefs = loadCommandDefinitions(context, effectiveModDirs, extraCommandJsons)
            val passDefs = loadWorldGenPassDefinitions(context, effectiveModDirs, extraWorldGenPassJsons)
            val structoidDefs = loadStructoidDefinitions(context, effectiveModDirs, extraStructoidJsons)

            // Sort block definitions so air loads first (guaranteeing numeric ID 0 for empty chunks)
            val orderedBlocks = orderBlockDefinitions(blockDefs)
            for (def in orderedBlocks) {
                registerBlockDefinition(def)
            }

            // 3. Resolve relationships (link items -> registered BlockType, recipes -> registered Items)
            for (def in itemDefs) {
                registerItemDefinition(def)
            }
            for (def in recipeDefs) {
                registerRecipeDefinition(def)
            }

            // Register vanilla + mod worldgen passes, structoids, and commands before freezing
            WorldGenRegistry.registerVanillaDefaults()
            for (def in passDefs) {
                WorldGenRegistry.register(DataDrivenWorldGenPass(def))
            }
            for (def in structoidDefs) {
                WorldGenRegistry.registerStructoid(
                    StructoidDefinition(
                        id = def.id,
                        originChunkX = def.originChunkX,
                        originChunkY = def.originChunkY,
                        originChunkZ = def.originChunkZ,
                        sizeChunks = def.sizeChunks,
                        noiseThreshold = def.noiseThreshold,
                        noiseFrequency = def.noiseFrequency,
                        carveAll = def.carveAll
                    )
                )
            }
            for (def in commandDefs) {
                CommandRegistry.register(JsonCommand(def))
            }

            // Allow optional programmatic mod injection prior to freezing
            preFreezeHook?.invoke()

            // 4. Freeze all registries
            BlockRegistry.freeze()
            ItemRegistry.freeze()
            RecipeRegistry.freeze()
            WorldGenRegistry.freeze()
            CommandRegistry.freeze()
            lastLoadedModSignature = computeContextModSignature(context)
            state = State.INITIALIZED
        } catch (t: Throwable) {
            state = State.UNINITIALIZED
            throw t
        }
    }

    private fun loadBlockDefinitions(
        context: Context?,
        modContentDirs: List<File>,
        extraBlockJsons: List<String>
    ): List<BlockDefinitionJson> {
        val sources = collectJsonSources(context, ASSETS_BLOCKS_DIR, modContentDirs, "blocks", extraBlockJsons)
        if (sources.isEmpty()) {
            throw ContentLoadException("No block JSON definitions found under $ASSETS_BLOCKS_DIR")
        }
        return sources.map { (name, json) ->
            parseJson(name, json, blockAdapter, "block definition")
        }
    }

    private fun loadItemDefinitions(
        context: Context?,
        modContentDirs: List<File>,
        extraItemJsons: List<String>
    ): List<ItemDefinitionJson> {
        val sources = collectJsonSources(context, ASSETS_ITEMS_DIR, modContentDirs, "items", extraItemJsons)
        if (sources.isEmpty()) {
            throw ContentLoadException("No item JSON definitions found under $ASSETS_ITEMS_DIR")
        }
        return sources.map { (name, json) ->
            parseJson(name, json, itemAdapter, "item definition")
        }
    }

    private fun loadRecipeDefinitions(
        context: Context?,
        modContentDirs: List<File>,
        extraRecipeJsons: List<String>
    ): List<RecipeDefinitionJson> {
        val sources = collectJsonSources(context, ASSETS_RECIPES_DIR, modContentDirs, "recipes", extraRecipeJsons)
        return sources.map { (name, json) ->
            parseJson(name, json, recipeAdapter, "recipe definition")
        }
    }

    private fun loadCommandDefinitions(
        context: Context?,
        modContentDirs: List<File>,
        extraCommandJsons: List<String>
    ): List<CommandDefinitionJson> {
        val sources = collectJsonSources(context, ASSETS_COMMANDS_DIR, modContentDirs, "commands", extraCommandJsons)
        return sources.map { (name, json) ->
            parseJson(name, json, commandAdapter, "command definition")
        }
    }

    private fun loadWorldGenPassDefinitions(
        context: Context?,
        modContentDirs: List<File>,
        extraWorldGenPassJsons: List<String>
    ): List<WorldGenPassDefinitionJson> {
        val sources = collectJsonSources(
            context = context,
            assetSubDir = ASSETS_WORLDGEN_PASSES_DIR,
            modContentDirs = modContentDirs,
            modSubFolder = "worldgen/passes",
            extraJsons = extraWorldGenPassJsons
        )
        return sources.map { (name, json) ->
            parseJson(name, json, worldGenPassAdapter, "worldgen pass definition")
        }
    }

    private fun loadStructoidDefinitions(
        context: Context?,
        modContentDirs: List<File>,
        extraStructoidJsons: List<String>
    ): List<StructoidDefinitionJson> {
        val sources = collectJsonSources(
            context = context,
            assetSubDir = ASSETS_STRUCTOIDS_DIR,
            modContentDirs = modContentDirs,
            modSubFolder = "worldgen/structoids",
            extraJsons = extraStructoidJsons
        )
        return sources.map { (name, json) ->
            parseJson(name, json, structoidAdapter, "structoid definition")
        }
    }

    private fun <T> parseJson(
        name: String,
        json: String,
        adapter: com.squareup.moshi.JsonAdapter<T>,
        label: String
    ): T {
        return try {
            adapter.fromJson(json)
                ?: throw ContentLoadException("$label JSON '$name' parsed to null")
        } catch (e: ContentLoadException) {
            throw e
        } catch (e: Exception) {
            throw ContentLoadException("Failed to parse $label JSON '$name'", e)
        }
    }

    private fun orderBlockDefinitions(defs: List<BlockDefinitionJson>): List<BlockDefinitionJson> {
        val airList = mutableListOf<BlockDefinitionJson>()
        val otherList = mutableListOf<BlockDefinitionJson>()
        for (def in defs) {
            val id = Identifier.parse(def.id)
            if (id.normalizedString == BlockType.AIR_IDENTIFIER_NORMALIZED) {
                airList.add(def)
            } else {
                otherList.add(def)
            }
        }
        return airList + otherList
    }

    private fun registerBlockDefinition(def: BlockDefinitionJson) {
        val identifier = Identifier.parse(def.id)
        val isOpaque = def.resolveOpaque()
        val isSimple = def.resolveSimple()
        BlockRegistry.registerDynamic(
            identifier = identifier,
            isOpaque = isOpaque,
            topTexture = def.textures.resolveTop(),
            sideTexture = def.textures.resolveSide(),
            bottomTexture = def.textures.resolveBottom(),
            hardness = def.hardness,
            isTransparent = def.isTransparent,
            isSolid = def.isSolid,
            isSimple = isSimple
        )
    }

    private fun registerItemDefinition(def: ItemDefinitionJson) {
        val identifier = Identifier.parse(def.id)
        val displayName = def.displayName?.takeIf { it.isNotBlank() }
            ?: identifier.path.split('_').joinToString(" ") { part ->
                part.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
        val maxStack = def.maxStackSize.coerceAtLeast(1)

        val resolvedBlock: BlockType? = def.blockId?.takeIf { it.isNotBlank() }?.let { rawBlockId ->
            BlockRegistry.get(rawBlockId)
                ?: throw ContentLoadException(
                    "Item '${def.id}' references unknown block '$rawBlockId'"
                )
        }

        val resolvedType: ItemType = parseItemType(def.itemType, resolvedBlock != null)

        val item: Item = if (resolvedBlock != null && resolvedType == ItemType.BLOCK) {
            BlockItem(
                identifier = identifier,
                displayName = displayName,
                block = resolvedBlock,
                maxStackSize = maxStack
            )
        } else {
            Item(
                identifier = identifier,
                displayName = displayName,
                maxStackSize = maxStack,
                associatedBlock = resolvedBlock,
                itemType = resolvedType
            )
        }

        ItemRegistry.register(item)
    }

    private fun registerRecipeDefinition(def: RecipeDefinitionJson) {
        val identifier = Identifier.parse(def.id)
        if (def.ingredients.isEmpty()) {
            throw ContentLoadException("Recipe '${def.id}' must declare at least one ingredient")
        }
        val resolvedIngredients = def.ingredients.map { ing ->
            val registered = ItemRegistry.internalRegistry.get(ing.item)
                ?: throw ContentLoadException("Recipe '${def.id}' references unknown ingredient item '${ing.item}'")
            val count = ing.count.coerceAtLeast(1)
            RecipeIngredient(
                itemId = registered.identifier.toCanonicalString(),
                normalizedItemId = registered.identifier.normalizedString,
                count = count
            )
        }
        val resultItem = ItemRegistry.internalRegistry.get(def.result.item)
            ?: throw ContentLoadException("Recipe '${def.id}' references unknown result item '${def.result.item}'")
        val resultCount = def.result.count.coerceAtLeast(1)

        RecipeRegistry.register(
            CraftingRecipe(
                identifier = identifier,
                ingredients = resolvedIngredients,
                resultItemId = resultItem.identifier.toCanonicalString(),
                resultCount = resultCount
            )
        )
    }

    private fun parseItemType(raw: String?, hasBlock: Boolean): ItemType {
        if (raw.isNullOrBlank()) {
            return if (hasBlock) ItemType.BLOCK else ItemType.STANDARD
        }
        return when (raw.trim().uppercase(Locale.ROOT)) {
            "BLOCK" -> ItemType.BLOCK
            "TOOL" -> ItemType.TOOL
            "MATERIAL" -> ItemType.MATERIAL
            "DEBUG" -> ItemType.DEBUG
            "STANDARD" -> ItemType.STANDARD
            else -> throw ContentLoadException("Unknown item_type '$raw'")
        }
    }

    private fun collectJsonSources(
        context: Context?,
        assetSubDir: String,
        modContentDirs: List<File>,
        modSubFolder: String,
        extraJsons: List<String>
    ): List<Pair<String, String>> {
        val collected = mutableListOf<Pair<String, String>>()

        // 1. Base assets (Android AssetManager or local JVM fallback)
        var loadedFromAssets = false
        if (context != null) {
            val assetManager = context.assets
            val names = assetManager.list(assetSubDir)?.filter { it.endsWith(".json", ignoreCase = true) }?.sorted().orEmpty()
            if (names.isNotEmpty()) {
                loadedFromAssets = true
                for (name in names) {
                    val path = "$assetSubDir/$name"
                    val json = assetManager.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    collected.add(path to json)
                }
            }
        }

        if (!loadedFromAssets) {
            val jvmDir = findLocalAssetsSubDir(assetSubDir)
            if (jvmDir != null && jvmDir.isDirectory) {
                val files = jvmDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".json", ignoreCase = true)
                }?.sortedBy { it.name }.orEmpty()
                for (file in files) {
                    collected.add(file.path to file.readText(Charsets.UTF_8))
                }
            }
        }

        // 2. Mod filesystem directories (loaded in order after base assets)
        for (modDir in modContentDirs) {
            val targetDir = File(modDir, modSubFolder)
            if (targetDir.isDirectory) {
                val modFiles = targetDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".json", ignoreCase = true)
                }?.sortedBy { it.name }.orEmpty()
                for (file in modFiles) {
                    collected.add(file.path to file.readText(Charsets.UTF_8))
                }
            }
        }

        // 3. Direct in-memory mod JSON strings (loaded after files)
        val safeLabel = modSubFolder.replace('/', '_')
        extraJsons.forEachIndexed { index, rawJson ->
            collected.add("inline_mod_${safeLabel}_$index.json" to rawJson)
        }

        return collected
    }

    private fun findLocalAssetsSubDir(assetSubDir: String): File? {
        val candidates = listOf(
            File("app/src/main/assets/$assetSubDir"),
            File("src/main/assets/$assetSubDir"),
            File("../app/src/main/assets/$assetSubDir")
        )
        return candidates.firstOrNull { it.isDirectory }
    }
}
