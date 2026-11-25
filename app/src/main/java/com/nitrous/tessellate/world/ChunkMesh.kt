package com.nitrous.tessellate.world

import android.opengl.GLES30
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.texture.TexturePipeline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * OpenGL ES 3.0 GPU buffer container for a 16x16x16 chunk.
 *
 * Architecture (TDD-022.1):
 * - CPU geometry generation (greedy meshing + cross-models + raw [FloatArray] output) runs in
 *   `:core` via [ChunkGeometryBuilder] and [ParallelChunkMesher].
 * - Direct staging buffer pooling + 1/tick Flood GC runs in `:core` via [NativeBufferPool].
 * - [ChunkMesh] in `:app` receives raw [FloatArray] / [ShortArray] buffers and uploads them to
 *   OpenGL VBO/EBOs.
 */
class ChunkMesh(
    val chunk: Chunk
) : PrebuiltChunkMesh {
    /** World-space bounding box of this 16x16x16 chunk for frustum culling. */
    val worldBounds: AABB = AABB(
        minX = (chunk.chunkX * Chunk.SIZE).toDouble(),
        minY = (chunk.chunkY * Chunk.SIZE).toDouble(),
        minZ = (chunk.chunkZ * Chunk.SIZE).toDouble(),
        maxX = ((chunk.chunkX + 1) * Chunk.SIZE).toDouble(),
        maxY = ((chunk.chunkY + 1) * Chunk.SIZE).toDouble(),
        maxZ = ((chunk.chunkZ + 1) * Chunk.SIZE).toDouble()
    )

    val originX: Double get() = worldBounds.minX
    val originY: Double get() = worldBounds.minY
    val originZ: Double get() = worldBounds.minZ

    val centerX: Double get() = worldBounds.minX + 8.0
    val centerY: Double get() = worldBounds.minY + 8.0
    val centerZ: Double get() = worldBounds.minZ + 8.0

    @JvmField
    var sortDistSq: Double = 0.0

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

    var opaqueFacesCount: Int = 0
        private set
    var transparentFacesCount: Int = 0
        private set
    val generatedFacesCount: Int
        get() = opaqueFacesCount + transparentFacesCount

    var culledFacesCount: Int = 0
        private set

    var rebuildCount: Int = 0
        private set

    @Volatile
    var hasPendingGpuUpload: Boolean = false
        private set

    // Raw FloatArray / ShortArray payloads received from :core builders/workers, trimmed on upload
    private var rawOpaqueVertices = ChunkGeometryBuilder.EMPTY_FLOAT_ARRAY
    private var rawOpaqueIndices = ChunkGeometryBuilder.EMPTY_SHORT_ARRAY
    private var opaqueVertexFloatLength: Int = 0
    private var opaqueIndexArrayLength: Int = 0

    private var rawTransparentVertices = ChunkGeometryBuilder.EMPTY_FLOAT_ARRAY
    private var rawTransparentIndices = ChunkGeometryBuilder.EMPTY_SHORT_ARRAY
    private var transparentVertexFloatLength: Int = 0
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

    private val glHandleScratch = IntArray(2)

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

        // Single 12-byte packed uvec3 attribute (fed as 3 raw floats / 12 bytes)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribIPointer(0, FLOATS_PER_VERTEX, GLES30.GL_UNSIGNED_INT, VERTEX_STRIDE_BYTES, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Generates CPU-side geometry synchronously via `:core`'s [ChunkGeometryBuilder] and stores
     * the resulting raw [FloatArray] vertex buffers pending OpenGL upload.
     */
    fun generateMeshData(world: World, texturePipeline: TexturePipeline) {
        val builder = threadLocalGeometryBuilder.get() ?: ChunkGeometryBuilder()
        val output = builder.buildChunkGeometry(world, chunk, texturePipeline)
        applyMeshOutput(output, texturePipeline)
    }

    /**
     * Applies pre-computed raw [FloatArray] geometry emitted by `:core`'s [ParallelChunkMesher]
     * worker threads without performing geometry math on the GL thread.
     */
    fun applyMeshOutput(output: ChunkMeshOutput, texturePipeline: TexturePipeline? = null) {
        if (texturePipeline != null) {
            lastTexturePipeline = texturePipeline
        }
        val hadGeometry = opaqueIndexCount > 0 || transparentIndexCount > 0

        rawOpaqueVertices = output.opaqueVertices
        opaqueVertexFloatLength = output.opaqueVertexFloatLength
        rawOpaqueIndices = output.opaqueIndices
        opaqueIndexArrayLength = output.opaqueIndexLength
        opaqueIndexCount = output.opaqueIndexLength
        opaqueQuadCount = output.opaqueQuadCount
        opaqueFacesCount = output.opaqueFacesCount

        rawTransparentVertices = output.transparentVertices
        transparentVertexFloatLength = output.transparentVertexFloatLength
        rawTransparentIndices = output.transparentIndices
        transparentIndexArrayLength = output.transparentIndexLength
        transparentIndexCount = output.transparentIndexLength
        transparentQuadCount = output.transparentQuadCount
        transparentFacesCount = output.transparentFacesCount

        culledFacesCount = output.culledFacesCount
        rebuildCount++
        hasPendingGpuUpload = (opaqueIndexCount > 0 || transparentIndexCount > 0 || hadGeometry)
    }

    /**
     * Returns a snapshot of generated (u, v) atlas pairs from current CPU vertex [FloatArray] data.
     */
    fun getGeneratedUvs(transparentPass: Boolean): List<Pair<Float, Float>> {
        val arr = if (transparentPass) rawTransparentVertices else rawOpaqueVertices
        val floatLen = if (transparentPass) transparentVertexFloatLength else opaqueVertexFloatLength
        val pipeline = lastTexturePipeline
        val result = ArrayList<Pair<Float, Float>>(floatLen / FLOATS_PER_VERTEX)
        var i = 0
        while (i + 2 < floatLen) {
            val w1 = arr[i + 1].toRawBits()
            val w2 = arr[i + 2].toRawBits()
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
            i += FLOATS_PER_VERTEX
        }
        return result
    }

    /**
     * Uploads raw [FloatArray] / [ShortArray] mesh buffers to OpenGL ES 3.0 VBO/EBOs.
     * `:core`'s [NativeBufferPool] owns DirectByteBuffer staging and 1/tick Flood GC lifecycle;
     * `:app` strictly binds buffers to OpenGL.
     */
    fun uploadCpuMeshToGpu(
        bufferPool: NativeBufferPool? = null,
        currentFrameIndex: Long = 0L
    ) {
        if (opaqueIndexCount > 0 && opaqueVertexFloatLength > 0) {
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
                    bufferPool = bufferPool,
                    currentFrameIndex = currentFrameIndex
                )
            }
        } else if (hasOpaqueGpuBuffers) {
            releaseOpaqueGl()
        }

        if (transparentIndexCount > 0 && transparentVertexFloatLength > 0) {
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
                    bufferPool = bufferPool,
                    currentFrameIndex = currentFrameIndex
                )
            }
        } else if (hasTransparentGpuBuffers) {
            releaseTransparentGl()
        }

        trimCpuArrays()
        hasPendingGpuUpload = false
    }

    private fun trimCpuArrays() {
        rawOpaqueVertices = ChunkGeometryBuilder.EMPTY_FLOAT_ARRAY
        rawOpaqueIndices = ChunkGeometryBuilder.EMPTY_SHORT_ARRAY
        opaqueVertexFloatLength = 0
        opaqueIndexArrayLength = 0

        rawTransparentVertices = ChunkGeometryBuilder.EMPTY_FLOAT_ARRAY
        rawTransparentIndices = ChunkGeometryBuilder.EMPTY_SHORT_ARRAY
        transparentVertexFloatLength = 0
        transparentIndexArrayLength = 0
    }

    fun buildMesh(
        world: World,
        texturePipeline: TexturePipeline,
        bufferPool: NativeBufferPool? = null,
        currentFrameIndex: Long = 0L
    ) {
        generateMeshData(world, texturePipeline)
        uploadCpuMeshToGpu(bufferPool = bufferPool, currentFrameIndex = currentFrameIndex)
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
        bufferPool: NativeBufferPool? = null,
        currentFrameIndex: Long = 0L
    ) {
        val staging = threadLocalStaging.get()!!
        val vBytes = vertexFloatLength * 4
        val pooledV = bufferPool?.borrowAndStageFloats(vertices, vertexFloatLength)
        if (pooledV != null) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vBytes, pooledV, GLES30.GL_STATIC_DRAW)
            bufferPool.markUploaded(pooledV, currentFrameIndex)
        } else {
            val vBuf = staging.ensureVertexBuffer(vBytes)
            vBuf.clear()
            vBuf.put(vertices, 0, vertexFloatLength)
            vBuf.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vBytes, vBuf, GLES30.GL_STATIC_DRAW)
        }

        val iBytes = indexLength * 2
        val pooledI = bufferPool?.borrowAndStageShorts(indices, indexLength)
        if (pooledI != null) {
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iBytes, pooledI, GLES30.GL_STATIC_DRAW)
            bufferPool.markUploaded(pooledI, currentFrameIndex)
        } else {
            val iBuf = staging.ensureIndexBuffer(iBytes)
            iBuf.clear()
            iBuf.put(indices, 0, indexLength)
            iBuf.flip()
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iBytes, iBuf, GLES30.GL_STATIC_DRAW)
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
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
        private var vertexFloatView: FloatBuffer = vertexByteBuf.asFloatBuffer()
        private var indexByteBuf: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_STAGING_I_BYTES).order(ByteOrder.nativeOrder())
        private var indexShortView: ShortBuffer = indexByteBuf.asShortBuffer()

        fun ensureVertexBuffer(neededBytes: Int): FloatBuffer {
            if (vertexByteBuf.capacity() < neededBytes) {
                val nextCap = maxOf(neededBytes, vertexByteBuf.capacity() * 2)
                vertexByteBuf = ByteBuffer.allocateDirect(nextCap).order(ByteOrder.nativeOrder())
                vertexFloatView = vertexByteBuf.asFloatBuffer()
            }
            return vertexFloatView
        }

        fun ensureIndexBuffer(neededBytes: Int): ShortBuffer {
            if (indexByteBuf.capacity() < neededBytes) {
                val nextCap = maxOf(neededBytes, indexByteBuf.capacity() * 2)
                indexByteBuf = ByteBuffer.allocateDirect(nextCap).order(ByteOrder.nativeOrder())
                indexShortView = indexByteBuf.asShortBuffer()
            }
            return indexShortView
        }
    }

    companion object {
        const val FLOATS_PER_VERTEX = ChunkGeometryBuilder.FLOATS_PER_VERTEX
        const val VERTEX_STRIDE_BYTES = ChunkGeometryBuilder.VERTEX_STRIDE_BYTES // 12 bytes
        private const val INITIAL_STAGING_V_BYTES = 65_536
        private const val INITIAL_STAGING_I_BYTES = 32_768

        private val threadLocalStaging = ThreadLocal.withInitial { StagingScratch() }
        private val threadLocalGeometryBuilder = ThreadLocal.withInitial { ChunkGeometryBuilder() }

        fun shouldRenderFace(block: BlockType, neighbor: BlockType): Boolean {
            return ChunkGeometryBuilder.shouldRenderFace(block, neighbor)
        }
    }
}
