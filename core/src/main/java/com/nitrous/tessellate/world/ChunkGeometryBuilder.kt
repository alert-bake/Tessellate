package com.nitrous.tessellate.world

import kotlin.math.roundToInt

/**
 * Platform-independent atlas tile lookup consumed by `:core` chunk mesh workers.
 */
fun interface AtlasTileResolver {
    fun getTileIndex(textureId: String): Int
}

/**
 * Raw CPU mesh geometry produced by `:core` (both synchronously and by [ParallelChunkMesher]
 * worker threads). Vertex data is emitted as raw [FloatArray] objects ready for `:app` GPU upload.
 */
class ChunkMeshOutput(
    val chunk: Chunk,
    val opaqueVertices: FloatArray,
    val opaqueVertexFloatLength: Int,
    val opaqueIndices: ShortArray,
    val opaqueIndexLength: Int,
    val opaqueQuadCount: Int,
    val opaqueFacesCount: Int,
    val transparentVertices: FloatArray,
    val transparentVertexFloatLength: Int,
    val transparentIndices: ShortArray,
    val transparentIndexLength: Int,
    val transparentQuadCount: Int,
    val transparentFacesCount: Int,
    val culledFacesCount: Int,
    val dirtyEpoch: Long = 0L
) {
    val totalIndexCount: Int
        get() = opaqueIndexLength + transparentIndexLength
}

/**
 * Pure Kotlin greedy chunk & cross-model geometry builder in `:core` (zero OpenGL/Android deps).
 * Packs 3 32-bit vertex words (`3 floats = 12 bytes`) into raw [FloatArray] buffers whose bit
 * representations never collide with IEEE-754 NaN/Infinity encodings.
 */
class ChunkGeometryBuilder {

    private val sliceKeysScratch = IntArray(SLICE_AREA)
    private val sliceBlocksScratch = ByteArray(SLICE_AREA)
    private val sliceLightScratch = IntArray(SLICE_AREA)

    private var opaqueVertsScratch = FloatArray(128 * FLOATS_PER_QUAD)
    private var opaqueIndsScratch = ShortArray(128 * INDICES_PER_QUAD)
    private var transVertsScratch = FloatArray(64 * FLOATS_PER_QUAD)
    private var transIndsScratch = ShortArray(64 * INDICES_PER_QUAD)

    fun buildChunkGeometry(
        world: World,
        chunk: Chunk,
        tileResolver: AtlasTileResolver
    ): ChunkMeshOutput {
        val startEpoch = chunk.meshDirtyEpoch
        if (chunk.isEmpty) {
            if (chunk.meshDirtyEpoch == startEpoch) {
                chunk.needsMeshRebuild = false
            }
            return ChunkMeshOutput(
                chunk = chunk,
                opaqueVertices = EMPTY_FLOAT_ARRAY,
                opaqueVertexFloatLength = 0,
                opaqueIndices = EMPTY_SHORT_ARRAY,
                opaqueIndexLength = 0,
                opaqueQuadCount = 0,
                opaqueFacesCount = 0,
                transparentVertices = EMPTY_FLOAT_ARRAY,
                transparentVertexFloatLength = 0,
                transparentIndices = EMPTY_SHORT_ARRAY,
                transparentIndexLength = 0,
                transparentQuadCount = 0,
                transparentFacesCount = 0,
                culledFacesCount = 0,
                dirtyEpoch = startEpoch
            )
        }

        val cx = chunk.chunkX
        val cy = chunk.chunkY
        val cz = chunk.chunkZ
        val nXNeg = world.getChunk(cx - 1, cy, cz)
        val nXPos = world.getChunk(cx + 1, cy, cz)
        val nYNeg = world.getChunk(cx, cy - 1, cz)
        val nYPos = world.getChunk(cx, cy + 1, cz)
        val nZNeg = world.getChunk(cx, cy, cz - 1)
        val nZPos = world.getChunk(cx, cy, cz + 1)

        val air = BlockType.AIR
        val airId = air.id

        var opaqueVerts = opaqueVertsScratch
        var opaqueInds = opaqueIndsScratch
        var opaqueVCount = 0
        var opaqueICount = 0
        var opaqueQuads = 0
        var opaqueFaces = 0
        var opaqueVOffset = 0
        var opaqueIOffset = 0

        var transVerts = transVertsScratch
        var transInds = transIndsScratch
        var transVCount = 0
        var transICount = 0
        var transQuads = 0
        var transFaces = 0
        var transVOffset = 0
        var transIOffset = 0

        val culledCountInitial = 0
        var culledCount = culledCountInitial
        val blockDataSnapshot = if (chunk.blockDataMap.isEmpty()) emptyMap() else chunk.snapshotBlockData()
        val hasBlockData = blockDataSnapshot.isNotEmpty()

        // 1. Greedy mesh standard cube blocks along all 6 primary directions
        for (dIdx in 0 until DIRECTIONS.size) {
            val dir = DIRECTIONS[dIdx]
            val neighborChunk = when (dir) {
                Direction.WEST -> nXNeg
                Direction.EAST -> nXPos
                Direction.DOWN -> nYNeg
                Direction.UP -> nYPos
                Direction.NORTH -> nZNeg
                Direction.SOUTH -> nZPos
            }

            for (slice in 0 until Chunk.SIZE) {
                var sliceHasVisible = false
                sliceKeysScratch.fill(0)

                for (v in 0 until Chunk.SIZE) {
                    for (u in 0 until Chunk.SIZE) {
                        val lx: Int
                        val ly: Int
                        val lz: Int
                        when (dir) {
                            Direction.UP, Direction.DOWN -> { lx = u; ly = slice; lz = v }
                            Direction.NORTH, Direction.SOUTH -> { lx = u; ly = v; lz = slice }
                            Direction.WEST, Direction.EAST -> { lx = slice; ly = v; lz = u }
                        }

                        val blockId = chunk.getBlockIdUnchecked(lx, ly, lz)
                        if (blockId == airId) continue
                        val block = BlockType.fromId(blockId)
                        if (block.isCrossModel || (!block.isOpaque && !block.isTransparent)) continue

                        val nlx = lx + dir.dx
                        val nly = ly + dir.dy
                        val nlz = lz + dir.dz

                        val neighborId: Byte
                        val neighborRenderedLight: Int
                        if (((nlx or nly or nlz) and -16) == 0) {
                            neighborId = chunk.getBlockIdUnchecked(nlx, nly, nlz)
                            neighborRenderedLight = chunk.getRenderedLightUnchecked(nlx, nly, nlz)
                        } else if (neighborChunk != null) {
                            val rlx = nlx and Chunk.MASK
                            val rly = nly and Chunk.MASK
                            val rlz = nlz and Chunk.MASK
                            neighborId = neighborChunk.getBlockIdUnchecked(rlx, rly, rlz)
                            neighborRenderedLight = neighborChunk.getRenderedLightUnchecked(rlx, rly, rlz)
                        } else {
                            neighborId = airId
                            neighborRenderedLight = Chunk.MAX_LIGHT
                        }

                        if (neighborId == blockId) {
                            culledCount++
                            continue
                        }

                        val neighbor = if (neighborId == airId) air else BlockType.fromId(neighborId)
                        if (!shouldRenderFace(block, neighbor)) {
                            culledCount++
                            continue
                        }

                        val isTransparentPass = block.isTransparent
                        if (isTransparentPass) {
                            transFaces++
                        } else {
                            opaqueFaces++
                        }

                        val blockIdx = chunk.getBlockIndex(lx, ly, lz)
                        val isDevPainted = hasBlockData &&
                            ((blockDataSnapshot[blockIdx]?.get("devpaint") as? TypedValue.BooleanValue)?.value == true)

                        val lightBiased = (neighborRenderedLight + LIGHT_BIAS).coerceIn(0, MAX_LIGHT_BIASED)
                        val cellIdx = (v shl 4) or u
                        val key = (1 shl 31) or
                            ((if (isDevPainted) 1 else 0) shl 17) or
                            ((if (isTransparentPass) 1 else 0) shl 16) or
                            ((lightBiased and 0x3F) shl 8) or
                            (blockId.toInt() and 0xFF)
                        sliceKeysScratch[cellIdx] = key
                        sliceBlocksScratch[cellIdx] = blockId
                        sliceLightScratch[cellIdx] = lightBiased
                        sliceHasVisible = true
                    }
                }

                if (!sliceHasVisible) continue

                var v = 0
                while (v < Chunk.SIZE) {
                    var u = 0
                    while (u < Chunk.SIZE) {
                        val startIdx = (v shl 4) or u
                        val key = sliceKeysScratch[startIdx]
                        if (key == 0) {
                            u++
                            continue
                        }

                        var w = 1
                        while (u + w < Chunk.SIZE && sliceKeysScratch[(v shl 4) or (u + w)] == key) {
                            w++
                        }

                        var h = 1
                        outer@ while (v + h < Chunk.SIZE) {
                            val rowOffset = (v + h) shl 4
                            for (du in 0 until w) {
                                if (sliceKeysScratch[rowOffset or (u + du)] != key) {
                                    break@outer
                                }
                            }
                            h++
                        }

                        for (dv in 0 until h) {
                            val rowOffset = (v + dv) shl 4
                            for (du in 0 until w) {
                                sliceKeysScratch[rowOffset or (u + du)] = 0
                            }
                        }

                        val block = BlockType.fromId(sliceBlocksScratch[startIdx])
                        val lightBiased = sliceLightScratch[startIdx]
                        val tileIndex = tileResolver.getTileIndex(block.getTextureForFace(dir))
                        val isTransparentPass = (key and (1 shl 16)) != 0
                        val isDevPainted = (key and (1 shl 17)) != 0
                        val colorR = if (isDevPainted) DEV_PAINT_R else 1.0f
                        val colorG = if (isDevPainted) DEV_PAINT_G else 1.0f
                        val colorB = if (isDevPainted) DEV_PAINT_B else 1.0f

                        if (isTransparentPass) {
                            if (transVCount + 4 <= MAX_SHORT_INDEX_VERTICES) {
                                val neededFloats = transVOffset + FLOATS_PER_QUAD
                                if (neededFloats > transVerts.size) {
                                    transVerts = transVerts.copyOf(maxOf(neededFloats, transVerts.size * 2))
                                }
                                val neededIdx = transIOffset + INDICES_PER_QUAD
                                if (neededIdx > transInds.size) {
                                    transInds = transInds.copyOf(maxOf(neededIdx, transInds.size * 2))
                                }
                                val startVert = transVCount.toShort()
                                emitGreedyCubeQuad(
                                    target = transVerts,
                                    offset = transVOffset,
                                    dir = dir,
                                    slice = slice,
                                    u0 = u,
                                    v0 = v,
                                    w = w,
                                    h = h,
                                    lightBiased = lightBiased,
                                    tileIndex = tileIndex,
                                    colorR = colorR,
                                    colorG = colorG,
                                    colorB = colorB
                                )
                                transVOffset += FLOATS_PER_QUAD
                                transInds[transIOffset++] = startVert
                                transInds[transIOffset++] = (startVert + 1).toShort()
                                transInds[transIOffset++] = (startVert + 2).toShort()
                                transInds[transIOffset++] = startVert
                                transInds[transIOffset++] = (startVert + 2).toShort()
                                transInds[transIOffset++] = (startVert + 3).toShort()
                                transVCount += 4
                                transICount += 6
                                transQuads++
                            }
                        } else {
                            if (opaqueVCount + 4 <= MAX_SHORT_INDEX_VERTICES) {
                                val neededFloats = opaqueVOffset + FLOATS_PER_QUAD
                                if (neededFloats > opaqueVerts.size) {
                                    opaqueVerts = opaqueVerts.copyOf(maxOf(neededFloats, opaqueVerts.size * 2))
                                }
                                val neededIdx = opaqueIOffset + INDICES_PER_QUAD
                                if (neededIdx > opaqueInds.size) {
                                    opaqueInds = opaqueInds.copyOf(maxOf(neededIdx, opaqueInds.size * 2))
                                }
                                val startVert = opaqueVCount.toShort()
                                emitGreedyCubeQuad(
                                    target = opaqueVerts,
                                    offset = opaqueVOffset,
                                    dir = dir,
                                    slice = slice,
                                    u0 = u,
                                    v0 = v,
                                    w = w,
                                    h = h,
                                    lightBiased = lightBiased,
                                    tileIndex = tileIndex,
                                    colorR = colorR,
                                    colorG = colorG,
                                    colorB = colorB
                                )
                                opaqueVOffset += FLOATS_PER_QUAD
                                opaqueInds[opaqueIOffset++] = startVert
                                opaqueInds[opaqueIOffset++] = (startVert + 1).toShort()
                                opaqueInds[opaqueIOffset++] = (startVert + 2).toShort()
                                opaqueInds[opaqueIOffset++] = startVert
                                opaqueInds[opaqueIOffset++] = (startVert + 2).toShort()
                                opaqueInds[opaqueIOffset++] = (startVert + 3).toShort()
                                opaqueVCount += 4
                                opaqueICount += 6
                                opaqueQuads++
                            }
                        }

                        u += w
                    }
                    v++
                }
            }
        }

        // 2. Cross-model blocks (e.g., tessellate:torch) rendered as double-sided intersecting X-quads
        for (ly in 0 until Chunk.SIZE) {
            for (lz in 0 until Chunk.SIZE) {
                for (lx in 0 until Chunk.SIZE) {
                    val blockId = chunk.getBlockIdUnchecked(lx, ly, lz)
                    if (blockId == airId) continue
                    val block = BlockType.fromId(blockId)
                    if (!block.isCrossModel) continue

                    if (transVCount + 16 > MAX_SHORT_INDEX_VERTICES) continue

                    val tileIndex = tileResolver.getTileIndex(block.topTexture)
                    val voxelLight = chunk.getRenderedLightUnchecked(lx, ly, lz)
                    val lightBiased = (voxelLight + LIGHT_BIAS).coerceIn(0, MAX_LIGHT_BIASED)
                    val blockIdx = chunk.getBlockIndex(lx, ly, lz)
                    val isDevPainted = hasBlockData &&
                        ((blockDataSnapshot[blockIdx]?.get("devpaint") as? TypedValue.BooleanValue)?.value == true)
                    val colorR = if (isDevPainted) DEV_PAINT_R else 1.0f
                    val colorG = if (isDevPainted) DEV_PAINT_G else 1.0f
                    val colorB = if (isDevPainted) DEV_PAINT_B else 1.0f

                    val neededFloats = transVOffset + FLOATS_PER_QUAD * 4
                    if (neededFloats > transVerts.size) {
                        transVerts = transVerts.copyOf(maxOf(neededFloats, transVerts.size * 2))
                    }
                    val neededIdx = transIOffset + INDICES_PER_QUAD * 4
                    if (neededIdx > transInds.size) {
                        transInds = transInds.copyOf(maxOf(neededIdx, transInds.size * 2))
                    }

                    val fx = lx.toFloat()
                    val fy = ly.toFloat()
                    val fz = lz.toFloat()
                    val minOff = CROSS_MIN_OFFSET
                    val maxOff = CROSS_MAX_OFFSET

                    emitDoubleSidedCrossQuad(
                        targetVerts = transVerts,
                        vOffset = transVOffset,
                        targetIndices = transInds,
                        iOffset = transIOffset,
                        baseVertex = transVCount,
                        x0 = fx + minOff, y0 = fy, z0 = fz + minOff,
                        x1 = fx + maxOff, y1 = fy + 1f, z1 = fz + maxOff,
                        lightBiased = lightBiased,
                        tileIndex = tileIndex,
                        colorR = colorR,
                        colorG = colorG,
                        colorB = colorB
                    )
                    transVOffset += FLOATS_PER_QUAD * 2
                    transIOffset += INDICES_PER_QUAD * 2
                    transVCount += 8
                    transICount += 12

                    emitDoubleSidedCrossQuad(
                        targetVerts = transVerts,
                        vOffset = transVOffset,
                        targetIndices = transInds,
                        iOffset = transIOffset,
                        baseVertex = transVCount,
                        x0 = fx + minOff, y0 = fy, z0 = fz + maxOff,
                        x1 = fx + maxOff, y1 = fy + 1f, z1 = fz + minOff,
                        lightBiased = lightBiased,
                        tileIndex = tileIndex,
                        colorR = colorR,
                        colorG = colorG,
                        colorB = colorB
                    )
                    transVOffset += FLOATS_PER_QUAD * 2
                    transIOffset += INDICES_PER_QUAD * 2
                    transVCount += 8
                    transICount += 12

                    transQuads += 4
                    transFaces += 4
                }
            }
        }

        opaqueVertsScratch = opaqueVerts
        opaqueIndsScratch = opaqueInds
        transVertsScratch = transVerts
        transIndsScratch = transInds

        if (chunk.meshDirtyEpoch == startEpoch) {
            chunk.needsMeshRebuild = false
        }

        val finalOpaqueVerts = if (opaqueVOffset == 0) EMPTY_FLOAT_ARRAY else opaqueVerts.copyOf(opaqueVOffset)
        val finalOpaqueInds = if (opaqueIOffset == 0) EMPTY_SHORT_ARRAY else opaqueInds.copyOf(opaqueIOffset)
        val finalTransVerts = if (transVOffset == 0) EMPTY_FLOAT_ARRAY else transVerts.copyOf(transVOffset)
        val finalTransInds = if (transIOffset == 0) EMPTY_SHORT_ARRAY else transInds.copyOf(transIOffset)

        return ChunkMeshOutput(
            chunk = chunk,
            opaqueVertices = finalOpaqueVerts,
            opaqueVertexFloatLength = finalOpaqueVerts.size,
            opaqueIndices = finalOpaqueInds,
            opaqueIndexLength = finalOpaqueInds.size,
            opaqueQuadCount = opaqueQuads,
            opaqueFacesCount = opaqueFaces,
            transparentVertices = finalTransVerts,
            transparentVertexFloatLength = finalTransVerts.size,
            transparentIndices = finalTransInds,
            transparentIndexLength = finalTransInds.size,
            transparentQuadCount = transQuads,
            transparentFacesCount = transFaces,
            culledFacesCount = culledCount,
            dirtyEpoch = startEpoch
        )
    }

    private fun emitGreedyCubeQuad(
        target: FloatArray,
        offset: Int,
        dir: Direction,
        slice: Int,
        u0: Int,
        v0: Int,
        w: Int,
        h: Int,
        lightBiased: Int,
        tileIndex: Int,
        colorR: Float = 1.0f,
        colorG: Float = 1.0f,
        colorB: Float = 1.0f
    ) {
        val normIdx = dir.ordinal
        val shadeByte = (dir.faceShade * 255f).roundToInt().coerceIn(0, 255)
        var o = offset

        when (dir) {
            Direction.UP -> {
                val fy = (slice + 1).toFloat()
                val fx0 = u0.toFloat()
                val fx1 = (u0 + w).toFloat()
                val fz0 = v0.toFloat()
                val fz1 = (v0 + h).toFloat()
                packVertex(target, o, fx0, fy, fz1, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx1, fy, fz1, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx1, fy, fz0, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx0, fy, fz0, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte, colorR, colorG, colorB)
            }
            Direction.DOWN -> {
                val fy = slice.toFloat()
                val fx0 = u0.toFloat()
                val fx1 = (u0 + w).toFloat()
                val fz0 = v0.toFloat()
                val fz1 = (v0 + h).toFloat()
                packVertex(target, o, fx0, fy, fz0, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx1, fy, fz0, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx1, fy, fz1, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx0, fy, fz1, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte, colorR, colorG, colorB)
            }
            Direction.NORTH -> {
                val fz = slice.toFloat()
                val fx0 = u0.toFloat()
                val fx1 = (u0 + w).toFloat()
                val fy0 = v0.toFloat()
                val fy1 = (v0 + h).toFloat()
                packVertex(target, o, fx1, fy0, fz, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx0, fy0, fz, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx0, fy1, fz, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx1, fy1, fz, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte, colorR, colorG, colorB)
            }
            Direction.SOUTH -> {
                val fz = (slice + 1).toFloat()
                val fx0 = u0.toFloat()
                val fx1 = (u0 + w).toFloat()
                val fy0 = v0.toFloat()
                val fy1 = (v0 + h).toFloat()
                packVertex(target, o, fx0, fy0, fz, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx1, fy0, fz, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx1, fy1, fz, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx0, fy1, fz, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte, colorR, colorG, colorB)
            }
            Direction.WEST -> {
                val fx = slice.toFloat()
                val fy0 = v0.toFloat()
                val fy1 = (v0 + h).toFloat()
                val fz0 = u0.toFloat()
                val fz1 = (u0 + w).toFloat()
                packVertex(target, o, fx, fy0, fz0, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx, fy0, fz1, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx, fy1, fz1, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx, fy1, fz0, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte, colorR, colorG, colorB)
            }
            Direction.EAST -> {
                val fx = (slice + 1).toFloat()
                val fy0 = v0.toFloat()
                val fy1 = (v0 + h).toFloat()
                val fz0 = u0.toFloat()
                val fz1 = (u0 + w).toFloat()
                packVertex(target, o, fx, fy0, fz1, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx, fy0, fz0, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx, fy1, fz0, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte, colorR, colorG, colorB); o += FLOATS_PER_VERTEX
                packVertex(target, o, fx, fy1, fz1, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte, colorR, colorG, colorB)
            }
        }
    }

    private fun emitDoubleSidedCrossQuad(
        targetVerts: FloatArray,
        vOffset: Int,
        targetIndices: ShortArray,
        iOffset: Int,
        baseVertex: Int,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        lightBiased: Int,
        tileIndex: Int,
        colorR: Float = 1.0f,
        colorG: Float = 1.0f,
        colorB: Float = 1.0f
    ) {
        val normIdx = CROSS_NORMAL_INDEX
        val flags = CROSS_FLAG
        val shadeByte = 255
        var vo = vOffset

        packVertex(targetVerts, vo, x0, y0, z0, normIdx, flags, lightBiased, 0, 1, tileIndex, shadeByte, colorR, colorG, colorB); vo += FLOATS_PER_VERTEX
        packVertex(targetVerts, vo, x1, y0, z1, normIdx, flags, lightBiased, 1, 1, tileIndex, shadeByte, colorR, colorG, colorB); vo += FLOATS_PER_VERTEX
        packVertex(targetVerts, vo, x1, y1, z1, normIdx, flags, lightBiased, 1, 0, tileIndex, shadeByte, colorR, colorG, colorB); vo += FLOATS_PER_VERTEX
        packVertex(targetVerts, vo, x0, y1, z0, normIdx, flags, lightBiased, 0, 0, tileIndex, shadeByte, colorR, colorG, colorB); vo += FLOATS_PER_VERTEX

        packVertex(targetVerts, vo, x1, y0, z1, normIdx, flags, lightBiased, 0, 1, tileIndex, shadeByte, colorR, colorG, colorB); vo += FLOATS_PER_VERTEX
        packVertex(targetVerts, vo, x0, y0, z0, normIdx, flags, lightBiased, 1, 1, tileIndex, shadeByte, colorR, colorG, colorB); vo += FLOATS_PER_VERTEX
        packVertex(targetVerts, vo, x0, y1, z0, normIdx, flags, lightBiased, 1, 0, tileIndex, shadeByte, colorR, colorG, colorB); vo += FLOATS_PER_VERTEX
        packVertex(targetVerts, vo, x1, y1, z1, normIdx, flags, lightBiased, 0, 0, tileIndex, shadeByte, colorR, colorG, colorB)

        val fStart = baseVertex.toShort()
        targetIndices[iOffset + 0] = fStart
        targetIndices[iOffset + 1] = (fStart + 1).toShort()
        targetIndices[iOffset + 2] = (fStart + 2).toShort()
        targetIndices[iOffset + 3] = fStart
        targetIndices[iOffset + 4] = (fStart + 2).toShort()
        targetIndices[iOffset + 5] = (fStart + 3).toShort()

        val bStart = (baseVertex + 4).toShort()
        targetIndices[iOffset + 6] = bStart
        targetIndices[iOffset + 7] = (bStart + 1).toShort()
        targetIndices[iOffset + 8] = (bStart + 2).toShort()
        targetIndices[iOffset + 9] = bStart
        targetIndices[iOffset + 10] = (bStart + 2).toShort()
        targetIndices[iOffset + 11] = (bStart + 3).toShort()
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun packVertex(
        target: FloatArray,
        offset: Int,
        localX: Float,
        localY: Float,
        localZ: Float,
        normIdx: Int,
        flags: Int,
        lightBiased: Int,
        uvU: Int,
        uvV: Int,
        tileIndex: Int,
        shadeByte: Int,
        colorR: Float = 1.0f,
        colorG: Float = 1.0f,
        colorB: Float = 1.0f
    ) {
        val xQ = (localX * POS_QUANT_SCALE).roundToInt().coerceIn(0, 8191)
        val yQ = (localY * POS_QUANT_SCALE).roundToInt().coerceIn(0, 8191)
        val zQ = (localZ * POS_QUANT_SCALE).roundToInt().coerceIn(0, 8191)

        val word0 = (xQ and 0x1FFF) or
            ((yQ and 0x1FFF) shl 13) or
            ((normIdx and 0x7) shl 26) or
            ((flags and 0x7) shl 29)

        val word1 = (zQ and 0x1FFF) or
            ((lightBiased and 0x3F) shl 13) or
            ((uvU and 0x1F) shl 19) or
            ((uvV and 0x1F) shl 24)

        val rQ = (colorR * COLOR_QUANT_SCALE).roundToInt().coerceIn(0, COLOR_QUANT_MAX)
        val gQ = (colorG * COLOR_QUANT_SCALE).roundToInt().coerceIn(0, COLOR_QUANT_MAX)
        val bQ = (colorB * COLOR_QUANT_SCALE).roundToInt().coerceIn(0, COLOR_QUANT_MAX)

        // Note: bit 30 stays 0 so IEEE-754 exponent bits [23..30] can never form NaN/Infinity.
        val word2 = (tileIndex and 0xFF) or
            ((shadeByte and 0xFF) shl 8) or
            ((rQ and 0x1F) shl 16) or
            ((gQ and 0x1F) shl 21) or
            ((bQ and 0x0F) shl 26) or
            (((bQ ushr 4) and 0x1) shl 31)

        target[offset] = Float.fromBits(word0)
        target[offset + 1] = Float.fromBits(word1)
        target[offset + 2] = Float.fromBits(word2)
    }

    companion object {
        const val FLOATS_PER_VERTEX = 3
        const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * 4 // 12 bytes
        const val FLOATS_PER_QUAD = FLOATS_PER_VERTEX * 4
        const val INDICES_PER_QUAD = 6
        const val DEV_PAINT_R = 0.3f
        const val DEV_PAINT_G = 1.0f
        const val DEV_PAINT_B = 0.3f
        private const val COLOR_QUANT_MAX = 30
        private const val COLOR_QUANT_SCALE = 30f
        private const val SLICE_AREA = Chunk.SIZE * Chunk.SIZE
        private const val POS_QUANT_SCALE = 256f
        private const val LIGHT_BIAS = 16
        private const val MAX_LIGHT_BIASED = 32
        private const val CROSS_NORMAL_INDEX = 6
        private const val CROSS_FLAG = 1
        private const val CROSS_MIN_OFFSET = 0.15f
        private const val CROSS_MAX_OFFSET = 0.85f
        private const val MAX_SHORT_INDEX_VERTICES = 65532

        val EMPTY_FLOAT_ARRAY = FloatArray(0)
        val EMPTY_SHORT_ARRAY = ShortArray(0)
        private val DIRECTIONS = Direction.values()

        fun decodeVertexColor(word2: Int): Triple<Float, Float, Float> {
            val rQ = (word2 ushr 16) and 0x1F
            val gQ = (word2 ushr 21) and 0x1F
            val bQ = ((word2 ushr 26) and 0x0F) or (((word2 ushr 31) and 0x1) shl 4)
            return Triple(
                (rQ / COLOR_QUANT_SCALE).coerceIn(0f, 1f),
                (gQ / COLOR_QUANT_SCALE).coerceIn(0f, 1f),
                (bQ / COLOR_QUANT_SCALE).coerceIn(0f, 1f)
            )
        }

        fun shouldRenderFace(block: BlockType, neighbor: BlockType): Boolean {
            if (block == BlockType.AIR || block.isCrossModel) return false
            if (block.isOpaque) {
                return !neighbor.isOpaque
            }
            if (block.isTransparent) {
                if (neighbor.isOpaque) return false
                if (neighbor == block) return false
                return true
            }
            return false
        }
    }
}
