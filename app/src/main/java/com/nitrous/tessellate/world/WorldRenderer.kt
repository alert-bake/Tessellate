package com.nitrous.tessellate.world

import android.opengl.GLES30
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.graphics.Frustum
import com.nitrous.tessellate.graphics.Matrix4
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.texture.TexturePipeline
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages chunk GPU meshes, frustum culling, block outline highlights, entity hitboxes,
 * safe GPU resource flushing, and atlas-based batch rendering.
 */
class WorldRenderer(
    initialWorld: World
) {
    @Volatile
    var world: World = initialWorld
        private set

    @Volatile
    var showHitboxes: Boolean = false

    @Volatile
    private var pendingGpuFlush: Boolean = false

    val gpuFlushCount = AtomicInteger(0)

    private val meshSwapLock = Any()
    private val chunkMeshMap = LinkedHashMap<ChunkPos, ChunkMesh>()
    private val chunkMeshes = mutableListOf<ChunkMesh>()
    private val visibleChunkMeshes = mutableListOf<ChunkMesh>()
    private val pendingReleaseMeshes = mutableListOf<ChunkMesh>()
    private val dirtyQueueScratch = mutableListOf<ChunkMesh>()
    private var syncedWorldRef: World? = null
    private var syncedTopologyVersion: Long = -1L
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

    /**
     * Thread-safe deferred request to flush and rebuild chunk GPU buffers at the next safe
     * GL-thread frame boundary, without destroying resources currently in use mid-draw.
     */
    fun requestSafeGpuFlush() {
        pendingGpuFlush = true
    }

    fun isGpuFlushPending(): Boolean = pendingGpuFlush

    fun processPendingGpuFlushIfSafe(
        texturePipeline: TexturePipeline? = null,
        isGlThread: Boolean = true
    ): Boolean {
        if (!pendingGpuFlush) return false
        pendingGpuFlush = false

        synchronized(meshSwapLock) {
            if (isGlThread) {
                for (mesh in pendingReleaseMeshes) {
                    mesh.release()
                }
                pendingReleaseMeshes.clear()
                for (mesh in chunkMeshes) {
                    mesh.release()
                    if (!mesh.chunk.isEmpty) {
                        mesh.chunk.markDirty()
                    }
                }
            } else {
                for (mesh in chunkMeshes) {
                    if (!mesh.chunk.isEmpty) {
                        mesh.chunk.markDirty()
                    }
                }
            }
        }
        if (isGlThread) {
            texturePipeline?.reloadAll()
        }
        gpuFlushCount.incrementAndGet()
        return true
    }

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
            syncedWorldRef = null
            syncedTopologyVersion = -1L
            world = newWorld
        }
        syncWithWorld(initGlBuffers = false)
    }

    fun syncWithWorld(initGlBuffers: Boolean = false) {
        val currentWorld = world
        val currentVersion = currentWorld.chunkTopologyVersion.get()
        if (!initGlBuffers && syncedWorldRef === currentWorld && syncedTopologyVersion == currentVersion) {
            return
        }

        val loadedChunks = currentWorld.getAllChunks()
        val activePositions = HashSet<ChunkPos>(loadedChunks.size)

        synchronized(meshSwapLock) {
            if (world !== currentWorld) return
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
            syncedWorldRef = currentWorld
            syncedTopologyVersion = currentVersion
        }
    }

    fun initGl() {
        releaseWorldShadersAndHighlight()
        syncedWorldRef = null
        syncedTopologyVersion = -1L
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
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
        """.trimIndent()

        highlightShader = Shader(highlightVs, highlightFs).apply {
            compile()
        }
    }

    /**
     * Syncs chunk meshes with dynamically streamed chunks and updates dirty/pending GPU meshes.
     * When [uploadToGpu] is true (live GL render loop), applies a per-frame rebuild/upload budget
     * prioritized nearest to ([focusX], [focusZ]) so chunk streaming never freezes the render thread.
     */
    fun updateMeshes(
        texturePipeline: TexturePipeline,
        uploadToGpu: Boolean = true,
        focusX: Float = 0f,
        focusZ: Float = 0f
    ) {
        if (uploadToGpu) {
            processPendingGpuFlushIfSafe(texturePipeline, isGlThread = true)
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

        val currentWorld = world
        val prevTopology = syncedTopologyVersion
        syncWithWorld(initGlBuffers = false)
        val topologyChanged = prevTopology != syncedTopologyVersion

        dirtyQueueScratch.clear()
        synchronized(meshSwapLock) {
            for (i in 0 until chunkMeshes.size) {
                val mesh = chunkMeshes[i]
                val prebuiltAvailable = mesh.rebuildCount == 0 && !mesh.chunk.isEmpty
                if (mesh.chunk.needsMeshRebuild || (uploadToGpu && mesh.hasPendingGpuUpload) || prebuiltAvailable) {
                    dirtyQueueScratch.add(mesh)
                }
            }
        }

        if (dirtyQueueScratch.isEmpty() && !topologyChanged) {
            return
        }

        if (uploadToGpu && dirtyQueueScratch.size > 1) {
            dirtyQueueScratch.sortBy { mesh ->
                val cx = mesh.chunk.chunkX * Chunk.SIZE + 8f - focusX
                val cz = mesh.chunk.chunkZ * Chunk.SIZE + 8f - focusZ
                cx * cx + cz * cz
            }
        }

        val maxCount = if (uploadToGpu) MAX_MESH_UPLOADS_PER_FRAME else Int.MAX_VALUE
        val startNs = if (uploadToGpu) System.nanoTime() else 0L
        var processed = 0
        var anyUpdated = false

        for (i in 0 until dirtyQueueScratch.size) {
            if (processed >= maxCount) break
            if (uploadToGpu && processed > 0 && (System.nanoTime() - startNs) >= MAX_MESH_BUDGET_NS) {
                break
            }

            var mesh = dirtyQueueScratch[i]
            val c = mesh.chunk
            val pos = ChunkPos(c.chunkX, c.chunkY, c.chunkZ)

            // Check if the background streaming thread finished a prebuilt CPU mesh for this chunk
            val prebuilt = currentWorld.consumePrebuiltMesh(pos)
            if (prebuilt != null && prebuilt.chunk === c && prebuilt.rebuildCount > 0 && !c.needsMeshRebuild) {
                synchronized(meshSwapLock) {
                    val currentInMap = chunkMeshMap[pos]
                    if (currentInMap === mesh) {
                        chunkMeshMap[pos] = prebuilt
                        val idx = chunkMeshes.indexOf(mesh)
                        if (idx >= 0) {
                            chunkMeshes[idx] = prebuilt
                        }
                        if (uploadToGpu) {
                            pendingReleaseMeshes.add(mesh)
                        }
                        mesh = prebuilt
                    }
                }
            }

            if (c.needsMeshRebuild) {
                if (uploadToGpu) {
                    mesh.buildMesh(currentWorld, texturePipeline)
                } else {
                    mesh.generateMeshData(currentWorld, texturePipeline)
                }
                anyUpdated = true
                if (!c.isEmpty || mesh.generatedFacesCount > 0) {
                    processed++
                }
            } else if (uploadToGpu && mesh.hasPendingGpuUpload) {
                mesh.uploadCpuMeshToGpu()
                anyUpdated = true
                processed++
            }
        }
        dirtyQueueScratch.clear()

        if (anyUpdated || topologyChanged) {
            var rendered = 0
            var culled = 0
            synchronized(meshSwapLock) {
                for (i in 0 until chunkMeshes.size) {
                    val m = chunkMeshes[i]
                    rendered += m.generatedFacesCount
                    culled += m.culledFacesCount
                }
            }
            totalRenderedFaces = rendered
            totalCulledFaces = culled
        }
    }

    /**
     * Extracts the 6 frustum planes from [viewProjectionMatrix] and filters [chunkMeshes]
     * to only those whose world-space [ChunkMesh.worldBounds] AABB intersects or is inside the frustum.
     */
    private fun updateVisibleChunks(viewProjectionMatrix: FloatArray) {
        frustum.extractPlanes(viewProjectionMatrix)
        visibleChunkMeshes.clear()
        var totalChunks = 0
        synchronized(meshSwapLock) {
            totalChunks = chunkMeshes.size
            for (i in 0 until totalChunks) {
                val mesh = chunkMeshes[i]
                if (frustum.intersectsAabb(mesh.worldBounds)) {
                    visibleChunkMeshes.add(mesh)
                }
            }
        }
        visibleChunksCount = visibleChunkMeshes.size
        frustumCulledChunksCount = totalChunks - visibleChunksCount
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
        val inset = 0.003f
        renderAabbOutline(
            viewProjMatrix = viewProjMatrix,
            minX = pos.x - inset,
            minY = pos.y - inset,
            minZ = pos.z - inset,
            maxX = pos.x + 1f + inset,
            maxY = pos.y + 1f + inset,
            maxZ = pos.z + 1f + inset,
            r = 1.0f,
            g = 0.9f,
            b = 0.2f,
            a = 0.85f,
            lineWidth = 4.0f
        )
    }

    /**
     * Renders wireframe AABB hitboxes for all active entities when [showHitboxes] is true.
     */
    fun renderHitboxes(
        viewProjMatrix: FloatArray,
        entities: List<Entity>,
        player: Player? = null
    ) {
        if (!showHitboxes) return
        if (highlightShader == null || highlightVaoId == 0) return

        for (i in 0 until entities.size) {
            val e = entities[i]
            if (e.isRemoved) continue
            val box = e.boundingBox
            renderAabbOutline(
                viewProjMatrix = viewProjMatrix,
                minX = box.minX,
                minY = box.minY,
                minZ = box.minZ,
                maxX = box.maxX,
                maxY = box.maxY,
                maxZ = box.maxZ,
                r = 0.15f,
                g = 0.95f,
                b = 1.0f,
                a = 0.95f,
                lineWidth = 3.0f
            )
        }
        if (player != null) {
            val box = player.boundingBox
            renderAabbOutline(
                viewProjMatrix = viewProjMatrix,
                minX = box.minX,
                minY = box.minY,
                minZ = box.minZ,
                maxX = box.maxX,
                maxY = box.maxY,
                maxZ = box.maxZ,
                r = 0.2f,
                g = 1.0f,
                b = 0.45f,
                a = 0.7f,
                lineWidth = 2.5f
            )
        }
    }

    private fun renderAabbOutline(
        viewProjMatrix: FloatArray,
        minX: Float,
        minY: Float,
        minZ: Float,
        maxX: Float,
        maxY: Float,
        maxZ: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        lineWidth: Float
    ) {
        val shader = highlightShader ?: return
        if (shader.programId == 0 || highlightVaoId == 0) return

        val sx = (maxX - minX).coerceAtLeast(0.01f)
        val sy = (maxY - minY).coerceAtLeast(0.01f)
        val sz = (maxZ - minZ).coerceAtLeast(0.01f)

        Matrix4.setIdentity(modelMatrix)
        Matrix4.translate(modelMatrix, minX, minY, minZ)
        Matrix4.scale(modelMatrix, sx, sy, sz)
        Matrix4.multiply(mvpMatrix, viewProjMatrix, modelMatrix)

        GLES30.glLineWidth(lineWidth)
        shader.bind()
        shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)
        shader.setUniform4f("uColor", r, g, b, a)

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
        syncedWorldRef = null
        syncedTopologyVersion = -1L
    }

    companion object {
        private const val MAX_MESH_UPLOADS_PER_FRAME = 6
        private const val MAX_MESH_BUDGET_NS = 4_000_000L
    }
}
