package com.nitrous.tessellate.save

import android.util.Log
import java.io.File

/**
 * Compatibility facade delegating pure format encoding/decoding to [OsmiumCodec]
 * and file quarantine handling to [WorldSaveManager].
 */
object OsmiumProtocol {
    private const val TAG = "OsmiumProtocol"
    const val MAGIC_BYTE_0 = OsmiumCodec.MAGIC_BYTE_0
    const val VERSION_V1 = OsmiumCodec.VERSION_V1
    const val VERSION_V2 = OsmiumCodec.VERSION_V2
    const val VERSION_V3 = OsmiumCodec.VERSION_V3
    const val VERSION_V4 = OsmiumCodec.VERSION_V4

    const val SLICES = OsmiumCodec.SLICES
    const val BLOCKS_PER_SLICE = OsmiumCodec.BLOCKS_PER_SLICE

    fun encodeChunk(blocks: ByteArray): ByteArray = OsmiumCodec.encode(blocks)

    fun decodeChunkFile(file: File): ByteArray? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val bytes = file.readBytes()
            decodeBytes(bytes, file)
        } catch (e: Exception) {
            Log.e(TAG, "Exception reading chunk file ${file.name}", e)
            WorldSaveManager.quarantineCorruptChunkFile(file)
            null
        }
    }

    fun decodeBytes(bytes: ByteArray, file: File? = null): ByteArray? {
        val decoded = OsmiumCodec.decode(bytes)
        if (decoded == null && file != null) {
            WorldSaveManager.quarantineCorruptChunkFile(file)
        }
        return decoded
    }
}
