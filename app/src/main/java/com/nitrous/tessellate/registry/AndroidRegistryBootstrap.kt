package com.nitrous.tessellate.registry

import android.content.Context
import com.nitrous.tessellate.command.CommandRegistry
import java.io.File

/**
 * Android [ContentAssetSource] backed by `Context.assets` and `Context.filesDir/content`.
 */
class AndroidContentAssetSource(context: Context) : ContentAssetSource {
    private val appContext = context.applicationContext ?: context

    override fun listJsonAssets(assetSubDir: String): List<Pair<String, String>> {
        val assetManager = appContext.assets
        val names = assetManager.list(assetSubDir)
            ?.filter { it.endsWith(".json", ignoreCase = true) }
            ?.sorted()
            .orEmpty()
        if (names.isEmpty()) return emptyList()
        return names.map { name ->
            val path = "$assetSubDir/$name"
            val json = assetManager.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            path to json
        }
    }

    override fun getUserModContentDir(): File {
        return File(appContext.filesDir, "content")
    }
}

/**
 * Android entrypoint bridging `Context` and `:app`'s [CommandRegistry] into `:core`'s [RegistryBootstrap].
 */
object AndroidRegistryBootstrap {

    fun ensureInitialized(context: Context? = null) {
        CommandRegistry.ensureBootstrapHook()
        val source = context?.let { AndroidContentAssetSource(it) }
        RegistryBootstrap.ensureInitialized(assetSource = source)
    }

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
        CommandRegistry.ensureBootstrapHook()
        val source = context?.let { AndroidContentAssetSource(it) }
        RegistryBootstrap.initialize(
            assetSource = source,
            modContentDirs = modContentDirs,
            extraBlockJsons = extraBlockJsons,
            extraItemJsons = extraItemJsons,
            extraRecipeJsons = extraRecipeJsons,
            extraCommandJsons = extraCommandJsons,
            extraWorldGenPassJsons = extraWorldGenPassJsons,
            extraStructoidJsons = extraStructoidJsons,
            forceReinitialize = forceReinitialize,
            preFreezeHook = preFreezeHook
        )
    }
}
