package com.nitrous.tessellate.world

import android.opengl.GLES30
import com.nitrous.tessellate.texture.TexturePipeline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * High-performance two-pass mesh generator and GPU buffer container for a 16x16x16 chunk.
 *
 * Optimizations & Features:
 * - **Greedy Meshing:** Coplanar cube faces with identical [BlockType.id] and neighbor `lightRendered`
 *   merge into maximal axis-aligned rectangles within each 16x16 chunk slice.
 * - **Double-Sided Cross Models (TDD-019.5):** Non-cube `"cross"` blocks (e.g., `tessellate:torch`)
 *   render as two centered intersecting diagonal quads in the transparent pass with both front and
 *   back CCW windings emitted.
 * - **12-Byte Packed Vertices:** Compresses each vertex into 3 `uint`s (`uvec3`) carrying quantized
 *   chunk-local coordinates (`*256`), normal index, cross-flag, biased voxel light (`0..32` mapped
 *   from `-16..+16`), greedy tile UV coordinates (`0..16`), atlas tile index, and face shade byte.
 * - **Staging Buffer Pool & CPU Array Trimming:** Uses a thread-local direct staging buffer for
 *   `glBufferData` and releases CPU working arrays once uploaded to VRAM.
 */
class ChunkMesh(
    val chunk: Chunk
) {
    /** World-space bounding box of this 16x16x16 chunk for frustum culling. */
    val worldBounds: AABB = AABB(
        minX = (chunk.chunkX * Chunk.SIZE).toFloat(),
        minY = (chunk.chunkY * Chunk.SIZE).toFloat(),
        minZ = (chunk.chunkZ * Chunk.SIZE).toFloat(),
        maxX = ((chunk.chunkX + 1) * Chunk.SIZE).toFloat(),
        maxY = ((chunk.chunkY + 1) * Chunk.SIZE).toFloat(),
        maxZ = ((chunk.chunkZ + 1) * Chunk.SIZE).toFloat()
    )

    val originX: Float get() = worldBounds.minX
    val originY: Float get() = worldBounds.minY
    val originZ: Float get() = worldBounds.minZ

    val centerX: Float get() = worldBounds.minX + 8f
    val centerY: Float get() = worldBounds.minY + 8f
    val centerZ: Float get() = worldBounds.minZ + 8f

    /** Scratch distance squared used for allocation-free front-to-back / dirty sorting. */
    @JvmField
    var sortDistSq: Float = 0f

    // Pass 1: Opaque GPU handles
    var opaqueVaoId: Int = 0
        private set
    private var opaqueVboId: Int = 0
    private var opaqueEboId: Int = 0

    // Pass 2: Transparent GPU handles
    var transparentVaoId: Int = 0
        private set
    private var transparentVboId: Int = 0
    private var transparentEboId: Int = 0

    // Compatibility alias for Pass 1 VAO
    val vaoId: Int
        get() = opaqueVaoId

    var opaqueIndexCount: Int = 0
        private set
    var transparentIndexCount: Int = 0
        private set
    val indexCount: Int
        get() = opaqueIndexCount + transparentIndexCount

    var opaqueQuadCount: Int = 0
        private set
    var transparentQuadCount: Int = 0
        private set
    val totalQuadCount: Int
        get() = opaqueQuadCount + transparentQuadCount

    /** Logical exposed voxel faces (pre-merge) preserved for diagnostics & culling verification. */
    var opaqueFacesCount: Int = 0
        private set
    var transparentFacesCount: Int = 0
        private set
    val generatedFacesCount: Int
        get() = opaqueFacesCount + transparentFacesCount

    var culledFacesCount: Int = 0
        private set

    /** Counts how many times this chunk's mesh has been rebuilt. */
    var rebuildCount: Int = 0
        private set

    @Volatile
    var hasPendingGpuUpload: Boolean = false
        private set

    // Working packed CPU arrays (3 ints = 12 bytes per vertex) trimmed after uploadCpuMeshToGpu()
    private var rawOpaqueVertices = IntArray(0)
    private var rawOpaqueIndices = ShortArray(0)
    private var opaqueVertexIntLength: Int = 0
    private var opaqueIndexArrayLength: Int = 0

    private var rawTransparentVertices = IntArray(0)
    private var rawTransparentIndices = ShortArray(0)
    private var transparentVertexIntLength: Int = 0
    private var transparentIndexArrayLength: Int = 0

    @Volatile
    private var lastTexturePipeline: TexturePipeline? = null

    val hasOpaqueGpuBuffers: Boolean
        get() = opaqueVaoId != 0 || opaqueVboId != 0 || opaqueEboId != 0

    val hasTransparentGpuBuffers: Boolean
        get() = transparentVaoId != 0 || transparentVboId != 0 || transparentEboId != 0

    val hasAllocatedGpuBuffers: Boolean
        get() = hasOpaqueGpuBuffers || hasTransparentGpuBuffers

    val hasTrimmedCpuArrays: Boolean
        get() = rawOpaqueVertices.isEmpty() &&
            rawOpaqueIndices.isEmpty() &&
            rawTransparentVertices.isEmpty() &&
            rawTransparentIndices.isEmpty()

    // Reusable 2-int scratch array for GL handle allocation/deletion
    private val glHandleScratch = IntArray(2)

    // Reusable 16x16 slice buffers for allocation-free greedy meshing
    private val sliceKeysScratch = IntArray(SLICE_AREA)
    private val sliceBlocksScratch = ByteArray(SLICE_AREA)
    private val sliceLightScratch = IntArray(SLICE_AREA)

    fun initGl() {
        if (chunk.isEmpty) return
        if (opaqueIndexCount > 0) {
            initOpaqueGl()
        }
        if (transparentIndexCount > 0) {
            initTransparentGl()
        }
    }

    private fun initOpaqueGl() {
        if (opaqueVaoId == 0) {
            GLES30.glGenVertexArrays(1, glHandleScratch, 0)
            val allocatedVao = glHandleScratch[0]
            GLES30.glGenBuffers(2, glHandleScratch, 0)
            opaqueVaoId = allocatedVao
            opaqueVboId = glHandleScratch[0]
            opaqueEboId = glHandleScratch[1]
            configureVao(opaqueVaoId, opaqueVboId, opaqueEboId)
        }
    }

    private fun initTransparentGl() {
        if (transparentVaoId == 0) {
            GLES30.glGenVertexArrays(1, glHandleScratch, 0)
            val allocatedVao = glHandleScratch[0]
            GLES30.glGenBuffers(2, glHandleScratch, 0)
            transparentVaoId = allocatedVao
            transparentVboId = glHandleScratch[0]
            transparentEboId = glHandleScratch[1]
            configureVao(transparentVaoId, transparentVboId, transparentEboId)
        }
    }

    private fun releaseOpaqueGl() {
        if (opaqueVaoId != 0) {
            glHandleScratch[0] = opaqueVaoId
            GLES30.glDeleteVertexArrays(1, glHandleScratch, 0)
            opaqueVaoId = 0
        }
        if (opaqueVboId != 0 || opaqueEboId != 0) {
            glHandleScratch[0] = opaqueVboId
            glHandleScratch[1] = opaqueEboId
            GLES30.glDeleteBuffers(2, glHandleScratch, 0)
            opaqueVboId = 0
            opaqueEboId = 0
        }
    }

    private fun releaseTransparentGl() {
        if (transparentVaoId != 0) {
            glHandleScratch[0] = transparentVaoId
            GLES30.glDeleteVertexArrays(1, glHandleScratch, 0)
            transparentVaoId = 0
        }
        if (transparentVboId != 0 || transparentEboId != 0) {
            glHandleScratch[0] = transparentVboId
            glHandleScratch[1] = transparentEboId
            GLES30.glDeleteBuffers(2, glHandleScratch, 0)
            transparentVboId = 0
            transparentEboId = 0
        }
    }

    private fun configureVao(vao: Int, vbo: Int, ebo: Int) {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)

        // Single 12-byte packed uvec3 attribute
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribIPointer(0, INTS_PER_VERTEX, GLES30.GL_UNSIGNED_INT, VERTEX_STRIDE_BYTES, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Generates CPU-side greedy-meshed & cross-model geometry in 12-byte packed vertex format
     * and clears [Chunk.needsMeshRebuild]. Safe to run on background threads & in headless tests.
     */
    fun generateMeshData(world: World, texturePipeline: TexturePipeline) {
        lastTexturePipeline = texturePipeline
        val hadGeometry = opaqueIndexCount > 0 || transparentIndexCount > 0
        if (chunk.isEmpty) {
            opaqueVertexIntLength = 0
            opaqueIndexArrayLength = 0
            opaqueIndexCount = 0
            opaqueQuadCount = 0
            opaqueFacesCount = 0
            transparentVertexIntLength = 0
            transparentIndexArrayLength = 0
            transparentIndexCount = 0
            transparentQuadCount = 0
            transparentFacesCount = 0
            culledFacesCount = 0
            trimCpuArrays()
            rebuildCount++
            chunk.needsMeshRebuild = false
            hasPendingGpuUpload = hadGeometry
            return
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

        var opaqueVCount = 0
        var opaqueICount = 0
        var opaqueQuads = 0
        var opaqueFaces = 0
        var opaqueVOffset = 0
        var opaqueIOffset = 0

        var transVCount = 0
        var transICount = 0
        var transQuads = 0
        var transFaces = 0
        var transVOffset = 0
        var transIOffset = 0

        var culledCount = 0

        // 1. Greedy mesh all standard cube blocks along all 6 primary face directions
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

                        val lightBiased = (neighborRenderedLight + LIGHT_BIAS).coerceIn(0, MAX_LIGHT_BIASED)
                        val cellIdx = (v shl 4) or u
                        // Bit 31 marks active visible cell; merge key matches identical blockId + pass + lightBiased
                        val key = (1 shl 31) or
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

                // Greedy 2D rectangle merging over the 16x16 slice mask
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

                        // Clear merged rectangle
                        for (dv in 0 until h) {
                            val rowOffset = (v + dv) shl 4
                            for (du in 0 until w) {
                                sliceKeysScratch[rowOffset or (u + du)] = 0
                            }
                        }

                        val block = BlockType.fromId(sliceBlocksScratch[startIdx])
                        val lightBiased = sliceLightScratch[startIdx]
                        val tileIndex = texturePipeline.getAtlasTileIndex(block.getTextureForFace(dir))
                        val isTransparentPass = (key and (1 shl 16)) != 0

                        if (isTransparentPass) {
                            ensureTransparentCapacity(transVOffset + INTS_PER_QUAD, transIOffset + INDICES_PER_QUAD)
                            val startVert = transVCount.toShort()
                            emitGreedyCubeQuad(
                                target = rawTransparentVertices,
                                offset = transVOffset,
                                dir = dir,
                                slice = slice,
                                u0 = u,
                                v0 = v,
                                w = w,
                                h = h,
                                lightBiased = lightBiased,
                                tileIndex = tileIndex
                            )
                            transVOffset += INTS_PER_QUAD
                            rawTransparentIndices[transIOffset++] = startVert
                            rawTransparentIndices[transIOffset++] = (startVert + 1).toShort()
                            rawTransparentIndices[transIOffset++] = (startVert + 2).toShort()
                            rawTransparentIndices[transIOffset++] = startVert
                            rawTransparentIndices[transIOffset++] = (startVert + 2).toShort()
                            rawTransparentIndices[transIOffset++] = (startVert + 3).toShort()
                            transVCount += 4
                            transICount += 6
                            transQuads++
                        } else {
                            ensureOpaqueCapacity(opaqueVOffset + INTS_PER_QUAD, opaqueIOffset + INDICES_PER_QUAD)
                            val startVert = opaqueVCount.toShort()
                            emitGreedyCubeQuad(
                                target = rawOpaqueVertices,
                                offset = opaqueVOffset,
                                dir = dir,
                                slice = slice,
                                u0 = u,
                                v0 = v,
                                w = w,
                                h = h,
                                lightBiased = lightBiased,
                                tileIndex = tileIndex
                            )
                            opaqueVOffset += INTS_PER_QUAD
                            rawOpaqueIndices[opaqueIOffset++] = startVert
                            rawOpaqueIndices[opaqueIOffset++] = (startVert + 1).toShort()
                            rawOpaqueIndices[opaqueIOffset++] = (startVert + 2).toShort()
                            rawOpaqueIndices[opaqueIOffset++] = startVert
                            rawOpaqueIndices[opaqueIOffset++] = (startVert + 2).toShort()
                            rawOpaqueIndices[opaqueIOffset++] = (startVert + 3).toShort()
                            opaqueVCount += 4
                            opaqueICount += 6
                            opaqueQuads++
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

                    val tileIndex = texturePipeline.getAtlasTileIndex(block.topTexture)
                    val voxelLight = chunk.getRenderedLightUnchecked(lx, ly, lz)
                    val lightBiased = (voxelLight + LIGHT_BIAS).coerceIn(0, MAX_LIGHT_BIASED)

                    // 2 intersecting diagonal quads emitted double-sided (4 quads = 16 verts, 24 indices)
                    ensureTransparentCapacity(transVOffset + INTS_PER_QUAD * 4, transIOffset + INDICES_PER_QUAD * 4)
                    val fx = lx.toFloat()
                    val fy = ly.toFloat()
                    val fz = lz.toFloat()
                    val minOff = CROSS_MIN_OFFSET
                    val maxOff = CROSS_MAX_OFFSET

                    // Diagonal 1 front & back
                    emitDoubleSidedCrossQuad(
                        targetVerts = rawTransparentVertices,
                        vOffset = transVOffset,
                        targetIndices = rawTransparentIndices,
                        iOffset = transIOffset,
                        baseVertex = transVCount,
                        x0 = fx + minOff, y0 = fy, z0 = fz + minOff,
                        x1 = fx + maxOff, y1 = fy + 1f, z1 = fz + maxOff,
                        lightBiased = lightBiased,
                        tileIndex = tileIndex
                    )
                    transVOffset += INTS_PER_QUAD * 2
                    transIOffset += INDICES_PER_QUAD * 2
                    transVCount += 8
                    transICount += 12

                    // Diagonal 2 front & back
                    emitDoubleSidedCrossQuad(
                        targetVerts = rawTransparentVertices,
                        vOffset = transVOffset,
                        targetIndices = rawTransparentIndices,
                        iOffset = transIOffset,
                        baseVertex = transVCount,
                        x0 = fx + minOff, y0 = fy, z0 = fz + maxOff,
                        x1 = fx + maxOff, y1 = fy + 1f, z1 = fz + minOff,
                        lightBiased = lightBiased,
                        tileIndex = tileIndex
                    )
                    transVOffset += INTS_PER_QUAD * 2
                    transIOffset += INDICES_PER_QUAD * 2
                    transVCount += 8
                    transICount += 12

                    transQuads += 4
                    transFaces += 4
                }
            }
        }

        opaqueVertexIntLength = opaqueVOffset
        opaqueIndexArrayLength = opaqueIOffset
        opaqueIndexCount = opaqueICount
        opaqueQuadCount = opaqueQuads
        opaqueFacesCount = opaqueFaces

        transparentVertexIntLength = transVOffset
        transparentIndexArrayLength = transIOffset
        transparentIndexCount = transICount
        transparentQuadCount = transQuads
        transparentFacesCount = transFaces

        culledFacesCount = culledCount
        rebuildCount++
        chunk.needsMeshRebuild = false
        hasPendingGpuUpload = (opaqueICount > 0 || transICount > 0 || hadGeometry)
    }

    /**
     * Returns a snapshot of generated (u, v) atlas pairs from current CPU vertex data (for tests
     * before [uploadCpuMeshToGpu] trims CPU arrays).
     */
    fun getGeneratedUvs(transparentPass: Boolean): List<Pair<Float, Float>> {
        val arr = if (transparentPass) rawTransparentVertices else rawOpaqueVertices
        val intLen = if (transparentPass) transparentVertexIntLength else opaqueVertexIntLength
        val pipeline = lastTexturePipeline
        val result = ArrayList<Pair<Float, Float>>(intLen / INTS_PER_VERTEX)
        var i = 0
        while (i + 2 < intLen) {
            val w1 = arr[i + 1]
            val w2 = arr[i + 2]
            val tileU = (w1 ushr 19) and 0x1F
            val tileV = (w1 ushr 24) and 0x1F
            val tileIdx = w2 and 0xFF
            if (pipeline != null) {
                val rect = pipeline.getAtlasUv(pipeline.getAtlasTileIdByIndex(tileIdx))
                val uNorm = tileU.toFloat().coerceIn(0f, 1f)
                val vNorm = tileV.toFloat().coerceIn(0f, 1f)
                val u = rect.uMin + (rect.uMax - rect.uMin) * uNorm
                val v = rect.vMin + (rect.vMax - rect.vMin) * vNorm
                result.add(Pair(u, v))
            } else {
                result.add(Pair(tileU.toFloat(), tileV.toFloat()))
            }
            i += INTS_PER_VERTEX
        }
        return result
    }

    /**
     * Uploads pre-built packed CPU mesh buffers to OpenGL ES 3.0 VBO/EBOs using a shared
     * [ThreadLocal] native staging buffer, then trims CPU working arrays to keep heap lean.
     */
    fun uploadCpuMeshToGpu() {
        if (opaqueIndexCount > 0 && opaqueVertexIntLength > 0) {
            if (opaqueVaoId == 0) {
                initOpaqueGl()
            }
            if (opaqueVboId != 0 && opaqueEboId != 0) {
                uploadPassToGpu(
                    vboId = opaqueVboId,
                    eboId = opaqueEboId,
                    vertices = rawOpaqueVertices,
                    vertexIntLength = opaqueVertexIntLength,
                    indices = rawOpaqueIndices,
                    indexLength = opaqueIndexArrayLength
                )
            }
        } else if (hasOpaqueGpuBuffers) {
            releaseOpaqueGl()
        }

        if (transparentIndexCount > 0 && transparentVertexIntLength > 0) {
            if (transparentVaoId == 0) {
                initTransparentGl()
            }
            if (transparentVboId != 0 && transparentEboId != 0) {
                uploadPassToGpu(
                    vboId = transparentVboId,
                    eboId = transparentEboId,
                    vertices = rawTransparentVertices,
                    vertexIntLength = transparentVertexIntLength,
                    indices = rawTransparentIndices,
                    indexLength = transparentIndexArrayLength
                )
            }
        } else if (hasTransparentGpuBuffers) {
            releaseTransparentGl()
        }

        trimCpuArrays()
        hasPendingGpuUpload = false
    }

    private fun trimCpuArrays() {
        rawOpaqueVertices = EMPTY_INT_ARRAY
        rawOpaqueIndices = EMPTY_SHORT_ARRAY
        opaqueVertexIntLength = 0
        opaqueIndexArrayLength = 0

        rawTransparentVertices = EMPTY_INT_ARRAY
        rawTransparentIndices = EMPTY_SHORT_ARRAY
        transparentVertexIntLength = 0
        transparentIndexArrayLength = 0
    }

    /**
     * Builds or rebuilds chunk mesh geometry and uploads Pass 1 & Pass 2 buffers to the GPU.
     */
    fun buildMesh(world: World, texturePipeline: TexturePipeline) {
        generateMeshData(world, texturePipeline)
        uploadCpuMeshToGpu()
    }

    fun rebuildMesh(world: World, texturePipeline: TexturePipeline) {
        buildMesh(world, texturePipeline)
    }

    private fun uploadPassToGpu(
        vboId: Int,
        eboId: Int,
        vertices: IntArray,
        vertexIntLength: Int,
        indices: ShortArray,
        indexLength: Int
    ) {
        val staging = threadLocalStaging.get()!!
        val vBytes = vertexIntLength * 4
        val vBuf = staging.ensureVertexBuffer(vBytes)
        vBuf.clear()
        vBuf.put(vertices, 0, vertexIntLength)
        vBuf.flip()

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vBytes, vBuf, GLES30.GL_STATIC_DRAW)

        val iBytes = indexLength * 2
        val iBuf = staging.ensureIndexBuffer(iBytes)
        iBuf.clear()
        iBuf.put(indices, 0, indexLength)
        iBuf.flip()

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iBytes, iBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun ensureOpaqueCapacity(neededInts: Int, neededIndices: Int) {
        if (neededInts > rawOpaqueVertices.size) {
            val minGrow = maxOf(neededInts, 128 * INTS_PER_QUAD)
            rawOpaqueVertices = rawOpaqueVertices.copyOf(maxOf(minGrow, rawOpaqueVertices.size * 2))
        }
        if (neededIndices > rawOpaqueIndices.size) {
            val minGrow = maxOf(neededIndices, 128 * INDICES_PER_QUAD)
            rawOpaqueIndices = rawOpaqueIndices.copyOf(maxOf(minGrow, rawOpaqueIndices.size * 2))
        }
    }

    private fun ensureTransparentCapacity(neededInts: Int, neededIndices: Int) {
        if (neededInts > rawTransparentVertices.size) {
            val minGrow = maxOf(neededInts, 64 * INTS_PER_QUAD)
            rawTransparentVertices = rawTransparentVertices.copyOf(maxOf(minGrow, rawTransparentVertices.size * 2))
        }
        if (neededIndices > rawTransparentIndices.size) {
            val minGrow = maxOf(neededIndices, 64 * INDICES_PER_QUAD)
            rawTransparentIndices = rawTransparentIndices.copyOf(maxOf(minGrow, rawTransparentIndices.size * 2))
        }
    }

    private fun emitGreedyCubeQuad(
        target: IntArray,
        offset: Int,
        dir: Direction,
        slice: Int,
        u0: Int,
        v0: Int,
        w: Int,
        h: Int,
        lightBiased: Int,
        tileIndex: Int
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
                packVertex(target, o, fx0, fy, fz1, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx1, fy, fz1, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx1, fy, fz0, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx0, fy, fz0, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte)
            }
            Direction.DOWN -> {
                val fy = slice.toFloat()
                val fx0 = u0.toFloat()
                val fx1 = (u0 + w).toFloat()
                val fz0 = v0.toFloat()
                val fz1 = (v0 + h).toFloat()
                packVertex(target, o, fx0, fy, fz0, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx1, fy, fz0, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx1, fy, fz1, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx0, fy, fz1, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte)
            }
            Direction.NORTH -> {
                val fz = slice.toFloat()
                val fx0 = u0.toFloat()
                val fx1 = (u0 + w).toFloat()
                val fy0 = v0.toFloat()
                val fy1 = (v0 + h).toFloat()
                packVertex(target, o, fx1, fy0, fz, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx0, fy0, fz, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx0, fy1, fz, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx1, fy1, fz, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte)
            }
            Direction.SOUTH -> {
                val fz = (slice + 1).toFloat()
                val fx0 = u0.toFloat()
                val fx1 = (u0 + w).toFloat()
                val fy0 = v0.toFloat()
                val fy1 = (v0 + h).toFloat()
                packVertex(target, o, fx0, fy0, fz, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx1, fy0, fz, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx1, fy1, fz, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx0, fy1, fz, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte)
            }
            Direction.WEST -> {
                val fx = slice.toFloat()
                val fy0 = v0.toFloat()
                val fy1 = (v0 + h).toFloat()
                val fz0 = u0.toFloat()
                val fz1 = (u0 + w).toFloat()
                packVertex(target, o, fx, fy0, fz0, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx, fy0, fz1, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx, fy1, fz1, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx, fy1, fz0, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte)
            }
            Direction.EAST -> {
                val fx = (slice + 1).toFloat()
                val fy0 = v0.toFloat()
                val fy1 = (v0 + h).toFloat()
                val fz0 = u0.toFloat()
                val fz1 = (u0 + w).toFloat()
                packVertex(target, o, fx, fy0, fz1, normIdx, 0, lightBiased, 0, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx, fy0, fz0, normIdx, 0, lightBiased, w, h, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx, fy1, fz0, normIdx, 0, lightBiased, w, 0, tileIndex, shadeByte); o += INTS_PER_VERTEX
                packVertex(target, o, fx, fy1, fz1, normIdx, 0, lightBiased, 0, 0, tileIndex, shadeByte)
            }
        }
    }

    private fun emitDoubleSidedCrossQuad(
        targetVerts: IntArray,
        vOffset: Int,
        targetIndices: ShortArray,
        iOffset: Int,
        baseVertex: Int,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        lightBiased: Int,
        tileIndex: Int
    ) {
        val normIdx = CROSS_NORMAL_INDEX
        val flags = CROSS_FLAG
        val shadeByte = 255
        var vo = vOffset

        // Front side quad (CCW)
        packVertex(targetVerts, vo, x0, y0, z0, normIdx, flags, lightBiased, 0, 1, tileIndex, shadeByte); vo += INTS_PER_VERTEX
        packVertex(targetVerts, vo, x1, y0, z1, normIdx, flags, lightBiased, 1, 1, tileIndex, shadeByte); vo += INTS_PER_VERTEX
        packVertex(targetVerts, vo, x1, y1, z1, normIdx, flags, lightBiased, 1, 0, tileIndex, shadeByte); vo += INTS_PER_VERTEX
        packVertex(targetVerts, vo, x0, y1, z0, normIdx, flags, lightBiased, 0, 0, tileIndex, shadeByte); vo += INTS_PER_VERTEX

        // Back side quad (opposite winding so both sides render cleanly even with GL_BACK culling)
        packVertex(targetVerts, vo, x1, y0, z1, normIdx, flags, lightBiased, 0, 1, tileIndex, shadeByte); vo += INTS_PER_VERTEX
        packVertex(targetVerts, vo, x0, y0, z0, normIdx, flags, lightBiased, 1, 1, tileIndex, shadeByte); vo += INTS_PER_VERTEX
        packVertex(targetVerts, vo, x0, y1, z0, normIdx, flags, lightBiased, 1, 0, tileIndex, shadeByte); vo += INTS_PER_VERTEX
        packVertex(targetVerts, vo, x1, y1, z1, normIdx, flags, lightBiased, 0, 0, tileIndex, shadeByte)

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
        target: IntArray,
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
        shadeByte: Int
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

        val word2 = (tileIndex and 0xFF) or
            ((shadeByte and 0xFF) shl 8)

        target[offset] = word0
        target[offset + 1] = word1
        target[offset + 2] = word2
    }

    fun renderOpaque() {
        if (opaqueIndexCount <= 0 || opaqueVaoId == 0) return
        GLES30.glBindVertexArray(opaqueVaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, opaqueIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun renderTransparent() {
        if (transparentIndexCount <= 0 || transparentVaoId == 0) return
        GLES30.glBindVertexArray(transparentVaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, transparentIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun render() {
        renderOpaque()
        renderTransparent()
    }

    fun release() {
        releaseOpaqueGl()
        releaseTransparentGl()
        trimCpuArrays()

        opaqueIndexCount = 0
        transparentIndexCount = 0
        opaqueQuadCount = 0
        transparentQuadCount = 0
        opaqueFacesCount = 0
        transparentFacesCount = 0
    }

    private class StagingScratch {
        private var vertexByteBuf: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_STAGING_V_BYTES).order(ByteOrder.nativeOrder())
        private var vertexIntView = vertexByteBuf.asIntBuffer()
        private var indexByteBuf: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_STAGING_I_BYTES).order(ByteOrder.nativeOrder())
        private var indexShortView = indexByteBuf.asShortBuffer()

        fun ensureVertexBuffer(neededBytes: Int): java.nio.IntBuffer {
            if (vertexByteBuf.capacity() < neededBytes) {
                val nextCap = maxOf(neededBytes, vertexByteBuf.capacity() * 2)
                vertexByteBuf = ByteBuffer.allocateDirect(nextCap).order(ByteOrder.nativeOrder())
                vertexIntView = vertexByteBuf.asIntBuffer()
            }
            return vertexIntView
        }

        fun ensureIndexBuffer(neededBytes: Int): java.nio.ShortBuffer {
            if (indexByteBuf.capacity() < neededBytes) {
                val nextCap = maxOf(neededBytes, indexByteBuf.capacity() * 2)
                indexByteBuf = ByteBuffer.allocateDirect(nextCap).order(ByteOrder.nativeOrder())
                indexShortView = indexByteBuf.asShortBuffer()
            }
            return indexShortView
        }
    }

    companion object {
        const val INTS_PER_VERTEX = 3
        const val VERTEX_STRIDE_BYTES = INTS_PER_VERTEX * 4 // 12 bytes
        const val INTS_PER_QUAD = INTS_PER_VERTEX * 4
        const val INDICES_PER_QUAD = 6
        private const val SLICE_AREA = Chunk.SIZE * Chunk.SIZE
        private const val POS_QUANT_SCALE = 256f
        private const val LIGHT_BIAS = 16
        private const val MAX_LIGHT_BIASED = 32
        private const val CROSS_NORMAL_INDEX = 6
        private const val CROSS_FLAG = 1
        private const val CROSS_MIN_OFFSET = 0.15f
        private const val CROSS_MAX_OFFSET = 0.85f
        private const val INITIAL_STAGING_V_BYTES = 65_536
        private const val INITIAL_STAGING_I_BYTES = 32_768

        private val EMPTY_INT_ARRAY = IntArray(0)
        private val EMPTY_SHORT_ARRAY = ShortArray(0)
        private val DIRECTIONS = Direction.values()

        private val threadLocalStaging = ThreadLocal.withInitial { StagingScratch() }

        /**
         * Face culling rules:
         * - Transparent blocks never cull adjacent opaque block faces.
         * - Transparent blocks cull faces against opaque neighbors and identical transparent blocks.
         */
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
