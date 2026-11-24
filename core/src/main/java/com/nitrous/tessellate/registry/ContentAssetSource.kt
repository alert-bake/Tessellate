package com.nitrous.tessellate.registry

import java.io.File

/**
 * Platform-agnostic provider of JSON content assets and user mod directories.
 * Android implements this via `Context.assets` and `Context.filesDir`;
 * Desktop / headless JVM falls back to local filesystem directories when null.
 */
interface ContentAssetSource {
    fun listJsonAssets(assetSubDir: String): List<Pair<String, String>>
    fun getUserModContentDir(): File? = null
}

/**
 * Platform delegate allowing [RegistryBootstrap] and [GameRegistries] in `:core`
 * to clear, populate, and freeze command definitions implemented in `:app`.
 */
interface CommandBootstrapDelegate {
    fun clearForBootstrap()
    fun registerDefinition(definition: CommandDefinitionJson)
    fun freeze()
}
