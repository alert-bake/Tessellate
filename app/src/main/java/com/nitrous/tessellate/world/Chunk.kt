package com.nitrous.tessellate.world

/**
 * 16x16x16 block storage unit decoupled from mesh generation and rendering.
 *
 * Includes compact Lazyprop lighting state ([lightIntended], [lightRendered]) allocated lazily
 * only for chunks that are neither uniformly dark nor uniformly sky-lit.
 */
class Chunk(
    val chunkX: Int,
    val chunkY: Int,
    val chunkZ: Int
) {
    companion object {
        const val SIZE = 16
        const val VOLUME = SIZE * SIZE * SIZE // 4096 blocks
        const val MASK = SIZE - 1
        const val MAX_LIGHT = 15
    }

    // 4096-byte compact block storage
    val blocks = ByteArray(VOLUME)

    /**
     * Lazyprop dual-layer light buffers (`0..15` per voxel).
     * Stay `null` while the chunk is uniformly dark (`isCompletelyDark`) or uniformly lit
     * (`isCompletelyLit`) to avoid allocating 8 KB per sky/bedrock chunk.
     */
    @Volatile
    var lightIntended: ByteArray? = null
        private set

    @Volatile
    var lightRendered: ByteArray? = null
        private set

    @Volatile
    var uniformIntendedLight: Byte = MAX_LIGHT.toByte()
        private set

    @Volatile
    var uniformRenderedLight: Byte = MAX_LIGHT.toByte()
        private set

    // Dirty tracking for incremental mesh & light updates
    @Volatile
    var needsMeshRebuild: Boolean = true
    var isDirty: Boolean
        get() = needsMeshRebuild
        set(value) {
            needsMeshRebuild = value
        }

    @Volatile
    var needsLightUpdate: Boolean = false

    @Volatile
    var inLightQueue: Boolean = false

    @Volatile
    var isCompletelyDark: Boolean = false
        private set

    @Volatile
    var isCompletelyLit: Boolean = true
        private set

    @Volatile
    var isEmpty: Boolean = true
        private set

    @Volatile
    var isSolidOpaque: Boolean = false
        private set

    @Suppress("NOTHING_TO_INLINE")
    private inline fun getIndex(lx: Int, ly: Int, lz: Int): Int {
        return (ly shl 8) or (lz shl 4) or lx
    }

    /**
     * Fast unchecked raw block ID lookup for callers that already verified `0 <= lx, ly, lz < 16`.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun getBlockIdUnchecked(lx: Int, ly: Int, lz: Int): Byte {
        return blocks[(ly shl 8) or (lz shl 4) or lx]
    }

    /**
     * Fast unchecked [BlockType] lookup for callers that already verified `0 <= lx, ly, lz < 16`.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun getBlockUnchecked(lx: Int, ly: Int, lz: Int): BlockType {
        return BlockType.fromId(blocks[(ly shl 8) or (lz shl 4) or lx])
    }

    fun getBlock(lx: Int, ly: Int, lz: Int): BlockType {
        if (lx !in 0 until SIZE || ly !in 0 until SIZE || lz !in 0 until SIZE) {
            return BlockType.AIR
        }
        val id = blocks[getIndex(lx, ly, lz)]
        return BlockType.fromId(id)
    }

    fun setBlock(lx: Int, ly: Int, lz: Int, type: BlockType): Boolean {
        if (lx !in 0 until SIZE || ly !in 0 until SIZE || lz !in 0 until SIZE) {
            return false
        }
        val idx = getIndex(lx, ly, lz)
        val oldId = blocks[idx]
        if (oldId != type.id) {
            blocks[idx] = type.id
            needsMeshRebuild = true
            needsLightUpdate = true
            if (type != BlockType.AIR) {
                isEmpty = false
                isCompletelyLit = false
            }
            if (!type.isOpaque) {
                isSolidOpaque = false
            }
            return true
        }
        return false
    }

    /**
     * Fast unchecked block write used inside worldgen passes before [recalculateEmpty] / [markDirty].
     */
    fun setBlockRaw(lx: Int, ly: Int, lz: Int, type: BlockType) {
        if (lx !in 0 until SIZE || ly !in 0 until SIZE || lz !in 0 until SIZE) return
        blocks[getIndex(lx, ly, lz)] = type.id
        if (type != BlockType.AIR) {
            isEmpty = false
            isCompletelyLit = false
        }
        if (!type.isOpaque) {
            isSolidOpaque = false
        }
    }

    /**
     * Fills the entire chunk with [type] (e.g. Pass 0 base stone fill).
     */
    fun fill(type: BlockType) {
        blocks.fill(type.id)
        isEmpty = (type == BlockType.AIR)
        isSolidOpaque = type.isOpaque
        needsMeshRebuild = true
        if (type.isOpaque) {
            markCompletelyDark()
        } else if (type == BlockType.AIR) {
            markCompletelyLit()
        }
    }

    fun markDirty() {
        needsMeshRebuild = true
    }

    fun markLightDirty() {
        needsLightUpdate = true
    }

    fun recalculateEmpty() {
        var empty = true
        var allOpaque = true
        val airByte = 0.toByte()
        for (i in 0 until VOLUME) {
            val id = blocks[i]
            if (id != airByte) {
                empty = false
                if (allOpaque && !BlockType.fromId(id).isOpaque) {
                    allOpaque = false
                }
            } else {
                allOpaque = false
            }
        }
        isEmpty = empty
        isSolidOpaque = !empty && allOpaque
    }

    // =========================================================================
    // Lazyprop Light State Helpers
    // =========================================================================

    fun ensureLightStorage(): Pair<ByteArray, ByteArray> {
        var intended = lightIntended
        var rendered = lightRendered
        if (intended == null) {
            intended = ByteArray(VOLUME)
            intended.fill(uniformIntendedLight)
            lightIntended = intended
        }
        if (rendered == null) {
            rendered = ByteArray(VOLUME)
            rendered.fill(uniformRenderedLight)
            lightRendered = rendered
        }
        isCompletelyDark = false
        isCompletelyLit = false
        return intended to rendered
    }

    fun getIntendedLightUnchecked(lx: Int, ly: Int, lz: Int): Int {
        val arr = lightIntended
        return if (arr != null) {
            arr[(ly shl 8) or (lz shl 4) or lx].toInt() and 0x0F
        } else {
            uniformIntendedLight.toInt() and 0x0F
        }
    }

    fun getRenderedLightUnchecked(lx: Int, ly: Int, lz: Int): Int {
        val arr = lightRendered
        return if (arr != null) {
            arr[(ly shl 8) or (lz shl 4) or lx].toInt() and 0x0F
        } else {
            uniformRenderedLight.toInt() and 0x0F
        }
    }

    fun getIntendedLight(lx: Int, ly: Int, lz: Int): Int {
        if (lx !in 0 until SIZE || ly !in 0 until SIZE || lz !in 0 until SIZE) return MAX_LIGHT
        return getIntendedLightUnchecked(lx, ly, lz)
    }

    fun getRenderedLight(lx: Int, ly: Int, lz: Int): Int {
        if (lx !in 0 until SIZE || ly !in 0 until SIZE || lz !in 0 until SIZE) return MAX_LIGHT
        return getRenderedLightUnchecked(lx, ly, lz)
    }

    fun setIntendedLight(lx: Int, ly: Int, lz: Int, level: Int): Boolean {
        if (lx !in 0 until SIZE || ly !in 0 until SIZE || lz !in 0 until SIZE) return false
        val clamped = level.coerceIn(0, MAX_LIGHT).toByte()
        val existingArr = lightIntended
        if (existingArr == null) {
            if (uniformIntendedLight == clamped) return false
            val (intended, _) = ensureLightStorage()
            intended[getIndex(lx, ly, lz)] = clamped
            return true
        }
        val idx = getIndex(lx, ly, lz)
        if (existingArr[idx] == clamped) return false
        existingArr[idx] = clamped
        isCompletelyDark = false
        isCompletelyLit = false
        return true
    }

    fun setRenderedLight(lx: Int, ly: Int, lz: Int, level: Int): Boolean {
        if (lx !in 0 until SIZE || ly !in 0 until SIZE || lz !in 0 until SIZE) return false
        val clamped = level.coerceIn(0, MAX_LIGHT).toByte()
        val existingArr = lightRendered
        if (existingArr == null) {
            if (uniformRenderedLight == clamped) return false
            val (_, rendered) = ensureLightStorage()
            rendered[getIndex(lx, ly, lz)] = clamped
            needsMeshRebuild = true
            return true
        }
        val idx = getIndex(lx, ly, lz)
        if (existingArr[idx] == clamped) return false
        existingArr[idx] = clamped
        isCompletelyDark = false
        isCompletelyLit = false
        needsMeshRebuild = true
        return true
    }

    fun markCompletelyDark() {
        lightIntended = null
        lightRendered = null
        uniformIntendedLight = 0
        uniformRenderedLight = 0
        isCompletelyDark = true
        isCompletelyLit = false
        needsLightUpdate = false
    }

    fun markCompletelyLit() {
        lightIntended = null
        lightRendered = null
        uniformIntendedLight = MAX_LIGHT.toByte()
        uniformRenderedLight = MAX_LIGHT.toByte()
        isCompletelyDark = false
        isCompletelyLit = true
        needsLightUpdate = false
    }

    /**
     * Compacts light arrays back to uniform scalar state if every voxel is identically `0` or `15`,
     * freeing 8 KB of heap memory and flagging [isCompletelyDark] / [isCompletelyLit] for future skips.
     */
    fun compactUniformLightIfPossible() {
        val intended = lightIntended ?: return
        val rendered = lightRendered ?: return
        val first = intended[0]
        if (first != 0.toByte() && first != MAX_LIGHT.toByte()) return
        for (i in 0 until VOLUME) {
            if (intended[i] != first || rendered[i] != first) return
        }
        if (first == 0.toByte()) {
            markCompletelyDark()
        } else {
            markCompletelyLit()
        }
    }
}
