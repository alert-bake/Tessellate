package com.nitrous.tessellate.world

import com.nitrous.tessellate.entity.Entity
import kotlinx.coroutines.Job

/**
 * Marker interface for prebuilt CPU chunk mesh buffers generated asynchronously off the GL thread.
 * Implemented by `ChunkMesh` in the rendering module.
 */
interface PrebuiltChunkMesh

/**
 * Platform-agnostic chunk mesh prebuilder invoked during background chunk streaming.
 */
fun interface ChunkMeshPrebuilder {
    fun buildPrebuiltMesh(world: World, chunk: Chunk): PrebuiltChunkMesh
}

/**
 * Platform-agnostic world chunk & entity persistence storage contract used by [World].
 */
interface WorldChunkStorage {
    fun loadChunkBytes(pos: ChunkPos): ByteArray?
    fun loadBlockData(pos: ChunkPos): Map<Int, Map<String, TypedValue>> = emptyMap()
    fun loadEntities(pos: ChunkPos): List<Entity>
    fun saveChunkAndEntitiesAsync(chunk: Chunk, chunkEntities: List<Entity>): Job
}
