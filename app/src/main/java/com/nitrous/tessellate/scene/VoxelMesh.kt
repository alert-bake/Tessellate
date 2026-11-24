package com.nitrous.tessellate.scene

import android.opengl.GLES30
import com.nitrous.tessellate.core.ITexturePipeline
import com.nitrous.tessellate.graphics.Shader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

enum class CubeFace(val index: Int) {
    TOP(0),
    BOTTOM(1),
    NORTH(2),
    SOUTH(3),
    WEST(4),
    EAST(5)
}

/**
 * 3D Voxel Cube Geometric Primitive.
 * Features 6 distinct faces with verified outward-facing normals and CCW winding.
 * Fully resolves inside-out and negative-shading bugs under GL_BACK culling.
 */
class VoxelMesh {

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var eboId: Int = 0
    private var instancedVaoId: Int = 0
    private var instanceVboId: Int = 0
    private var instanceVboCapacityFloats: Int = 0

    // Stride: 3 (Pos) + 3 (Normal) + 2 (UV) = 8 floats = 32 bytes
    private val vertexStrideBytes = 8 * 4

    init {
        initBuffers()
    }

    private fun initBuffers() {
        val vao = IntArray(1)
        val buffers = IntArray(2)

        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)

        vaoId = vao[0]
        vboId = buffers[0]
        eboId = buffers[1]

        // 6 faces * 4 vertices = 24 vertices
        // All faces defined with CCW winding when viewed from the outside
        val vertices = floatArrayOf(
            // --- TOP FACE (+Y) --- Normal: (0, 1, 0)
            -0.5f,  0.5f,  0.5f,   0f,  1f,  0f,   0f, 1f, // 0 (BL)
             0.5f,  0.5f,  0.5f,   0f,  1f,  0f,   1f, 1f, // 1 (BR)
             0.5f,  0.5f, -0.5f,   0f,  1f,  0f,   1f, 0f, // 2 (TR)
            -0.5f,  0.5f, -0.5f,   0f,  1f,  0f,   0f, 0f, // 3 (TL)

            // --- BOTTOM FACE (-Y) --- Normal: (0, -1, 0)
            -0.5f, -0.5f, -0.5f,   0f, -1f,  0f,   0f, 1f, // 4 (BL)
             0.5f, -0.5f, -0.5f,   0f, -1f,  0f,   1f, 1f, // 5 (BR)
             0.5f, -0.5f,  0.5f,   0f, -1f,  0f,   1f, 0f, // 6 (TR)
            -0.5f, -0.5f,  0.5f,   0f, -1f,  0f,   0f, 0f, // 7 (TL)

            // --- NORTH FACE (-Z) --- Normal: (0, 0, -1)
             0.5f, -0.5f, -0.5f,   0f,  0f, -1f,   0f, 1f, // 8 (BL)
            -0.5f, -0.5f, -0.5f,   0f,  0f, -1f,   1f, 1f, // 9 (BR)
            -0.5f,  0.5f, -0.5f,   0f,  0f, -1f,   1f, 0f, // 10 (TR)
             0.5f,  0.5f, -0.5f,   0f,  0f, -1f,   0f, 0f, // 11 (TL)

            // --- SOUTH FACE (+Z) --- Normal: (0, 0, 1)
            -0.5f, -0.5f,  0.5f,   0f,  0f,  1f,   0f, 1f, // 12 (BL)
             0.5f, -0.5f,  0.5f,   0f,  0f,  1f,   1f, 1f, // 13 (BR)
             0.5f,  0.5f,  0.5f,   0f,  0f,  1f,   1f, 0f, // 14 (TR)
            -0.5f,  0.5f,  0.5f,   0f,  0f,  1f,   0f, 0f, // 15 (TL)

            // --- WEST FACE (-X) --- Normal: (-1, 0, 0)
            -0.5f, -0.5f, -0.5f,  -1f,  0f,  0f,   0f, 1f, // 16 (BL)
            -0.5f, -0.5f,  0.5f,  -1f,  0f,  0f,   1f, 1f, // 17 (BR)
            -0.5f,  0.5f,  0.5f,  -1f,  0f,  0f,   1f, 0f, // 18 (TR)
            -0.5f,  0.5f, -0.5f,  -1f,  0f,  0f,   0f, 0f, // 19 (TL)

            // --- EAST FACE (+X) --- Normal: (1, 0, 0)
             0.5f, -0.5f,  0.5f,   1f,  0f,  0f,   0f, 1f, // 20 (BL)
             0.5f, -0.5f, -0.5f,   1f,  0f,  0f,   1f, 1f, // 21 (BR)
             0.5f,  0.5f, -0.5f,   1f,  0f,  0f,   1f, 0f, // 22 (TR)
             0.5f,  0.5f,  0.5f,   1f,  0f,  0f,   0f, 0f  // 23 (TL)
        )

        // 6 faces * 6 indices = 36 indices with CCW order: (0, 1, 2) and (0, 2, 3)
        val indices = ShortArray(36)
        for (i in 0 until 6) {
            val baseVertex = (i * 4).toShort()
            val baseIndex = i * 6
            indices[baseIndex + 0] = baseVertex
            indices[baseIndex + 1] = (baseVertex + 1).toShort()
            indices[baseIndex + 2] = (baseVertex + 2).toShort()
            indices[baseIndex + 3] = baseVertex
            indices[baseIndex + 4] = (baseVertex + 2).toShort()
            indices[baseIndex + 5] = (baseVertex + 3).toShort()
        }

        val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(indices)
                position(0)
            }

        GLES30.glBindVertexArray(vaoId)

        // Upload vertex buffer
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)

        // Upload index buffer
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, indexBuffer, GLES30.GL_STATIC_DRAW)

        // Attribute 0: Position (vec3)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, vertexStrideBytes, 0)

        // Attribute 1: Normal (vec3)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, vertexStrideBytes, 3 * 4)

        // Attribute 2: TexCoord (vec2)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, vertexStrideBytes, 6 * 4)

        // Unbind VAO
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        initInstancedVao()
    }

    private fun initInstancedVao() {
        val vaoArr = IntArray(1)
        val vboArr = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoArr, 0)
        GLES30.glGenBuffers(1, vboArr, 0)
        instancedVaoId = vaoArr[0]
        instanceVboId = vboArr[0]

        GLES30.glBindVertexArray(instancedVaoId)

        // Bind shared base cube vertices & indices (attributes 0, 1, 2)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, vertexStrideBytes, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, vertexStrideBytes, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, vertexStrideBytes, 6 * 4)

        // Bind per-instance attribute buffer:
        // loc 3: vec4 (cx, cy, cz, rotYawRad)
        // loc 4: vec4 (scale, lightMul, 0, 0)
        // loc 5: vec4 (uMin, vMin, uMax, vMax)
        val instanceStrideBytes = FLOATS_PER_INSTANCE * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVboId)
        instanceVboCapacityFloats = 256 * FLOATS_PER_INSTANCE
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            instanceVboCapacityFloats * 4,
            null,
            GLES30.GL_STREAM_DRAW
        )
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, instanceStrideBytes, 0)
        GLES30.glVertexAttribDivisor(3, 1)

        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, instanceStrideBytes, 4 * 4)
        GLES30.glVertexAttribDivisor(4, 1)

        GLES30.glEnableVertexAttribArray(5)
        GLES30.glVertexAttribPointer(5, 4, GLES30.GL_FLOAT, false, instanceStrideBytes, 8 * 4)
        GLES30.glVertexAttribDivisor(5, 1)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Draws [instanceCount] cubes in a single instanced OpenGL ES 3.0 draw call.
     */
    fun drawInstanced(instanceData: FloatBuffer, instanceCount: Int) {
        if (instanceCount <= 0 || instancedVaoId == 0 || instanceVboId == 0) return
        val neededFloats = instanceCount * FLOATS_PER_INSTANCE
        val neededBytes = neededFloats * 4

        GLES30.glBindVertexArray(instancedVaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVboId)
        if (neededFloats > instanceVboCapacityFloats) {
            instanceVboCapacityFloats = maxOf(neededFloats, maxOf(256 * FLOATS_PER_INSTANCE, instanceVboCapacityFloats * 2))
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                instanceVboCapacityFloats * 4,
                null,
                GLES30.GL_STREAM_DRAW
            )
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, neededBytes, instanceData)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, 36, GLES30.GL_UNSIGNED_SHORT, 0, instanceCount)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Draws a single face with a specified texture.
     */
    fun drawFace(face: CubeFace, textureHandle: Int, shader: Shader) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandle)
        shader.setUniform1i("uTexture", 0)

        GLES30.glBindVertexArray(vaoId)
        val offsetBytes = face.index * 6 * 2
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, 6, GLES30.GL_UNSIGNED_SHORT, offsetBytes)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Draws a voxel block with custom textures for top, sides, and bottom.
     */
    fun drawVoxelBlock(
        pipeline: ITexturePipeline,
        shader: Shader,
        topTextureId: String,
        sideTextureId: String,
        bottomTextureId: String
    ) {
        val topHandle = pipeline.getTextureHandle(topTextureId)
        val sideHandle = pipeline.getTextureHandle(sideTextureId)
        val bottomHandle = pipeline.getTextureHandle(bottomTextureId)

        drawFace(CubeFace.TOP, topHandle, shader)
        drawFace(CubeFace.BOTTOM, bottomHandle, shader)
        drawFace(CubeFace.NORTH, sideHandle, shader)
        drawFace(CubeFace.SOUTH, sideHandle, shader)
        drawFace(CubeFace.WEST, sideHandle, shader)
        drawFace(CubeFace.EAST, sideHandle, shader)
    }

    /**
     * Draws a uniform cube where all 6 faces share the same texture.
     */
    fun drawUniformBlock(
        pipeline: ITexturePipeline,
        shader: Shader,
        textureId: String,
        bindTexture: Boolean = true
    ) {
        if (bindTexture) {
            val handle = pipeline.getTextureHandle(textureId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)
            shader.setUniform1i("uTexture", 0)
        }

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, 36, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (vaoId != 0) {
            val vaoArr = intArrayOf(vaoId)
            GLES30.glDeleteVertexArrays(1, vaoArr, 0)
            vaoId = 0
        }
        if (instancedVaoId != 0) {
            val vaoArr = intArrayOf(instancedVaoId)
            GLES30.glDeleteVertexArrays(1, vaoArr, 0)
            instancedVaoId = 0
        }
        val bufArr = intArrayOf(vboId, eboId, instanceVboId)
        GLES30.glDeleteBuffers(3, bufArr, 0)
        vboId = 0
        eboId = 0
        instanceVboId = 0
        instanceVboCapacityFloats = 0
    }

    companion object {
        const val FLOATS_PER_INSTANCE = 12
    }
}
