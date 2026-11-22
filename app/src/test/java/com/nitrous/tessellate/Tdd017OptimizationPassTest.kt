package com.nitrous.tessellate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.FrameProfiler
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.scene.WorldScene
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.ChunkMesh
import com.nitrous.tessellate.world.Direction
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.LightEngine
import com.nitrous.tessellate.world.RaycastHit
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldRenderer
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Verification test suite for TDD-017: The NHPHP Optimization Pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd017OptimizationPassTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RegistryBootstrap.initialize(context)
        ShadowLog.clear()
    }

    // =========================================================================
    // 1. CHUNK MESH OPTIMIZATION (Face Culling, Lazy Buffers, Frustum Culling)
    // =========================================================================

    @Test
    fun `chunk mesh culls solid-solid, same-transparent, and air faces with lazy GPU buffers`() {
        val world = createEmptyWorld(chunksX = 1, chunksY = 2, chunksZ = 1)
        val pipeline = TexturePipeline(context)

        val emptyChunk = world.getChunk(0, 1, 0)!!
        assertTrue(emptyChunk.isEmpty)
        val emptyMesh = ChunkMesh(emptyChunk)
        emptyMesh.generateMeshData(world, pipeline)
        assertEquals(0, emptyMesh.indexCount)
        assertEquals(0, emptyMesh.generatedFacesCount)
        assertFalse("Empty chunk must never allocate GPU buffers", emptyMesh.hasAllocatedGpuBuffers)

        // Two adjacent solid blocks + two adjacent identical glass blocks in chunk (0, 0, 0)
        val workChunk = world.getChunk(0, 0, 0)!!
        world.setBlock(4, 4, 4, BlockType.STONE)
        world.setBlock(5, 4, 4, BlockType.STONE)
        world.setBlock(8, 8, 8, BlockType.GLASS)
        world.setBlock(9, 8, 8, BlockType.GLASS)

        val workMesh = ChunkMesh(workChunk)
        workMesh.generateMeshData(world, pipeline)

        assertEquals("Two adjacent stones expose 10 outer faces (2 culled)", 10, workMesh.opaqueFacesCount)
        assertEquals("Two adjacent identical glass blocks expose 10 outer faces (2 culled)", 10, workMesh.transparentFacesCount)
        assertTrue(workMesh.culledFacesCount >= 4)

        // Opaque-only chunk must never allocate transparent GPU buffers
        world.setBlock(8, 8, 8, BlockType.AIR)
        world.setBlock(9, 8, 8, BlockType.AIR)
        workMesh.generateMeshData(world, pipeline)
        assertEquals(10, workMesh.opaqueFacesCount)
        assertEquals(0, workMesh.transparentFacesCount)
        assertEquals(0, workMesh.transparentIndexCount)
        assertFalse("Opaque-only chunk must have no transparent GPU buffers", workMesh.hasTransparentGpuBuffers)
    }

    @Test
    fun `world renderer skips chunks behind camera before draw calls`() {
        val world = World(chunksX = 3, chunksY = 2, chunksZ = 3)
        val renderer = WorldRenderer(world)
        val camera = Camera3D(fovDegrees = 60f, nearPlane = 0.1f, farPlane = 180f)
        camera.updateProjection(16f / 9f)

        // Look outward toward +Z from z=44: chunks behind (chunkZ == 0, z in 0..16) must be culled
        camera.setFirstPersonView(posX = 24f, posY = 16f, posZ = 44f, playerPitch = 0f, playerYaw = 180f)
        val visible = renderer.computeVisibleChunks(camera.viewProjectionMatrix)

        assertTrue("Frustum culling must skip behind-camera chunks", renderer.frustumCulledChunksCount > 0)
        assertFalse(
            "Chunks behind camera must not appear in visible chunks",
            visible.any { it.chunk.chunkZ == 0 }
        )
    }

    // =========================================================================
    // 2. ENTITY & TICK OPTIMIZATION (Distance Culling & AABB Caching)
    // =========================================================================

    @Test
    fun `entities beyond 4 chunks sleep until player approaches and refresh AABB once per tick`() {
        val world = createEmptyWorld(chunksX = 6, chunksY = 1, chunksZ = 2)
        // Flat floor so dropped item lands instead of falling into void
        for (x in 0 until world.sizeX) {
            for (z in 0 until world.sizeZ) {
                world.setBlock(x, 2, z, BlockType.STONE)
            }
        }

        val drop = ItemDropEntity(Vec3(8.5f, 8.0f, 8.5f))
        val initialAabbUpdates = drop.aabbUpdateCount
        assertEquals(1, initialAabbUpdates)
        world.spawnEntity(drop)

        // 1. Tick within active radius (< 64 blocks) with large dt triggering multiple substeps
        world.tickEntities(deltaTime = 0.15f, playerX = 12f, playerZ = 8.5f)
        assertFalse("Entity within 4 chunks must stay awake", drop.isSleeping)
        assertEquals(1, drop.tickCount)
        assertEquals(
            "Entity AABB must refresh strictly once per tick, not per substep",
            initialAabbUpdates + 1,
            drop.aabbUpdateCount
        )
        assertEquals(1, world.lastTickedEntitiesCount)
        assertEquals(0, world.lastSleepingEntitiesCount)

        // 2. Walk > 4 chunks (e.g., 80 blocks) away -> entity goes to sleep and skips ticks
        val ticksBeforeSleep = drop.tickCount
        val aabbsBeforeSleep = drop.aabbUpdateCount
        val posYBeforeSleep = drop.position.y

        repeat(5) {
            world.tickEntities(deltaTime = 0.05f, playerX = 88.5f, playerZ = 8.5f)
        }
        assertTrue("Entity beyond 4 chunks must be asleep", drop.isSleeping)
        assertEquals("Sleeping entity tickCount must not advance", ticksBeforeSleep, drop.tickCount)
        assertEquals("Sleeping entity AABB must not update", aabbsBeforeSleep, drop.aabbUpdateCount)
        assertEquals("Sleeping entity position must not move", posYBeforeSleep, drop.position.y, 1e-5f)
        assertEquals(0, world.lastTickedEntitiesCount)
        assertEquals(1, world.lastSleepingEntitiesCount)

        // 3. Walk back within 4 chunks -> entity wakes up cleanly
        world.tickEntities(deltaTime = 0.05f, playerX = 20f, playerZ = 8.5f)
        assertFalse("Returning near entity must wake it up", drop.isSleeping)
        assertEquals(ticksBeforeSleep + 1, drop.tickCount)
        assertEquals(aabbsBeforeSleep + 1, drop.aabbUpdateCount)
        assertEquals(1, world.lastTickedEntitiesCount)
        assertEquals(0, world.lastSleepingEntitiesCount)
    }

    // =========================================================================
    // 3. LAZYPROP LIGHTING OPTIMIZATION (Time-Slicing & Dark/Lit Skipping)
    // =========================================================================

    @Test
    fun `lazyprop lighting time-slices 15-block wave across frames and skips completely dark or lit chunks`() {
        val world = createEmptyWorld(chunksX = 2, chunksY = 2, chunksZ = 1)
        val bottomChunk = world.getChunk(0, 0, 0)!!
        val topChunk = world.getChunk(0, 1, 0)!!

        // Completely opaque chunk vs completely open sky chunk
        bottomChunk.fill(BlockType.STONE)
        world.lightEngine.initializeChunkLighting(bottomChunk, world)
        assertTrue(bottomChunk.isCompletelyDark)
        assertFalse(bottomChunk.needsLightUpdate)
        assertNull("Uniform dark chunk must not allocate lightIntended buffer", bottomChunk.lightIntended)
        assertNull("Uniform dark chunk must not allocate lightRendered buffer", bottomChunk.lightRendered)

        topChunk.fill(BlockType.AIR)
        world.lightEngine.initializeChunkLighting(topChunk, world)
        assertTrue(topChunk.isCompletelyLit)
        assertFalse(topChunk.needsLightUpdate)
        assertNull("Uniform lit sky chunk must not allocate lightIntended buffer", topChunk.lightIntended)

        // Trying to queue completely dark/lit chunks must keep them clean and skipped
        world.lightEngine.scheduleChunkLightUpdate(bottomChunk)
        world.lightEngine.scheduleChunkLightUpdate(topChunk)
        assertFalse(bottomChunk.needsLightUpdate)
        assertFalse(topChunk.needsLightUpdate)

        // Carve a 15-block dark tunnel inside stone chunk (x in 1..15, y=8, z=8) and seed light=15 at (1,8,8)
        val tunnelChunk = world.getChunk(0, 0, 0)!!
        for (x in 1..15) {
            tunnelChunk.setBlock(x, 8, 8, BlockType.AIR)
        }
        tunnelChunk.markCompletelyDark()
        tunnelChunk.ensureLightStorage()

        val slicedLightEngine = LightEngine(maxNodesPerFrame = 3, maxBudgetNs = 5_000_000L)
        slicedLightEngine.seedLight(world, 1, 8, 8, 15)

        // Before stepping, intended is seeded at origin while rendered is still -16 (absolute darkness)
        assertEquals(15, tunnelChunk.getIntendedLight(1, 8, 8))
        assertEquals(Chunk.MIN_LIGHT, tunnelChunk.getRenderedLight(1, 8, 8))

        // First slice (3 nodes): origin syncs to rendered=15 and wave reaches ~2-3 blocks, NOT all 15 blocks!
        val processedStep1 = slicedLightEngine.step(world, maxNodes = 3, budgetNs = 5_000_000L)
        assertEquals(3, processedStep1)
        assertEquals(15, tunnelChunk.getRenderedLight(1, 8, 8))
        assertTrue("Time-sliced BFS must not solve entire 15-block tunnel in 1 small frame", tunnelChunk.getRenderedLight(15, 8, 8) == Chunk.MIN_LIGHT)
        assertTrue("BFS queue must still have pending nodes across frames", slicedLightEngine.hasPendingWork())

        // Continue stepping frame by frame until wave finishes
        var steps = 1
        while (slicedLightEngine.hasPendingWork() && steps < 32) {
            slicedLightEngine.step(world, maxNodes = 3, budgetNs = 5_000_000L)
            steps++
        }
        assertTrue("Propagation must span multiple frames", steps > 2)
        assertEquals(14, tunnelChunk.getRenderedLight(2, 8, 8))
        assertEquals(1, tunnelChunk.getRenderedLight(15, 8, 8))
    }

    // =========================================================================
    // 4. MEMORY / ZERO-ALLOC HELPERS & PROFILER / ACCEPTANCE TESTS
    // =========================================================================

    @Test
    fun `texture pipeline fallback uv reuses singleton and world steady-state queries remain consistent`() {
        val pipeline = TexturePipeline(context)
        val a = pipeline.getAtlasUv("nonexistent_texture_1")
        val b = pipeline.getAtlasUv("nonexistent_texture_2")
        assertSame("Fallback TextureUvRect must be cached without per-call allocations", a, b)

        // Steady-state player & world update loop executes cleanly
        val scene = WorldScene()
        scene.profiler.enabled = true
        scene.startSession(
            WorldSession(name = "OptTest", gamemode = GameMode.SURVIVAL, worldType = WorldType.FLAT, seed = 42L)
        )
        repeat(10) {
            scene.player.update(0.016f, scene.world, moveForward = 0f, moveStrafe = 0f, crouchInput = false)
            scene.update(0.016f)
        }
        assertTrue(scene.profiler.chunkGenerationMs >= 0f)
        assertTrue(scene.profiler.entityUpdatesMs >= 0f)
        assertTrue(scene.profiler.lightingUpdateMs >= 0f)
        scene.destroySession()
    }

    @Test
    fun `acceptance test - block placement rebuilds only dirty chunk without stuttering and profiler tracks sleeping items`() {
        val scene = WorldScene()
        scene.profiler.enabled = true
        scene.startSession(
            WorldSession(name = "PerfAcceptance", gamemode = GameMode.CREATIVE, worldType = WorldType.FLAT, seed = 77L)
        )
        val pipeline = TexturePipeline(context)

        // Wait for initial async streaming pass to settle before verifying clean rebuild state
        runBlocking {
            scene.world.streamChunksAround(
                scene.player.posX,
                scene.player.posZ,
                pipeline,
                forceCheck = true
            )?.join()
        }

        // Build all initial meshes headlessly
        scene.worldRenderer.updateMeshes(pipeline, uploadToGpu = false)
        assertTrue(scene.worldRenderer.getChunkMeshes().all { !it.chunk.needsMeshRebuild })

        // Acceptance Test 2: Place a block cleanly -> only target chunk (plus edge neighbor if on border) marks dirty and rebuilds
        val px = scene.player.posX.toInt()
        val py = (scene.player.posY - 1f).toInt().coerceAtLeast(1)
        val pz = scene.player.posZ.toInt() + 3
        val baseHit = RaycastHit(
            blockPos = BlockPos(px, py, pz),
            blockType = scene.world.getBlock(px, py, pz),
            face = Direction.UP,
            distance = 3f
        )
        assertTrue(scene.placeBlockAt(baseHit, BlockType.STONE))

        val dirtyCount = scene.worldRenderer.getChunkMeshes().count { it.chunk.needsMeshRebuild }
        assertTrue("Block placement must dirty only localized chunks (got $dirtyCount)", dirtyCount in 1..2)
        scene.worldRenderer.updateMeshes(pipeline, uploadToGpu = false)
        assertEquals(0, scene.worldRenderer.getChunkMeshes().count { it.chunk.needsMeshRebuild })

        // Acceptance Test 3: Spawn item drop nearby (outside pickup range), walk > 4 chunks away, check profiler shows 0 ticked & 1 asleep
        val drop = ItemDropEntity(Vec3(scene.player.posX + 5f, scene.player.posY + 1f, scene.player.posZ))
        scene.world.spawnEntity(drop)

        scene.update(0.016f)
        assertFalse(drop.isSleeping)
        assertEquals(1, scene.profiler.tickedEntitiesCount)
        assertEquals(0, scene.profiler.sleepingEntitiesCount)

        // Move player > 64 blocks away along X
        scene.player.posX += 80f
        scene.player.updateBoundingBox()

        ShadowLog.clear()
        scene.profiler.reset()
        scene.update(0.016f)

        assertTrue("Item > 4 chunks away must enter sleeping state", drop.isSleeping)
        assertEquals(0, scene.profiler.tickedEntitiesCount)
        assertEquals(1, scene.profiler.sleepingEntitiesCount)

        val profilerLogs = ShadowLog.getLogsForTag("Tessellate.Profiler")
        assertTrue("Profiler must emit timed summary log", profilerLogs.isNotEmpty())
        val msg = profilerLogs.last().msg
        assertTrue(msg.contains("ChunkGen="))
        assertTrue(msg.contains("Entities="))
        assertTrue(msg.contains("ticked=0, asleep=1"))
        assertTrue(msg.contains("MeshBuild="))
        assertTrue(msg.contains("Render="))

        scene.destroySession()
    }

    private fun createEmptyWorld(chunksX: Int, chunksY: Int, chunksZ: Int): World {
        val world = World(chunksX = chunksX, chunksY = chunksY, chunksZ = chunksZ)
        for (x in 0 until world.sizeX) {
            for (y in 0 until world.sizeY) {
                for (z in 0 until world.sizeZ) {
                    world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }
        world.lightEngine.clear()
        for (cx in 0 until chunksX) {
            for (cy in 0 until chunksY) {
                for (cz in 0 until chunksZ) {
                    world.getChunk(cx, cy, cz)?.let {
                        it.recalculateEmpty()
                        it.markCompletelyLit()
                    }
                }
            }
        }
        return world
    }
}
