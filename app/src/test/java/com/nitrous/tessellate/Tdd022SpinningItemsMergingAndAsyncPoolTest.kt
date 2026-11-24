package com.nitrous.tessellate

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.EntityRenderer
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.save.EntityPersistenceRegistry
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.settings.VanillaSettingsRegistrar
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.ChunkMesh
import com.nitrous.tessellate.world.ChunkMeshOutput
import com.nitrous.tessellate.world.NativeBufferPool
import com.nitrous.tessellate.world.ParallelChunkMesher
import com.nitrous.tessellate.world.World
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Verification test suite for TDD-022:
 * - Spinning, sine-wave bobbing, and floating ItemDropEntity
 * - Unbounded simulation stack (e.g. 192+) with 64-layer visual cap in EntityRenderer GPU instancing
 * - NHPHP 1.5-block item merging capped at 5 candidate checks per entity per tick
 * - Debug setting "Use Native Memory Pooling" (50 DirectByteBuffers + 1-per-tick Flood GC + fallback)
 * - Bounded ParallelChunkMesher (availableProcessors - 1) + ConcurrentLinkedQueue handoff
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd022SpinningItemsMergingAndAsyncPoolTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        SettingsManager.resolveSettingsFile(context).delete()
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        SettingsManager.resolveSettingsFile(context).delete()
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
    }

    @Test
    fun `acceptance 1 dropped item spins on Y axis, bobs with sine wave, and floats`() {
        val world = World(chunksX = 2, chunksY = 2, chunksZ = 2)
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                world.setBlock(x, 4, z, BlockType.STONE)
            }
        }
        val drop = ItemDropEntity(Vec3(8.5f, 5.0f, 8.5f), itemId = "tessellate:stone")
        world.spawnEntity(drop)

        val initialYaw = drop.rotationYawDegrees
        val bobSamples = mutableListOf<Float>()
        repeat(30) {
            world.tickEntities(1f / 30f, 8.5f, 8.5f)
            bobSamples.add(drop.bobOffsetY)
        }

        assertTrue("Item drop must spin around Y axis over time", drop.rotationYawDegrees > initialYaw)
        assertTrue(
            "Item drop must stay floating above its floor block",
            bobSamples.all { it > 0.0f }
        )
        val minBob = bobSamples.minOrNull()!!
        val maxBob = bobSamples.maxOrNull()!!
        assertTrue("Sine wave bobbing must oscillate smoothly (${maxBob - minBob})", (maxBob - minBob) > 0.03f)

        // Stack count persists cleanly through EntityPersistenceRegistry
        val bigDrop = ItemDropEntity(Vec3(8.5f, 5.0f, 8.5f), itemId = "tessellate:stone", count = 192)
        val saved = EntityPersistenceRegistry.serialize(bigDrop)
        val restored = EntityPersistenceRegistry.deserialize(saved) as? ItemDropEntity
        assertNotNull(restored)
        assertEquals(192, restored!!.count)
    }

    @Test
    fun `acceptance 2 five identical drops within 1_5 blocks fuse into one entity and distinct or far drops stay separate`() {
        val engine = TessellateEngine(context)
        val scene = engine.worldScene
        try {
            scene.world.clearEntities()
            scene.player.posX = 50f
            scene.player.posZ = 50f

            // Spawn 5 identical stone drops within 1.5 blocks of (8.5, 8.0, 8.5)
            val nearbyDrops = listOf(
                ItemDropEntity(Vec3(8.2f, 8.0f, 8.5f), "tessellate:stone"),
                ItemDropEntity(Vec3(8.5f, 8.0f, 8.5f), "tessellate:stone"),
                ItemDropEntity(Vec3(8.8f, 8.0f, 8.6f), "tessellate:stone"),
                ItemDropEntity(Vec3(9.1f, 8.0f, 8.5f), "tessellate:stone"),
                ItemDropEntity(Vec3(9.3f, 8.0f, 8.7f), "tessellate:stone")
            )
            // 1 different item type nearby + 1 identical item further than 1.5 blocks
            val dirtNearby = ItemDropEntity(Vec3(8.5f, 8.0f, 8.5f), "tessellate:dirt")
            val farStone = ItemDropEntity(Vec3(12.0f, 8.0f, 8.5f), "tessellate:stone")

            (nearbyDrops + dirtNearby + farStone).forEach { scene.world.spawnEntity(it) }

            repeat(3) {
                scene.update(1f / 60f)
            }

            val survivingDrops = scene.world.entities.filterIsInstance<ItemDropEntity>().filter { !it.isRemoved }
            assertEquals("5 identical nearby + 1 dirt + 1 far stone = 3 surviving entities", 3, survivingDrops.size)

            val mergedStone = survivingDrops.first { it.itemId.equals("tessellate:stone", true) && it.position.x < 10f }
            assertEquals(5, mergedStone.count)
            assertTrue(survivingDrops.any { it === dirtNearby && it.count == 1 })
            assertTrue(survivingDrops.any { it === farStone && it.count == 1 })
        } finally {
            scene.destroySession()
        }
    }

    @Test
    fun `nhphp merge caps candidate checks per entity per tick to 5 and supports 192+ simulation stack pickup`() {
        val engine = TessellateEngine(context)
        val scene = engine.worldScene
        try {
            scene.world.clearEntities()
            scene.player.posX = 50f
            scene.player.posZ = 50f

            // Place 12 non-matching alternating or tightly packed drops within dx <= 1.5f
            for (i in 0 until 12) {
                val id = if (i % 2 == 0) "tessellate:stone" else "tessellate:dirt"
                scene.world.spawnEntity(ItemDropEntity(Vec3(8.0f + i * 0.05f, 8.0f, 8.5f), id))
            }
            scene.mergeNearbyItemDrops()
            // Each of the <= 12 entities may check at most 5 nearby candidates -> total <= 12 * 5
            assertTrue(
                "Total merge candidate checks (${scene.lastMergeCandidateChecks}) must respect max 5 checks per entity",
                scene.lastMergeCandidateChecks <= 12 * 5
            )

            // Unlimited simulation stack (192 items in 1 entity) fills multiple inventory slots on pickup
            scene.world.clearEntities()
            val megaDrop = ItemDropEntity(Vec3(8.5f, 6.0f, 8.5f), "tessellate:stone", count = 192)
            scene.world.spawnEntity(megaDrop)
            scene.player.posX = 8.5f
            scene.player.posY = 6.0f
            scene.player.posZ = 8.5f
            scene.checkItemPickups()

            assertTrue(megaDrop.isRemoved)
            assertEquals(192, scene.player.inventory.getTotalCount("tessellate:stone"))
        } finally {
            scene.destroySession()
        }
    }

    @Test
    fun `gpu instancing builder caps visual stack layers at 64 for 192+ stacks with offset spinning transforms`() {
        val renderer = EntityRenderer()
        val pipeline = TexturePipeline(context)
        for (id in TextureConstants.ALL_BLOCK_IDS) {
            pipeline.loadTexture(id, TextureConstants.getAssetPath(id))
        }
        pipeline.createAtlasBitmap().recycle()

        val world = World(chunksX = 1, chunksY = 1, chunksZ = 1)
        val megaDrop = ItemDropEntity(Vec3(8.5f, 8.0f, 8.5f), itemId = "tessellate:stone", count = 192)
        megaDrop.tick(world, 0.5f) // Advance rotation & sine bobbing

        assertEquals(64, EntityRenderer.computeRenderLayerCount(192))
        assertEquals(1, EntityRenderer.computeRenderLayerCount(1))

        val totalInstances = renderer.buildInstancedDropData(
            visibleEntities = listOf(megaDrop),
            texturePipeline = pipeline,
            world = world,
            useAtlasUvs = true
        )

        assertEquals("192 simulation stack must emit strictly 64 GPU instances", 64, totalInstances)
        assertEquals(64, renderer.lastRenderedInstanceCount)

        val first = FloatArray(12)
        val second = FloatArray(12)
        renderer.getStagingInstanceValues(0, first)
        renderer.getStagingInstanceValues(1, second)

        // Offset positions & Y rotations must differ between layer 0 and layer 1
        assertNotEquals(first[0], second[0], 1e-5f)
        assertNotEquals(first[3], second[3], 1e-5f)
        assertTrue("Atlas UV span must be valid", first[10] > first[8])
    }

    @Test
    fun `acceptance 3-4 native memory pool preallocates 50 buffers, flood gc reclaims 1 per tick safely, and debug toggle switches cleanly`() {
        val warnings = mutableListOf<String>()
        val pool = NativeBufferPool(
            capacity = 50,
            defaultBufferBytes = 4096,
            onWarning = { warnings.add(it) }
        )
        assertFalse(pool.isEnabled)
        assertNull("Disabled pool must return null for JVM heap fallback", pool.borrowBuffer(256))

        // Enable pool -> pre-allocates strictly 50 DirectByteBuffers
        pool.setEnabled(true)
        assertTrue(pool.isInitialized)
        assertEquals(50, pool.availableCount)
        assertEquals(0, pool.inFlightCount)

        val borrowedList = mutableListOf<ByteBuffer>()
        repeat(5) { idx ->
            val buf = pool.borrowBuffer(512)
            assertNotNull(buf)
            assertTrue("Pooled buffer must be direct native memory", buf!!.isDirect)
            pool.markUploaded(buf, uploadedFrameIndex = 10L + idx)
            borrowedList.add(buf)
        }
        assertEquals(45, pool.availableCount)
        assertEquals(5, pool.inFlightCount)

        // Safety guard: same frame index must NOT reclaim buffers still in GPU use
        assertFalse(pool.floodGcTick(currentFrameIndex = 10L))
        assertEquals(45, pool.availableCount)

        // Continuous Flood GC: strictly 1 buffer reclaimed per tick when currentFrame > uploadedFrame
        assertTrue(pool.floodGcTick(currentFrameIndex = 11L)) // reclaims frame 10
        assertEquals(46, pool.availableCount)
        assertEquals(4, pool.inFlightCount)

        assertFalse("Frame 11 uploaded == current 11 must wait", pool.floodGcTick(currentFrameIndex = 11L))
        assertTrue(pool.floodGcTick(currentFrameIndex = 20L)) // reclaims ONLY 1 (frame 11), NOT a batch sweep!
        assertEquals(47, pool.availableCount)
        assertEquals(3, pool.inFlightCount)

        // Exhaust remaining 47 buffers -> 51st borrow invokes warning callback and falls back cleanly
        while (pool.availableCount > 0) {
            val b = pool.borrowBuffer(256)
            assertNotNull(b)
            pool.markUploaded(b!!, 100L)
        }
        assertEquals(0, pool.availableCount)
        val exhausted = pool.borrowBuffer(256)
        assertNull("Exhausted pool must return null for JVM fallback", exhausted)
        assertEquals(1L, pool.exhaustedWarningCount)
        assertEquals(1L, pool.fallbackCount)
        assertTrue(warnings.any { it.contains("NativeBufferPool exhausted") })

        // Verify Debug Options toggle UI & persistence wiring
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.settingsRegistry.setValue("tessellate:enable_debug_options", true)
            engine.setAppState(AppState.DEBUG_OPTIONS)
            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_DEBUG_RENDERING)

            val row = screen.getVisibleRows().firstOrNull { it.definition.id == "tessellate:debug_native_memory_pooling" }
            assertNotNull("Use Native Memory Pooling toggle must appear in Debug Rendering", row)
            assertEquals("Use Native Memory Pooling", row!!.definition.label)
            assertFalse(engine.worldScene.worldRenderer.useNativeMemoryPooling)

            // Toggle ON
            val tapX = row.actionButton!!.rect.centerX()
            val tapY = row.actionButton!!.rect.centerY()
            val down = android.view.MotionEvent.obtain(0L, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_DOWN, tapX, tapY, 0)
            val up = android.view.MotionEvent.obtain(0L, SystemClock.uptimeMillis() + 20, android.view.MotionEvent.ACTION_UP, tapX, tapY, 0)
            engine.onTouchEvent(down)
            engine.onTouchEvent(up)
            down.recycle()
            up.recycle()

            assertTrue(engine.currentSettings.useNativeMemoryPooling)
            assertTrue(engine.worldScene.worldRenderer.useNativeMemoryPooling)
            assertEquals(50, engine.worldScene.worldRenderer.nativeBufferPool.availableCount)

            // Toggle OFF -> game keeps running cleanly falling back to JVM staging
            engine.settingsRegistry.setValue("tessellate:debug_native_memory_pooling", false)
            assertFalse(engine.worldScene.worldRenderer.useNativeMemoryPooling)
            val pipeline = TexturePipeline(context)
            engine.worldScene.worldRenderer.updateMeshes(pipeline, uploadToGpu = false)
        } finally {
            engine.worldScene.destroySession()
            pool.release()
        }
    }

    @Test
    fun `parallel chunk mesher in core bounds threads and emits raw FloatArray ChunkMeshOutput via ConcurrentLinkedQueue`() {
        val expectedWorkers = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        val mesher = ParallelChunkMesher()
        assertEquals(expectedWorkers, mesher.workerThreadCount)

        val world = World(chunksX = 1, chunksY = 1, chunksZ = 1)
        val chunk = world.getChunk(0, 0, 0)!!
        chunk.setBlock(4, 4, 4, BlockType.STONE)
        chunk.markDirty()

        val pipeline = TexturePipeline(context)
        for (id in TextureConstants.ALL_BLOCK_IDS) {
            pipeline.loadTexture(id, TextureConstants.getAssetPath(id))
        }
        pipeline.createAtlasBitmap().recycle()

        val mesh = ChunkMesh(chunk)
        try {
            assertTrue(mesher.submitDirtyChunk(chunk, world, pipeline, forceRebuild = true))

            val deadlineMs = System.currentTimeMillis() + 5000L
            var completed: ChunkMeshOutput? = null
            while (completed == null && System.currentTimeMillis() < deadlineMs) {
                completed = mesher.pollCompletedMesh()
                if (completed == null) {
                    Thread.sleep(10)
                }
            }
            assertNotNull("Core parallel mesher must push raw FloatArray output to ConcurrentLinkedQueue", completed)
            assertEquals(chunk, completed!!.chunk)
            assertTrue("Worker output must contain raw FloatArray vertex data", completed.opaqueVertices.isNotEmpty())
            assertFalse(chunk.needsMeshRebuild)

            mesh.applyMeshOutput(completed, pipeline)
            assertTrue("ChunkMesh must apply core FloatArray geometry cleanly", mesh.indexCount > 0)
        } finally {
            mesher.shutdown()
        }
    }
}
