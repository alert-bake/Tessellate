package com.nitrous.tessellate.save

import android.content.Context
import android.util.Log
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.ChunkPos
import com.nitrous.tessellate.world.TypedValue
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldChunkStorage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified, single-queue disk I/O coordinator for world saves (`level.json`, `chunks/c_X_Y_Z.bin`,
 * `chunks/masters/<sha256>.bin`, and `entities/c_X_Y_Z.json`).
 *
 * Implements `:core`'s [WorldChunkStorage] contract (`World -> WorldChunkStorage -> WorldSaveManager -> Disk`)
 * and routes every write (chunk unloads, pause/autosave snapshots, level metadata, master deduplication,
 * orphaned master cleanup, and blocking save-and-quit flushes) through a single sequential
 * [Dispatchers.IO] coroutine queue with per-chunk request overwriting/debouncing.
 */
class WorldSaveManager(
    val worldDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : WorldChunkStorage {
    val chunksDir = File(worldDir, CHUNKS_DIR_NAME)
    val mastersDir = File(chunksDir, MASTERS_DIR_NAME)
    val entitiesDir = File(worldDir, ENTITIES_DIR_NAME)
    val levelFile = File(worldDir, LEVEL_FILE_NAME)

    private val ioExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e(TAG, "Async world save operation failed safely", throwable)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + ioExceptionHandler)
    private val queueLock = Any()
    private val fileLocks = ConcurrentHashMap<ChunkPos, Any>()
    private val masterLock = Any()
    private val levelLock = Any()

    private data class PendingChunkWrite(val version: Long, val bytes: ByteArray?)
    private data class PendingBlockDataWrite(val version: Long, val blockData: Map<Int, Map<String, TypedValue>>)
    private data class PendingEntityWrite(val version: Long, val entities: List<SavedEntityData>)

    private data class QueuedChunkTask(
        val chunk: Chunk?,
        val epochAtStart: Int,
        val pos: ChunkPos,
        val bytesCopy: ByteArray?,
        val chunkVer: Long,
        val blockDataCopy: Map<Int, Map<String, TypedValue>>,
        val dataVer: Long,
        val serializedEntities: List<SavedEntityData>,
        val entityVer: Long,
        val waiters: MutableList<CompletableDeferred<Unit>> = mutableListOf()
    )

    private data class QueuedLevelTask(
        val data: WorldSaveData,
        val waiters: MutableList<CompletableDeferred<Boolean>> = mutableListOf()
    )

    private data class QueuedCleanupTask(
        val deferred: CompletableDeferred<Boolean>,
        val preScanHook: (() -> Unit)? = null
    )

    private val writeVersionGen = AtomicLong(0L)
    private val pendingChunkWrites = ConcurrentHashMap<ChunkPos, PendingChunkWrite>()
    private val pendingBlockDataWrites = ConcurrentHashMap<ChunkPos, PendingBlockDataWrite>()
    private val pendingEntityWrites = ConcurrentHashMap<ChunkPos, PendingEntityWrite>()

    private val queuedChunks = LinkedHashMap<ChunkPos, QueuedChunkTask>()
    private var queuedLevelData: QueuedLevelTask? = null
    private var queuedCleanup: QueuedCleanupTask? = null
    private var workerJob: Job? = null

    val totalChunkDiskWrites = AtomicInteger(0)
    val coalescedChunkSaves = AtomicInteger(0)

    @Volatile
    var lastSaveJob: Job? = null
        private set

    fun chunkFileFor(pos: ChunkPos): File = File(chunksDir, "c_${pos.x}_${pos.y}_${pos.z}.bin")

    fun chunkDataFileFor(pos: ChunkPos): File = File(chunksDir, "c_${pos.x}_${pos.y}_${pos.z}.data")

    fun masterFileFor(hashHex: String): File = File(mastersDir, "$hashHex.bin")

    fun entityFileFor(pos: ChunkPos): File = File(entitiesDir, "c_${pos.x}_${pos.y}_${pos.z}.json")

    private fun ensureDirectoriesExist() {
        if (!worldDir.exists()) worldDir.mkdirs()
        if (!chunksDir.exists()) chunksDir.mkdirs()
        if (!mastersDir.exists()) mastersDir.mkdirs()
        if (!entitiesDir.exists()) entitiesDir.mkdirs()
    }

    private fun lockFor(pos: ChunkPos): Any = fileLocks.computeIfAbsent(pos) { Any() }

    /**
     * Queues an async [Dispatchers.IO] write for a chunk's block data and its entities JSON file
     * onto the single sequential save queue. Overwrites any unstarted pending request for the same
     * [ChunkPos] to avoid redundant disk writes.
     */
    override fun saveChunkAndEntitiesAsync(chunk: Chunk, chunkEntities: List<Entity>): Job {
        val serializedEntities = chunkEntities.map { EntityPersistenceRegistry.serialize(it) }
        val deferred = enqueueChunkTaskLocked(chunk, serializedEntities)
        synchronized(queueLock) {
            ensureWorkerRunningLocked()
            lastSaveJob = deferred
        }
        return deferred
    }

    private fun enqueueChunkTaskLocked(
        chunk: Chunk,
        serializedEntities: List<SavedEntityData>
    ): CompletableDeferred<Unit> {
        val epochAtStart = chunk.saveEpoch
        val pos = ChunkPos(chunk.chunkX, chunk.chunkY, chunk.chunkZ)
        val bytesCopy: ByteArray? = if (isAllAir(chunk.blocks)) null else chunk.blocks.copyOf()
        val blockDataCopy: Map<Int, Map<String, TypedValue>> = chunk.snapshotBlockData()

        val chunkVer = writeVersionGen.incrementAndGet()
        val dataVer = writeVersionGen.incrementAndGet()
        val entityVer = writeVersionGen.incrementAndGet()
        pendingChunkWrites[pos] = PendingChunkWrite(chunkVer, bytesCopy)
        pendingBlockDataWrites[pos] = PendingBlockDataWrite(dataVer, blockDataCopy)
        pendingEntityWrites[pos] = PendingEntityWrite(entityVer, serializedEntities)

        val deferred = CompletableDeferred<Unit>()
        synchronized(queueLock) {
            val existing = queuedChunks[pos]
            val waiters = if (existing != null) {
                coalescedChunkSaves.incrementAndGet()
                existing.waiters.apply { add(deferred) }
            } else {
                mutableListOf(deferred)
            }
            queuedChunks[pos] = QueuedChunkTask(
                chunk = chunk,
                epochAtStart = epochAtStart,
                pos = pos,
                bytesCopy = bytesCopy,
                chunkVer = chunkVer,
                blockDataCopy = blockDataCopy,
                dataVer = dataVer,
                serializedEntities = serializedEntities,
                entityVer = entityVer,
                waiters = waiters
            )
        }
        return deferred
    }

    private fun enqueueLevelTask(data: WorldSaveData): CompletableDeferred<Boolean> {
        val normalized = data.copy(folderName = worldDir.name)
        val deferred = CompletableDeferred<Boolean>()
        synchronized(queueLock) {
            val existing = queuedLevelData
            val waiters = if (existing != null) {
                existing.waiters.apply { add(deferred) }
            } else {
                mutableListOf(deferred)
            }
            queuedLevelData = QueuedLevelTask(normalized, waiters)
            ensureWorkerRunningLocked()
        }
        return deferred
    }

    /**
     * Schedules an asynchronous safe cleanup scan on [Dispatchers.IO] to remove orphaned
     * master files under `chunks/masters/` that are no longer referenced by any `0x04` pointer
     * file in `chunks/`. Aborts immediately if interrupted or if any scan I/O error occurs.
     */
    fun cleanupOrphanedMastersAsync(preScanHook: (() -> Unit)? = null): Job {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(queueLock) {
            queuedCleanup?.deferred?.complete(false)
            queuedCleanup = QueuedCleanupTask(deferred, preScanHook)
            ensureWorkerRunningLocked()
        }
        return deferred
    }

    private fun ensureWorkerRunningLocked(): Job {
        val existing = workerJob
        if (existing != null && existing.isActive) {
            return existing
        }
        val job = scope.launch(ioDispatcher) {
            processQueue()
        }
        workerJob = job
        return job
    }

    private fun processQueue() {
        while (true) {
            var levelTask: QueuedLevelTask? = null
            var chunkTask: QueuedChunkTask? = null
            var cleanupTask: QueuedCleanupTask? = null
            synchronized(queueLock) {
                if (queuedLevelData != null) {
                    levelTask = queuedLevelData
                    queuedLevelData = null
                } else if (queuedChunks.isNotEmpty()) {
                    val iter = queuedChunks.entries.iterator()
                    val first = iter.next()
                    chunkTask = first.value
                    iter.remove()
                } else if (queuedCleanup != null) {
                    cleanupTask = queuedCleanup
                    queuedCleanup = null
                } else {
                    workerJob = null
                    return
                }
            }

            if (levelTask != null) {
                val task = levelTask!!
                var ok = false
                try {
                    ok = writeLevelDataInternal(task.data)
                } catch (e: CancellationException) {
                    for (w in task.waiters) w.complete(false)
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed queued level.json write", t)
                } finally {
                    for (w in task.waiters) {
                        w.complete(ok)
                    }
                }
            } else if (chunkTask != null) {
                val task = chunkTask!!
                try {
                    val ok = writeChunkAndEntitiesInternal(
                        task.pos,
                        task.bytesCopy,
                        task.chunkVer,
                        task.blockDataCopy,
                        task.dataVer,
                        task.serializedEntities,
                        task.entityVer
                    )
                    if (ok && task.chunk != null && task.chunk.saveEpoch == task.epochAtStart) {
                        task.chunk.needsSave = false
                    }
                } catch (e: CancellationException) {
                    for (w in task.waiters) w.complete(Unit)
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed queued chunk/entity write for ${task.pos}", t)
                } finally {
                    for (w in task.waiters) {
                        w.complete(Unit)
                    }
                }
            } else if (cleanupTask != null) {
                val task = cleanupTask!!
                var completedCleanly = false
                try {
                    completedCleanly = runSafeMasterCleanup(task)
                } catch (e: CancellationException) {
                    task.deferred.complete(false)
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Orphaned master cleanup aborted safely due to error", t)
                } finally {
                    if (Thread.currentThread().isInterrupted) {
                        Thread.interrupted()
                    }
                    task.deferred.complete(completedCleanly)
                }
            }
        }
    }

    private fun runSafeMasterCleanup(task: QueuedCleanupTask): Boolean {
        if (task.deferred.isCancelled || Thread.currentThread().isInterrupted) {
            return false
        }
        task.preScanHook?.invoke()
        if (task.deferred.isCancelled || Thread.currentThread().isInterrupted) {
            return false
        }

        if (!chunksDir.exists() || !chunksDir.isDirectory || !mastersDir.exists() || !mastersDir.isDirectory) {
            return true
        }

        val chunkEntries = chunksDir.listFiles() ?: return false
        val referencedHashes = HashSet<String>()

        // Preserve any hashes currently queued in memory so active saves cannot race cleanup
        synchronized(queueLock) {
            for (queued in queuedChunks.values) {
                val bytes = queued.bytesCopy
                if (bytes != null) {
                    referencedHashes.add(OsmiumCodec.sha256Hex(OsmiumCodec.sha256Bytes(bytes)))
                }
            }
        }

        val headerBuf = ByteArray(OsmiumCodec.POINTER_FILE_SIZE)
        for (entry in chunkEntries) {
            if (task.deferred.isCancelled || Thread.currentThread().isInterrupted) {
                Log.w(TAG, "Orphaned master cleanup interrupted during scan; aborting safely.")
                return false
            }
            if (!entry.isFile || !entry.name.endsWith(".bin")) continue

            try {
                FileInputStream(entry).use { fis ->
                    var totalRead = 0
                    while (totalRead < OsmiumCodec.POINTER_FILE_SIZE) {
                        val read = fis.read(headerBuf, totalRead, OsmiumCodec.POINTER_FILE_SIZE - totalRead)
                        if (read < 0) break
                        totalRead += read
                    }
                    if (totalRead == OsmiumCodec.POINTER_FILE_SIZE &&
                        headerBuf[0] == OsmiumCodec.MAGIC_BYTE_0 &&
                        headerBuf[1] == OsmiumCodec.VERSION_V4 &&
                        entry.length() == OsmiumCodec.POINTER_FILE_SIZE.toLong()
                    ) {
                        val hashBytes = ByteArray(OsmiumCodec.SHA256_BYTE_LENGTH)
                        System.arraycopy(headerBuf, 2, hashBytes, 0, OsmiumCodec.SHA256_BYTE_LENGTH)
                        referencedHashes.add(OsmiumCodec.sha256Hex(hashBytes))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup scan failed reading ${entry.name}; aborting master deletion safely.", e)
                return false
            }
        }

        val masterEntries = mastersDir.listFiles() ?: return false
        for (master in masterEntries) {
            if (task.deferred.isCancelled || Thread.currentThread().isInterrupted) {
                Log.w(TAG, "Orphaned master cleanup interrupted during prune; aborting remaining deletions.")
                return false
            }
            if (!master.isFile || !master.name.endsWith(".bin")) continue
            val hashHex = master.name.removeSuffix(".bin")
            if (!referencedHashes.contains(hashHex)) {
                master.delete()
            }
        }
        return true
    }

    private fun writeLevelDataInternal(normalized: WorldSaveData): Boolean = synchronized(levelLock) {
        return try {
            ensureDirectoriesExist()
            levelFile.writeText(serializeLevelData(normalized))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing level.json at ${levelFile.absolutePath}", e)
            false
        }
    }

    private fun writeChunkAndEntitiesInternal(
        pos: ChunkPos,
        bytesCopy: ByteArray?,
        chunkVer: Long,
        blockDataCopy: Map<Int, Map<String, TypedValue>>,
        dataVer: Long,
        serializedEntities: List<SavedEntityData>,
        entityVer: Long
    ): Boolean {
        var chunkSuccess = false
        var dataSuccess = false
        var entitySuccess = false
        synchronized(lockFor(pos)) {
            ensureDirectoriesExist()

            // 1. Binary chunk block data (V4 SHA-256 deduplicated pointer -> V2 / V3 LZ4 / V1 master)
            // CRITICAL: Hash ONLY the raw 4096-byte block array, NEVER custom blockDataMap.
            val cFile = chunkFileFor(pos)
            try {
                if (bytesCopy == null) {
                    if (cFile.exists()) {
                        cFile.delete()
                    }
                } else {
                    val hashBytes = OsmiumCodec.sha256Bytes(bytesCopy)
                    val hashHex = OsmiumCodec.sha256Hex(hashBytes)
                    synchronized(masterLock) {
                        val masterFile = masterFileFor(hashHex)
                        if (!masterFile.exists()) {
                            val encodedMaster = OsmiumCodec.encode(bytesCopy)
                            masterFile.writeBytes(encodedMaster)
                        }
                    }
                    val pointerBytes = OsmiumCodec.encodePointer(hashBytes)
                    cFile.writeBytes(pointerBytes)
                }
                totalChunkDiskWrites.incrementAndGet()
                chunkSuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing chunk file ${cFile.name}", e)
            } finally {
                val current = pendingChunkWrites[pos]
                if (current != null && current.version == chunkVer) {
                    pendingChunkWrites.remove(pos, current)
                }
            }

            // 2. Sparse block data sidecar (`chunks/c_X_Y_Z.data`)
            val dFile = chunkDataFileFor(pos)
            try {
                if (blockDataCopy.isEmpty()) {
                    if (dFile.exists()) {
                        dFile.delete()
                    }
                } else {
                    dFile.writeText(serializeChunkBlockData(blockDataCopy))
                }
                dataSuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing block data sidecar ${dFile.name}", e)
            } finally {
                val current = pendingBlockDataWrites[pos]
                if (current != null && current.version == dataVer) {
                    pendingBlockDataWrites.remove(pos, current)
                }
            }

            // 3. Entity JSON data
            val eFile = entityFileFor(pos)
            try {
                if (serializedEntities.isEmpty()) {
                    if (eFile.exists()) {
                        eFile.delete()
                    }
                } else {
                    val payload = ChunkEntityFileData(
                        chunkX = pos.x,
                        chunkY = pos.y,
                        chunkZ = pos.z,
                        entities = serializedEntities
                    )
                    eFile.writeText(serializeChunkEntities(payload))
                }
                entitySuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing entity file ${eFile.name}", e)
            } finally {
                val current = pendingEntityWrites[pos]
                if (current != null && current.version == entityVer) {
                    pendingEntityWrites.remove(pos, current)
                }
            }
        }
        return chunkSuccess && dataSuccess && entitySuccess
    }

    /**
     * Reads raw 4096-byte chunk data if saved (checking any pending async write first),
     * resolving V4 SHA-256 pointers against `chunks/masters/` or decoding Legacy/V1/V2/V3 directly,
     * and quarantining missing/corrupted files.
     */
    override fun loadChunkBytes(pos: ChunkPos): ByteArray? {
        pendingChunkWrites[pos]?.let { pending ->
            return pending.bytes?.copyOf()
        }
        synchronized(lockFor(pos)) {
            pendingChunkWrites[pos]?.let { pending ->
                return pending.bytes?.copyOf()
            }
            val file = chunkFileFor(pos)
            if (!file.exists() || !file.isFile) return null
            return try {
                val rawBytes = file.readBytes()
                val format = OsmiumCodec.detectFormat(rawBytes)
                if (format == null) {
                    quarantineCorruptChunkFile(file)
                    return null
                }
                if (format == OsmiumFormat.V4_POINTER) {
                    val expectedHash = OsmiumCodec.decodePointerHash(rawBytes)
                    if (expectedHash == null) {
                        quarantineCorruptChunkFile(file)
                        return null
                    }
                    val hashHex = OsmiumCodec.sha256Hex(expectedHash)
                    val masterFile = masterFileFor(hashHex)
                    val masterBytes = synchronized(masterLock) {
                        if (!masterFile.exists() || !masterFile.isFile) null else masterFile.readBytes()
                    }
                    if (masterBytes == null) {
                        Log.e(TAG, "CRITICAL: Missing master file $hashHex.bin for pointer ${file.name}. Quarantining pointer.")
                        quarantineCorruptChunkFile(file)
                        return null
                    }
                    val decoded = OsmiumCodec.decode(masterBytes)
                    if (decoded == null || decoded.size != Chunk.VOLUME) {
                        Log.e(TAG, "CRITICAL: Corrupted master payload $hashHex.bin for pointer ${file.name}. Quarantining pointer.")
                        quarantineCorruptChunkFile(file)
                        return null
                    }
                    val actualHash = OsmiumCodec.sha256Bytes(decoded)
                    if (!MessageDigest.isEqual(actualHash, expectedHash)) {
                        Log.e(TAG, "CRITICAL: Master SHA-256 hash mismatch for $hashHex.bin (${file.name}). Quarantining pointer.")
                        quarantineCorruptChunkFile(file)
                        return null
                    }
                    decoded
                } else {
                    val decoded = OsmiumCodec.decode(rawBytes)
                    if (decoded == null) {
                        quarantineCorruptChunkFile(file)
                    }
                    decoded
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read chunk bytes ${file.name}", e)
                quarantineCorruptChunkFile(file)
                null
            }
        }
    }

    /**
     * Reads sparse custom block metadata from `chunks/c_X_Y_Z.data` (or pending async write).
     */
    override fun loadBlockData(pos: ChunkPos): Map<Int, Map<String, TypedValue>> {
        pendingBlockDataWrites[pos]?.let { pending ->
            return pending.blockData
        }
        synchronized(lockFor(pos)) {
            pendingBlockDataWrites[pos]?.let { pending ->
                return pending.blockData
            }
            val file = chunkDataFileFor(pos)
            return parseChunkBlockData(file)
        }
    }

    /**
     * Reads saved entities for [pos] (checking any pending async write first) and instantiates them.
     */
    override fun loadEntities(pos: ChunkPos): List<Entity> {
        pendingEntityWrites[pos]?.let { pending ->
            return pending.entities.mapNotNull { EntityPersistenceRegistry.deserialize(it) }
        }
        synchronized(lockFor(pos)) {
            pendingEntityWrites[pos]?.let { pending ->
                return pending.entities.mapNotNull { EntityPersistenceRegistry.deserialize(it) }
            }
            val file = entityFileFor(pos)
            val parsed = parseChunkEntities(file) ?: return emptyList()
            return parsed.entities.mapNotNull { EntityPersistenceRegistry.deserialize(it) }
        }
    }

    fun loadLevelData(): WorldSaveData? = synchronized(levelLock) {
        val parsed = parseLevelData(levelFile) ?: return null
        parsed.copy(folderName = worldDir.name)
    }

    fun saveLevelDataAsync(data: WorldSaveData): Job {
        val deferred = enqueueLevelTask(data)
        synchronized(queueLock) {
            lastSaveJob = deferred
        }
        return deferred
    }

    fun saveLevelDataSync(data: WorldSaveData): Boolean {
        val deferred = enqueueLevelTask(data)
        synchronized(queueLock) {
            lastSaveJob = deferred
        }
        return runBlocking {
            deferred.await()
        }
    }

    /**
     * Enqueues `level.json`, all currently loaded chunks, and all active entities
     * onto the single [Dispatchers.IO] save queue.
     */
    fun saveWorldSnapshotAsync(
        saveData: WorldSaveData,
        loadedChunks: List<Chunk>,
        activeEntities: List<Entity>,
        chunksY: Int
    ): Job {
        val grouped = groupEntitiesForSnapshot(activeEntities, chunksY)
        val batchDone = CompletableDeferred<Unit>()
        val waiterJobs = ArrayList<Job>(1 + loadedChunks.size)

        synchronized(queueLock) {
            waiterJobs.add(enqueueLevelTask(saveData))
            for (chunk in loadedChunks) {
                val pos = ChunkPos(chunk.chunkX, chunk.chunkY, chunk.chunkZ)
                val entities = grouped[pos].orEmpty()
                waiterJobs.add(enqueueChunkTaskLocked(chunk, entities))
            }
            ensureWorkerRunningLocked()

            val remaining = AtomicInteger(waiterJobs.size)
            for (w in waiterJobs) {
                w.invokeOnCompletion {
                    if (remaining.decrementAndGet() == 0) {
                        batchDone.complete(Unit)
                    }
                }
            }
            lastSaveJob = batchDone
        }
        return batchDone
    }

    /**
     * Enqueues `level.json`, all loaded chunks, and all active entities onto the single
     * [Dispatchers.IO] queue and performs a blocking flush so every pending write finishes
     * before returning (used on save-and-quit and app teardown).
     */
    fun saveWorldSnapshotSync(
        saveData: WorldSaveData,
        loadedChunks: List<Chunk>,
        activeEntities: List<Entity>,
        chunksY: Int
    ) {
        saveWorldSnapshotAsync(saveData, loadedChunks, activeEntities, chunksY)
        flushBlocking()
    }

    /**
     * Blocks until all queued writes on the single [Dispatchers.IO] queue have drained completely.
     */
    fun flushBlocking() {
        while (true) {
            val activeWorker = synchronized(queueLock) {
                val job = workerJob
                if (job != null && job.isActive) {
                    job
                } else if (queuedLevelData != null || queuedChunks.isNotEmpty() || queuedCleanup != null) {
                    ensureWorkerRunningLocked()
                } else {
                    null
                }
            } ?: break

            runBlocking {
                activeWorker.join()
            }
        }
    }

    fun awaitPendingWritesBlocking() {
        flushBlocking()
    }

    private fun groupEntitiesForSnapshot(
        activeEntities: List<Entity>,
        chunksY: Int
    ): Map<ChunkPos, List<SavedEntityData>> {
        if (activeEntities.isEmpty()) return emptyMap()
        val grouped = HashMap<ChunkPos, MutableList<SavedEntityData>>()
        for (entity in activeEntities) {
            if (entity.isRemoved) continue
            val pos = chunkEntityPos(entity, chunksY)
            grouped.getOrPut(pos) { mutableListOf() }
                .add(EntityPersistenceRegistry.serialize(entity))
        }
        return grouped
    }

    companion object {
        private const val TAG = "WorldSaveManager"
        const val SAVES_DIR_NAME = "saves"
        const val CHUNKS_DIR_NAME = "chunks"
        const val MASTERS_DIR_NAME = "masters"
        const val ENTITIES_DIR_NAME = "entities"
        const val LEVEL_FILE_NAME = "level.json"

        private val moshi: Moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        private val levelAdapter = moshi.adapter(WorldSaveData::class.java).indent("  ")
        private val chunkEntitiesAdapter = moshi.adapter(ChunkEntityFileData::class.java).indent("  ")
        private val chunkBlockDataAdapter = moshi.adapter(ChunkBlockDataFileData::class.java).indent("  ")

        fun getBaseFilesDir(context: Context): File {
            return context.getExternalFilesDir(null) ?: context.filesDir
        }

        fun getSavesRootDir(context: Context): File {
            return File(getBaseFilesDir(context), SAVES_DIR_NAME)
        }

        fun sanitizeFolderName(worldName: String): String {
            val cleaned = worldName
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trimEnd('.', ' ')
            return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
                "New World"
            } else {
                cleaned
            }
        }

        fun resolveNewWorldFolder(context: Context, worldName: String): File {
            val root = getSavesRootDir(context)
            if (!root.exists()) {
                root.mkdirs()
            }
            val base = sanitizeFolderName(worldName)
            var candidate = File(root, base)
            var suffix = 2
            while (candidate.exists() && File(candidate, LEVEL_FILE_NAME).exists()) {
                candidate = File(root, "${base}_$suffix")
                suffix++
            }
            return candidate
        }

        fun resolveExistingWorldFolder(context: Context, folderName: String): File {
            val safeName = sanitizeFolderName(folderName)
            return File(getSavesRootDir(context), safeName)
        }

        fun listSavedWorlds(context: Context): List<WorldSaveData> {
            val savesRoot = getSavesRootDir(context)
            if (!savesRoot.exists() || !savesRoot.isDirectory) {
                return emptyList()
            }
            val dirs = savesRoot.listFiles()?.filter { it.isDirectory } ?: return emptyList()
            val result = ArrayList<WorldSaveData>(dirs.size)
            for (dir in dirs) {
                val levelFile = File(dir, LEVEL_FILE_NAME)
                if (!levelFile.exists() || !levelFile.isFile) continue
                try {
                    val json = levelFile.readText()
                    if (json.isBlank()) continue
                    val parsed = levelAdapter.fromJson(json) ?: continue
                    result.add(
                        parsed.copy(
                            folderName = dir.name
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read world metadata from ${levelFile.absolutePath}", e)
                }
            }
            result.sortWith(
                compareByDescending<WorldSaveData> { it.lastSavedEpochMs }
                    .thenBy { it.worldName.lowercase() }
            )
            return result
        }

        fun deleteWorld(context: Context, folderName: String): Boolean {
            if (folderName.isBlank()) return false
            val root = getSavesRootDir(context)
            val target = File(root, folderName)
            if (!target.exists()) return false
            return try {
                val canonicalRoot = root.canonicalPath
                val canonicalTarget = target.canonicalPath
                if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                    Log.w(TAG, "Refusing to delete path outside saves directory: $canonicalTarget")
                    return false
                }
                target.deleteRecursively()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete world folder: $folderName", e)
                false
            }
        }

        fun parseLevelData(file: File): WorldSaveData? {
            if (!file.exists() || !file.isFile) return null
            return try {
                val json = file.readText()
                if (json.isBlank()) null else levelAdapter.fromJson(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse level.json at ${file.absolutePath}", e)
                null
            }
        }

        fun serializeLevelData(data: WorldSaveData): String = levelAdapter.toJson(data)

        fun parseChunkEntities(file: File): ChunkEntityFileData? {
            if (!file.exists() || !file.isFile) return null
            return try {
                val json = file.readText()
                if (json.isBlank()) null else chunkEntitiesAdapter.fromJson(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse entities file at ${file.absolutePath}", e)
                null
            }
        }

        fun serializeChunkEntities(data: ChunkEntityFileData): String = chunkEntitiesAdapter.toJson(data)

        fun serializeChunkBlockData(blockData: Map<Int, Map<String, TypedValue>>): String {
            val encodedEntries = LinkedHashMap<String, Map<String, TypedValueJson>>(blockData.size)
            for ((index, props) in blockData) {
                if (props.isEmpty()) continue
                val encodedProps = LinkedHashMap<String, TypedValueJson>(props.size)
                for ((k, v) in props) {
                    encodedProps[k] = TypedValueJson.from(v)
                }
                encodedEntries[index.toString()] = encodedProps
            }
            return chunkBlockDataAdapter.toJson(ChunkBlockDataFileData(entries = encodedEntries))
        }

        fun parseChunkBlockData(file: File): Map<Int, Map<String, TypedValue>> {
            if (!file.exists() || !file.isFile) return emptyMap()
            return try {
                val json = file.readText()
                if (json.isBlank()) return emptyMap()
                val parsed = chunkBlockDataAdapter.fromJson(json) ?: return emptyMap()
                val result = HashMap<Int, Map<String, TypedValue>>(parsed.entries.size)
                for ((idxStr, propsJson) in parsed.entries) {
                    val idx = idxStr.toIntOrNull() ?: continue
                    if (idx !in 0 until Chunk.VOLUME || propsJson.isEmpty()) continue
                    val decodedProps = HashMap<String, TypedValue>(propsJson.size)
                    for ((k, vJson) in propsJson) {
                        vJson.toTypedValue()?.let { decodedProps[k] = it }
                    }
                    if (decodedProps.isNotEmpty()) {
                        result[idx] = decodedProps
                    }
                }
                result
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse block data sidecar at ${file.absolutePath}", e)
                emptyMap()
            }
        }

        fun chunkEntityPos(entity: Entity, chunksY: Int): ChunkPos = World.chunkEntityPos(entity, chunksY)

        fun isAllAir(blocks: ByteArray): Boolean {
            for (i in blocks.indices) {
                if (blocks[i] != 0.toByte()) return false
            }
            return true
        }

        fun quarantineCorruptChunkFile(file: File) {
            try {
                Log.e(TAG, "CRITICAL: Corrupted chunk file detected: ${file.absolutePath}. Quarantining to .corrupt.")
                val corruptFile = File(file.parentFile, "${file.name}.corrupt")
                if (corruptFile.exists()) {
                    corruptFile.delete()
                }
                if (!file.renameTo(corruptFile)) {
                    file.copyTo(corruptFile, overwrite = true)
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to quarantine corrupted chunk file ${file.absolutePath}", e)
            }
        }
    }
}

/**
 * Backwards-compatible typealias pointing to the unified [WorldSaveManager].
 */
typealias WorldSaveStorage = WorldSaveManager
