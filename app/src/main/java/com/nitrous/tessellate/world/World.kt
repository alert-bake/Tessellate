package com.nitrous.tessellate.world

import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.save.WorldSaveStorage
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.worldgen.WorldGenRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    val renderDistance: Int,
    val chunksX: Int,
    val chunksY: Int,
    val chunksZ: Int,
    val isInfinite: Boolean,
    val saveStorage: WorldSaveStorage? = null,
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
        saveStorage: WorldSaveStorage? = null,
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
    private val prebuiltMeshes = ConcurrentHashMap<ChunkPos, ChunkMesh>()

    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeStreamJob: Job? = null
    private var lastStreamCenterCx: Int = Int.MIN_VALUE
    private var lastStreamCenterCz: Int = Int.MIN_VALUE

    val asyncGeneratedChunksCount = AtomicInteger(0)

    @Volatile
    var isDestroyed: Boolean = false
        private set

    val entities = mutableListOf<Entity>()
    private val pendingSpawnEntities = mutableListOf<Entity>()
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
        return synchronized(chunkLock) {
            chunks[ChunkPos(cx, cy, cz)]
        }
    }

    fun hasChunk(cx: Int, cy: Int, cz: Int): Boolean = getChunk(cx, cy, cz) != null

    fun getLoadedChunkCount(): Int = synchronized(chunkLock) { chunks.size }

    fun getAllChunks(): List<Chunk> = synchronized(chunkLock) {
        chunks.values.toList()
    }

    fun consumePrebuiltMesh(pos: ChunkPos): ChunkMesh? {
        return prebuiltMeshes.remove(pos)
    }

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

    fun setBlock(wx: Int, wy: Int, wz: Int, type: BlockType): Boolean {
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
            chunks[ChunkPos(cx, cy, cz)] ?: if (isInfinite) {
                Chunk(cx, cy, cz).also { created -> chunks[ChunkPos(cx, cy, cz)] = created }
            } else {
                null
            }
        } ?: return false

        val changed = targetChunk.setBlock(lx, ly, lz, type)

        if (changed) {
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
        if (isInfinite) {
            generateInfiniteSpawnCore()
        } else {
            generateFiniteFlatTerrain()
        }
    }

    private fun generateInfiniteSpawnCore() {
        synchronized(chunkLock) {
            chunks.clear()
        }
        prebuiltMeshes.clear()
        lastStreamCenterCx = Int.MIN_VALUE
        lastStreamCenterCz = Int.MIN_VALUE

        val centerCx = floor(initialCenterX).toInt() shr 4
        val centerCz = floor(initialCenterZ).toInt() shr 4

        for (cx in (centerCx - 1)..(centerCx + 1)) {
            for (cz in (centerCz - 1)..(centerCz + 1)) {
                for (cy in 0 until chunksY) {
                    val pos = ChunkPos(cx, cy, cz)
                    val (chunk, restoredEntities) = buildOrLoadChunk(pos)
                    synchronized(chunkLock) {
                        chunks[pos] = chunk
                    }
                    for (entity in restoredEntities) {
                        spawnEntity(entity)
                    }
                }
            }
        }
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
            for (cx in 0 until chunksX) {
                for (cy in 0 until chunksY) {
                    for (cz in 0 until chunksZ) {
                        chunks[ChunkPos(cx, cy, cz)] = Chunk(cx, cy, cz)
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
        }
    }

    /**
     * Synchronously ensures a single chunk column at `(cx, cz)` is generated or loaded from disk.
     */
    fun ensureChunkColumnLoadedSync(cx: Int, cz: Int) {
        if (!isInfinite) return
        for (cy in 0 until chunksY) {
            val pos = ChunkPos(cx, cy, cz)
            if (getChunk(cx, cy, cz) == null) {
                val (chunk, restoredEntities) = buildOrLoadChunk(pos)
                val inserted = synchronized(chunkLock) {
                    if (!chunks.containsKey(pos)) {
                        chunks[pos] = chunk
                        true
                    } else {
                        false
                    }
                }
                if (inserted) {
                    for (entity in restoredEntities) {
                        spawnEntity(entity)
                    }
                }
            }
        }
    }

    /**
     * Streams chunks around `(playerX, playerZ)` within [renderDistance]:
     * unloads out-of-range chunks (saving non-empty chunks and their active entities asynchronously
     * on [Dispatchers.IO]) and loads or generates missing columns nearest-first on a background coroutine.
     */
    fun streamChunksAround(
        playerX: Float,
        playerZ: Float,
        texturePipeline: TexturePipeline? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
        forceCheck: Boolean = false
    ): Job? {
        if (!isInfinite || isDestroyed) return null

        val centerCx = floor(playerX).toInt() shr 4
        val centerCz = floor(playerZ).toInt() shr 4

        if (!forceCheck && centerCx == lastStreamCenterCx && centerCz == lastStreamCenterCz) {
            val currentJob = activeStreamJob
            if (currentJob != null && currentJob.isActive) return currentJob
        }
        lastStreamCenterCx = centerCx
        lastStreamCenterCz = centerCz

        // 1. Unload chunks outside render distance immediately to bound memory
        val unloadedChunks = ArrayList<Pair<ChunkPos, Chunk>>()
        synchronized(chunkLock) {
            val iterator = chunks.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val pos = entry.key
                if (abs(pos.x - centerCx) > renderDistance || abs(pos.z - centerCz) > renderDistance) {
                    unloadedChunks.add(pos to entry.value)
                    iterator.remove()
                    prebuiltMeshes.remove(pos)
                }
            }
        }

        // Persist unloaded chunks and their active entities off the render thread
        if (unloadedChunks.isNotEmpty() && saveStorage != null) {
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
                    val ePos = WorldSaveManager.chunkEntityPos(entity, chunksY)
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
                    val ePos = WorldSaveManager.chunkEntityPos(entity, chunksY)
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
            for (cx in (centerCx - renderDistance)..(centerCx + renderDistance)) {
                for (cz in (centerCz - renderDistance)..(centerCz + renderDistance)) {
                    var columnNeedsLoad = false
                    for (cy in 0 until chunksY) {
                        if (!chunks.containsKey(ChunkPos(cx, cy, cz))) {
                            columnNeedsLoad = true
                            break
                        }
                    }
                    if (columnNeedsLoad) {
                        missingColumns.add(cx to cz)
                    }
                }
            }
        }

        if (missingColumns.isEmpty()) {
            return activeStreamJob
        }

        missingColumns.sortBy { (cx, cz) ->
            val dx = cx - centerCx
            val dz = cz - centerCz
            dx * dx + dz * dz
        }

        activeStreamJob?.cancel()
        val job = streamScope.launch(ioDispatcher) {
            val newlyGenerated = mutableListOf<ChunkPos>()
            for ((cx, cz) in missingColumns) {
                if (!isActive || isDestroyed) break
                // Double check column hasn't wandered outside active window
                if (abs(cx - lastStreamCenterCx) > renderDistance ||
                    abs(cz - lastStreamCenterCz) > renderDistance
                ) {
                    continue
                }
                for (cy in 0 until chunksY) {
                    if (!isActive || isDestroyed) break
                    val pos = ChunkPos(cx, cy, cz)
                    if (getChunk(cx, cy, cz) != null) continue

                    val (chunk, restoredEntities) = buildOrLoadChunk(pos)

                    val inserted = synchronized(chunkLock) {
                        if (!isDestroyed &&
                            abs(cx - lastStreamCenterCx) <= renderDistance &&
                            abs(cz - lastStreamCenterCz) <= renderDistance &&
                            !chunks.containsKey(pos)
                        ) {
                            chunks[pos] = chunk
                            newlyGenerated.add(pos)
                            asyncGeneratedChunksCount.incrementAndGet()
                            true
                        } else {
                            false
                        }
                    }

                    if (inserted && restoredEntities.isNotEmpty()) {
                        for (entity in restoredEntities) {
                            spawnEntity(entity)
                        }
                    }

                    // Mark adjacent loaded neighbors dirty so cross-chunk faces update cleanly
                    getChunk(cx - 1, cy, cz)?.markDirty()
                    getChunk(cx + 1, cy, cz)?.markDirty()
                    getChunk(cx, cy, cz - 1)?.markDirty()
                    getChunk(cx, cy, cz + 1)?.markDirty()
                }
            }

            // Build CPU chunk meshes off the GL thread so main GL render only uploads buffers
            if (texturePipeline != null && isActive) {
                for (pos in newlyGenerated) {
                    if (!isActive) break
                    val chunk = getChunk(pos.x, pos.y, pos.z) ?: continue
                    if (!chunk.isEmpty) {
                        val mesh = ChunkMesh(chunk)
                        mesh.generateMeshData(this@World, texturePipeline)
                        prebuiltMeshes[pos] = mesh
                    }
                }
            }
        }
        activeStreamJob = job
        return job
    }

    fun shutdownStreaming() {
        activeStreamJob?.cancel()
        streamScope.cancel()
    }

    /**
     * Destroys this world session, cancelling background chunk streaming and releasing chunk/entity memory.
     */
    fun destroy() {
        isDestroyed = true
        shutdownStreaming()
        clearEntities()
        synchronized(chunkLock) {
            chunks.clear()
        }
        prebuiltMeshes.clear()
        lastStreamCenterCx = Int.MIN_VALUE
        lastStreamCenterCz = Int.MIN_VALUE
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
     * Ticks all active entities safely, deferring structural list mutations until after iteration.
     */
    fun tickEntities(deltaTime: Float) {
        val tickList = synchronized(entityLock) {
            isTickingEntities = true
            ArrayList(entities)
        }
        try {
            for (i in 0 until tickList.size) {
                val entity = tickList[i]
                if (!entity.isRemoved) {
                    entity.tick(this, deltaTime)
                }
            }
        } finally {
            synchronized(entityLock) {
                isTickingEntities = false
                entities.removeAll { it.isRemoved }
                if (pendingSpawnEntities.isNotEmpty()) {
                    for (i in 0 until pendingSpawnEntities.size) {
                        val pending = pendingSpawnEntities[i]
                        if (!pending.isRemoved && entities.none { it === pending }) {
                            entities.add(pending)
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
}

data class RaycastHit(
    val blockPos: BlockPos,
    val blockType: BlockType,
    val face: Direction,
    val distance: Float
)
