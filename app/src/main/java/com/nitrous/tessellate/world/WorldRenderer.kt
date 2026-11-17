package com.nitrous.tessellate.world

import android.opengl.GLES30
import com.nitrous.tessellate.graphics.Frustum
import com.nitrous.tessellate.graphics.Matrix4
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.texture.TexturePipeline

/**
 * Manages chunk GPU meshes, frustum culling, block outline highlights, and atlas-based batch rendering.
 */
class WorldRenderer(
    initialWorld: World
) {
    @Volatile
    var world: World = initialWorld
        private set

    private val meshSwapLock = Any()
    private val chunkMeshMap = LinkedHashMap<ChunkPos, ChunkMesh>()
    private val chunkMeshes = mutableListOf<ChunkMesh>()
    private val visibleChunkMeshes = mutableListOf<ChunkMesh>()
    private val pendingReleaseMeshes = mutableListOf<ChunkMesh>()
    private val frustum = Frustum()
    private var worldShader: Shader? = null
    private var highlightShader: Shader? = null
    private var highlightVaoId: Int = 0
    private var highlightVboId: Int = 0

    private val modelMatrix = Matrix4.create()
    private val mvpMatrix = Matrix4.create()

    var totalRenderedFaces: Int = 0
        private set
    var totalCulledFaces: Int = 0
        private set
    var visibleChunksCount: Int = 0
        private set
    var frustumCulledChunksCount: Int = 0
        private set

    fun getChunkMeshes(): List<ChunkMesh> = synchronized(meshSwapLock) { chunkMeshes.toList() }

    init {
        syncWithWorld(initGlBuffers = false)
    }

    /**
     * Replaces the active [World] safely from any thread, deferring GPU buffer deletion
     * to the GL thread on the next [updateMeshes] or [release] call.
     */
    fun replaceWorld(newWorld: World) {
        synchronized(meshSwapLock) {
            pendingReleaseMeshes.addAll(chunkMeshes)
            chunkMeshMap.clear()
            chunkMeshes.clear()
            visibleChunkMeshes.clear()
            world = newWorld
        }
        syncWithWorld(initGlBuffers = false)
    }

    fun syncWithWorld(initGlBuffers: Boolean = false) {
        val currentWorld = world
        val loadedChunks = currentWorld.getAllChunks()
        val activePositions = HashSet<ChunkPos>(loadedChunks.size)

        synchronized(meshSwapLock) {
            var changed = false

            for (c in loadedChunks) {
                val pos = ChunkPos(c.chunkX, c.chunkY, c.chunkZ)
                activePositions.add(pos)
                val existing = chunkMeshMap[pos]
                if (existing == null || existing.chunk !== c) {
                    existing?.let {
                        if (initGlBuffers) it.release() else pendingReleaseMeshes.add(it)
                    }
                    val prebuilt = currentWorld.consumePrebuiltMesh(pos)
                    val mesh = if (prebuilt != null && prebuilt.chunk === c) {
                        prebuilt
                    } else {
                        ChunkMesh(c)
                    }
                    if (initGlBuffers) {
                        mesh.initGl()
                    }
                    chunkMeshMap[pos] = mesh
                    changed = true
                }
            }

            val iterator = chunkMeshMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!activePositions.contains(entry.key)) {
                    if (initGlBuffers) {
                        entry.value.release()
                    } else {
                        pendingReleaseMeshes.add(entry.value)
                    }
                    iterator.remove()
                    changed = true
                }
            }

            if (changed) {
                chunkMeshes.clear()
                chunkMeshes.addAll(chunkMeshMap.values)
            }
        }
    }

    fun initGl() {
        releaseWorldShadersAndHighlight()
        syncWithWorld(initGlBuffers = false)
        val vertexSource = """
            #version 300 es
            layout (location = 0) in vec3 aPosition;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;
            layout (location = 3) in float aShade;

            uniform mat4 uMVPMatrix;

            out vec2 vTexCoord;
            out float vShade;
            out vec3 vNormal;

            void main() {
                vTexCoord = aTexCoord;
                vShade = aShade;
                vNormal = aNormal;
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        val fragmentSource = """
            #version 300 es
            precision mediump float;

            in vec2 vTexCoord;
            in float vShade;
            in vec3 vNormal;

            uniform sampler2D uTexture;
            uniform vec3 uSunDir;

            out vec4 fragColor;

            void main() {
                vec4 texColor = texture(uTexture, vTexCoord);
                if (texColor.a < 0.01) {
                    discard;
                }

                // Sun directional lighting
                vec3 norm = normalize(vNormal);
                vec3 sun = normalize(uSunDir);
                float sunDiff = max(dot(norm, sun), 0.0) * 0.45;
                float ambient = 0.55;

                // Combine sun lighting with face directional shade
                float totalLight = (ambient + sunDiff) * vShade;
                vec3 litColor = texColor.rgb * totalLight;

                fragColor = vec4(litColor, texColor.a);
            }
        """.trimIndent()

        worldShader = Shader(vertexSource, fragmentSource).apply {
            compile()
        }

        initHighlightGl()

        for (mesh in chunkMeshes) {
            mesh.initGl()
        }
    }

    private fun initHighlightGl() {
        val vao = IntArray(1)
        val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)

        highlightVaoId = vao[0]
        highlightVboId = vbo[0]

        // 12 lines of a unit cube [0, 1] x [0, 1] x [0, 1]
        val lines = floatArrayOf(
            // Bottom 4 lines
            0f, 0f, 0f,  1f, 0f, 0f,
            1f, 0f, 0f,  1f, 0f, 1f,
            1f, 0f, 1f,  0f, 0f, 1f,
            0f, 0f, 1f,  0f, 0f, 0f,
            // Top 4 lines
            0f, 1f, 0f,  1f, 1f, 0f,
            1f, 1f, 0f,  1f, 1f, 1f,
            1f, 1f, 1f,  0f, 1f, 1f,
            0f, 1f, 1f,  0f, 1f, 0f,
            // Vertical 4 lines
            0f, 0f, 0f,  0f, 1f, 0f,
            1f, 0f, 0f,  1f, 1f, 0f,
            1f, 0f, 1f,  1f, 1f, 1f,
            0f, 0f, 1f,  0f, 1f, 1f
        )

        val buffer = java.nio.ByteBuffer.allocateDirect(lines.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(lines)
                position(0)
            }

        GLES30.glBindVertexArray(highlightVaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, highlightVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lines.size * 4, buffer, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val highlightVs = """
            #version 300 es
            layout (location = 0) in vec3 aPosition;
            uniform mat4 uMVPMatrix;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        val highlightFs = """
            #version 300 es
            precision mediump float;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0, 0.9, 0.2, 0.85); // Glowing gold selector line
            }
        """.trimIndent()

        highlightShader = Shader(highlightVs, highlightFs).apply {
            compile()
        }
    }

    /**
     * Syncs chunk meshes with dynamically streamed chunks and updates dirty/pending GPU meshes.
     */
    fun updateMeshes(texturePipeline: TexturePipeline, uploadToGpu: Boolean = true) {
        if (uploadToGpu) {
            val toRelease = synchronized(meshSwapLock) {
                if (pendingReleaseMeshes.isEmpty()) {
                    emptyList()
                } else {
                    pendingReleaseMeshes.toList().also { pendingReleaseMeshes.clear() }
                }
            }
            for (mesh in toRelease) {
                mesh.release()
            }
        }

        syncWithWorld(initGlBuffers = uploadToGpu)

        val currentWorld = world
        val snapshot = synchronized(meshSwapLock) { chunkMeshes.toList() }
        var rendered = 0
        var culled = 0
        for (mesh in snapshot) {
            if (mesh.chunk.needsMeshRebuild) {
                if (uploadToGpu) {
                    mesh.buildMesh(currentWorld, texturePipeline)
                } else {
                    mesh.generateMeshData(currentWorld, texturePipeline)
                }
            } else if (uploadToGpu && mesh.hasPendingGpuUpload) {
                mesh.uploadCpuMeshToGpu()
            }
            rendered += mesh.generatedFacesCount
            culled += mesh.culledFacesCount
        }
        totalRenderedFaces = rendered
        totalCulledFaces = culled
    }

    /**
     * Extracts the 6 frustum planes from [viewProjectionMatrix] and filters [chunkMeshes]
     * to only those whose world-space [ChunkMesh.worldBounds] AABB intersects or is inside the frustum.
     */
    private fun updateVisibleChunks(viewProjectionMatrix: FloatArray) {
        frustum.extractPlanes(viewProjectionMatrix)
        visibleChunkMeshes.clear()
        val snapshot = synchronized(meshSwapLock) { chunkMeshes.toList() }
        for (i in 0 until snapshot.size) {
            val mesh = snapshot[i]
            if (frustum.intersectsAabb(mesh.worldBounds)) {
                visibleChunkMeshes.add(mesh)
            }
        }
        visibleChunksCount = visibleChunkMeshes.size
        frustumCulledChunksCount = snapshot.size - visibleChunksCount
    }

    /**
     * Computes and returns a snapshot list of frustum-visible chunk meshes for the given [viewProjectionMatrix].
     */
    fun computeVisibleChunks(viewProjectionMatrix: FloatArray): List<ChunkMesh> {
        updateVisibleChunks(viewProjectionMatrix)
        return visibleChunkMeshes.toList()
    }

    /**
     * Renders frustum-visible chunk meshes in two passes:
     * - Pass 1: Opaque geometry (GL_BLEND disabled, depth writes enabled)
     * - Pass 2: Transparent geometry (GL_BLEND enabled, depth mask disabled during blend pass)
     */
    fun render(viewProjectionMatrix: FloatArray, texturePipeline: TexturePipeline, targetHit: RaycastHit?) {
        updateVisibleChunks(viewProjectionMatrix)
        val visibleChunks = visibleChunkMeshes

        val shader = worldShader ?: return
        if (shader.programId == 0) return
        val atlasHandle = texturePipeline.atlasTextureHandle
        if (atlasHandle == 0) return

        shader.bind()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasHandle)
        shader.setUniform1i("uTexture", 0)

        // Sun direction (overhead diagonal)
        shader.setUniform3f("uSunDir", 0.4f, 0.85f, 0.35f)

        // Identity model matrix since chunk vertices are already in world space coordinates
        Matrix4.setIdentity(modelMatrix)
        Matrix4.multiply(mvpMatrix, viewProjectionMatrix, modelMatrix)
        shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)

        // Pass 1: Opaque blocks (Dirt, Stone, Grass)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        for (i in 0 until visibleChunks.size) {
            visibleChunks[i].renderOpaque()
        }

        // Pass 2: Transparent blocks (Glass, Water, Leaves)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        for (i in 0 until visibleChunks.size) {
            visibleChunks[i].renderTransparent()
        }
        GLES30.glDepthMask(true)

        shader.unbind()

        // Render targeted block wireframe outline if raycast hit a block
        if (targetHit != null && highlightShader != null && highlightVaoId != 0) {
            renderBlockOutline(viewProjectionMatrix, targetHit.blockPos)
        }
    }

    private fun renderBlockOutline(viewProjMatrix: FloatArray, pos: BlockPos) {
        val shader = highlightShader ?: return

        // Slightly expand outline to prevent z-fighting with block face
        val inset = 0.003f
        Matrix4.setIdentity(modelMatrix)
        Matrix4.translate(modelMatrix, pos.x - inset, pos.y - inset, pos.z - inset)
        Matrix4.scale(modelMatrix, 1f + inset * 2f, 1f + inset * 2f, 1f + inset * 2f)
        Matrix4.multiply(mvpMatrix, viewProjMatrix, modelMatrix)

        GLES30.glLineWidth(4.0f)
        shader.bind()
        shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)

        GLES30.glBindVertexArray(highlightVaoId)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 24)
        GLES30.glBindVertexArray(0)

        shader.unbind()
    }

    fun markAllChunksDirty() {
        for (mesh in chunkMeshes) {
            mesh.chunk.markDirty()
        }
    }

    private fun releaseWorldShadersAndHighlight() {
        worldShader?.release()
        worldShader = null
        highlightShader?.release()
        highlightShader = null

        if (highlightVaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(highlightVaoId), 0)
            highlightVaoId = 0
        }
        if (highlightVboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(highlightVboId), 0)
            highlightVboId = 0
        }
    }

    fun release() {
        releaseWorldShadersAndHighlight()

        val allToRelease = synchronized(meshSwapLock) {
            val combined = ArrayList<ChunkMesh>(chunkMeshes.size + pendingReleaseMeshes.size)
            combined.addAll(chunkMeshes)
            combined.addAll(pendingReleaseMeshes)
            chunkMeshes.clear()
            chunkMeshMap.clear()
            visibleChunkMeshes.clear()
            pendingReleaseMeshes.clear()
            combined
        }
        for (mesh in allToRelease) {
            mesh.release()
        }
    }
}
