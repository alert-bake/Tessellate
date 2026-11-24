package com.nitrous.tessellate.world

/**
 * 16x16x16 block storage unit decoupled from mesh generation and rendering.
 *
 * Includes compact Lazyprop lighting state ([lightIntended], [lightRendered]) on the signed
 * `-16..+16` scale (`-16` = absolute darkness, `0` = ambient baseline, `+16` = brightest),
 * allocated lazily only for chunks that are neither uniformly `-16` dark nor uniformly `+16` lit.
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
        const val MIN_LIGHT = -16
        const val DEFAULT_AMBIENT_LIGHT = 0
        const val MAX_LIGHT = 16
    }

    // 4096-byte compact block storage
    val blocks = ByteArray(VOLUME)

    /**
     * Lazyprop dual-layer light buffers (`-16..+16` per voxel).
     * Stay `null` while the chunk is uniformly dark (`-16`, [isCompletelyDark]) or uniformly lit
     * (`+16`, [isCompletelyLit]) to avoid allocating 8 KB per sky/bedrock chunk.
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

    private val lightStorageLock = Any()

    // Dirty tracking for incremental mesh & light updates
    @Volatile
    var meshDirtyEpoch: Long = 0L
        private set

    @Volatile
    var needsMeshRebuild: Boolean = true
    var isDirty: Boolean
        get() = needsMeshRebuild
        set(value) {
            if (value) {
                meshDirtyEpoch++
            }
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
            meshDirtyEpoch++
            needsMeshRebuild = true
            needsLightUpdate = true
            if (type != BlockType.AIR) {
                isEmpty = false
                isCompletelyLit = false
            }
            if (type.lightEmission > 0) {
                isCompletelyDark = false
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
        if (type.lightEmission > 0) {
            isCompletelyDark = false
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
        meshDirtyEpoch++
        needsMeshRebuild = true
        if (type.isOpaque) {
            markCompletelyDark()
        } else if (type == BlockType.AIR) {
            markCompletelyLit()
        }
    }

    fun markDirty() {
        meshDirtyEpoch++
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
    // Lazyprop Light State Helpers (-16..+16)
    // =========================================================================

    fun ensureLightStorage(): Pair<ByteArray, ByteArray> = synchronized(lightStorageLock) {
        var intended = lightIntended
        var rendered = lightRendered
        if (intended == null) {
            val newIntended = ByteArray(VOLUME)
            newIntended.fill(uniformIntendedLight)
            lightIntended = newIntended
            intended = newIntended
        }
        if (rendered == null) {
            val newRendered = ByteArray(VOLUME)
            newRendered.fill(uniformRenderedLight)
            lightRendered = newRendered
            rendered = newRendered
        }
        isCompletelyDark = false
        isCompletelyLit = false
        intended to rendered
    }

    /**
     * Replaces/populates both light buffers directly from [srcIntended] and [srcRendered].
     * Populates new arrays completely before publishing to lock-free readers.
     */
    fun setLightArrays(srcIntended: ByteArray, srcRendered: ByteArray) = synchronized(lightStorageLock) {
        val existingIntended = lightIntended
        val existingRendered = lightRendered
        if (existingIntended != null && existingRendered != null) {
            System.arraycopy(srcIntended, 0, existingIntended, 0, VOLUME)
            System.arraycopy(srcRendered, 0, existingRendered, 0, VOLUME)
        } else {
            val newIntended = ByteArray(VOLUME)
            val newRendered = ByteArray(VOLUME)
            System.arraycopy(srcIntended, 0, newIntended, 0, VOLUME)
            System.arraycopy(srcRendered, 0, newRendered, 0, VOLUME)
            lightIntended = newIntended
            lightRendered = newRendered
        }
        isCompletelyDark = false
        isCompletelyLit = false
    }

    fun getIntendedLightUnchecked(lx: Int, ly: Int, lz: Int): Int {
        val arr = lightIntended
        return if (arr != null) {
            arr[(ly shl 8) or (lz shl 4) or lx].toInt()
        } else {
            uniformIntendedLight.toInt()
        }
    }

    fun getRenderedLightUnchecked(lx: Int, ly: Int, lz: Int): Int {
        val arr = lightRendered
        return if (arr != null) {
            arr[(ly shl 8) or (lz shl 4) or lx].toInt()
        } else {
            uniformRenderedLight.toInt()
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
        val clamped = level.coerceIn(MIN_LIGHT, MAX_LIGHT).toByte()
        synchronized(lightStorageLock) {
            val existingArr = lightIntended
            if (existingArr == null) {
                if (uniformIntendedLight == clamped) return false
                val newIntended = ByteArray(VOLUME)
                newIntended.fill(uniformIntendedLight)
                newIntended[getIndex(lx, ly, lz)] = clamped
                lightIntended = newIntended
                if (lightRendered == null) {
                    val newRendered = ByteArray(VOLUME)
                    newRendered.fill(uniformRenderedLight)
                    lightRendered = newRendered
                }
                isCompletelyDark = false
                isCompletelyLit = false
                return true
            }
            val idx = getIndex(lx, ly, lz)
            if (existingArr[idx] == clamped) return false
            existingArr[idx] = clamped
            isCompletelyDark = false
            isCompletelyLit = false
            return true
        }
    }

    fun setRenderedLight(lx: Int, ly: Int, lz: Int, level: Int): Boolean {
        if (lx !in 0 until SIZE || ly !in 0 until SIZE || lz !in 0 until SIZE) return false
        val clamped = level.coerceIn(MIN_LIGHT, MAX_LIGHT).toByte()
        synchronized(lightStorageLock) {
            val existingArr = lightRendered
            if (existingArr == null) {
                if (uniformRenderedLight == clamped) return false
                val newRendered = ByteArray(VOLUME)
                newRendered.fill(uniformRenderedLight)
                newRendered[getIndex(lx, ly, lz)] = clamped
                lightRendered = newRendered
                if (lightIntended == null) {
                    val newIntended = ByteArray(VOLUME)
                    newIntended.fill(uniformIntendedLight)
                    lightIntended = newIntended
                }
                isCompletelyDark = false
                isCompletelyLit = false
                meshDirtyEpoch++
                needsMeshRebuild = true
                return true
            }
            val idx = getIndex(lx, ly, lz)
            if (existingArr[idx] == clamped) return false
            existingArr[idx] = clamped
            isCompletelyDark = false
            isCompletelyLit = false
            meshDirtyEpoch++
            needsMeshRebuild = true
            return true
        }
    }

    fun markCompletelyDark() = synchronized(lightStorageLock) {
        lightIntended = null
        lightRendered = null
        uniformIntendedLight = MIN_LIGHT.toByte()
        uniformRenderedLight = MIN_LIGHT.toByte()
        isCompletelyDark = true
        isCompletelyLit = false
        needsLightUpdate = false
    }

    fun markCompletelyLit() = synchronized(lightStorageLock) {
        lightIntended = null
        lightRendered = null
        uniformIntendedLight = MAX_LIGHT.toByte()
        uniformRenderedLight = MAX_LIGHT.toByte()
        isCompletelyDark = false
        isCompletelyLit = true
        needsLightUpdate = false
    }

    /**
     * Compacts light arrays back to uniform scalar state if every voxel is identically `-16` or `+16`,
     * freeing 8 KB of heap memory and flagging [isCompletelyDark] / [isCompletelyLit] for future skips.
     */
    fun compactUniformLightIfPossible() = synchronized(lightStorageLock) {
        val intended = lightIntended ?: return
        val rendered = lightRendered ?: return
        val first = intended[0]
        if (first != MIN_LIGHT.toByte() && first != MAX_LIGHT.toByte()) return
        for (i in 0 until VOLUME) {
            if (intended[i] != first || rendered[i] != first) return
        }
        if (first == MIN_LIGHT.toByte()) {
            markCompletelyDark()
        } else {
            markCompletelyLit()
        }
    }
}
