package com.nitrous.tessellate.world

/**
 * 16x16x16 block storage unit decoupled from mesh generation and rendering.
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
    }

    // 4096-byte compact block storage
    val blocks = ByteArray(VOLUME)

    // Dirty tracking for incremental mesh rebuilds
    var needsMeshRebuild: Boolean = true
    var isDirty: Boolean
        get() = needsMeshRebuild
        set(value) {
            needsMeshRebuild = value
        }
    var isEmpty: Boolean = true
        private set

    @Suppress("NOTHING_TO_INLINE")
    private inline fun getIndex(lx: Int, ly: Int, lz: Int): Int {
        return (ly shl 8) or (lz shl 4) or lx
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
            if (type != BlockType.AIR) {
                isEmpty = false
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
        }
    }

    /**
     * Fills the entire chunk with [type] (e.g. Pass 0 base stone fill).
     */
    fun fill(type: BlockType) {
        blocks.fill(type.id)
        isEmpty = (type == BlockType.AIR)
        needsMeshRebuild = true
    }

    fun markDirty() {
        needsMeshRebuild = true
    }

    fun recalculateEmpty() {
        var empty = true
        for (i in 0 until VOLUME) {
            if (blocks[i] != 0.toByte()) {
                empty = false
                break
            }
        }
        isEmpty = empty
    }
}
