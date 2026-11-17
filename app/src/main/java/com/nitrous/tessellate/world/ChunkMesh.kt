package com.nitrous.tessellate.world

import android.opengl.GLES30
import com.nitrous.tessellate.texture.TexturePipeline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * High-performance two-pass mesh generator and GPU buffer container for a 16x16x16 chunk.
 * - Pass 1: Opaque geometry (Dirt, Stone, Grass, etc.)
 * - Pass 2: Transparent geometry (Glass, Water, Leaves, etc.)
 *
 * Implements dirty-chunk gating via [Chunk.needsMeshRebuild], hidden face culling rules
 * for opaque/transparent boundaries, reusable GPU buffers, and CCW outward winding.
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

    // Vertex layout: Pos (3) + Normal (3) + UV (2) + Shade (1) = 9 floats = 36 bytes
    private val vertexStrideBytes = 9 * 4

    // Reusable direct native buffers to prevent GC thrashing during mesh rebuilds
    private var opaqueVertexBuffer: FloatBuffer? = null
    private var opaqueIndexBuffer: ShortBuffer? = null
    private var transparentVertexBuffer: FloatBuffer? = null
    private var transparentIndexBuffer: ShortBuffer? = null

    // Working CPU arrays lazily grown only for non-empty visible faces (Pass 1 Opaque & Pass 2 Transparent)
    private var rawOpaqueVertices = FloatArray(0)
    private var rawOpaqueIndices = ShortArray(0)
    private var opaqueVertexFloatLength: Int = 0
    private var opaqueIndexArrayLength: Int = 0

    private var rawTransparentVertices = FloatArray(0)
    private var rawTransparentIndices = ShortArray(0)
    private var transparentVertexFloatLength: Int = 0
    private var transparentIndexArrayLength: Int = 0

    fun initGl() {
        if (chunk.isEmpty) return
        initOpaqueGl()
        initTransparentGl()
    }

    private fun initOpaqueGl() {
        if (opaqueVaoId == 0) {
            val vao = IntArray(1)
            val buffers = IntArray(2)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glGenBuffers(2, buffers, 0)
            opaqueVaoId = vao[0]
            opaqueVboId = buffers[0]
            opaqueEboId = buffers[1]
            configureVao(opaqueVaoId, opaqueVboId, opaqueEboId)
        }
    }

    private fun initTransparentGl() {
        if (transparentVaoId == 0) {
            val vao = IntArray(1)
            val buffers = IntArray(2)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glGenBuffers(2, buffers, 0)
            transparentVaoId = vao[0]
            transparentVboId = buffers[0]
            transparentEboId = buffers[1]
            configureVao(transparentVaoId, transparentVboId, transparentEboId)
        }
    }

    private fun configureVao(vao: Int, vbo: Int, ebo: Int) {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)

        // Attrib 0: aPosition (vec3)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, vertexStrideBytes, 0)

        // Attrib 1: aNormal (vec3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, vertexStrideBytes, 3 * 4)

        // Attrib 2: aTexCoord (vec2)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, vertexStrideBytes, 6 * 4)

        // Attrib 3: aShade (float)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, vertexStrideBytes, 8 * 4)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Generates CPU-side opaque and transparent mesh geometry from the voxel world
     * and clears [Chunk.needsMeshRebuild]. Safe to run in background threads & headless unit tests.
     */
    fun generateMeshData(world: World, texturePipeline: TexturePipeline) {
        val hadGeometry = opaqueIndexCount > 0 || transparentIndexCount > 0
        if (chunk.isEmpty) {
            opaqueVertexFloatLength = 0
            opaqueIndexArrayLength = 0
            opaqueIndexCount = 0
            opaqueFacesCount = 0
            transparentVertexFloatLength = 0
            transparentIndexArrayLength = 0
            transparentIndexCount = 0
            transparentFacesCount = 0
            culledFacesCount = 0
            rebuildCount++
            chunk.needsMeshRebuild = false
            hasPendingGpuUpload = hadGeometry
            return
        }

        // Fetch the 6 immediate neighbor chunks once before the 4,096-block loop
        // so interior and boundary face-culling checks incur zero chunkLock/ChunkPos overhead.
        val cx = chunk.chunkX
        val cy = chunk.chunkY
        val cz = chunk.chunkZ
        val nXNeg = world.getChunk(cx - 1, cy, cz)
        val nXPos = world.getChunk(cx + 1, cy, cz)
        val nYNeg = world.getChunk(cx, cy - 1, cz)
        val nYPos = world.getChunk(cx, cy + 1, cz)
        val nZNeg = world.getChunk(cx, cy, cz - 1)
        val nZPos = world.getChunk(cx, cy, cz + 1)

        val baseX = cx * Chunk.SIZE
        val baseY = cy * Chunk.SIZE
        val baseZ = cz * Chunk.SIZE
        val air = BlockType.AIR
        val airId = air.id

        var opaqueVCount = 0
        var opaqueICount = 0
        var opaqueFaces = 0
        var opaqueVOffset = 0
        var opaqueIOffset = 0

        var transVCount = 0
        var transICount = 0
        var transFaces = 0
        var transVOffset = 0
        var transIOffset = 0

        var culledCount = 0

        for (ly in 0 until Chunk.SIZE) {
            val wy = baseY + ly
            val fy = wy.toFloat()
            for (lz in 0 until Chunk.SIZE) {
                val wz = baseZ + lz
                val fz = wz.toFloat()
                for (lx in 0 until Chunk.SIZE) {
                    val blockId = chunk.getBlockIdUnchecked(lx, ly, lz)
                    if (blockId == airId) continue
                    val block = BlockType.fromId(blockId)
                    if (!block.isOpaque && !block.isTransparent) continue

                    val isTransparentPass = block.isTransparent
                    val fx = (baseX + lx).toFloat()

                    for (dIdx in 0 until DIRECTIONS.size) {
                        val dir = DIRECTIONS[dIdx]
                        val nlx = lx + dir.dx
                        val nly = ly + dir.dy
                        val nlz = lz + dir.dz

                        val neighborId: Byte = if ((nlx or nly or nlz) and -16 == 0) {
                            chunk.getBlockIdUnchecked(nlx, nly, nlz)
                        } else {
                            val neighborChunk = when (dir) {
                                Direction.WEST -> nXNeg
                                Direction.EAST -> nXPos
                                Direction.DOWN -> nYNeg
                                Direction.UP -> nYPos
                                Direction.NORTH -> nZNeg
                                Direction.SOUTH -> nZPos
                            }
                            neighborChunk?.getBlockIdUnchecked(nlx and Chunk.MASK, nly and Chunk.MASK, nlz and Chunk.MASK) ?: airId
                        }

                        val neighbor = if (neighborId == airId) air else BlockType.fromId(neighborId)
                        if (!shouldRenderFace(block, neighbor)) {
                            culledCount++
                            continue
                        }

                        val textureId = block.getTextureForFace(dir)
                        val uv = texturePipeline.getAtlasUv(textureId)

                        if (isTransparentPass) {
                            ensureTransparentCapacity(transVOffset + 36, transIOffset + 6)
                            val startVertexShort = transVCount.toShort()
                            emitFaceVertices(
                                rawTransparentVertices,
                                transVOffset,
                                dir,
                                fx, fy, fz,
                                uv.uMin, uv.vMin, uv.uMax, uv.vMax
                            )
                            transVOffset += 36

                            rawTransparentIndices[transIOffset++] = startVertexShort
                            rawTransparentIndices[transIOffset++] = (startVertexShort + 1).toShort()
                            rawTransparentIndices[transIOffset++] = (startVertexShort + 2).toShort()
                            rawTransparentIndices[transIOffset++] = startVertexShort
                            rawTransparentIndices[transIOffset++] = (startVertexShort + 2).toShort()
                            rawTransparentIndices[transIOffset++] = (startVertexShort + 3).toShort()

                            transVCount += 4
                            transICount += 6
                            transFaces++
                        } else {
                            ensureOpaqueCapacity(opaqueVOffset + 36, opaqueIOffset + 6)
                            val startVertexShort = opaqueVCount.toShort()
                            emitFaceVertices(
                                rawOpaqueVertices,
                                opaqueVOffset,
                                dir,
                                fx, fy, fz,
                                uv.uMin, uv.vMin, uv.uMax, uv.vMax
                            )
                            opaqueVOffset += 36

                            rawOpaqueIndices[opaqueIOffset++] = startVertexShort
                            rawOpaqueIndices[opaqueIOffset++] = (startVertexShort + 1).toShort()
                            rawOpaqueIndices[opaqueIOffset++] = (startVertexShort + 2).toShort()
                            rawOpaqueIndices[opaqueIOffset++] = startVertexShort
                            rawOpaqueIndices[opaqueIOffset++] = (startVertexShort + 2).toShort()
                            rawOpaqueIndices[opaqueIOffset++] = (startVertexShort + 3).toShort()

                            opaqueVCount += 4
                            opaqueICount += 6
                            opaqueFaces++
                        }
                    }
                }
            }
        }

        opaqueVertexFloatLength = opaqueVOffset
        opaqueIndexArrayLength = opaqueIOffset
        opaqueIndexCount = opaqueICount
        opaqueFacesCount = opaqueFaces

        transparentVertexFloatLength = transVOffset
        transparentIndexArrayLength = transIOffset
        transparentIndexCount = transICount
        transparentFacesCount = transFaces

        culledFacesCount = culledCount
        rebuildCount++
        chunk.needsMeshRebuild = false
        hasPendingGpuUpload = (opaqueICount > 0 || transICount > 0 || hadGeometry)
    }

    /**
     * Returns a snapshot of generated (u, v) vertex pairs for opaque or transparent pass inspection.
     */
    fun getGeneratedUvs(transparentPass: Boolean): List<Pair<Float, Float>> {
        val arr = if (transparentPass) rawTransparentVertices else rawOpaqueVertices
        val floatLen = if (transparentPass) transparentVertexFloatLength else opaqueVertexFloatLength
        val result = ArrayList<Pair<Float, Float>>(floatLen / 9)
        var i = 0
        while (i + 8 < floatLen) {
            result.add(Pair(arr[i + 6], arr[i + 7]))
            i += 9
        }
        return result
    }

    /**
     * Uploads pre-built CPU mesh buffers to OpenGL ES 3.0 VBO/EBOs on the GL thread.
     */
    fun uploadCpuMeshToGpu() {
        if (opaqueIndexCount <= 0 && transparentIndexCount <= 0) {
            hasPendingGpuUpload = false
            return
        }

        if (opaqueIndexCount > 0) {
            if (opaqueVaoId == 0) {
                initOpaqueGl()
            }
            if (opaqueVboId != 0 && opaqueEboId != 0) {
                uploadPassToGpu(
                    vboId = opaqueVboId,
                    eboId = opaqueEboId,
                    vertices = rawOpaqueVertices,
                    vertexFloatLength = opaqueVertexFloatLength,
                    indices = rawOpaqueIndices,
                    indexLength = opaqueIndexArrayLength,
                    isTransparent = false
                )
            }
        }

        if (transparentIndexCount > 0) {
            if (transparentVaoId == 0) {
                initTransparentGl()
            }
            if (transparentVboId != 0 && transparentEboId != 0) {
                uploadPassToGpu(
                    vboId = transparentVboId,
                    eboId = transparentEboId,
                    vertices = rawTransparentVertices,
                    vertexFloatLength = transparentVertexFloatLength,
                    indices = rawTransparentIndices,
                    indexLength = transparentIndexArrayLength,
                    isTransparent = true
                )
            }
        }

        hasPendingGpuUpload = false
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
        vertices: FloatArray,
        vertexFloatLength: Int,
        indices: ShortArray,
        indexLength: Int,
        isTransparent: Boolean
    ) {
        var vBuf = if (isTransparent) transparentVertexBuffer else opaqueVertexBuffer
        if (vBuf == null || vBuf.capacity() < vertexFloatLength) {
            vBuf = ByteBuffer.allocateDirect(vertexFloatLength * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            if (isTransparent) transparentVertexBuffer = vBuf else opaqueVertexBuffer = vBuf
        }
        vBuf.clear()
        vBuf.put(vertices, 0, vertexFloatLength)
        vBuf.position(0)

        var iBuf = if (isTransparent) transparentIndexBuffer else opaqueIndexBuffer
        if (iBuf == null || iBuf.capacity() < indexLength) {
            iBuf = ByteBuffer.allocateDirect(indexLength * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
            if (isTransparent) transparentIndexBuffer = iBuf else opaqueIndexBuffer = iBuf
        }
        iBuf.clear()
        iBuf.put(indices, 0, indexLength)
        iBuf.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexFloatLength * 4, vBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexLength * 2, iBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun ensureOpaqueCapacity(neededFloats: Int, neededIndices: Int) {
        if (neededFloats > rawOpaqueVertices.size) {
            val minGrow = maxOf(neededFloats, 128 * 36)
            rawOpaqueVertices = rawOpaqueVertices.copyOf(maxOf(minGrow, rawOpaqueVertices.size * 2))
        }
        if (neededIndices > rawOpaqueIndices.size) {
            val minGrow = maxOf(neededIndices, 128 * 6)
            rawOpaqueIndices = rawOpaqueIndices.copyOf(maxOf(minGrow, rawOpaqueIndices.size * 2))
        }
    }

    private fun ensureTransparentCapacity(neededFloats: Int, neededIndices: Int) {
        if (neededFloats > rawTransparentVertices.size) {
            val minGrow = maxOf(neededFloats, 64 * 36)
            rawTransparentVertices = rawTransparentVertices.copyOf(maxOf(minGrow, rawTransparentVertices.size * 2))
        }
        if (neededIndices > rawTransparentIndices.size) {
            val minGrow = maxOf(neededIndices, 64 * 6)
            rawTransparentIndices = rawTransparentIndices.copyOf(maxOf(minGrow, rawTransparentIndices.size * 2))
        }
    }

    private fun emitFaceVertices(
        target: FloatArray,
        offset: Int,
        dir: Direction,
        fx: Float, fy: Float, fz: Float,
        uMin: Float, vMin: Float, uMax: Float, vMax: Float
    ) {
        var o = offset
        val shade = dir.faceShade
        when (dir) {
            Direction.UP -> {
                addVertex(target, o, fx, fy + 1f, fz + 1f, 0f, 1f, 0f, uMin, vMax, shade); o += 9
                addVertex(target, o, fx + 1f, fy + 1f, fz + 1f, 0f, 1f, 0f, uMax, vMax, shade); o += 9
                addVertex(target, o, fx + 1f, fy + 1f, fz, 0f, 1f, 0f, uMax, vMin, shade); o += 9
                addVertex(target, o, fx, fy + 1f, fz, 0f, 1f, 0f, uMin, vMin, shade)
            }
            Direction.DOWN -> {
                addVertex(target, o, fx, fy, fz, 0f, -1f, 0f, uMin, vMax, shade); o += 9
                addVertex(target, o, fx + 1f, fy, fz, 0f, -1f, 0f, uMax, vMax, shade); o += 9
                addVertex(target, o, fx + 1f, fy, fz + 1f, 0f, -1f, 0f, uMax, vMin, shade); o += 9
                addVertex(target, o, fx, fy, fz + 1f, 0f, -1f, 0f, uMin, vMin, shade)
            }
            Direction.NORTH -> {
                addVertex(target, o, fx + 1f, fy, fz, 0f, 0f, -1f, uMin, vMax, shade); o += 9
                addVertex(target, o, fx, fy, fz, 0f, 0f, -1f, uMax, vMax, shade); o += 9
                addVertex(target, o, fx, fy + 1f, fz, 0f, 0f, -1f, uMax, vMin, shade); o += 9
                addVertex(target, o, fx + 1f, fy + 1f, fz, 0f, 0f, -1f, uMin, vMin, shade)
            }
            Direction.SOUTH -> {
                addVertex(target, o, fx, fy, fz + 1f, 0f, 0f, 1f, uMin, vMax, shade); o += 9
                addVertex(target, o, fx + 1f, fy, fz + 1f, 0f, 0f, 1f, uMax, vMax, shade); o += 9
                addVertex(target, o, fx + 1f, fy + 1f, fz + 1f, 0f, 0f, 1f, uMax, vMin, shade); o += 9
                addVertex(target, o, fx, fy + 1f, fz + 1f, 0f, 0f, 1f, uMin, vMin, shade)
            }
            Direction.WEST -> {
                addVertex(target, o, fx, fy, fz, -1f, 0f, 0f, uMin, vMax, shade); o += 9
                addVertex(target, o, fx, fy, fz + 1f, -1f, 0f, 0f, uMax, vMax, shade); o += 9
                addVertex(target, o, fx, fy + 1f, fz + 1f, -1f, 0f, 0f, uMax, vMin, shade); o += 9
                addVertex(target, o, fx, fy + 1f, fz, -1f, 0f, 0f, uMin, vMin, shade)
            }
            Direction.EAST -> {
                addVertex(target, o, fx + 1f, fy, fz + 1f, 1f, 0f, 0f, uMin, vMax, shade); o += 9
                addVertex(target, o, fx + 1f, fy, fz, 1f, 0f, 0f, uMax, vMax, shade); o += 9
                addVertex(target, o, fx + 1f, fy + 1f, fz, 1f, 0f, 0f, uMax, vMin, shade); o += 9
                addVertex(target, o, fx + 1f, fy + 1f, fz + 1f, 1f, 0f, 0f, uMin, vMin, shade)
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun addVertex(
        target: FloatArray,
        offset: Int,
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        u: Float, v: Float,
        shade: Float
    ) {
        target[offset + 0] = x
        target[offset + 1] = y
        target[offset + 2] = z
        target[offset + 3] = nx
        target[offset + 4] = ny
        target[offset + 5] = nz
        target[offset + 6] = u
        target[offset + 7] = v
        target[offset + 8] = shade
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
        if (opaqueVaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(opaqueVaoId), 0)
            opaqueVaoId = 0
        }
        if (opaqueVboId != 0 || opaqueEboId != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(opaqueVboId, opaqueEboId), 0)
            opaqueVboId = 0
            opaqueEboId = 0
        }

        if (transparentVaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(transparentVaoId), 0)
            transparentVaoId = 0
        }
        if (transparentVboId != 0 || transparentEboId != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(transparentVboId, transparentEboId), 0)
            transparentVboId = 0
            transparentEboId = 0
        }

        opaqueIndexCount = 0
        transparentIndexCount = 0
        opaqueFacesCount = 0
        transparentFacesCount = 0
        opaqueVertexBuffer = null
        opaqueIndexBuffer = null
        transparentVertexBuffer = null
        transparentIndexBuffer = null
    }

    companion object {
        private val DIRECTIONS = Direction.values()

        /**
         * Face culling rules:
         * - Transparent blocks never cull adjacent opaque block faces.
         * - Transparent blocks cull faces against opaque neighbors and identical transparent blocks
         *   (e.g., glass next to glass emits no interior shared face).
         */
        fun shouldRenderFace(block: BlockType, neighbor: BlockType): Boolean {
            if (block == BlockType.AIR) return false
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
