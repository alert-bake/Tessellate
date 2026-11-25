package com.nitrous.tessellate.desktop

import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.NativeBufferPool
import com.nitrous.tessellate.world.ParallelChunkMesher
import com.nitrous.tessellate.world.World

fun main() {
    val testVec = Vec3(1.0, 2.0, 3.0)
    check(testVec.length() > 0.0)

    // Verify platform-independent TDD-022.1 systems load & execute on Desktop JVM
    val world = World(chunksX = 1, chunksY = 1, chunksZ = 1)
    world.setBlock(8, 4, 8, BlockType.STONE)
    val dropA = ItemDropEntity(Vec3(8.5, 6.0, 8.5))
    val dropB = ItemDropEntity(Vec3(8.8, 6.0, 8.5))
    world.spawnEntity(dropA)
    world.spawnEntity(dropB)
    world.tickEntities(0.05f)
    val mergeResult = world.mergeNearbyItemDrops()
    val renderState = dropA.createRenderState()

    val pool = NativeBufferPool(capacity = 50, defaultBufferBytes = 4096)
    pool.setEnabled(true)
    val pooled = pool.borrowBuffer(256)
    if (pooled != null) {
        pool.markUploaded(pooled, uploadedFrameIndex = 1L)
        pool.floodGcTick(currentFrameIndex = 2L)
    }

    val mesher = ParallelChunkMesher()
    val chunk = world.getChunk(0, 0, 0)!!
    mesher.submitDirtyChunk(chunk, world, { 0 }, forceRebuild = true)
    var meshOut = mesher.pollCompletedMesh()
    val deadline = System.currentTimeMillis() + 3000L
    while (meshOut == null && System.currentTimeMillis() < deadline) {
        Thread.sleep(5)
        meshOut = mesher.pollCompletedMesh()
    }
    mesher.shutdown()
    pool.release()
    world.destroy()

    println(
        "Tesselate Core successfully loaded on Desktop JVM " +
            "(merged=${mergeResult.mergedCount}, yawRad=${renderState?.yawRadians}, " +
            "meshFloats=${meshOut?.opaqueVertexFloatLength ?: 0})."
    )
}
