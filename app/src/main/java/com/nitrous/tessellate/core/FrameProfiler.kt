package com.nitrous.tessellate.core

import android.util.Log

/**
 * Lightweight zero-allocation per-frame profiler using [System.nanoTime] across core engine stages:
 * - Chunk Generation
 * - Entity Updates (including active vs sleeping distance-culled entity counts)
 * - Mesh Building
 * - Rendering
 * - Lazyprop Lighting
 *
 * Throttles Logcat output cleanly so logging itself never causes GC pressure during gameplay.
 */
class FrameProfiler(
    private val logIntervalMs: Long = DEFAULT_LOG_INTERVAL_MS,
    @Volatile
    var enabled: Boolean = false
) {
    @Volatile var chunkGenerationMs: Float = 0f
        private set
    @Volatile var entityUpdatesMs: Float = 0f
        private set
    @Volatile var meshBuildingMs: Float = 0f
        private set
    @Volatile var renderingMs: Float = 0f
        private set
    @Volatile var lightingUpdateMs: Float = 0f
        private set

    @Volatile var tickedEntitiesCount: Int = 0
        private set
    @Volatile var sleepingEntitiesCount: Int = 0
        private set
    @Volatile var visibleChunksCount: Int = 0
        private set
    @Volatile var frustumCulledChunksCount: Int = 0
        private set
    @Volatile var loggedFrameCount: Long = 0L
        private set
    @Volatile var lastLogMessage: String = ""
        private set

    private var lastLogTimestampMs: Long = 0L

    fun recordUpdateStage(
        chunkGenMs: Float,
        entityUpdateMs: Float,
        lightingMs: Float,
        tickedEntities: Int,
        sleepingEntities: Int
    ) {
        chunkGenerationMs = chunkGenMs
        entityUpdatesMs = entityUpdateMs
        lightingUpdateMs = lightingMs
        tickedEntitiesCount = tickedEntities
        sleepingEntitiesCount = sleepingEntities
    }

    fun recordRenderStage(
        meshBuildMs: Float,
        renderMs: Float,
        visibleChunks: Int,
        culledChunks: Int
    ) {
        meshBuildingMs = meshBuildMs
        renderingMs = renderMs
        visibleChunksCount = visibleChunks
        frustumCulledChunksCount = culledChunks
    }

    fun formatSummary(): String {
        return "ChunkGen=%.2fms | Entities=%.2fms (ticked=%d, asleep=%d) | Light=%.2fms | MeshBuild=%.2fms | Render=%.2fms (visChunks=%d, culled=%d)".format(
            chunkGenerationMs,
            entityUpdatesMs,
            tickedEntitiesCount,
            sleepingEntitiesCount,
            lightingUpdateMs,
            meshBuildingMs,
            renderingMs,
            visibleChunksCount,
            frustumCulledChunksCount
        )
    }

    fun formatCompactHudSummary(): String {
        return "nanoTime: Gen=%.2f Ent=%.2f(%d/%d) L=%.2f M=%.2f R=%.2f ms".format(
            chunkGenerationMs,
            entityUpdatesMs,
            tickedEntitiesCount,
            sleepingEntitiesCount,
            lightingUpdateMs,
            meshBuildingMs,
            renderingMs
        )
    }

    fun maybeLog(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        if (lastLogTimestampMs != 0L && (nowMs - lastLogTimestampMs) < logIntervalMs) {
            return false
        }
        lastLogTimestampMs = nowMs
        val summary = formatSummary()
        lastLogMessage = summary
        loggedFrameCount++
        Log.i(TAG, summary)
        return true
    }

    fun reset() {
        chunkGenerationMs = 0f
        entityUpdatesMs = 0f
        meshBuildingMs = 0f
        renderingMs = 0f
        lightingUpdateMs = 0f
        tickedEntitiesCount = 0
        sleepingEntitiesCount = 0
        visibleChunksCount = 0
        frustumCulledChunksCount = 0
        loggedFrameCount = 0L
        lastLogMessage = ""
        lastLogTimestampMs = 0L
    }

    companion object {
        private const val TAG = "Tessellate.Profiler"
        const val DEFAULT_LOG_INTERVAL_MS = 1000L
        const val NS_TO_MS = 1f / 1_000_000f
    }
}
