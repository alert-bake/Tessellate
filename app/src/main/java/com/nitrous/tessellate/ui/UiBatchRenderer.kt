package com.nitrous.tessellate.ui

import android.opengl.GLES30
import com.nitrous.tessellate.graphics.Matrix4
import com.nitrous.tessellate.graphics.Shader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Dynamic 2D quad batcher for OpenGL ES 3.0 UI and bitmap font rendering.
 *
 * Groups consecutive screen-space quads sharing the same `(useTexture, whiteOverlay, textureHandle)`
 * state into a single dynamic VBO + shared static EBO `glDrawElements` call, collapsing dozens of
 * per-glyph, 9-slice, and outline draw calls down to minimal GPU state changes.
 */
class UiBatchRenderer {

    private var batchShader: Shader? = null
    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var eboId: Int = 0

    // 8 floats per vertex: (x, y, u, v, r, g, b, a) * 4 verts * MAX_QUADS
    private val vertexData = FloatArray(MAX_QUADS * FLOATS_PER_QUAD)
    private var vertexBuffer: FloatBuffer? = null
    private val activeOrtho = Matrix4.create()

    private var activeQuadCount: Int = 0
    private var batchUseTexture: Int = -1
    private var batchWhiteOverlay: Int = -1
    private var batchTextureHandle: Int = -1
    private var batchForceNearest: Boolean = true
    private var isInFrame: Boolean = false

    var lastFrameFlushCount: Int = 0
        private set
    var lastFrameBatchedQuadCount: Int = 0
        private set

    private var currentFrameFlushCount: Int = 0
    private var currentFrameQuadCount: Int = 0

    val isGlInitialized: Boolean
        get() = batchShader != null && vaoId != 0

    fun initGl() {
        release()

        val vao = IntArray(1)
        val bufs = IntArray(2)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, bufs, 0)
        vaoId = vao[0]
        vboId = bufs[0]
        eboId = bufs[1]

        vertexBuffer = ByteBuffer.allocateDirect(MAX_QUADS * FLOATS_PER_QUAD * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val indices = ShortArray(MAX_QUADS * INDICES_PER_QUAD)
        for (q in 0 until MAX_QUADS) {
            val baseV = (q * 4).toShort()
            val iBase = q * 6
            indices[iBase + 0] = baseV
            indices[iBase + 1] = (baseV + 1).toShort()
            indices[iBase + 2] = (baseV + 2).toShort()
            indices[iBase + 3] = baseV
            indices[iBase + 4] = (baseV + 2).toShort()
            indices[iBase + 5] = (baseV + 3).toShort()
        }
        val iBuf = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(indices)
                position(0)
            }

        GLES30.glBindVertexArray(vaoId)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            MAX_QUADS * FLOATS_PER_QUAD * 4,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * 2,
            iBuf,
            GLES30.GL_STATIC_DRAW
        )

        // Attrib 0: aPosition (vec2)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0)
        // Attrib 1: aTexCoord (vec2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE_BYTES, 2 * 4)
        // Attrib 2: aColor (vec4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, VERTEX_STRIDE_BYTES, 4 * 4)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val vs = """
            #version 300 es
            layout (location = 0) in vec2 aPosition;
            layout (location = 1) in vec2 aTexCoord;
            layout (location = 2) in vec4 aColor;

            uniform mat4 uOrthoMatrix;
            out vec2 vTexCoord;
            out vec4 vColor;

            void main() {
                vTexCoord = aTexCoord;
                vColor = aColor;
                gl_Position = uOrthoMatrix * vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val fs = """
            #version 300 es
            precision mediump float;

            in vec2 vTexCoord;
            in vec4 vColor;

            uniform sampler2D uTexture;
            uniform int uUseTexture;
            uniform int uWhiteOverlay;

            out vec4 fragColor;

            void main() {
                if (uUseTexture == 1) {
                    vec4 texColor = texture(uTexture, vTexCoord);
                    if (uWhiteOverlay == 1) {
                        fragColor = vec4(vColor.rgb, texColor.a * vColor.a);
                    } else {
                        fragColor = texColor * vColor;
                    }
                } else {
                    fragColor = vColor;
                }
            }
        """.trimIndent()

        batchShader = Shader(vs, fs).apply {
            compile()
        }
    }

    fun begin(orthoMatrix: FloatArray) {
        System.arraycopy(orthoMatrix, 0, activeOrtho, 0, minOf(16, orthoMatrix.size))
        activeQuadCount = 0
        batchUseTexture = -1
        batchWhiteOverlay = -1
        batchTextureHandle = -1
        currentFrameFlushCount = 0
        currentFrameQuadCount = 0
        isInFrame = true
    }

    fun pushSolidQuad(
        x: Float, y: Float, width: Float, height: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        pushQuadInternal(
            useTexture = 0,
            whiteOverlay = 0,
            textureHandle = 0,
            forceNearest = false,
            x = x, y = y, width = width, height = height,
            u1 = 0f, v1 = 0f, u2 = 1f, v2 = 1f,
            r = r, g = g, b = b, a = a
        )
    }

    fun pushTexturedQuad(
        textureHandle: Int,
        x: Float, y: Float, width: Float, height: Float,
        u1: Float, v1: Float, u2: Float, v2: Float,
        r: Float, g: Float, b: Float, a: Float,
        whiteOverlay: Boolean = false,
        forceNearest: Boolean = true
    ) {
        if (textureHandle == 0) return
        pushQuadInternal(
            useTexture = 1,
            whiteOverlay = if (whiteOverlay) 1 else 0,
            textureHandle = textureHandle,
            forceNearest = forceNearest,
            x = x, y = y, width = width, height = height,
            u1 = u1, v1 = v1, u2 = u2, v2 = v2,
            r = r, g = g, b = b, a = a
        )
    }

    private fun pushQuadInternal(
        useTexture: Int,
        whiteOverlay: Int,
        textureHandle: Int,
        forceNearest: Boolean,
        x: Float, y: Float, width: Float, height: Float,
        u1: Float, v1: Float, u2: Float, v2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        if (!isInFrame || width <= 0f || height <= 0f) return

        val stateChanged = activeQuadCount > 0 && (
            batchUseTexture != useTexture ||
                batchWhiteOverlay != whiteOverlay ||
                batchTextureHandle != textureHandle ||
                (useTexture == 1 && batchForceNearest != forceNearest)
            )

        if (stateChanged || activeQuadCount >= MAX_QUADS) {
            flushCurrentBatch()
        }

        batchUseTexture = useTexture
        batchWhiteOverlay = whiteOverlay
        batchTextureHandle = textureHandle
        batchForceNearest = forceNearest

        val x2 = x + width
        val y2 = y + height
        var o = activeQuadCount * FLOATS_PER_QUAD

        // Top-Left (v0)
        vertexData[o++] = x;  vertexData[o++] = y;  vertexData[o++] = u1; vertexData[o++] = v1
        vertexData[o++] = r;  vertexData[o++] = g;  vertexData[o++] = b;  vertexData[o++] = a

        // Top-Right (v1)
        vertexData[o++] = x2; vertexData[o++] = y;  vertexData[o++] = u2; vertexData[o++] = v1
        vertexData[o++] = r;  vertexData[o++] = g;  vertexData[o++] = b;  vertexData[o++] = a

        // Bottom-Right (v2)
        vertexData[o++] = x2; vertexData[o++] = y2; vertexData[o++] = u2; vertexData[o++] = v2
        vertexData[o++] = r;  vertexData[o++] = g;  vertexData[o++] = b;  vertexData[o++] = a

        // Bottom-Left (v3)
        vertexData[o++] = x;  vertexData[o++] = y2; vertexData[o++] = u1; vertexData[o++] = v2
        vertexData[o++] = r;  vertexData[o++] = g;  vertexData[o++] = b;  vertexData[o++] = a

        activeQuadCount++
        currentFrameQuadCount++
    }

    private fun flushCurrentBatch() {
        if (activeQuadCount <= 0) return

        val shader = batchShader
        val buf = vertexBuffer
        if (shader != null && shader.programId != 0 && vaoId != 0 && vboId != 0 && buf != null) {
            val floatCount = activeQuadCount * FLOATS_PER_QUAD
            buf.clear()
            buf.put(vertexData, 0, floatCount)
            buf.flip()

            shader.bind()
            shader.setUniformMatrix4fv("uOrthoMatrix", activeOrtho)
            shader.setUniform1i("uUseTexture", batchUseTexture)
            shader.setUniform1i("uWhiteOverlay", batchWhiteOverlay)

            if (batchUseTexture == 1 && batchTextureHandle != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, batchTextureHandle)
                if (batchForceNearest) {
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                }
                shader.setUniform1i("uTexture", 0)
            }

            GLES30.glBindVertexArray(vaoId)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, floatCount * 4, buf)
            GLES30.glDrawElements(
                GLES30.GL_TRIANGLES,
                activeQuadCount * INDICES_PER_QUAD,
                GLES30.GL_UNSIGNED_SHORT,
                0
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindVertexArray(0)
            shader.unbind()
        }

        currentFrameFlushCount++
        activeQuadCount = 0
    }

    fun endFrame() {
        if (!isInFrame) return
        flushCurrentBatch()
        lastFrameFlushCount = currentFrameFlushCount
        lastFrameBatchedQuadCount = currentFrameQuadCount
        isInFrame = false
    }

    fun release() {
        batchShader?.release()
        batchShader = null
        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (vboId != 0 || eboId != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(vboId, eboId), 0)
            vboId = 0
            eboId = 0
        }
        vertexBuffer = null
        activeQuadCount = 0
        isInFrame = false
    }

    companion object {
        const val MAX_QUADS = 2048
        const val FLOATS_PER_VERTEX = 8
        const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * 4 // 32 bytes
        const val FLOATS_PER_QUAD = FLOATS_PER_VERTEX * 4
        const val INDICES_PER_QUAD = 6
    }
}
