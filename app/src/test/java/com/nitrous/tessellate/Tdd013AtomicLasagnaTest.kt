package com.nitrous.tessellate

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.command.CommandConsole
import com.nitrous.tessellate.command.CommandRegistry
import com.nitrous.tessellate.command.JsonCommand
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.SceneMode
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.registry.CommandDefinitionJson
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.registry.RegistryFrozenException
import com.nitrous.tessellate.scene.WorldScene
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.Direction
import com.nitrous.tessellate.world.RaycastHit
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldRenderer
import com.nitrous.tessellate.worldgen.BasePass
import com.nitrous.tessellate.worldgen.CavePass
import com.nitrous.tessellate.worldgen.OrePass
import com.nitrous.tessellate.worldgen.StructoidSizeChunks
import com.nitrous.tessellate.worldgen.SurfacePass
import com.nitrous.tessellate.worldgen.TreeFeaturePass
import com.nitrous.tessellate.worldgen.WorldGenRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.floor

/**
 * Verification test suite for TDD-013: The Atomic Lasagna (Infinite World, Structoids, & Mod Commands).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd013AtomicLasagnaTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
    }

    @After
    fun tearDown() {
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
    }

    // =========================================================================
    // ACCEPTANCE TEST 1: VANILLA ATOMIC LASAGNA PASSES, HILLS, CAVES, ORES, TREES
    // =========================================================================

    @Test
    fun `vanilla worldgen passes register sorted by priority and freeze cleanly`() {
        assertTrue(WorldGenRegistry.isFrozen)
        val passes = WorldGenRegistry.getPasses()
        assertEquals(
            listOf(0, 10, 30, 40, 50, 60),
            passes.map { it.priority }
        )
        assertTrue(passes[0] is BasePass)
        assertTrue(passes[1] is SurfacePass)
        assertTrue(passes[2] is CavePass)
        assertTrue(passes[4] is OrePass)
        assertTrue(passes[5] is TreeFeaturePass)

        assertThrows(RegistryFrozenException::class.java) {
            WorldGenRegistry.register(BasePass(id = "mod:late_pass", priority = 99))
        }
    }

    @Test
    fun `infinite world generates rolling hills with grass dirt stone ore caves and oak trees`() {
        val world = World(seed = 1337L, renderDistance = 4, heightChunks = 8)
        try {
            assertTrue(world.isInfinite)
            assertEquals(128, world.sizeY)

            val surfaceY = SurfacePass.computeSurfaceY(world.seed, 24, 24)
            assertTrue("Surface Y=$surfaceY must be in 40..80", surfaceY in 40..80)

            val player = Player()
            player.spawnAtSurface(world, 24.5f, 24.5f)
            assertTrue("Player must spawn atop terrain hill/tree >= 40", player.posY >= 40f)

            var foundGrass = false
            var foundDirt = false
            var foundStone = false
            var foundOreCobblestone = false
            var foundCaveAirBelowSurface = false
            var foundOakLog = false
            var foundLeaves = false

            for (cx in 0..4) {
                for (cz in 0..4) {
                    world.ensureChunkColumnLoadedSync(cx, cz)
                }
            }

            for (wx in 0 until 80) {
                for (wz in 0 until 80) {
                    val colSurface = SurfacePass.computeSurfaceY(world.seed, wx, wz)
                    for (wy in 10 until 90) {
                        val b = world.getBlock(wx, wy, wz)
                        when (b) {
                            BlockType.GRASS -> foundGrass = true
                            BlockType.DIRT -> foundDirt = true
                            BlockType.STONE -> foundStone = true
                            BlockType.COBBLESTONE -> foundOreCobblestone = true
                            BlockType.OAK_LOG -> foundOakLog = true
                            BlockType.LEAVES -> foundLeaves = true
                            BlockType.AIR -> {
                                if (wy < colSurface - 6 && wy < CavePass.MAX_CAVE_Y) {
                                    foundCaveAirBelowSurface = true
                                }
                            }
                        }
                    }
                }
            }

            assertTrue("Must contain grass surface blocks", foundGrass)
            assertTrue("Must contain dirt sub-surface layers", foundDirt)
            assertTrue("Must contain stone bedrock layers", foundStone)
            assertTrue("Must contain cobblestone ore replacements", foundOreCobblestone)
            assertTrue("Must contain carved underground caves below Y=60", foundCaveAirBelowSurface)
            assertTrue("Must spawn oak_log tree trunks", foundOakLog)
            assertTrue("Must spawn 3x3 leaves canopies", foundLeaves)

            var verifiedFullTree = false
            outer@ for (wx in 2 until 78) {
                for (wz in 2 until 78) {
                    val rootY = SurfacePass.computeSurfaceY(world.seed, wx, wz)
                    if (world.getBlock(wx, rootY, wz) == BlockType.GRASS &&
                        world.getBlock(wx, rootY + 1, wz) == BlockType.OAK_LOG
                    ) {
                        var trunkCount = 0
                        for (ty in (rootY + 1)..(rootY + 6)) {
                            if (world.getBlock(wx, ty, wz) == BlockType.OAK_LOG) trunkCount++
                        }
                        val hasCanopy = world.getBlock(wx + 1, rootY + trunkCount, wz) == BlockType.LEAVES &&
                            world.getBlock(wx - 1, rootY + trunkCount, wz + 1) == BlockType.LEAVES &&
                            world.getBlock(wx, rootY + trunkCount + 1, wz) == BlockType.LEAVES
                        if (trunkCount in 4..6 && hasCanopy) {
                            verifiedFullTree = true
                            break@outer
                        }
                    }
                }
            }
            assertTrue("Trees must have 4-6 block oak_log trunk and 3x3 leaves canopy", verifiedFullTree)
        } finally {
            world.shutdownStreaming()
        }
    }

    // =========================================================================
    // ACCEPTANCE TEST 2: INFINITE WALKING & ASYNC CHUNK STREAMING
    // =========================================================================

    @Test
    fun `infinite world streams chunks asynchronously on Dispatchers Default and unloads distant chunks`() = runBlocking {
        val world = World(seed = 1337L, renderDistance = 4, heightChunks = 8)
        val renderer = WorldRenderer(world)
        val pipeline = TexturePipeline(context)
        for (id in TextureConstants.ALL_BLOCK_IDS) {
            pipeline.loadTexture(id, TextureConstants.getAssetPath(id))
        }
        pipeline.createAtlasBitmap().recycle()

        try {
            val initialJob = world.streamChunksAround(
                playerX = 24.5f,
                playerZ = 24.5f,
                meshPrebuilder = pipeline,
                ioDispatcher = Dispatchers.Default,
                forceCheck = true
            )
            assertNotNull(initialJob)
            initialJob!!.join()

            assertEquals((2 * 4 + 1) * (2 * 4 + 1) * 8, world.getLoadedChunkCount())
            assertTrue(world.asyncGeneratedChunksCount.get() > 0)
            renderer.updateMeshes(pipeline, uploadToGpu = false)
            assertEquals(world.getLoadedChunkCount(), renderer.getChunkMeshes().size)

            val player = Player(posX = 400.5f, posY = 90f, posZ = -200.5f)
            player.update(deltaTime = 0.05f, world = world, moveForward = 1f, moveStrafe = 0f, crouchInput = false)
            assertTrue("Infinite world must NOT clamp player posX to 48", player.posX > 395f)
            assertTrue("Infinite world must NOT clamp player posZ to >= 0", player.posZ < -195f)

            val moveJob = world.streamChunksAround(
                playerX = player.posX,
                playerZ = player.posZ,
                meshPrebuilder = pipeline,
                ioDispatcher = Dispatchers.Default
            )
            assertNotNull(moveJob)
            moveJob!!.join()

            assertNull("Old chunk (0, 0, 0) must unload outside renderDistance", world.getChunk(0, 0, 0))
            val farCx = floor(player.posX).toInt() shr 4
            val farCz = floor(player.posZ).toInt() shr 4
            assertNotNull("Far chunk ($farCx, 3, $farCz) must be streamed in", world.getChunk(farCx, 3, farCz))
            assertEquals((2 * 4 + 1) * (2 * 4 + 1) * 8, world.getLoadedChunkCount())

            renderer.updateMeshes(pipeline, uploadToGpu = false)
            assertEquals(world.getLoadedChunkCount(), renderer.getChunkMeshes().size)
        } finally {
            world.shutdownStreaming()
        }
    }

    // =========================================================================
    // ACCEPTANCE TEST 3: /instamine COMMAND & INSTANT PUNCH BREAKING
    // =========================================================================

    @Test
    fun `instamine command toggles instant breaking and breaks stone on first punch`() {
        val engine = TessellateEngine(context)
        val scene = engine.worldScene
        try {
            assertFalse(scene.player.isInstaMine)

            val enableResult = engine.executeCommand("/instamine")
            assertTrue(enableResult.success)
            assertTrue(scene.player.isInstaMine)

            val stonePos = BlockPos(24, 65, 22)
            scene.world.setBlock(stonePos, BlockType.STONE)
            val hit = RaycastHit(stonePos, BlockType.STONE, Direction.SOUTH, 2.0f)

            assertTrue(scene.startMining(stonePos))
            val brokeInstantly = scene.updateMining(deltaTimeSeconds = 0.001f, currentHit = hit)
            assertTrue("Stone (hardness 1.5f) must break in 1 tick under /instamine", brokeInstantly)
            assertEquals(BlockType.AIR, scene.world.getBlock(stonePos))

            val tapStonePos = BlockPos(24, 65, 21)
            scene.world.setBlock(tapStonePos, BlockType.STONE)
            scene.player.posX = 24.5f
            scene.player.posY = 64.0f
            scene.player.posZ = 24.5f
            scene.player.yaw = 0f
            scene.player.pitch = 0f
            engine.camera.setFirstPersonView(scene.player.posX, scene.player.eyeY, scene.player.posZ, 0f, 0f)
            scene.updateCameraRaycast(engine.camera)
            assertEquals(tapStonePos, scene.currentTargetHit?.blockPos)

            engine.setAppState(AppState.PLAYING)
            engine.switchSceneMode(SceneMode.WORLD)
            engine.touchController.onSurfaceChanged(1920, 1080)

            val t0 = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 960f, 540f, 0)
            engine.onTouchEvent(down)
            down.recycle()
            val up = MotionEvent.obtain(t0, t0 + 35, MotionEvent.ACTION_UP, 960f, 540f, 0)
            engine.onTouchEvent(up)
            up.recycle()

            assertEquals("Punch tap under /instamine must break stone immediately", BlockType.AIR, scene.world.getBlock(tapStonePos))

            val disableResult = engine.executeCommand("/instamine")
            assertTrue(disableResult.success)
            assertFalse(scene.player.isInstaMine)
        } finally {
            scene.release()
        }
    }

    // =========================================================================
    // ACCEPTANCE TEST 4: STRUCTOID CARVING + NHPHP EMPTY CHUNK OPTIMIZATION
    // =========================================================================

    @Test
    fun `structoid Ch1x1x1 carves terrain hole and skips air-only chunks via NHPHP optimization`() {
        val parsed = StructoidSizeChunks.parse("Ch2x4x2")
        assertEquals(StructoidSizeChunks(2, 4, 2), parsed)

        val structoidJson = """
            {
              "id": "tessellate:test_hole_ch1",
              "origin_chunk_x": 1,
              "origin_chunk_y": 2,
              "origin_chunk_z": 1,
              "size_chunks": "Ch1x1x1",
              "carve_all": true
            }
        """.trimIndent()

        AndroidRegistryBootstrap.initialize(
            context = context,
            extraStructoidJsons = listOf(structoidJson),
            forceReinitialize = true
        )

        val world = World(seed = 1337L, renderDistance = 2, heightChunks = 8)
        try {
            for (lx in 0 until Chunk.SIZE) {
                for (ly in 0 until Chunk.SIZE) {
                    for (lz in 0 until Chunk.SIZE) {
                        assertEquals(
                            "Structoid Ch1x1x1 must carve chunk (1,2,1) block ($lx,$ly,$lz) to AIR",
                            BlockType.AIR,
                            world.getBlock(16 + lx, 32 + ly, 16 + lz)
                        )
                    }
                }
            }

            assertEquals(BlockType.STONE, world.getBlock(5, 35, 20))

            assertTrue(
                "StructoidPass must skip air-only sky chunks via NHPHP optimization",
                WorldGenRegistry.structoidPass.skippedEmptyChunksCount.get() > 0
            )
            assertTrue(
                "StructoidPass must have carved intersecting non-empty chunk (1,2,1)",
                WorldGenRegistry.structoidPass.carvedChunksCount.get() >= 1
            )
        } finally {
            world.shutdownStreaming()
        }
    }

    // =========================================================================
    // ACCEPTANCE TEST 5: DATA-DRIVEN COMMAND REGISTRY & MOD WORLDGEN PASS INJECTION
    // =========================================================================

    @Test
    fun `data driven command registry parses base and mod json commands cleanly`() {
        assertTrue(CommandRegistry.isFrozen)
        assertNotNull(CommandRegistry.get("give"))
        assertNotNull(CommandRegistry.get("clear"))
        assertNotNull(CommandRegistry.get("spawn"))
        assertNotNull(CommandRegistry.get("instamine"))

        assertThrows(RegistryFrozenException::class.java) {
            CommandRegistry.register(
                JsonCommand(
                    CommandDefinitionJson(name = "late")
                )
            )
        }

        val modCommandJson = """
            {
              "name": "sparkle",
              "description": "Dummy mod command verifying JSON parsing",
              "usage": "/sparkle",
              "action": "dummy_mod_action",
              "response": "Sparkles deployed across the Atomic Lasagna!"
            }
        """.trimIndent()

        AndroidRegistryBootstrap.initialize(
            context = context,
            extraCommandJsons = listOf(modCommandJson),
            forceReinitialize = true
        )

        val scene = WorldScene()
        try {
            val registered = CommandRegistry.get("sparkle")
            assertNotNull(registered)
            val result = CommandConsole.execute("/sparkle", scene)
            assertTrue(result.success)
            assertEquals("Sparkles deployed across the Atomic Lasagna!", result.message)
        } finally {
            scene.release()
        }
    }

    @Test
    fun `mod worldgen pass json injects by priority offset before freezing`() {
        val modPassJson = """
            {
              "id": "testmod:glass_crust",
              "priority": 10,
              "priority_offset": 5,
              "operation": "fill_layer",
              "block_id": "tessellate:glass",
              "replace_block_id": "tessellate:grass",
              "min_y": 40,
              "max_y": 80,
              "chance": 1.0
            }
        """.trimIndent()

        AndroidRegistryBootstrap.initialize(
            context = context,
            extraWorldGenPassJsons = listOf(modPassJson),
            forceReinitialize = true
        )

        val passes = WorldGenRegistry.getPasses()
        val modPass = passes.find { it.id == "testmod:glass_crust" }
        assertNotNull(modPass)
        assertEquals(15, modPass!!.priority)
        assertEquals(listOf(0, 10, 15, 30, 40, 50, 60), passes.map { it.priority })

        val world = World(seed = 1337L, renderDistance = 1, heightChunks = 8)
        try {
            var foundGlassSurface = false
            for (wx in 0..31) {
                for (wz in 0..31) {
                    val sy = SurfacePass.computeSurfaceY(world.seed, wx, wz)
                    if (world.getBlock(wx, sy, wz) == BlockType.GLASS) {
                        foundGlassSurface = true
                        break
                    }
                }
                if (foundGlassSurface) break
            }
            assertTrue("Mod pass at priority 15 must transform grass surface to glass", foundGlassSurface)
        } finally {
            world.shutdownStreaming()
        }
    }
}
