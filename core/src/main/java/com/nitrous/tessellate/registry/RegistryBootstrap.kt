package com.nitrous.tessellate.registry

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

    @Volatile
    private var defaultAssetSource: ContentAssetSource? = null

    @Volatile
    private var commandDelegate: CommandBootstrapDelegate? = null

    fun setDefaultAssetSource(assetSource: ContentAssetSource?) {
        defaultAssetSource = assetSource
    }

    fun setCommandDelegate(delegate: CommandBootstrapDelegate?) {
        commandDelegate = delegate
    }

    fun freezeCommandDelegate() {
        commandDelegate?.freeze()
    }

    /**
     * Ensures the bootstrap pipeline has run once cleanly.
     * Safe for standalone unit tests or code accessing registries before `TessellateEngine`.
     */
    fun ensureInitialized(assetSource: ContentAssetSource? = defaultAssetSource) {
        if (state != State.UNINITIALIZED) return
        synchronized(bootstrapLock) {
            if (state == State.UNINITIALIZED) {
                initializeInternal(
                    assetSource = assetSource ?: defaultAssetSource,
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
        assetSource: ContentAssetSource? = defaultAssetSource,
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
            val effectiveSource = assetSource ?: defaultAssetSource
            if (assetSource != null) {
                defaultAssetSource = assetSource
            }
            val contextModSignature = computeModSignature(effectiveSource)
            val modsChanged = effectiveSource != null && contextModSignature != lastLoadedModSignature
            if (state == State.INITIALIZED && !forceReinitialize && !modsChanged &&
                modContentDirs.isEmpty() && extraBlockJsons.isEmpty() &&
                extraItemJsons.isEmpty() && extraRecipeJsons.isEmpty() &&
                extraCommandJsons.isEmpty() && extraWorldGenPassJsons.isEmpty() &&
                extraStructoidJsons.isEmpty() && preFreezeHook == null
            ) {
                return
            }
            initializeInternal(
                assetSource = effectiveSource,
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

    private fun computeModSignature(assetSource: ContentAssetSource?): String {
        val userModDir = assetSource?.getUserModContentDir() ?: return ""
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
        assetSource: ContentAssetSource?,
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
            commandDelegate?.clearForBootstrap()

            // Collect mod directories (user mod content dir if present + caller-supplied dirs)
            val effectiveModDirs = buildList {
                val userModDir = assetSource?.getUserModContentDir()
                if (userModDir != null && userModDir.isDirectory) {
                    add(userModDir)
                }
                addAll(modContentDirs)
            }

            // 2. Load JSON content definitions
            val blockDefs = loadBlockDefinitions(assetSource, effectiveModDirs, extraBlockJsons)
            val itemDefs = loadItemDefinitions(assetSource, effectiveModDirs, extraItemJsons)
            val recipeDefs = loadRecipeDefinitions(assetSource, effectiveModDirs, extraRecipeJsons)
            val commandDefs = loadCommandDefinitions(assetSource, effectiveModDirs, extraCommandJsons)
            val passDefs = loadWorldGenPassDefinitions(assetSource, effectiveModDirs, extraWorldGenPassJsons)
            val structoidDefs = loadStructoidDefinitions(assetSource, effectiveModDirs, extraStructoidJsons)

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
            val cmdDelegate = commandDelegate
            if (cmdDelegate != null) {
                for (def in commandDefs) {
                    cmdDelegate.registerDefinition(def)
                }
            }

            // Allow optional programmatic mod injection prior to freezing
            preFreezeHook?.invoke()

            // 4. Freeze all registries
            BlockRegistry.freeze()
            ItemRegistry.freeze()
            RecipeRegistry.freeze()
            WorldGenRegistry.freeze()
            cmdDelegate?.freeze()
            lastLoadedModSignature = computeModSignature(assetSource)
            state = State.INITIALIZED
        } catch (t: Throwable) {
            state = State.UNINITIALIZED
            throw t
        }
    }

    private fun loadBlockDefinitions(
        assetSource: ContentAssetSource?,
        modContentDirs: List<File>,
        extraBlockJsons: List<String>
    ): List<BlockDefinitionJson> {
        val sources = collectJsonSources(assetSource, ASSETS_BLOCKS_DIR, modContentDirs, "blocks", extraBlockJsons)
        if (sources.isEmpty()) {
            throw ContentLoadException("No block JSON definitions found under $ASSETS_BLOCKS_DIR")
        }
        return sources.map { (name, json) ->
            parseJson(name, json, blockAdapter, "block definition")
        }
    }

    private fun loadItemDefinitions(
        assetSource: ContentAssetSource?,
        modContentDirs: List<File>,
        extraItemJsons: List<String>
    ): List<ItemDefinitionJson> {
        val sources = collectJsonSources(assetSource, ASSETS_ITEMS_DIR, modContentDirs, "items", extraItemJsons)
        if (sources.isEmpty()) {
            throw ContentLoadException("No item JSON definitions found under $ASSETS_ITEMS_DIR")
        }
        return sources.map { (name, json) ->
            parseJson(name, json, itemAdapter, "item definition")
        }
    }

    private fun loadRecipeDefinitions(
        assetSource: ContentAssetSource?,
        modContentDirs: List<File>,
        extraRecipeJsons: List<String>
    ): List<RecipeDefinitionJson> {
        val sources = collectJsonSources(assetSource, ASSETS_RECIPES_DIR, modContentDirs, "recipes", extraRecipeJsons)
        return sources.map { (name, json) ->
            parseJson(name, json, recipeAdapter, "recipe definition")
        }
    }

    private fun loadCommandDefinitions(
        assetSource: ContentAssetSource?,
        modContentDirs: List<File>,
        extraCommandJsons: List<String>
    ): List<CommandDefinitionJson> {
        val sources = collectJsonSources(assetSource, ASSETS_COMMANDS_DIR, modContentDirs, "commands", extraCommandJsons)
        return sources.map { (name, json) ->
            parseJson(name, json, commandAdapter, "command definition")
        }
    }

    private fun loadWorldGenPassDefinitions(
        assetSource: ContentAssetSource?,
        modContentDirs: List<File>,
        extraWorldGenPassJsons: List<String>
    ): List<WorldGenPassDefinitionJson> {
        val sources = collectJsonSources(
            assetSource = assetSource,
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
        assetSource: ContentAssetSource?,
        modContentDirs: List<File>,
        extraStructoidJsons: List<String>
    ): List<StructoidDefinitionJson> {
        val sources = collectJsonSources(
            assetSource = assetSource,
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
            isSimple = isSimple,
            model = def.resolveModel(),
            lightEmission = def.resolveLightEmission()
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
        assetSource: ContentAssetSource?,
        assetSubDir: String,
        modContentDirs: List<File>,
        modSubFolder: String,
        extraJsons: List<String>
    ): List<Pair<String, String>> {
        val collected = mutableListOf<Pair<String, String>>()

        // 1. Base assets (platform ContentAssetSource or local JVM fallback)
        var loadedFromAssets = false
        if (assetSource != null) {
            val entries = assetSource.listJsonAssets(assetSubDir)
            if (entries.isNotEmpty()) {
                loadedFromAssets = true
                collected.addAll(entries)
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
