package com.nitrous.tessellate.world

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Bounded background chunk-meshing worker pool in `:core` (platform-independent):
 * - Uses `max(1, availableProcessors() - 1)` daemon worker threads so the GL/UI thread stays smooth.
 * - Workers generate raw [FloatArray] vertex buffers via [ChunkGeometryBuilder] off-thread without
 *   touching OpenGL or Android APIs.
 * - Outputs [ChunkMeshOutput] objects via [ConcurrentLinkedQueue] for the render thread in `:app`
 *   to upload to OpenGL.
 */
class ParallelChunkMesher(
    val workerThreadCount: Int = defaultWorkerThreadCount()
) {
    private val threadId = AtomicInteger(1)
    private val executor = Executors.newFixedThreadPool(workerThreadCount.coerceAtLeast(1)) { runnable ->
        Thread(runnable, "Tessellate-Mesher-${threadId.getAndIncrement()}").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            LOGGER.log(Level.WARNING, "ParallelChunkMesher worker failed safely", throwable)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)

    @Volatile
    private var isShutdown = false

    val completedQueue = ConcurrentLinkedQueue<ChunkMeshOutput>()
    private val queuedPositions = ConcurrentHashMap.newKeySet<ChunkPos>()
    private val threadLocalBuilder = ThreadLocal.withInitial { ChunkGeometryBuilder() }

    val queuedCount: Int
        get() = queuedPositions.size

    val completedPendingCount: Int
        get() = completedQueue.size

    /**
     * Submits [chunk] for asynchronous off-thread [FloatArray] mesh generation if not already queued
     * and within [MAX_QUEUED_CHUNKS] capacity.
     */
    fun submitDirtyChunk(
        chunk: Chunk,
        world: World,
        tileResolver: AtlasTileResolver,
        forceRebuild: Boolean = false
    ): Boolean {
        if (isShutdown || world.isDestroyed) {
            return false
        }
        if (queuedPositions.size >= MAX_QUEUED_CHUNKS) {
            return false
        }
        val pos = ChunkPos(chunk.chunkX, chunk.chunkY, chunk.chunkZ)
        if (!queuedPositions.add(pos)) {
            return false
        }
        try {
            scope.launch {
                try {
                    if (!isShutdown && !world.isDestroyed && (forceRebuild || chunk.needsMeshRebuild)) {
                        val builder = threadLocalBuilder.get() ?: ChunkGeometryBuilder()
                        val output = builder.buildChunkGeometry(world, chunk, tileResolver)
                        if (!isShutdown && !world.isDestroyed) {
                            completedQueue.offer(output)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    LOGGER.log(Level.WARNING, "Failed meshing chunk $pos off-thread", t)
                } finally {
                    queuedPositions.remove(pos)
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            queuedPositions.remove(pos)
            return false
        } catch (t: Throwable) {
            queuedPositions.remove(pos)
            LOGGER.log(Level.WARNING, "Failed scheduling chunk mesh for $pos", t)
            return false
        }
    }

    fun pollCompletedMesh(): ChunkMeshOutput? = completedQueue.poll()

    fun isQueued(pos: ChunkPos): Boolean = queuedPositions.contains(pos)

    fun clearPending() {
        completedQueue.clear()
        queuedPositions.clear()
    }

    fun shutdown() {
        isShutdown = true
        scope.cancel()
        executor.shutdownNow()
        completedQueue.clear()
        queuedPositions.clear()
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(ParallelChunkMesher::class.java.name)
        const val MAX_QUEUED_CHUNKS: Int = 24

        fun defaultWorkerThreadCount(): Int {
            return (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        }
    }
}
