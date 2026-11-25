package com.nitrous.tessellate.world

import android.opengl.GLES30
import android.util.Log
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.graphics.Frustum
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.math.Matrix4
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.texture.TexturePipeline
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages chunk GPU meshes, frustum culling, empty-chunk skipping, front-to-back opaque &
 * back-to-front transparent chunk sorting, block outline highlights, entity hitboxes,
 * safe GPU resource flushing, and 12-byte packed vertex rendering.
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

    val nativeBufferPool = NativeBufferPool(
        onWarning = { msg -> Log.w("Tessellate.NativePool", msg) }
    )
    val parallelMesher = ParallelChunkMesher()

    @Volatile
    var parallelMeshingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                parallelMesher.clearPending()
            }
        }

    var useNativeMemoryPooling: Boolean
        get() = nativeBufferPool.isEnabled
        set(value) {
            nativeBufferPool.setEnabled(value)
        }

    private val meshSwapLock = Any()
    private val chunkMeshMap = LinkedHashMap<ChunkPos, ChunkMesh>()
    private val chunkMeshes = mutableListOf<ChunkMesh>()
    private val visibleChunkMeshes = mutableListOf<ChunkMesh>()
    private val renderVisibleScratch = mutableListOf<ChunkMesh>()
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
    private val tileRectsScratch = FloatArray(TexturePipeline.MAX_ATLAS_TILES * 4)

    var totalRenderedFaces: Int = 0
        private set
    var totalCulledFaces: Int = 0
        private set
    var visibleChunksCount: Int = 0
        private set
    var frustumCulledChunksCount: Int = 0
        private set
    var emptySkippedChunksCount: Int = 0
        private set
    @Volatile
    var lastFrameAtlasBindCount: Int = 0
        private set
    @Volatile
    var lastRenderedChunksCount: Int = 0
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
        parallelMesher.clearPending()
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
                    val prebuilt = currentWorld.consumePrebuiltMesh(pos) as? ChunkMesh
                    val mesh = if (prebuilt != null && prebuilt.chunk === c) {
                        prebuilt
                    } else {
                        ChunkMesh(c)
                    }
                    if (initGlBuffers && mesh.indexCount > 0) {
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
            layout (location = 0) in uvec3 aPacked;

            uniform mat4 uMVPMatrix;
            uniform vec3 uChunkOrigin;
            uniform vec4 uTileRects[${TexturePipeline.MAX_ATLAS_TILES}];

            out vec2 vTileUv;
            out vec4 vTileRect;
            out float vShade;
            out float vLightBiased;
            out vec3 vNormal;

            vec3 decodeNormal(uint idx) {
                if (idx == 0u) return vec3(0.0, 1.0, 0.0);
                if (idx == 1u) return vec3(0.0, -1.0, 0.0);
                if (idx == 2u) return vec3(0.0, 0.0, -1.0);
                if (idx == 3u) return vec3(0.0, 0.0, 1.0);
                if (idx == 4u) return vec3(-1.0, 0.0, 0.0);
                if (idx == 5u) return vec3(1.0, 0.0, 0.0);
                return vec3(0.0, 1.0, 0.0);
            }

            void main() {
                uint w0 = aPacked.x;
                uint w1 = aPacked.y;
                uint w2 = aPacked.z;

                float lx = float(w0 & 0x1FFFu) / 256.0;
                float ly = float((w0 >> 13u) & 0x1FFFu) / 256.0;
                float lz = float(w1 & 0x1FFFu) / 256.0;

                uint normIdx = (w0 >> 26u) & 0x7u;
                vNormal = decodeNormal(normIdx);

                vLightBiased = float((w1 >> 13u) & 0x3Fu);
                float uTile = float((w1 >> 19u) & 0x1Fu);
                float vTile = float((w1 >> 24u) & 0x1Fu);
                vTileUv = vec2(uTile, vTile);

                uint tileId = min(w2 & 0xFFu, ${TexturePipeline.MAX_ATLAS_TILES - 1}u);
                vTileRect = uTileRects[tileId];
                vShade = float((w2 >> 8u) & 0xFFu) / 255.0;

                vec3 worldPos = uChunkOrigin + vec3(lx, ly, lz);
                gl_Position = uMVPMatrix * vec4(worldPos, 1.0);
            }
        """.trimIndent()

        val fragmentSource = """
            #version 300 es
            precision mediump float;

            in vec2 vTileUv;
            in vec4 vTileRect;
            in float vShade;
            in float vLightBiased;
            in vec3 vNormal;

            uniform sampler2D uTexture;
            uniform vec3 uSunDir;

            out vec4 fragColor;

            void main() {
                vec2 tileSpan = vTileRect.zw - vTileRect.xy;
                vec2 wrappedUv = vTileUv - max(ceil(vTileUv) - 1.0, 0.0);
                vec2 atlasUv = mix(vTileRect.xy, vTileRect.zw, clamp(wrappedUv, 0.0, 1.0));
                vec2 dx = dFdx(vTileUv) * tileSpan;
                vec2 dy = dFdy(vTileUv) * tileSpan;

                vec4 texColor = textureGrad(uTexture, atlasUv, dx, dy);
                if (texColor.a < 0.01) {
                    discard;
                }

                // Sun directional lighting + TDD-019 signed [-16..+16] voxel brightness curve
                vec3 norm = normalize(vNormal);
                vec3 sun = normalize(uSunDir);
                float sunDiff = max(dot(norm, sun), 0.0) * 0.45;
                float ambient = 0.55;
                float voxelLight = clamp(vLightBiased / 32.0, 0.0, 1.0);

                float totalLight = (ambient + sunDiff) * vShade * voxelLight;
                vec3 litColor = texColor.rgb * totalLight;

                fragColor = vec4(litColor, texColor.a);
            }
        """.trimIndent()

        worldShader = Shader(vertexSource, fragmentSource).apply {
            compile()
        }

        initHighlightGl()

        synchronized(meshSwapLock) {
            for (i in 0 until chunkMeshes.size) {
                val mesh = chunkMeshes[i]
                if (mesh.indexCount > 0) {
                    mesh.initGl()
                }
            }
        }
    }

    private fun initHighlightGl() {
        val vao = IntArray(1)
        val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)

        highlightVaoId = vao[0]
        highlightVboId = vbo[0]

        val lines = floatArrayOf(
            0f, 0f, 0f,  1f, 0f, 0f,
            1f, 0f, 0f,  1f, 0f, 1f,
            1f, 0f, 1f,  0f, 0f, 1f,
            0f, 0f, 1f,  0f, 0f, 0f,
            0f, 1f, 0f,  1f, 1f, 0f,
            1f, 1f, 0f,  1f, 1f, 1f,
            1f, 1f, 1f,  0f, 1f, 1f,
            0f, 1f, 1f,  0f, 1f, 0f,
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
     * Uses allocation-free in-place insertion sorting near ([focusX], [focusZ]).
     */
    fun updateMeshes(
        texturePipeline: TexturePipeline,
        uploadToGpu: Boolean = true,
        focusX: Double = 0.0,
        focusZ: Double = 0.0,
        currentFrameIndex: Long = 0L
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

        val maxCount = if (uploadToGpu) MAX_MESH_UPLOADS_PER_FRAME else Int.MAX_VALUE
        val startNs = if (uploadToGpu) System.nanoTime() else 0L
        var processed = 0
        var anyUpdated = false

        // Drain completed raw FloatArray meshes built by :core workers and upload them on the GL thread
        if (uploadToGpu) {
            while (processed < maxCount) {
                val elapsed = System.nanoTime() - startNs
                if (processed > 0 && elapsed >= MAX_MESH_BUDGET_NS) {
                    break
                }
                if (elapsed >= MAX_DRAIN_BUDGET_NS) {
                    break
                }
                val finishedOutput = parallelMesher.pollCompletedMesh() ?: break
                val finishedChunk = finishedOutput.chunk
                val pos = ChunkPos(finishedChunk.chunkX, finishedChunk.chunkY, finishedChunk.chunkZ)
                val activeMesh = synchronized(meshSwapLock) { chunkMeshMap[pos] }
                if (!currentWorld.isDestroyed &&
                    activeMesh != null &&
                    activeMesh.chunk === finishedChunk &&
                    !finishedChunk.needsMeshRebuild &&
                    finishedChunk.meshDirtyEpoch == finishedOutput.dirtyEpoch
                ) {
                    activeMesh.applyMeshOutput(finishedOutput, texturePipeline)
                    if (activeMesh.hasPendingGpuUpload) {
                        activeMesh.uploadCpuMeshToGpu(nativeBufferPool, currentFrameIndex)
                    }
                    anyUpdated = true
                    processed++
                }
            }
        }

        if (currentWorld.isDestroyed) {
            return
        }

        dirtyQueueScratch.clear()
        synchronized(meshSwapLock) {
            for (i in 0 until chunkMeshes.size) {
                val mesh = chunkMeshes[i]
                val prebuiltAvailable = mesh.rebuildCount == 0 && !mesh.chunk.isEmpty
                if (mesh.chunk.needsMeshRebuild || (uploadToGpu && mesh.hasPendingGpuUpload) || prebuiltAvailable) {
                    val dx = mesh.centerX - focusX
                    val dz = mesh.centerZ - focusZ
                    mesh.sortDistSq = dx * dx + dz * dz
                    dirtyQueueScratch.add(mesh)
                }
            }
        }

        if (dirtyQueueScratch.isEmpty() && !topologyChanged && !anyUpdated) {
            return
        }

        if (uploadToGpu && dirtyQueueScratch.size > 1) {
            sortMeshesAscendingByDistSq(dirtyQueueScratch)
        }

        try {
            var submittedThisFrame = 0
            for (i in 0 until dirtyQueueScratch.size) {
                if (currentWorld.isDestroyed || processed >= maxCount) break
                val elapsed = System.nanoTime() - startNs
                if (uploadToGpu && (processed > 0 || submittedThisFrame >= MAX_SUBMISSIONS_PER_FRAME) && elapsed >= MAX_MESH_BUDGET_NS) {
                    break
                }

                var mesh = dirtyQueueScratch[i]
                val c = mesh.chunk
                val pos = ChunkPos(c.chunkX, c.chunkY, c.chunkZ)

                val prebuilt = currentWorld.consumePrebuiltMesh(pos) as? ChunkMesh
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
                    if (uploadToGpu && parallelMeshingEnabled) {
                        if (parallelMesher.queuedCount < ParallelChunkMesher.MAX_QUEUED_CHUNKS && submittedThisFrame < MAX_SUBMISSIONS_PER_FRAME) {
                            if (parallelMesher.submitDirtyChunk(
                                chunk = c,
                                world = currentWorld,
                                tileResolver = texturePipeline,
                                forceRebuild = (mesh.rebuildCount == 0)
                            )) {
                                submittedThisFrame++
                            }
                        }
                    } else if (uploadToGpu) {
                        mesh.buildMesh(currentWorld, texturePipeline, nativeBufferPool, currentFrameIndex)
                        anyUpdated = true
                        if (!c.isEmpty || mesh.generatedFacesCount > 0) {
                            processed++
                        }
                    } else {
                        mesh.generateMeshData(currentWorld, texturePipeline)
                        anyUpdated = true
                        if (!c.isEmpty || mesh.generatedFacesCount > 0) {
                            processed++
                        }
                    }
                } else if (uploadToGpu && mesh.hasPendingGpuUpload) {
                    mesh.uploadCpuMeshToGpu(nativeBufferPool, currentFrameIndex)
                    anyUpdated = true
                    processed++
                }
            }
        } finally {
            dirtyQueueScratch.clear()
        }

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
     * Extracts the 6 frustum planes from [viewProjectionMatrix], skips empty chunks early, and filters
     * [chunkMeshes] to only those whose world-space [ChunkMesh.worldBounds] intersects the frustum.
     */
    private fun updateVisibleChunks(
        viewProjectionMatrix: FloatArray,
        cameraX: Double = 0.0,
        cameraY: Double = 0.0,
        cameraZ: Double = 0.0,
        sortByDistance: Boolean = false,
        outList: MutableList<ChunkMesh> = visibleChunkMeshes
    ) {
        frustum.extractPlanes(viewProjectionMatrix)
        var totalChunks = 0
        var emptySkipped = 0
        var frustumCulled = 0

        synchronized(meshSwapLock) {
            outList.clear()
            totalChunks = chunkMeshes.size
            for (i in 0 until totalChunks) {
                val mesh = chunkMeshes[i]
                // Skip completely empty or cleanly rebuilt zero-index chunks before frustum tests
                if (mesh.chunk.isEmpty || (mesh.rebuildCount > 0 && mesh.indexCount <= 0)) {
                    emptySkipped++
                    continue
                }
                if (frustum.intersectsRelativeAabb(mesh.worldBounds, cameraX, cameraY, cameraZ)) {
                    if (sortByDistance) {
                        val dx = mesh.centerX - cameraX
                        val dy = mesh.centerY - cameraY
                        val dz = mesh.centerZ - cameraZ
                        mesh.sortDistSq = dx * dx + dy * dy + dz * dz
                    }
                    outList.add(mesh)
                } else {
                    frustumCulled++
                }
            }
        }

        if (sortByDistance && outList.size > 1) {
            sortMeshesAscendingByDistSq(outList)
        }

        visibleChunksCount = outList.size
        emptySkippedChunksCount = emptySkipped
        frustumCulledChunksCount = frustumCulled
    }

    /**
     * Computes and returns a snapshot list of frustum-visible chunk meshes for [viewProjectionMatrix],
     * sorted front-to-back relative to ([cameraX], [cameraY], [cameraZ]).
     */
    fun computeVisibleChunks(
        viewProjectionMatrix: FloatArray,
        cameraX: Double = 0.0,
        cameraY: Double = 0.0,
        cameraZ: Double = 0.0,
        sortFrontToBack: Boolean = true
    ): List<ChunkMesh> {
        updateVisibleChunks(
            viewProjectionMatrix = viewProjectionMatrix,
            cameraX = cameraX,
            cameraY = cameraY,
            cameraZ = cameraZ,
            sortByDistance = sortFrontToBack,
            outList = visibleChunkMeshes
        )
        return synchronized(meshSwapLock) { visibleChunkMeshes.toList() }
    }

    /**
     * Renders frustum-visible non-empty chunk meshes in two sorted passes:
     * - Pass 1 (Opaque): front-to-back for optimal early-Z rejection (GL_BLEND disabled, depth writes on).
     * - Pass 2 (Transparent): back-to-front via reverse iteration for clean alpha blending (depth mask off).
     */
    fun render(
        viewProjectionMatrix: FloatArray,
        texturePipeline: TexturePipeline,
        targetHit: RaycastHit?,
        cameraX: Double = 0.0,
        cameraY: Double = 0.0,
        cameraZ: Double = 0.0
    ) {
        lastFrameAtlasBindCount = 0
        lastRenderedChunksCount = 0
        updateVisibleChunks(
            viewProjectionMatrix = viewProjectionMatrix,
            cameraX = cameraX,
            cameraY = cameraY,
            cameraZ = cameraZ,
            sortByDistance = true,
            outList = renderVisibleScratch
        )
        val visibleChunks = renderVisibleScratch

        val shader = worldShader ?: return
        if (shader.programId == 0) return
        val atlasHandle = texturePipeline.atlasTextureHandle
        if (atlasHandle == 0) return

        shader.bind()

        // Bind texture atlas strictly ONCE for all chunk geometry across both render passes
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasHandle)
        lastFrameAtlasBindCount = 1
        shader.setUniform1i("uTexture", 0)
        texturePipeline.copyAtlasTileRects(tileRectsScratch)
        shader.setUniform4fv("uTileRects", TexturePipeline.MAX_ATLAS_TILES, tileRectsScratch)

        // Sun direction (overhead diagonal)
        shader.setUniform3f("uSunDir", 0.4f, 0.85f, 0.35f)
        shader.setUniformMatrix4fv("uMVPMatrix", viewProjectionMatrix)

        var renderedChunks = 0

        // Pass 1: Opaque blocks front-to-back (maximizes early-Z hardware culling)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        for (i in 0 until visibleChunks.size) {
            val mesh = visibleChunks[i]
            if (mesh.indexCount > 0) {
                renderedChunks++
            }
            if (mesh.opaqueIndexCount > 0) {
                shader.setUniform3f(
                    "uChunkOrigin",
                    (mesh.originX - cameraX).toFloat(),
                    (mesh.originY - cameraY).toFloat(),
                    (mesh.originZ - cameraZ).toFloat()
                )
                mesh.renderOpaque()
            }
        }
        lastRenderedChunksCount = renderedChunks

        // Pass 2: Transparent & cross-model blocks back-to-front (reverse order of sorted array)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        for (i in visibleChunks.size - 1 downTo 0) {
            val mesh = visibleChunks[i]
            if (mesh.transparentIndexCount > 0) {
                shader.setUniform3f(
                    "uChunkOrigin",
                    (mesh.originX - cameraX).toFloat(),
                    (mesh.originY - cameraY).toFloat(),
                    (mesh.originZ - cameraZ).toFloat()
                )
                mesh.renderTransparent()
            }
        }
        GLES30.glDepthMask(true)

        shader.unbind()

        if (targetHit != null && highlightShader != null && highlightVaoId != 0) {
            renderBlockOutline(viewProjectionMatrix, targetHit.blockPos, cameraX, cameraY, cameraZ)
        }
    }

    private fun renderBlockOutline(
        viewProjMatrix: FloatArray,
        pos: BlockPos,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double
    ) {
        val inset = 0.003
        renderAabbOutline(
            viewProjMatrix = viewProjMatrix,
            minX = (pos.x - inset - cameraX).toFloat(),
            minY = (pos.y - inset - cameraY).toFloat(),
            minZ = (pos.z - inset - cameraZ).toFloat(),
            maxX = (pos.x + 1.0 + inset - cameraX).toFloat(),
            maxY = (pos.y + 1.0 + inset - cameraY).toFloat(),
            maxZ = (pos.z + 1.0 + inset - cameraZ).toFloat(),
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
        player: Player? = null,
        cameraX: Double = 0.0,
        cameraY: Double = 0.0,
        cameraZ: Double = 0.0
    ) {
        if (!showHitboxes) return
        if (highlightShader == null || highlightVaoId == 0) return

        for (i in 0 until entities.size) {
            val e = entities[i]
            if (e.isRemoved) continue
            val box = e.boundingBox
            renderAabbOutline(
                viewProjMatrix = viewProjMatrix,
                minX = (box.minX - cameraX).toFloat(),
                minY = (box.minY - cameraY).toFloat(),
                minZ = (box.minZ - cameraZ).toFloat(),
                maxX = (box.maxX - cameraX).toFloat(),
                maxY = (box.maxY - cameraY).toFloat(),
                maxZ = (box.maxZ - cameraZ).toFloat(),
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
                minX = (box.minX - cameraX).toFloat(),
                minY = (box.minY - cameraY).toFloat(),
                minZ = (box.minZ - cameraZ).toFloat(),
                maxX = (box.maxX - cameraX).toFloat(),
                maxY = (box.maxY - cameraY).toFloat(),
                maxZ = (box.maxZ - cameraZ).toFloat(),
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
        synchronized(meshSwapLock) {
            for (i in 0 until chunkMeshes.size) {
                chunkMeshes[i].chunk.markDirty()
            }
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
        parallelMesher.clearPending()

        val allToRelease = synchronized(meshSwapLock) {
            val combined = ArrayList<ChunkMesh>(chunkMeshes.size + pendingReleaseMeshes.size)
            combined.addAll(chunkMeshes)
            combined.addAll(pendingReleaseMeshes)
            chunkMeshes.clear()
            chunkMeshMap.clear()
            visibleChunkMeshes.clear()
            renderVisibleScratch.clear()
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
        private const val MAX_SUBMISSIONS_PER_FRAME = 8
        private const val MAX_MESH_BUDGET_NS = 4_000_000L // 4ms
        private const val MAX_DRAIN_BUDGET_NS = 8_000_000L // 8ms

        /** Fast sort ascending by [ChunkMesh.sortDistSq]: insertion sort for small lists, TimSort for large. */
        private fun sortMeshesAscendingByDistSq(list: MutableList<ChunkMesh>) {
            if (list.size <= 32) {
                for (i in 1 until list.size) {
                    val key = list[i]
                    val keyDist = key.sortDistSq
                    var j = i - 1
                    while (j >= 0 && list[j].sortDistSq > keyDist) {
                        list[j + 1] = list[j]
                        j--
                    }
                    list[j + 1] = key
                }
            } else {
                list.sortBy { it.sortDistSq }
            }
        }
    }
}
