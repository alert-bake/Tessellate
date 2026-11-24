package com.nitrous.tessellate.world

import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.entity.ItemDropMerger
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.worldgen.WorldGenRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.math.floor

/**
 * Chunk-aligned integer coordinates used as keys in the loaded chunk map.
 */
data class ChunkPos(
    val x: Int,
    val y: Int,
    val z: Int
)

/**
 * Procedural voxel world container managing chunk storage, async chunk streaming, and entities.
 *
 * - Default and session constructors create an infinite streaming world (`isInfinite = true`,
 *   `renderDistance = 4`, `heightChunks = 8` for Y=0..127) backed by a [HashMap]<[ChunkPos], [Chunk]>
 *   and coroutine streaming on [Dispatchers.Default].
 * - The bounded constructor `World(chunksX, chunksY, chunksZ)` creates a finite flat world for tests
 *   and diagnostic harnesses.
 */
class World private constructor(
    val session: WorldSession,
    val seed: Long,
    @Volatile
    var renderDistance: Int,
    val chunksX: Int,
    val chunksY: Int,
    val chunksZ: Int,
    val isInfinite: Boolean,
    val saveStorage: WorldChunkStorage? = null,
    private val initialCenterX: Float = 24.5f,
    private val initialCenterZ: Float = 24.5f
) {
    /**
     * Creates an infinite procedural world configured by a player's [WorldSession] and optional save storage.
     */
    constructor(
        session: WorldSession,
        renderDistance: Int = 4,
        heightChunks: Int = 8,
        saveStorage: WorldChunkStorage? = null,
        initialCenterX: Float = 24.5f,
        initialCenterZ: Float = 24.5f
    ) : this(
        session = session,
        seed = session.seed,
        renderDistance = renderDistance.coerceAtLeast(1),
        chunksX = 3,
        chunksY = heightChunks.coerceAtLeast(1),
        chunksZ = 3,
        isInfinite = true,
        saveStorage = saveStorage,
        initialCenterX = initialCenterX,
        initialCenterZ = initialCenterZ
    )

    /**
     * Creates a default survival infinite procedural world using the given terrain [seed].
     */
    constructor(
        seed: Long = 1337L,
        renderDistance: Int = 4,
        heightChunks: Int = 8
    ) : this(
        session = WorldSession(
            name = WorldSession.DEFAULT_WORLD_NAME,
            gamemode = GameMode.SURVIVAL,
            worldType = WorldType.DEFAULT,
            seed = seed
        ),
        renderDistance = renderDistance,
        heightChunks = heightChunks
    )

    /**
     * Creates a finite, flat bounded world of `chunksX x chunksY x chunksZ` chunks.
     */
    constructor(
        chunksX: Int,
        chunksY: Int,
        chunksZ: Int
    ) : this(
        session = WorldSession(
            name = "Finite",
            gamemode = GameMode.SURVIVAL,
            worldType = WorldType.FLAT,
            seed = 1337L
        ),
        seed = 1337L,
        renderDistance = maxOf(chunksX, chunksZ),
        chunksX = chunksX,
        chunksY = chunksY,
        chunksZ = chunksZ,
        isInfinite = false
    )

    val sizeX: Int = chunksX * Chunk.SIZE
    val sizeY: Int = chunksY * Chunk.SIZE
    val sizeZ: Int = chunksZ * Chunk.SIZE

    private val chunkLock = Any()
    private val chunks = HashMap<ChunkPos, Chunk>()
    private val fastChunks = ChunkFastMap()
    private val prebuiltMeshes = ConcurrentHashMap<ChunkPos, PrebuiltChunkMesh>()
    private val vaporizedChunks = HashSet<ChunkPos>()

    val lightEngine = LightEngine()
    val lightQueue: LightEngine
        get() = lightEngine

    private val streamExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            LOGGER.log(Level.WARNING, "World chunk streaming failed safely", throwable)
        }
    }
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + streamExceptionHandler)
    private val streamControlLock = Any()
    private var activeStreamJob: Job? = null
    private var lastStreamCenterCx: Int = Int.MIN_VALUE
    private var lastStreamCenterCz: Int = Int.MIN_VALUE

    @Volatile
    private var isStreamWindowUpToDate: Boolean = false

    val asyncGeneratedChunksCount = AtomicInteger(0)
    val chunkTopologyVersion = AtomicLong(0L)
    private val streamEpoch = AtomicLong(0L)

    @Volatile
    var lastChunkGenMs: Float = 0f
        private set

    @Volatile
    var lastTickedEntitiesCount: Int = 0
        private set

    @Volatile
    var lastSleepingEntitiesCount: Int = 0
        private set

    @Volatile
    var isDestroyed: Boolean = false
        private set

    val entities = mutableListOf<Entity>()
    private val pendingSpawnEntities = mutableListOf<Entity>()
    private val entityTickScratch = ArrayList<Entity>()
    private val itemDropMerger = ItemDropMerger()
    private val entityLock = Any()
    private var isTickingEntities = false

    init {
        RegistryBootstrap.ensureInitialized()
        generateTerrain()
    }

    fun getChunk(cx: Int, cy: Int, cz: Int): Chunk? {
        if (cy !in 0 until chunksY) return null
        if (!isInfinite && (cx !in 0 until chunksX || cz !in 0 until chunksZ)) {
            return null
        }
        val key = packChunkKey(cx, cy, cz)
        return synchronized(chunkLock) {
            fastChunks.get(key)
        }
    }

    fun hasChunk(cx: Int, cy: Int, cz: Int): Boolean = getChunk(cx, cy, cz) != null

    fun getLoadedChunkCount(): Int = synchronized(chunkLock) { chunks.size }

    fun getAllChunks(): List<Chunk> = synchronized(chunkLock) {
        chunks.values.toList()
    }

    fun consumePrebuiltMesh(pos: ChunkPos): PrebuiltChunkMesh? {
        return prebuiltMeshes.remove(pos)
    }

    fun stepLighting(
        maxNodes: Int = lightEngine.maxNodesPerFrame,
        budgetNs: Long = lightEngine.maxBudgetNs
    ): Int = lightEngine.step(this, maxNodes, budgetNs)

    fun isHorizontalInBounds(wx: Int, wz: Int): Boolean {
        return if (isInfinite) true else (wx in 0 until sizeX && wz in 0 until sizeZ)
    }

    fun getBlock(wx: Int, wy: Int, wz: Int): BlockType {
        if (wy !in 0 until sizeY) return BlockType.AIR
        if (!isInfinite && (wx !in 0 until sizeX || wz !in 0 until sizeZ)) {
            return BlockType.AIR
        }
        val cx = wx shr 4
        val cy = wy shr 4
        val cz = wz shr 4

        val chunk = getChunk(cx, cy, cz) ?: return BlockType.AIR
        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK
        return chunk.getBlock(lx, ly, lz)
    }

    fun getBlock(pos: BlockPos): BlockType = getBlock(pos.x, pos.y, pos.z)

    /**
     * Returns the authoritative gameplay light (`-16..+16`) at `(wx, wy, wz)`.
     */
    fun getIntendedLight(wx: Int, wy: Int, wz: Int): Byte {
        if (wy >= sizeY) return Chunk.MAX_LIGHT.toByte()
        if (wy < 0) return Chunk.MIN_LIGHT.toByte()
        if (!isInfinite && (wx !in 0 until sizeX || wz !in 0 until sizeZ)) {
            return Chunk.MAX_LIGHT.toByte()
        }
        val chunk = getChunk(wx shr 4, wy shr 4, wz shr 4) ?: return Chunk.DEFAULT_AMBIENT_LIGHT.toByte()
        return chunk.getIntendedLightUnchecked(wx and Chunk.MASK, wy and Chunk.MASK, wz and Chunk.MASK).toByte()
    }

    /**
     * Returns the authoritative gameplay light (`-16..+16`) at [pos].
     */
    fun getIntendedLight(pos: BlockPos): Byte = getIntendedLight(pos.x, pos.y, pos.z)

    /**
     * Returns the visual rendered light (`-16..+16`) at `(wx, wy, wz)`.
     */
    fun getRenderedLight(wx: Int, wy: Int, wz: Int): Byte {
        if (wy >= sizeY) return Chunk.MAX_LIGHT.toByte()
        if (wy < 0) return Chunk.MIN_LIGHT.toByte()
        if (!isInfinite && (wx !in 0 until sizeX || wz !in 0 until sizeZ)) {
            return Chunk.MAX_LIGHT.toByte()
        }
        val chunk = getChunk(wx shr 4, wy shr 4, wz shr 4) ?: return Chunk.DEFAULT_AMBIENT_LIGHT.toByte()
        return chunk.getRenderedLightUnchecked(wx and Chunk.MASK, wy and Chunk.MASK, wz and Chunk.MASK).toByte()
    }

    fun setBlock(wx: Int, wy: Int, wz: Int, type: BlockType): Boolean {
        if (isDestroyed) return false
        if (wy !in 0 until sizeY) return false
        if (!isInfinite && (wx !in 0 until sizeX || wz !in 0 until sizeZ)) {
            return false
        }
        val cx = wx shr 4
        val cy = wy shr 4
        val cz = wz shr 4

        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK

        val targetChunk = synchronized(chunkLock) {
            val key = packChunkKey(cx, cy, cz)
            fastChunks.get(key) ?: if (isInfinite) {
                val pos = ChunkPos(cx, cy, cz)
                Chunk(cx, cy, cz).also { created ->
                    chunks[pos] = created
                    fastChunks.put(key, created)
                    chunkTopologyVersion.incrementAndGet()
                }
            } else {
                null
            }
        } ?: return false

        val oldBlock = targetChunk.getBlock(lx, ly, lz)
        val changed = targetChunk.setBlock(lx, ly, lz, type)

        if (changed) {
            lightEngine.onBlockChanged(this, wx, wy, wz, oldBlock, type)

            if (lx == 0) getChunk(cx - 1, cy, cz)?.markDirty()
            if (lx == Chunk.MASK) getChunk(cx + 1, cy, cz)?.markDirty()

            if (ly == 0 && cy > 0) getChunk(cx, cy - 1, cz)?.markDirty()
            if (ly == Chunk.MASK && cy < chunksY - 1) getChunk(cx, cy + 1, cz)?.markDirty()

            if (lz == 0) getChunk(cx, cy, cz - 1)?.markDirty()
            if (lz == Chunk.MASK) getChunk(cx, cy, cz + 1)?.markDirty()
        }

        return changed
    }

    fun setBlock(pos: BlockPos, type: BlockType): Boolean = setBlock(pos.x, pos.y, pos.z, type)

    /**
     * Generates initial terrain:
     * - Infinite mode: populates the immediate spawn core (`cx in 0..2, cz in 0..2`) synchronously
     *   through the Atomic Lasagna pipeline (`WorldGenRegistry`) so spawn placement and surface queries
     *   work immediately, while outer rings stream asynchronously via [streamChunksAround].
     * - Finite legacy mode: populates the flat `tessellate:stone` plane across `chunksX x chunksY x chunksZ`.
     */
    fun generateTerrain() {
        lightEngine.clear()
        if (isInfinite) {
            generateInfiniteSpawnCore()
        } else {
            generateFiniteFlatTerrain()
        }
    }

    private fun generateInfiniteSpawnCore() {
        val genStartNs = System.nanoTime()
        synchronized(chunkLock) {
            chunks.clear()
            fastChunks.clear()
            vaporizedChunks.clear()
        }
        prebuiltMeshes.clear()
        lastStreamCenterCx = Int.MIN_VALUE
        lastStreamCenterCz = Int.MIN_VALUE
        isStreamWindowUpToDate = false

        val centerCx = floor(initialCenterX).toInt() shr 4
        val centerCz = floor(initialCenterZ).toInt() shr 4

        for (cx in (centerCx - 1)..(centerCx + 1)) {
            for (cz in (centerCz - 1)..(centerCz + 1)) {
                for (cy in chunksY - 1 downTo 0) {
                    val pos = ChunkPos(cx, cy, cz)
                    val (chunk, restoredEntities) = buildOrLoadChunk(pos)
                    val key = packChunkKey(cx, cy, cz)
                    synchronized(chunkLock) {
                        chunks[pos] = chunk
                        fastChunks.put(key, chunk)
                    }
                    lightEngine.initializeChunkLighting(chunk, this)
                    for (entity in restoredEntities) {
                        spawnEntity(entity)
                    }
                }
            }
        }
        lastChunkGenMs = (System.nanoTime() - genStartNs) / 1_000_000f
        chunkTopologyVersion.incrementAndGet()
    }

    private fun buildOrLoadChunk(pos: ChunkPos): Pair<Chunk, List<Entity>> {
        val chunk = Chunk(pos.x, pos.y, pos.z)
        val savedBytes = saveStorage?.loadChunkBytes(pos)
        if (savedBytes != null && savedBytes.size == Chunk.VOLUME) {
            System.arraycopy(savedBytes, 0, chunk.blocks, 0, Chunk.VOLUME)
            chunk.recalculateEmpty()
            chunk.markDirty()
        } else {
            WorldGenRegistry.generateChunk(chunk, this)
        }
        val restoredEntities = saveStorage?.loadEntities(pos).orEmpty()
        return chunk to restoredEntities
    }

    private fun generateFiniteFlatTerrain() {
        val air = BlockType.AIR
        val stone = BlockType.fromIdentifier("tessellate:stone") ?: BlockType.STONE
        val floorLayers = 7.coerceAtMost(sizeY - 1) // wy in 0..6 = tessellate:stone, wy >= 7 = air

        synchronized(chunkLock) {
            chunks.clear()
            fastChunks.clear()
            for (cx in 0 until chunksX) {
                for (cy in 0 until chunksY) {
                    for (cz in 0 until chunksZ) {
                        val c = Chunk(cx, cy, cz)
                        chunks[ChunkPos(cx, cy, cz)] = c
                        fastChunks.put(packChunkKey(cx, cy, cz), c)
                    }
                }
            }
        }
        prebuiltMeshes.clear()

        for (wx in 0 until sizeX) {
            for (wz in 0 until sizeZ) {
                for (wy in 0 until sizeY) {
                    val block = if (wy < floorLayers) stone else air
                    setBlock(wx, wy, wz, block)
                }
            }
        }

        synchronized(chunkLock) {
            for (c in chunks.values) {
                c.recalculateEmpty()
                c.markDirty()
            }
            chunkTopologyVersion.incrementAndGet()
        }
        lightEngine.clear()
        for (cx in 0 until chunksX) {
            for (cz in 0 until chunksZ) {
                for (cy in chunksY - 1 downTo 0) {
                    getChunk(cx, cy, cz)?.let { lightEngine.initializeChunkLighting(it, this) }
                }
            }
        }
    }

    /**
     * Synchronously ensures a single chunk column at `(cx, cz)` is generated or loaded from disk.
     */
    fun ensureChunkColumnLoadedSync(cx: Int, cz: Int) {
        if (!isInfinite) return
        val genStartNs = System.nanoTime()
        for (cy in chunksY - 1 downTo 0) {
            val pos = ChunkPos(cx, cy, cz)
            val key = packChunkKey(cx, cy, cz)
            val isVaporized = synchronized(chunkLock) { vaporizedChunks.contains(pos) }
            if (!isVaporized && getChunk(cx, cy, cz) == null) {
                val (chunk, restoredEntities) = buildOrLoadChunk(pos)
                val inserted = synchronized(chunkLock) {
                    if (fastChunks.get(key) == null && !vaporizedChunks.contains(pos)) {
                        chunks[pos] = chunk
                        fastChunks.put(key, chunk)
                        chunkTopologyVersion.incrementAndGet()
                        true
                    } else {
                        false
                    }
                }
                if (inserted) {
                    lightEngine.initializeChunkLighting(chunk, this)
                    for (entity in restoredEntities) {
                        spawnEntity(entity)
                    }
                }
            }
        }
        lastChunkGenMs = (System.nanoTime() - genStartNs) / 1_000_000f
    }

    /**
     * Safely updates [renderDistance] and immediately triggers chunk unloading/loading around the player.
     */
    fun updateRenderDistance(
        newDistance: Int,
        playerX: Float,
        playerZ: Float,
        meshPrebuilder: ChunkMeshPrebuilder? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default
    ): Job? {
        val clamped = newDistance.coerceIn(1, 16)
        if (renderDistance != clamped) {
            renderDistance = clamped
        }
        isStreamWindowUpToDate = false
        return streamChunksAround(
            playerX = playerX,
            playerZ = playerZ,
            meshPrebuilder = meshPrebuilder,
            ioDispatcher = ioDispatcher,
            forceCheck = true
        )
    }

    /**
     * Safely removes a chunk at ([cx], [cy], [cz]), cleans up entities inside it, marks adjacent
     * loaded neighbor chunks dirty so boundary faces update, and increments [chunkTopologyVersion].
     */
    fun vaporizeChunkAt(cx: Int, cy: Int, cz: Int): Boolean {
        if (cy !in 0 until chunksY) return false
        if (!isInfinite && (cx !in 0 until chunksX || cz !in 0 until chunksZ)) return false

        val pos = ChunkPos(cx, cy, cz)
        val key = packChunkKey(cx, cy, cz)
        val removed = synchronized(chunkLock) {
            prebuiltMeshes.remove(pos)
            vaporizedChunks.add(pos)
            val existing = chunks.remove(pos)
            fastChunks.remove(key)
            if (existing != null) {
                chunkTopologyVersion.incrementAndGet()
                true
            } else {
                false
            }
        }

        synchronized(entityLock) {
            val entIter = entities.iterator()
            while (entIter.hasNext()) {
                val entity = entIter.next()
                if (chunkEntityPos(entity, chunksY) == pos) {
                    entity.isRemoved = true
                    if (!isTickingEntities) entIter.remove()
                }
            }
            val pendIter = pendingSpawnEntities.iterator()
            while (pendIter.hasNext()) {
                val entity = pendIter.next()
                if (chunkEntityPos(entity, chunksY) == pos) {
                    entity.isRemoved = true
                    if (!isTickingEntities) pendIter.remove()
                }
            }
        }

        getChunk(cx - 1, cy, cz)?.markDirty()
        getChunk(cx + 1, cy, cz)?.markDirty()
        if (cy > 0) getChunk(cx, cy - 1, cz)?.markDirty()
        if (cy < chunksY - 1) getChunk(cx, cy + 1, cz)?.markDirty()
        getChunk(cx, cy, cz - 1)?.markDirty()
        getChunk(cx, cy, cz + 1)?.markDirty()

        return removed
    }

    /**
     * Streams chunks around `(playerX, playerZ)` within [renderDistance]:
     * unloads out-of-range chunks (saving non-empty chunks and their active entities asynchronously
     * on [Dispatchers.IO]) and loads or generates missing columns nearest-first on a background coroutine.
     */
    fun streamChunksAround(
        playerX: Float,
        playerZ: Float,
        meshPrebuilder: ChunkMeshPrebuilder? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
        forceCheck: Boolean = false
    ): Job? {
        if (!isInfinite || isDestroyed) return null

        val centerCx = floor(playerX).toInt() shr 4
        val centerCz = floor(playerZ).toInt() shr 4
        val currentEpoch: Long
        synchronized(streamControlLock) {
            if (isDestroyed) return null
            if (!forceCheck && centerCx == lastStreamCenterCx && centerCz == lastStreamCenterCz) {
                val currentJob = activeStreamJob
                if (isStreamWindowUpToDate || (currentJob != null && currentJob.isActive)) {
                    return currentJob
                }
            }
            lastStreamCenterCx = centerCx
            lastStreamCenterCz = centerCz
            isStreamWindowUpToDate = false
            currentEpoch = streamEpoch.incrementAndGet()
            activeStreamJob?.cancel()
            activeStreamJob = null
        }

        // 1. Unload chunks outside render distance immediately to bound memory
        val unloadedChunks = ArrayList<Pair<ChunkPos, Chunk>>()
        synchronized(chunkLock) {
            val iterator = chunks.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val pos = entry.key
                if (abs(pos.x - centerCx) > renderDistance || abs(pos.z - centerCz) > renderDistance) {
                    unloadedChunks.add(pos to entry.value)
                    fastChunks.remove(packChunkKey(pos.x, pos.y, pos.z))
                    iterator.remove()
                    prebuiltMeshes.remove(pos)
                }
            }
            if (unloadedChunks.isNotEmpty()) {
                chunkTopologyVersion.incrementAndGet()
            }
        }

        // Persist unloaded chunks and their active entities off the render thread
        if (unloadedChunks.isNotEmpty() && saveStorage != null && !isDestroyed) {
            val unloadedPositions = HashSet<ChunkPos>(unloadedChunks.size)
            for ((pos, _) in unloadedChunks) {
                unloadedPositions.add(pos)
            }
            val entitiesByChunk = HashMap<ChunkPos, MutableList<Entity>>()
            synchronized(entityLock) {
                val entIter = entities.iterator()
                while (entIter.hasNext()) {
                    val entity = entIter.next()
                    if (entity.isRemoved) continue
                    val ePos = chunkEntityPos(entity, chunksY)
                    if (unloadedPositions.contains(ePos)) {
                        entitiesByChunk.getOrPut(ePos) { mutableListOf() }.add(entity)
                        if (!isTickingEntities) {
                            entIter.remove()
                        }
                    }
                }
                val pendIter = pendingSpawnEntities.iterator()
                while (pendIter.hasNext()) {
                    val entity = pendIter.next()
                    if (entity.isRemoved) continue
                    val ePos = chunkEntityPos(entity, chunksY)
                    if (unloadedPositions.contains(ePos)) {
                        entitiesByChunk.getOrPut(ePos) { mutableListOf() }.add(entity)
                        if (!isTickingEntities) {
                            pendIter.remove()
                        }
                    }
                }
            }

            for ((pos, chunk) in unloadedChunks) {
                val chunkEntities = entitiesByChunk[pos].orEmpty()
                saveStorage.saveChunkAndEntitiesAsync(chunk, chunkEntities)
                for (entity in chunkEntities) {
                    entity.isRemoved = true
                }
            }
        }

        // 2. Collect missing chunk columns sorted nearest-first to player
        val missingColumns = mutableListOf<Pair<Int, Int>>()
        synchronized(chunkLock) {
            val hasVaporized = vaporizedChunks.isNotEmpty()
            for (cx in (centerCx - renderDistance)..(centerCx + renderDistance)) {
                for (cz in (centerCz - renderDistance)..(centerCz + renderDistance)) {
                    var columnNeedsLoad = false
                    for (cy in 0 until chunksY) {
                        val key = packChunkKey(cx, cy, cz)
                        if (fastChunks.get(key) == null) {
                            if (!hasVaporized || !vaporizedChunks.contains(ChunkPos(cx, cy, cz))) {
                                columnNeedsLoad = true
                                break
                            }
                        }
                    }
                    if (columnNeedsLoad) {
                        missingColumns.add(cx to cz)
                    }
                }
            }
        }

        if (missingColumns.isEmpty()) {
            synchronized(streamControlLock) {
                if (streamEpoch.get() == currentEpoch) {
                    activeStreamJob?.cancel()
                    activeStreamJob = null
                    isStreamWindowUpToDate = true
                }
            }
            return null
        }

        missingColumns.sortBy { (cx, cz) ->
            val dx = cx - centerCx
            val dz = cz - centerCz
            dx * dx + dz * dz
        }

        val job = streamScope.launch(ioDispatcher) {
            try {
                for ((cx, cz) in missingColumns) {
                    if (!isActive || isDestroyed || streamEpoch.get() != currentEpoch) break
                    kotlinx.coroutines.yield()
                    val (activeCenterX, activeCenterZ) = synchronized(streamControlLock) {
                        lastStreamCenterCx to lastStreamCenterCz
                    }
                    if (abs(cx - activeCenterX) > renderDistance ||
                        abs(cz - activeCenterZ) > renderDistance
                    ) {
                        continue
                    }

                    val colGenStartNs = System.nanoTime()
                    val builtColumn = ArrayList<Pair<ChunkPos, Pair<Chunk, List<Entity>>>>(chunksY)
                    val columnChunks = arrayOfNulls<Chunk>(chunksY)
                    for (cy in chunksY - 1 downTo 0) {
                        if (!isActive || isDestroyed || streamEpoch.get() != currentEpoch) break
                        val pos = ChunkPos(cx, cy, cz)
                        val key = packChunkKey(cx, cy, cz)
                        val skip = synchronized(chunkLock) { fastChunks.get(key) != null || vaporizedChunks.contains(pos) }
                        if (skip) continue
                        val built = buildOrLoadChunk(pos)
                        builtColumn.add(pos to built)
                        columnChunks[cy] = built.first
                    }
                    if (!isActive || isDestroyed || streamEpoch.get() != currentEpoch || builtColumn.isEmpty()) continue

                    // Initialize column lighting top-down BEFORE publishing chunks to GL/render readers
                    for ((_, built) in builtColumn) {
                        if (!isActive || isDestroyed || streamEpoch.get() != currentEpoch) break
                        lightEngine.initializeChunkLighting(built.first, this@World) { lx, ly, lz ->
                            if (lx == cx && lz == cz && ly in 0 until chunksY) columnChunks[ly] else getChunk(lx, ly, lz)
                        }
                    }
                    if (!isActive || isDestroyed || streamEpoch.get() != currentEpoch) continue

                    val insertedChunks = ArrayList<Pair<ChunkPos, Chunk>>(builtColumn.size)
                    val entitiesToSpawn = ArrayList<Entity>()
                    synchronized(chunkLock) {
                        val (curCx, curCz) = synchronized(streamControlLock) {
                            lastStreamCenterCx to lastStreamCenterCz
                        }
                        if (!isDestroyed &&
                            streamEpoch.get() == currentEpoch &&
                            abs(cx - curCx) <= renderDistance &&
                            abs(cz - curCz) <= renderDistance
                        ) {
                            for ((pos, built) in builtColumn) {
                                val (chunk, restoredEntities) = built
                                val key = packChunkKey(pos.x, pos.y, pos.z)
                                if (fastChunks.get(key) == null && !vaporizedChunks.contains(pos)) {
                                    chunks[pos] = chunk
                                    fastChunks.put(key, chunk)
                                    insertedChunks.add(pos to chunk)
                                    asyncGeneratedChunksCount.incrementAndGet()
                                    if (restoredEntities.isNotEmpty()) {
                                        entitiesToSpawn.addAll(restoredEntities)
                                    }
                                }
                            }
                            if (insertedChunks.isNotEmpty()) {
                                chunkTopologyVersion.incrementAndGet()
                            }
                        }
                    }
                    if (insertedChunks.isEmpty()) continue
                    lastChunkGenMs = (System.nanoTime() - colGenStartNs) / 1_000_000f

                    for (entity in entitiesToSpawn) {
                        spawnEntity(entity)
                    }

                    // Mark adjacent loaded non-empty neighbor chunks dirty so cross-chunk faces update cleanly
                    for ((pos, _) in insertedChunks) {
                        val cy = pos.y
                        getChunk(cx - 1, cy, cz)?.let { if (!it.isEmpty) it.markDirty() }
                        getChunk(cx + 1, cy, cz)?.let { if (!it.isEmpty) it.markDirty() }
                        getChunk(cx, cy, cz - 1)?.let { if (!it.isEmpty) it.markDirty() }
                        getChunk(cx, cy, cz + 1)?.let { if (!it.isEmpty) it.markDirty() }
                    }

                    // Optional experimental off-thread CPU chunk mesh prebuilding (disabled by default via Debug Options)
                    if (meshPrebuilder != null && isActive && !isDestroyed && prebuiltMeshes.size < MAX_PREBUILT_MESHES) {
                        for ((pos, chunk) in insertedChunks) {
                            if (!isActive || isDestroyed || prebuiltMeshes.size >= MAX_PREBUILT_MESHES) break
                            if (!chunk.isEmpty) {
                                val epochBeforeBuild = chunk.meshDirtyEpoch
                                val prebuilt = meshPrebuilder.buildPrebuiltMesh(this@World, chunk)
                                if (!isDestroyed && chunk.meshDirtyEpoch == epochBeforeBuild) {
                                    prebuiltMeshes[pos] = prebuilt
                                }
                            } else {
                                chunk.needsMeshRebuild = false
                            }
                        }
                    }
                }
                synchronized(streamControlLock) {
                    if (isActive && !isDestroyed && streamEpoch.get() == currentEpoch) {
                        isStreamWindowUpToDate = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "Background chunk streaming caught exception", t)
            }
        }
        synchronized(streamControlLock) {
            if (streamEpoch.get() == currentEpoch && !isDestroyed) {
                activeStreamJob = job
            } else {
                job.cancel()
            }
        }
        return job
    }

    fun shutdownStreaming() {
        synchronized(streamControlLock) {
            streamEpoch.incrementAndGet()
            activeStreamJob?.cancel()
            activeStreamJob = null
            isStreamWindowUpToDate = false
        }
        streamScope.cancel()
    }

    /**
     * Destroys this world session, cancelling background chunk streaming and releasing chunk/entity memory.
     */
    fun destroy() {
        isDestroyed = true
        shutdownStreaming()
        lightEngine.clear()
        clearEntities()
        synchronized(chunkLock) {
            chunks.clear()
            fastChunks.clear()
            chunkTopologyVersion.incrementAndGet()
        }
        prebuiltMeshes.clear()
        synchronized(streamControlLock) {
            lastStreamCenterCx = Int.MIN_VALUE
            lastStreamCenterCz = Int.MIN_VALUE
        }
    }

    fun getSurfaceHeight(wx: Int, wz: Int): Int {
        if (isInfinite) {
            val cx = wx shr 4
            val cz = wz shr 4
            ensureChunkColumnLoadedSync(cx, cz)
        }
        for (wy in sizeY - 1 downTo 0) {
            val block = getBlock(wx, wy, wz)
            if (block.isSolid || block.isTransparent) {
                return wy
            }
        }
        return 0
    }

    /**
     * Raycasts through the voxel grid from an origin along a direction vector.
     * Returns the hit block and the face normal that was struck.
     */
    fun raycast(
        originX: Float, originY: Float, originZ: Float,
        dirX: Float, dirY: Float, dirZ: Float,
        maxDistance: Float = 25f
    ): RaycastHit? {
        if (dirX == 0f && dirY == 0f && dirZ == 0f) return null

        var x = floor(originX).toInt()
        var y = floor(originY).toInt()
        var z = floor(originZ).toInt()

        val stepX = if (dirX > 0f) 1 else if (dirX < 0f) -1 else 0
        val stepY = if (dirY > 0f) 1 else if (dirY < 0f) -1 else 0
        val stepZ = if (dirZ > 0f) 1 else if (dirZ < 0f) -1 else 0

        val tDeltaX = if (stepX != 0) kotlin.math.abs(1f / dirX) else Float.MAX_VALUE
        val tDeltaY = if (stepY != 0) kotlin.math.abs(1f / dirY) else Float.MAX_VALUE
        val tDeltaZ = if (stepZ != 0) kotlin.math.abs(1f / dirZ) else Float.MAX_VALUE

        var tMaxX = when {
            stepX > 0 -> (x + 1 - originX) * tDeltaX
            stepX < 0 -> (originX - x) * tDeltaX
            else -> Float.MAX_VALUE
        }
        var tMaxY = when {
            stepY > 0 -> (y + 1 - originY) * tDeltaY
            stepY < 0 -> (originY - y) * tDeltaY
            else -> Float.MAX_VALUE
        }
        var tMaxZ = when {
            stepZ > 0 -> (z + 1 - originZ) * tDeltaZ
            stepZ < 0 -> (originZ - z) * tDeltaZ
            else -> Float.MAX_VALUE
        }

        var hitFace = Direction.UP
        var distance = 0f

        while (distance <= maxDistance) {
            val block = getBlock(x, y, z)
            if (block.isSolid || block.isTransparent) {
                return RaycastHit(
                    blockPos = BlockPos(x, y, z),
                    blockType = block,
                    face = hitFace,
                    distance = distance
                )
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    distance = tMaxX
                    x += stepX
                    tMaxX += tDeltaX
                    hitFace = if (stepX > 0) Direction.WEST else Direction.EAST
                } else {
                    distance = tMaxZ
                    z += stepZ
                    tMaxZ += tDeltaZ
                    hitFace = if (stepZ > 0) Direction.NORTH else Direction.SOUTH
                }
            } else {
                if (tMaxY < tMaxZ) {
                    distance = tMaxY
                    y += stepY
                    tMaxY += tDeltaY
                    hitFace = if (stepY > 0) Direction.DOWN else Direction.UP
                } else {
                    distance = tMaxZ
                    z += stepZ
                    tMaxZ += tDeltaZ
                    hitFace = if (stepZ > 0) Direction.NORTH else Direction.SOUTH
                }
            }
        }
        return null
    }

    /**
     * Registers an entity into the world safely across UI and GL threads. If called while
     * entities are ticking, queues the entity until the end of the current tick.
     */
    fun spawnEntity(entity: Entity) {
        synchronized(entityLock) {
            if (entity.isRemoved) return
            if (entities.any { it === entity } || pendingSpawnEntities.any { it === entity }) return

            if (isTickingEntities) {
                pendingSpawnEntities.add(entity)
            } else {
                entities.add(entity)
            }
        }
    }

    /**
     * Marks an entity as removed and removes it from active world storage cleanly.
     */
    fun removeEntity(entity: Entity) {
        synchronized(entityLock) {
            entity.isRemoved = true
            if (!isTickingEntities) {
                entities.removeAll { it === entity }
                pendingSpawnEntities.removeAll { it === entity }
            }
        }
    }

    /**
     * Copies current active entities into [dest] under [entityLock] for safe zero-allocation iteration.
     */
    fun copyEntitiesTo(dest: MutableList<Entity>) {
        dest.clear()
        synchronized(entityLock) {
            for (i in 0 until entities.size) {
                dest.add(entities[i])
            }
        }
    }

    /**
     * Ticks active entities within [activeRadiusBlocks] of `(playerX, playerZ)` cleanly with
     * zero per-frame list allocations, putting distant entities to sleep until the player approaches.
     */
    fun tickEntities(
        deltaTime: Float,
        playerX: Float = Float.NaN,
        playerZ: Float = Float.NaN,
        activeRadiusBlocks: Float = DEFAULT_ENTITY_TICK_RADIUS_BLOCKS
    ) {
        val useDistanceCulling = playerX.isFinite() && playerZ.isFinite() && activeRadiusBlocks > 0f
        val radiusSq = activeRadiusBlocks * activeRadiusBlocks
        var ticked = 0
        var sleeping = 0

        synchronized(entityLock) {
            isTickingEntities = true
            entityTickScratch.clear()
            for (i in 0 until entities.size) {
                entityTickScratch.add(entities[i])
            }
        }
        try {
            for (i in 0 until entityTickScratch.size) {
                val entity = entityTickScratch[i]
                if (entity.isRemoved) continue

                if (useDistanceCulling) {
                    val dx = entity.position.x - playerX
                    val dz = entity.position.z - playerZ
                    if ((dx * dx + dz * dz) > radiusSq) {
                        entity.isSleeping = true
                        sleeping++
                        continue
                    }
                }

                entity.isSleeping = false
                entity.tick(this, deltaTime)
                ticked++
            }
        } finally {
            entityTickScratch.clear()
            lastTickedEntitiesCount = ticked
            lastSleepingEntitiesCount = sleeping
            synchronized(entityLock) {
                isTickingEntities = false
                for (i in entities.size - 1 downTo 0) {
                    if (entities[i].isRemoved) {
                        entities.removeAt(i)
                    }
                }
                if (pendingSpawnEntities.isNotEmpty()) {
                    for (i in 0 until pendingSpawnEntities.size) {
                        val pending = pendingSpawnEntities[i]
                        if (!pending.isRemoved) {
                            var duplicate = false
                            for (j in 0 until entities.size) {
                                if (entities[j] === pending) {
                                    duplicate = true
                                    break
                                }
                            }
                            if (!duplicate) {
                                entities.add(pending)
                            }
                        }
                    }
                    pendingSpawnEntities.clear()
                }
            }
        }
    }

    /**
     * Clears all tracked and pending entities from the world.
     */
    fun clearEntities() {
        synchronized(entityLock) {
            for (i in 0 until entities.size) {
                entities[i].isRemoved = true
            }
            for (i in 0 until pendingSpawnEntities.size) {
                pendingSpawnEntities[i].isRemoved = true
            }
            if (!isTickingEntities) {
                entities.clear()
                pendingSpawnEntities.clear()
            }
        }
    }

    fun getPrebuiltMeshCount(): Int = prebuiltMeshes.size

    /**
     * Merges nearby identical active [com.nitrous.tessellate.entity.ItemDropEntity] drops within
     * 1.5 blocks using capped candidate checks in `:core`.
     */
    fun mergeNearbyItemDrops(minTickCount: Int = 0): ItemDropMerger.MergePassResult {
        return itemDropMerger.mergeNearby(this, minTickCount)
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(World::class.java.name)

        /** Default distance culling radius for entity ticking: 4 chunks (64 blocks). */
        const val DEFAULT_ENTITY_TICK_RADIUS_BLOCKS: Float = (4 * Chunk.SIZE).toFloat()

        /** Upper bound on unconsumed off-thread CPU chunk meshes held in memory while paused/streaming. */
        const val MAX_PREBUILT_MESHES: Int = 48

        fun chunkEntityPos(entity: Entity, chunksY: Int): ChunkPos {
            val cx = floor(entity.position.x).toInt() shr 4
            val cy = (floor(entity.position.y).toInt() shr 4).coerceIn(0, (chunksY - 1).coerceAtLeast(0))
            val cz = floor(entity.position.z).toInt() shr 4
            return ChunkPos(cx, cy, cz)
        }

        @Suppress("NOTHING_TO_INLINE")
        internal inline fun packChunkKey(cx: Int, cy: Int, cz: Int): Long {
            return ((cx.toLong() and 0x3FFFFFFL) shl 38) or
                ((cz.toLong() and 0x3FFFFFFL) shl 12) or
                (cy.toLong() and 0xFFFL)
        }
    }
}

/**
 * Zero-allocation open-addressed primitive `Long -> Chunk` lookup table using backward-shift deletion.
 */
internal class ChunkFastMap(initialCapacity: Int = 1024) {
    private var capacity = initialCapacity.coerceAtLeast(16)
    private var mask = capacity - 1
    private var keys = LongArray(capacity)
    private var values = arrayOfNulls<Chunk>(capacity)
    private var count = 0

    private fun mix(key: Long): Int {
        var h = key * -7046029254386353131L
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    fun get(key: Long): Chunk? {
        var slot = mix(key)
        while (true) {
            val v = values[slot] ?: return null
            if (keys[slot] == key) return v
            slot = (slot + 1) and mask
        }
    }

    fun put(key: Long, chunk: Chunk) {
        if ((count + 1) * 2 >= capacity) {
            rehash(capacity * 2)
        }
        var slot = mix(key)
        while (true) {
            val existing = values[slot]
            if (existing == null) {
                keys[slot] = key
                values[slot] = chunk
                count++
                return
            }
            if (keys[slot] == key) {
                values[slot] = chunk
                return
            }
            slot = (slot + 1) and mask
        }
    }

    fun remove(key: Long): Chunk? {
        var slot = mix(key)
        while (true) {
            val existing = values[slot] ?: return null
            if (keys[slot] == key) {
                count--
                var hole = slot
                var next = (hole + 1) and mask
                while (values[next] != null) {
                    val ideal = mix(keys[next])
                    val distFromIdealToNext = (next - ideal) and mask
                    val distFromIdealToHole = (hole - ideal) and mask
                    if (distFromIdealToHole < distFromIdealToNext) {
                        keys[hole] = keys[next]
                        values[hole] = values[next]
                        hole = next
                    }
                    next = (next + 1) and mask
                }
                values[hole] = null
                keys[hole] = 0L
                return existing
            }
            slot = (slot + 1) and mask
        }
    }

    fun clear() {
        values.fill(null)
        keys.fill(0L)
        count = 0
    }

    private fun rehash(newCapacity: Int) {
        val oldKeys = keys
        val oldValues = values
        capacity = newCapacity
        mask = capacity - 1
        keys = LongArray(capacity)
        values = arrayOfNulls(capacity)
        count = 0
        for (i in oldValues.indices) {
            val v = oldValues[i]
            if (v != null) {
                put(oldKeys[i], v)
            }
        }
    }
}

data class RaycastHit(
    val blockPos: BlockPos,
    val blockType: BlockType,
    val face: Direction,
    val distance: Float
)
