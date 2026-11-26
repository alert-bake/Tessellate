package com.nitrous.tessellate.save

import com.nitrous.tessellate.world.Chunk
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Exception
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FastDecompressor
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Supported binary chunk storage formats for the Osmium Protocol.
 */
enum class OsmiumFormat {
    /** Raw 4096-byte legacy chunk payload with no header. */
    LEGACY_RAW,

    /** Raw 4096-byte chunk payload prefixed with `[0x54, 0x01]`. */
    V1_RAW,

    /** PlaneSlice RLE-compressed chunk payload prefixed with `[0x54, 0x02]` (payload < 64 bytes). */
    V2_OSMIUM,

    /** LZ4-compacted PlaneSlice RLE payload prefixed with `[0x54, 0x03]` (RLE payload >= 64 bytes). */
    V3_LZ4,

    /** 34-byte SHA-256 pointer file prefixed with `[0x54, 0x04]` + 32-byte raw digest. */
    V4_POINTER
}

/**
 * Result of encoding a 4096-byte chunk array into an Osmium binary representation.
 */
data class OsmiumEncodedChunk(
    val bytes: ByteArray,
    val format: OsmiumFormat
)

/**
 * Result of decoding an Osmium binary payload back into raw 4096-byte chunk blocks.
 */
data class OsmiumDecodedChunk(
    val blocks: ByteArray,
    val format: OsmiumFormat
)

/**
 * Pure in-memory codec translating raw 4096-byte chunk arrays <-> [OsmiumFormat] binary payloads.
 * Has zero knowledge of files, paths, or disk I/O.
 */
object OsmiumCodec {
    const val MAGIC_BYTE_0 = 0x54.toByte() // 'T'
    const val VERSION_V1 = 0x01.toByte()
    const val VERSION_V2 = 0x02.toByte()
    const val VERSION_V3 = 0x03.toByte()
    const val VERSION_V4 = 0x04.toByte()

    const val SLICES = 16
    const val BLOCKS_PER_SLICE = 256 // 16 x 16
    const val LZ4_THRESHOLD_BYTES = 64
    const val SHA256_BYTE_LENGTH = 32
    const val POINTER_FILE_SIZE = 2 + SHA256_BYTE_LENGTH // 34 bytes
    private const val MAX_RLE_PAYLOAD_BYTES = SLICES * (1 + BLOCKS_PER_SLICE * 2) // 8208 bytes

    // Cached LZ4 factory & codecs (static singletons for zero per-chunk initialization overhead)
    @JvmStatic
    private val lz4Factory: LZ4Factory = LZ4Factory.fastestInstance()

    @JvmStatic
    private val lz4Compressor: LZ4Compressor = lz4Factory.fastCompressor()

    @JvmStatic
    private val lz4Decompressor: LZ4FastDecompressor = lz4Factory.fastDecompressor()

    private val sha256ThreadLocal = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    /**
     * Computes the 32-byte SHA-256 digest of [rawBlocks].
     */
    fun sha256Bytes(rawBlocks: ByteArray): ByteArray {
        val md = sha256ThreadLocal.get()!!
        md.reset()
        return md.digest(rawBlocks)
    }

    /**
     * Converts a 32-byte SHA-256 digest into a 64-character lowercase hex string.
     */
    fun sha256Hex(hashBytes: ByteArray): String {
        val hexChars = CharArray(hashBytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in hashBytes.indices) {
            val v = hashBytes[i].toInt() and 0xFF
            hexChars[i * 2] = digits[v ushr 4]
            hexChars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Detects the [OsmiumFormat] of an in-memory chunk byte payload, or `null` if invalid/corrupt.
     */
    fun detectFormat(bytes: ByteArray): OsmiumFormat? {
        if (bytes.size == Chunk.VOLUME) {
            return OsmiumFormat.LEGACY_RAW
        }
        if (bytes.size < 2 || bytes[0] != MAGIC_BYTE_0) {
            return null
        }
        return when (bytes[1]) {
            VERSION_V1 -> if (bytes.size == 2 + Chunk.VOLUME) OsmiumFormat.V1_RAW else null
            VERSION_V2 -> OsmiumFormat.V2_OSMIUM
            VERSION_V3 -> if (bytes.size > 4) OsmiumFormat.V3_LZ4 else null
            VERSION_V4 -> if (bytes.size == POINTER_FILE_SIZE) OsmiumFormat.V4_POINTER else null
            else -> null
        }
    }

    /**
     * Encodes a 4096-byte chunk block array into Osmium V2 (if RLE < 64 bytes),
     * V3 LZ4 (if RLE >= 64 bytes), or falls back to V1 Raw if compressed payload > 4096 bytes.
     */
    fun encode(blocks: ByteArray): ByteArray = encodeWithFormat(blocks).bytes

    /**
     * Encodes a 4096-byte chunk block array and returns both the encoded bytes and selected [OsmiumFormat].
     */
    fun encodeWithFormat(blocks: ByteArray): OsmiumEncodedChunk {
        require(blocks.size == Chunk.VOLUME) {
            "Chunk blocks must be exactly 4096 bytes, got ${blocks.size}"
        }

        val rlePayload = encodeRlePayload(blocks)
        if (rlePayload.size < LZ4_THRESHOLD_BYTES) {
            val v2Bytes = ByteArray(2 + rlePayload.size)
            v2Bytes[0] = MAGIC_BYTE_0
            v2Bytes[1] = VERSION_V2
            System.arraycopy(rlePayload, 0, v2Bytes, 2, rlePayload.size)
            return OsmiumEncodedChunk(v2Bytes, OsmiumFormat.V2_OSMIUM)
        }

        val compressed = lz4Compressor.compress(rlePayload)
        // V3 payload: [2-byte uncompressed RLE length] + [LZ4 compressed bytes]
        val v3PayloadSize = 2 + compressed.size
        if (v3PayloadSize > Chunk.VOLUME) {
            val v1Bytes = ByteArray(2 + Chunk.VOLUME)
            v1Bytes[0] = MAGIC_BYTE_0
            v1Bytes[1] = VERSION_V1
            System.arraycopy(blocks, 0, v1Bytes, 2, Chunk.VOLUME)
            return OsmiumEncodedChunk(v1Bytes, OsmiumFormat.V1_RAW)
        }

        val v3Bytes = ByteArray(2 + v3PayloadSize)
        v3Bytes[0] = MAGIC_BYTE_0
        v3Bytes[1] = VERSION_V3
        v3Bytes[2] = ((rlePayload.size ushr 8) and 0xFF).toByte()
        v3Bytes[3] = (rlePayload.size and 0xFF).toByte()
        System.arraycopy(compressed, 0, v3Bytes, 4, compressed.size)
        return OsmiumEncodedChunk(v3Bytes, OsmiumFormat.V3_LZ4)
    }

    /**
     * Encodes a 32-byte SHA-256 hash into a 34-byte V4 pointer payload (`[0x54, 0x04] + hash`).
     */
    fun encodePointer(hashBytes: ByteArray): ByteArray {
        require(hashBytes.size == SHA256_BYTE_LENGTH) {
            "SHA-256 pointer hash must be 32 bytes, got ${hashBytes.size}"
        }
        val pointer = ByteArray(POINTER_FILE_SIZE)
        pointer[0] = MAGIC_BYTE_0
        pointer[1] = VERSION_V4
        System.arraycopy(hashBytes, 0, pointer, 2, SHA256_BYTE_LENGTH)
        return pointer
    }

    /**
     * Extracts the 32-byte SHA-256 hash from a V4 pointer payload, or returns `null` if invalid.
     */
    fun decodePointerHash(bytes: ByteArray): ByteArray? {
        if (bytes.size != POINTER_FILE_SIZE || bytes[0] != MAGIC_BYTE_0 || bytes[1] != VERSION_V4) {
            return null
        }
        val hash = ByteArray(SHA256_BYTE_LENGTH)
        System.arraycopy(bytes, 2, hash, 0, SHA256_BYTE_LENGTH)
        return hash
    }

    private fun encodeRlePayload(blocks: ByteArray): ByteArray {
        val payloadOut = ByteArrayOutputStream()
        for (ly in 0 until SLICES) {
            val sliceStart = ly shl 8
            val firstId = blocks[sliceStart]
            var isUniform = true
            for (i in 1 until BLOCKS_PER_SLICE) {
                if (blocks[sliceStart + i] != firstId) {
                    isUniform = false
                    break
                }
            }

            if (isUniform) {
                payloadOut.write(0x01)
                payloadOut.write(firstId.toInt())
            } else {
                payloadOut.write(0x00)
                var runId = firstId
                var runCount = 1
                for (i in 1 until BLOCKS_PER_SLICE) {
                    val currentId = blocks[sliceStart + i]
                    if (currentId == runId && runCount < 255) {
                        runCount++
                    } else {
                        writeRun(payloadOut, runId, runCount)
                        runId = currentId
                        runCount = 1
                    }
                }
                writeRun(payloadOut, runId, runCount)
            }
        }
        return payloadOut.toByteArray()
    }

    private fun writeRun(out: ByteArrayOutputStream, id: Byte, count: Int) {
        var rem = count
        while (rem > 0) {
            val batch = minOf(rem, 255)
            out.write(id.toInt())
            out.write(batch)
            rem -= batch
        }
    }

    /**
     * Decodes a Legacy, V1, V2, or V3 byte payload into a raw 4096-byte chunk array,
     * returning `null` if validation fails (or if [bytes] is a V4 pointer, which requires
     * resolving the master file via [WorldSaveManager]).
     */
    fun decode(bytes: ByteArray): ByteArray? = decodeWithFormat(bytes)?.blocks

    /**
     * Decodes a Legacy, V1, V2, or V3 byte payload into [OsmiumDecodedChunk],
     * returning `null` if validation fails or if [bytes] is a V4 pointer.
     */
    fun decodeWithFormat(bytes: ByteArray): OsmiumDecodedChunk? {
        // 1. Legacy (Raw 4096 bytes)
        if (bytes.size == Chunk.VOLUME) {
            return OsmiumDecodedChunk(bytes.copyOf(), OsmiumFormat.LEGACY_RAW)
        }

        if (bytes.size < 2 || bytes[0] != MAGIC_BYTE_0) {
            return null
        }

        return when (bytes[1]) {
            VERSION_V1 -> {
                if (bytes.size != 2 + Chunk.VOLUME) {
                    null
                } else {
                    val result = ByteArray(Chunk.VOLUME)
                    System.arraycopy(bytes, 2, result, 0, Chunk.VOLUME)
                    OsmiumDecodedChunk(result, OsmiumFormat.V1_RAW)
                }
            }
            VERSION_V2 -> {
                val decoded = decodeRlePayload(bytes, 2) ?: return null
                OsmiumDecodedChunk(decoded, OsmiumFormat.V2_OSMIUM)
            }
            VERSION_V3 -> {
                val decoded = decodeV3Payload(bytes) ?: return null
                OsmiumDecodedChunk(decoded, OsmiumFormat.V3_LZ4)
            }
            else -> null
        }
    }

    private fun decodeV3Payload(bytes: ByteArray): ByteArray? {
        if (bytes.size <= 4) return null
        val rleLength = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (rleLength <= 0 || rleLength > MAX_RLE_PAYLOAD_BYTES) {
            return null
        }
        val rlePayload = ByteArray(rleLength)
        try {
            val consumed = lz4Decompressor.decompress(bytes, 4, rlePayload, 0, rleLength)
            if (4 + consumed != bytes.size) {
                return null
            }
        } catch (_: LZ4Exception) {
            return null
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
        return decodeRlePayload(rlePayload, 0)
    }

    private fun decodeRlePayload(bytes: ByteArray, offset: Int): ByteArray? {
        val blocks = ByteArray(Chunk.VOLUME)
        var byteIdx = offset
        val totalBytes = bytes.size

        for (ly in 0 until SLICES) {
            if (byteIdx >= totalBytes) {
                return null
            }
            val sliceType = bytes[byteIdx++].toInt() and 0xFF
            var sliceBlockCount = 0
            val sliceStartBlockIdx = ly shl 8

            if (sliceType == 0x01) {
                if (byteIdx >= totalBytes) {
                    return null
                }
                val blockId = bytes[byteIdx++]
                for (i in 0 until BLOCKS_PER_SLICE) {
                    blocks[sliceStartBlockIdx + i] = blockId
                }
                sliceBlockCount = BLOCKS_PER_SLICE
            } else if (sliceType == 0x00) {
                while (sliceBlockCount < BLOCKS_PER_SLICE) {
                    if (byteIdx + 1 >= totalBytes) {
                        return null
                    }
                    val blockId = bytes[byteIdx++]
                    val countRaw = bytes[byteIdx++].toInt() and 0xFF
                    if (countRaw == 0) {
                        return null
                    }
                    if (sliceBlockCount + countRaw > BLOCKS_PER_SLICE) {
                        return null
                    }
                    for (i in 0 until countRaw) {
                        blocks[sliceStartBlockIdx + sliceBlockCount++] = blockId
                    }
                }
            } else {
                return null
            }

            if (sliceBlockCount != BLOCKS_PER_SLICE) {
                return null
            }
        }

        if (byteIdx != totalBytes) {
            return null
        }

        return blocks
    }
}
