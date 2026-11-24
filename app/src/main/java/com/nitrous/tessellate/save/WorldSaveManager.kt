package com.nitrous.tessellate.save

import android.content.Context
import android.util.Log
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.ChunkPos
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldChunkStorage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

/**
 * Coordinates world save directories under `context.getExternalFilesDir(null)/saves/<world_name>/`
 * (`Android/data/com.nitrous.tessellate/files/saves/`) for power-user backup and inspection.
 */
object WorldSaveManager {

    private const val TAG = "WorldSaveManager"
    const val SAVES_DIR_NAME = "saves"
    const val CHUNKS_DIR_NAME = "chunks"
    const val ENTITIES_DIR_NAME = "entities"
    const val LEVEL_FILE_NAME = "level.json"

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val levelAdapter = moshi.adapter(WorldSaveData::class.java).indent("  ")
    private val chunkEntitiesAdapter = moshi.adapter(ChunkEntityFileData::class.java).indent("  ")

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

    fun chunkEntityPos(entity: Entity, chunksY: Int): ChunkPos = World.chunkEntityPos(entity, chunksY)
}

/**
 * Per-world save storage managing binary chunk files (`chunks/c_X_Y_Z.bin`),
 * per-chunk entity JSON files (`entities/c_X_Y_Z.json`), and `level.json`.
 */
class WorldSaveStorage(
    val worldDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : WorldChunkStorage {
    val chunksDir = File(worldDir, WorldSaveManager.CHUNKS_DIR_NAME)
    val entitiesDir = File(worldDir, WorldSaveManager.ENTITIES_DIR_NAME)
    val levelFile = File(worldDir, WorldSaveManager.LEVEL_FILE_NAME)

    private val ioExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e(TAG, "Async world save operation failed safely", throwable)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + ioExceptionHandler)
    private val activeJobs: MutableSet<Job> = Collections.synchronizedSet(HashSet())
    private val fileLocks = ConcurrentHashMap<ChunkPos, Any>()
    private val levelLock = Any()

    private data class PendingChunkWrite(val version: Long, val bytes: ByteArray?)
    private data class PendingEntityWrite(val version: Long, val entities: List<SavedEntityData>)

    private val writeVersionGen = AtomicLong(0L)
    private val pendingChunkWrites = ConcurrentHashMap<ChunkPos, PendingChunkWrite>()
    private val pendingEntityWrites = ConcurrentHashMap<ChunkPos, PendingEntityWrite>()

    @Volatile
    var lastSaveJob: Job? = null
        private set

    fun chunkFileFor(pos: ChunkPos): File = File(chunksDir, "c_${pos.x}_${pos.y}_${pos.z}.bin")

    fun entityFileFor(pos: ChunkPos): File = File(entitiesDir, "c_${pos.x}_${pos.y}_${pos.z}.json")

    private fun ensureDirectoriesExist() {
        if (!worldDir.exists()) worldDir.mkdirs()
        if (!chunksDir.exists()) chunksDir.mkdirs()
        if (!entitiesDir.exists()) entitiesDir.mkdirs()
    }

    private fun lockFor(pos: ChunkPos): Any = fileLocks.computeIfAbsent(pos) { Any() }

    /**
     * Queues an async [Dispatchers.IO] write for a chunk's raw 4096-byte block data (omitting
     * 100%-air chunks) and its entities JSON file. Buffers the snapshot in-memory under
     * versioned maps first so an immediate reload sees the latest state even before I/O completes.
     */
    override fun saveChunkAndEntitiesAsync(chunk: Chunk, chunkEntities: List<Entity>): Job {
        val pos = ChunkPos(chunk.chunkX, chunk.chunkY, chunk.chunkZ)
        val bytesCopy: ByteArray? = if (isAllAir(chunk.blocks)) null else chunk.blocks.copyOf()
        val serializedEntities = chunkEntities.map { EntityPersistenceRegistry.serialize(it) }

        val chunkVer = writeVersionGen.incrementAndGet()
        val entityVer = writeVersionGen.incrementAndGet()
        pendingChunkWrites[pos] = PendingChunkWrite(chunkVer, bytesCopy)
        pendingEntityWrites[pos] = PendingEntityWrite(entityVer, serializedEntities)

        val job = scope.launch(ioDispatcher) {
            try {
                writeChunkAndEntitiesInternal(pos, bytesCopy, chunkVer, serializedEntities, entityVer)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed async chunk/entity save for $pos", t)
            }
        }
        trackJob(job)
        lastSaveJob = job
        return job
    }

    private fun writeChunkAndEntitiesInternal(
        pos: ChunkPos,
        bytesCopy: ByteArray?,
        chunkVer: Long,
        serializedEntities: List<SavedEntityData>,
        entityVer: Long
    ) {
        synchronized(lockFor(pos)) {
            ensureDirectoriesExist()

            // 1. Binary chunk block data (no headers, no metadata; never save 100% air)
            val cFile = chunkFileFor(pos)
            try {
                if (bytesCopy == null) {
                    if (cFile.exists()) {
                        cFile.delete()
                    }
                } else {
                    cFile.writeBytes(bytesCopy)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing chunk file ${cFile.name}", e)
            } finally {
                val current = pendingChunkWrites[pos]
                if (current != null && current.version == chunkVer) {
                    pendingChunkWrites.remove(pos, current)
                }
            }

            // 2. Entity JSON data
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
                    eFile.writeText(WorldSaveManager.serializeChunkEntities(payload))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing entity file ${eFile.name}", e)
            } finally {
                val current = pendingEntityWrites[pos]
                if (current != null && current.version == entityVer) {
                    pendingEntityWrites.remove(pos, current)
                }
            }
        }
    }

    /**
     * Reads raw 4096-byte chunk data if saved (checking any pending async write first),
     * or returns `null` if no non-empty saved chunk exists on disk.
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
                val bytes = file.readBytes()
                if (bytes.size == Chunk.VOLUME) bytes else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read chunk bytes ${file.name}", e)
                null
            }
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
            val parsed = WorldSaveManager.parseChunkEntities(file) ?: return emptyList()
            return parsed.entities.mapNotNull { EntityPersistenceRegistry.deserialize(it) }
        }
    }

    fun loadLevelData(): WorldSaveData? = synchronized(levelLock) {
        val parsed = WorldSaveManager.parseLevelData(levelFile) ?: return null
        parsed.copy(folderName = worldDir.name)
    }

    fun saveLevelDataSync(data: WorldSaveData): Boolean = synchronized(levelLock) {
        return try {
            ensureDirectoriesExist()
            val normalized = data.copy(folderName = worldDir.name)
            levelFile.writeText(WorldSaveManager.serializeLevelData(normalized))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing level.json at ${levelFile.absolutePath}", e)
            false
        }
    }

    fun saveLevelDataAsync(data: WorldSaveData): Job {
        val normalized = data.copy(folderName = worldDir.name)
        val job = scope.launch(ioDispatcher) {
            try {
                saveLevelDataSync(normalized)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed async level.json save", t)
            }
        }
        trackJob(job)
        lastSaveJob = job
        return job
    }

    /**
     * Captures and saves `level.json`, all currently loaded chunks, and all active entities asynchronously.
     */
    fun saveWorldSnapshotAsync(
        saveData: WorldSaveData,
        loadedChunks: List<Chunk>,
        activeEntities: List<Entity>,
        chunksY: Int
    ): Job {
        val grouped = groupEntitiesForSnapshot(activeEntities, chunksY)
        val chunkSnapshots = loadedChunks.map { chunk ->
            val pos = ChunkPos(chunk.chunkX, chunk.chunkY, chunk.chunkZ)
            val bytesCopy = if (isAllAir(chunk.blocks)) null else chunk.blocks.copyOf()
            val entities = grouped[pos].orEmpty()
            val cVer = writeVersionGen.incrementAndGet()
            val eVer = writeVersionGen.incrementAndGet()
            pendingChunkWrites[pos] = PendingChunkWrite(cVer, bytesCopy)
            pendingEntityWrites[pos] = PendingEntityWrite(eVer, entities)
            SnapshotEntry(pos, bytesCopy, cVer, entities, eVer)
        }
        val normalizedLevel = saveData.copy(folderName = worldDir.name)

        val job = scope.launch(ioDispatcher) {
            try {
                saveLevelDataSync(normalizedLevel)
                for (entry in chunkSnapshots) {
                    writeChunkAndEntitiesInternal(
                        entry.pos,
                        entry.bytes,
                        entry.chunkVer,
                        entry.entities,
                        entry.entityVer
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed async world snapshot save", t)
            }
        }
        trackJob(job)
        lastSaveJob = job
        return job
    }

    /**
     * Synchronously saves `level.json`, all loaded chunks, and all active entities,
     * and awaits any earlier in-flight async chunk unloads so disk state is 100% consistent.
     */
    fun saveWorldSnapshotSync(
        saveData: WorldSaveData,
        loadedChunks: List<Chunk>,
        activeEntities: List<Entity>,
        chunksY: Int
    ) {
        awaitPendingWritesBlocking()

        val grouped = groupEntitiesForSnapshot(activeEntities, chunksY)
        saveLevelDataSync(saveData)

        for (chunk in loadedChunks) {
            val pos = ChunkPos(chunk.chunkX, chunk.chunkY, chunk.chunkZ)
            val bytesCopy = if (isAllAir(chunk.blocks)) null else chunk.blocks.copyOf()
            val entities = grouped[pos].orEmpty()
            val cVer = writeVersionGen.incrementAndGet()
            val eVer = writeVersionGen.incrementAndGet()
            pendingChunkWrites[pos] = PendingChunkWrite(cVer, bytesCopy)
            pendingEntityWrites[pos] = PendingEntityWrite(eVer, entities)
            writeChunkAndEntitiesInternal(pos, bytesCopy, cVer, entities, eVer)
        }
    }

    fun awaitPendingWritesBlocking() {
        val snapshot = synchronized(activeJobs) { activeJobs.toList() }
        if (snapshot.isEmpty()) return
        runBlocking {
            for (job in snapshot) {
                job.join()
            }
        }
    }

    private fun trackJob(job: Job) {
        activeJobs.add(job)
        job.invokeOnCompletion {
            activeJobs.remove(job)
        }
    }

    private fun groupEntitiesForSnapshot(
        activeEntities: List<Entity>,
        chunksY: Int
    ): Map<ChunkPos, List<SavedEntityData>> {
        if (activeEntities.isEmpty()) return emptyMap()
        val grouped = HashMap<ChunkPos, MutableList<SavedEntityData>>()
        for (entity in activeEntities) {
            if (entity.isRemoved) continue
            val pos = WorldSaveManager.chunkEntityPos(entity, chunksY)
            grouped.getOrPut(pos) { mutableListOf() }
                .add(EntityPersistenceRegistry.serialize(entity))
        }
        return grouped
    }

    private data class SnapshotEntry(
        val pos: ChunkPos,
        val bytes: ByteArray?,
        val chunkVer: Long,
        val entities: List<SavedEntityData>,
        val entityVer: Long
    )

    companion object {
        private const val TAG = "WorldSaveStorage"

        fun isAllAir(blocks: ByteArray): Boolean {
            for (i in blocks.indices) {
                if (blocks[i] != 0.toByte()) return false
            }
            return true
        }
    }
}
