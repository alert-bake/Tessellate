package com.nitrous.tessellate.registry

import android.util.Log
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.DoublePrecisionFloat
import co.nstant.`in`.cbor.model.HalfPrecisionFloat
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.Number as CborNumber
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.SimpleValueType
import co.nstant.`in`.cbor.model.SinglePrecisionFloat
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Binary RFC 8949 CBOR encoder/decoder for [RegistryCacheSnapshot] backed by `co.nstant.in:cbor`.
 * Uses atomic temp-file writes (`data.cbor.tmp` -> `data.cbor`) so crashes never corrupt the cache.
 */
object CborRegistryCache : RegistryCborCacheDelegate {

    private const val TAG = "CborRegistryCache"
    private const val CACHE_MAGIC = "TESS_CBOR_REG"

    override fun readCache(cacheFile: File): RegistryCacheSnapshot? {
        if (!cacheFile.exists() || !cacheFile.isFile || cacheFile.length() == 0L) {
            return null
        }
        return try {
            val bytes = cacheFile.readBytes()
            decode(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid CBOR cache at ${cacheFile.absolutePath}; falling back to JSON", t)
            null
        }
    }

    override fun writeCacheAtomic(cacheFile: File, snapshot: RegistryCacheSnapshot): Boolean {
        val parent = cacheFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "Failed to create CBOR cache parent directory: ${parent.absolutePath}")
            return false
        }
        val tempFile = File(parent, "${cacheFile.name}.tmp")
        return try {
            val encoded = encode(snapshot)
            FileOutputStream(tempFile).use { fos ->
                fos.write(encoded)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                    // Best-effort fsync on Robolectric/virtual filesystems
                }
            }
            if (!tempFile.renameTo(cacheFile)) {
                if (cacheFile.exists()) {
                    val backupFile = File(parent, "${cacheFile.name}.bak")
                    if (backupFile.exists()) {
                        backupFile.delete()
                    }
                    if (!cacheFile.renameTo(backupFile)) {
                        throw IllegalStateException("Failed to move existing ${cacheFile.name} to backup")
                    }
                    if (tempFile.renameTo(cacheFile)) {
                        backupFile.delete()
                    } else {
                        backupFile.renameTo(cacheFile)
                        throw IllegalStateException("Failed to rename ${tempFile.name} to ${cacheFile.name}")
                    }
                } else {
                    throw IllegalStateException("Failed to rename ${tempFile.name} to ${cacheFile.name}")
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing atomic CBOR cache to ${cacheFile.absolutePath}", t)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            false
        }
    }

    fun encode(snapshot: RegistryCacheSnapshot): ByteArray {
        val root = CborMap().apply {
            putStr("magic", CACHE_MAGIC)
            putInt("version", snapshot.version)
            putStr("sourceHash", snapshot.sourceHash)
            put("blocks", encodeBlocks(snapshot.blocks))
            put("items", encodeItems(snapshot.items))
            put("recipes", encodeRecipes(snapshot.recipes))
            put("commands", encodeCommands(snapshot.commands))
            put("worldGenPasses", encodeWorldGenPasses(snapshot.worldGenPasses))
            put("structoids", encodeStructoids(snapshot.structoids))
        }
        val out = ByteArrayOutputStream(8192)
        CborEncoder(out).encode(root)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): RegistryCacheSnapshot? {
        if (bytes.isEmpty()) return null
        val items = CborDecoder(ByteArrayInputStream(bytes)).decode()
        if (items.size != 1) return null
        val root = items[0] as? CborMap ?: return null

        val magic = root.reqStr("magic")
        if (magic != CACHE_MAGIC) return null
        val version = root.reqInt("version")
        if (version != RegistryCacheSnapshot.CURRENT_VERSION) return null
        val sourceHash = root.reqStr("sourceHash")
        if (sourceHash.isBlank()) return null

        val blocks = decodeBlocks(root.reqArray("blocks"))
        val itemEntries = decodeItems(root.reqArray("items"))
        val recipes = decodeRecipes(root.reqArray("recipes"))
        val commands = decodeCommands(root.reqArray("commands"))
        val passes = decodeWorldGenPasses(root.reqArray("worldGenPasses"))
        val structoids = decodeStructoids(root.reqArray("structoids"))

        return RegistryCacheSnapshot(
            version = version,
            sourceHash = sourceHash,
            blocks = blocks,
            items = itemEntries,
            recipes = recipes,
            commands = commands,
            worldGenPasses = passes,
            structoids = structoids
        )
    }

    private fun encodeBlocks(blocks: List<CachedBlockEntry>): CborArray {
        return CborArray().apply {
            for (b in blocks) {
                add(CborMap().apply {
                    putStr("id", b.id)
                    putFloat("hardness", b.hardness)
                    putBool("isOpaque", b.isOpaque)
                    putBool("isTransparent", b.isTransparent)
                    putBool("isSolid", b.isSolid)
                    putBool("isSimple", b.isSimple)
                    putStr("model", b.model)
                    putInt("lightEmission", b.lightEmission)
                    putStr("topTexture", b.topTexture)
                    putStr("sideTexture", b.sideTexture)
                    putStr("bottomTexture", b.bottomTexture)
                })
            }
        }
    }

    private fun decodeBlocks(arr: CborArray): List<CachedBlockEntry> {
        return arr.dataItems.map { item ->
            val m = item as? CborMap ?: error("Expected CBOR map for block entry")
            CachedBlockEntry(
                id = m.reqStr("id"),
                hardness = m.reqFloat("hardness"),
                isOpaque = m.reqBool("isOpaque"),
                isTransparent = m.reqBool("isTransparent"),
                isSolid = m.reqBool("isSolid"),
                isSimple = m.reqBool("isSimple"),
                model = m.reqStr("model"),
                lightEmission = m.reqInt("lightEmission"),
                topTexture = m.reqStr("topTexture"),
                sideTexture = m.reqStr("sideTexture"),
                bottomTexture = m.reqStr("bottomTexture")
            )
        }
    }

    private fun encodeItems(items: List<CachedItemEntry>): CborArray {
        return CborArray().apply {
            for (item in items) {
                add(CborMap().apply {
                    putStr("id", item.id)
                    putStr("displayName", item.displayName)
                    putInt("maxStackSize", item.maxStackSize)
                    putStr("itemType", item.itemType)
                    putNullableStr("blockId", item.blockId)
                })
            }
        }
    }

    private fun decodeItems(arr: CborArray): List<CachedItemEntry> {
        return arr.dataItems.map { item ->
            val m = item as? CborMap ?: error("Expected CBOR map for item entry")
            CachedItemEntry(
                id = m.reqStr("id"),
                displayName = m.reqStr("displayName"),
                maxStackSize = m.reqInt("maxStackSize"),
                itemType = m.reqStr("itemType"),
                blockId = m.optStr("blockId")
            )
        }
    }

    private fun encodeRecipes(recipes: List<CachedRecipeEntry>): CborArray {
        return CborArray().apply {
            for (r in recipes) {
                add(CborMap().apply {
                    putStr("id", r.id)
                    put("ingredients", CborArray().apply {
                        for (ing in r.ingredients) {
                            add(CborMap().apply {
                                putStr("itemId", ing.itemId)
                                putStr("normalizedItemId", ing.normalizedItemId)
                                putInt("count", ing.count)
                            })
                        }
                    })
                    putStr("resultItemId", r.resultItemId)
                    putInt("resultCount", r.resultCount)
                })
            }
        }
    }

    private fun decodeRecipes(arr: CborArray): List<CachedRecipeEntry> {
        return arr.dataItems.map { item ->
            val m = item as? CborMap ?: error("Expected CBOR map for recipe entry")
            val ingredients = m.reqArray("ingredients").dataItems.map { ingItem ->
                val im = ingItem as? CborMap ?: error("Expected CBOR map for recipe ingredient")
                CachedRecipeIngredientEntry(
                    itemId = im.reqStr("itemId"),
                    normalizedItemId = im.reqStr("normalizedItemId"),
                    count = im.reqInt("count")
                )
            }
            CachedRecipeEntry(
                id = m.reqStr("id"),
                ingredients = ingredients,
                resultItemId = m.reqStr("resultItemId"),
                resultCount = m.reqInt("resultCount")
            )
        }
    }

    private fun encodeCommands(commands: List<CommandDefinitionJson>): CborArray {
        return CborArray().apply {
            for (c in commands) {
                add(CborMap().apply {
                    putStr("name", c.name)
                    putStr("description", c.description)
                    putStr("usage", c.usage)
                    putNullableStr("action", c.action)
                    putNullableStr("response", c.response)
                })
            }
        }
    }

    private fun decodeCommands(arr: CborArray): List<CommandDefinitionJson> {
        return arr.dataItems.map { item ->
            val m = item as? CborMap ?: error("Expected CBOR map for command entry")
            CommandDefinitionJson(
                name = m.reqStr("name"),
                description = m.reqStr("description"),
                usage = m.reqStr("usage"),
                action = m.optStr("action"),
                response = m.optStr("response")
            )
        }
    }

    private fun encodeWorldGenPasses(passes: List<WorldGenPassDefinitionJson>): CborArray {
        return CborArray().apply {
            for (p in passes) {
                add(CborMap().apply {
                    putStr("id", p.id)
                    putInt("priority", p.priority)
                    putInt("priorityOffset", p.priorityOffset)
                    putStr("operation", p.operation)
                    putStr("blockId", p.blockId)
                    putNullableStr("replaceBlockId", p.replaceBlockId)
                    putInt("minY", p.minY)
                    putInt("maxY", p.maxY)
                    putFloat("chance", p.chance)
                    putFloat("frequency", p.frequency)
                    putFloat("threshold", p.threshold)
                })
            }
        }
    }

    private fun decodeWorldGenPasses(arr: CborArray): List<WorldGenPassDefinitionJson> {
        return arr.dataItems.map { item ->
            val m = item as? CborMap ?: error("Expected CBOR map for worldgen pass")
            WorldGenPassDefinitionJson(
                id = m.reqStr("id"),
                priority = m.reqInt("priority"),
                priorityOffset = m.reqInt("priorityOffset"),
                operation = m.reqStr("operation"),
                blockId = m.reqStr("blockId"),
                replaceBlockId = m.optStr("replaceBlockId"),
                minY = m.reqInt("minY"),
                maxY = m.reqInt("maxY"),
                chance = m.reqFloat("chance"),
                frequency = m.reqFloat("frequency"),
                threshold = m.reqFloat("threshold")
            )
        }
    }

    private fun encodeStructoids(structoids: List<StructoidDefinitionJson>): CborArray {
        return CborArray().apply {
            for (s in structoids) {
                add(CborMap().apply {
                    putStr("id", s.id)
                    putInt("originChunkX", s.originChunkX)
                    putInt("originChunkY", s.originChunkY)
                    putInt("originChunkZ", s.originChunkZ)
                    putStr("sizeChunks", s.sizeChunks)
                    putFloat("noiseThreshold", s.noiseThreshold)
                    putFloat("noiseFrequency", s.noiseFrequency)
                    putBool("carveAll", s.carveAll)
                })
            }
        }
    }

    private fun decodeStructoids(arr: CborArray): List<StructoidDefinitionJson> {
        return arr.dataItems.map { item ->
            val m = item as? CborMap ?: error("Expected CBOR map for structoid")
            StructoidDefinitionJson(
                id = m.reqStr("id"),
                originChunkX = m.reqInt("originChunkX"),
                originChunkY = m.reqInt("originChunkY"),
                originChunkZ = m.reqInt("originChunkZ"),
                sizeChunks = m.reqStr("sizeChunks"),
                noiseThreshold = m.reqFloat("noiseThreshold"),
                noiseFrequency = m.reqFloat("noiseFrequency"),
                carveAll = m.reqBool("carveAll")
            )
        }
    }

    private fun CborMap.put(key: String, value: DataItem) {
        put(UnicodeString(key), value)
    }

    private fun CborMap.putStr(key: String, value: String) {
        put(UnicodeString(key), UnicodeString(value))
    }

    private fun CborMap.putNullableStr(key: String, value: String?) {
        put(UnicodeString(key), if (value == null) SimpleValue.NULL else UnicodeString(value))
    }

    private fun CborMap.putBool(key: String, value: Boolean) {
        put(UnicodeString(key), if (value) SimpleValue.TRUE else SimpleValue.FALSE)
    }

    private fun CborMap.putInt(key: String, value: Int) {
        val item: DataItem = if (value >= 0) {
            UnsignedInteger(value.toLong())
        } else {
            NegativeInteger(value.toLong())
        }
        put(UnicodeString(key), item)
    }

    private fun CborMap.putFloat(key: String, value: Float) {
        put(UnicodeString(key), DoublePrecisionFloat(value.toDouble()))
    }

    private fun CborMap.reqStr(key: String): String {
        val item = get(UnicodeString(key)) as? UnicodeString
            ?: error("Missing or invalid string key '$key'")
        return item.string
    }

    private fun CborMap.optStr(key: String): String? {
        val item = get(UnicodeString(key)) ?: return null
        if (item is SimpleValue && item.simpleValueType == SimpleValueType.NULL) {
            return null
        }
        return (item as? UnicodeString)?.string
            ?: error("Invalid optional string key '$key'")
    }

    private fun CborMap.reqBool(key: String): Boolean {
        val item = get(UnicodeString(key)) as? SimpleValue
            ?: error("Missing or invalid bool key '$key'")
        return when (item.simpleValueType) {
            SimpleValueType.TRUE -> true
            SimpleValueType.FALSE -> false
            else -> error("Invalid boolean SimpleValue for '$key'")
        }
    }

    private fun CborMap.reqInt(key: String): Int {
        return when (val item = get(UnicodeString(key))) {
            is UnsignedInteger -> item.value.toInt()
            is NegativeInteger -> item.value.toInt()
            is CborNumber -> item.value.toInt()
            else -> error("Missing or invalid integer key '$key'")
        }
    }

    private fun CborMap.reqFloat(key: String): Float {
        return when (val item = get(UnicodeString(key))) {
            is SinglePrecisionFloat -> item.value.toFloat()
            is DoublePrecisionFloat -> item.value.toFloat()
            is HalfPrecisionFloat -> item.value.toFloat()
            is CborNumber -> item.value.toFloat()
            else -> error("Missing or invalid float key '$key'")
        }
    }

    private fun CborMap.reqArray(key: String): CborArray {
        return get(UnicodeString(key)) as? CborArray
            ?: error("Missing or invalid array key '$key'")
    }
}
