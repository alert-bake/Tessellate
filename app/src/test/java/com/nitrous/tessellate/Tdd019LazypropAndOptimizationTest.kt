package com.nitrous.tessellate

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.command.CommandConsole
import com.nitrous.tessellate.entity.EntityRenderer
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.math.Matrix4
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.scene.WorldScene
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.ui.BitmapFontRenderer
import com.nitrous.tessellate.ui.UiBatchRenderer
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockRegistry
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive verification suite for:
 * - TDD-019: Lazyprop Signed [-16..+16] Lighting Engine & Starlight 5-Neighbor BFS
 * - TDD-019.5: Block Models ("cross"), Torch Emitter, & Transparent Atlas Asset
 * - Engine Optimization Pass: Greedy Meshing, 12-Byte Vertex Packing, Staging/CPU Trimming,
 *   Sorted Opaque/Transparent Passes, UiBatchRenderer, Entity Culling & Texture Grouping
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd019LazypropAndOptimizationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
    }

    // =========================================================================
    // TDD-019 ACCEPTANCE TESTS: LAZYPROP SIGNED [-16..+16] LIGHTING ENGINE
    // =========================================================================

    @Test
    fun `surface receives +16 skylight and underground cave decays into -16 darkness`() {
        val world = World(chunksX = 4, chunksY = 2, chunksZ = 3)
        // Build a sealed stone mountain block enclosing a 40-block deep tunnel (> 32 skylight decay span)
        val zCenter = 24
        for (y in 2..12) {
            for (z in (zCenter - 3)..(zCenter + 3)) {
                for (x in 8..54) {
                    world.setBlock(x, y, z, BlockType.STONE)
                }
            }
        }
        // Carve enclosed hallway at y=4..5, z=24 from x=10 to x=50 with sky shaft at x=50
        for (x in 10..50) {
            world.setBlock(x, 4, zCenter, BlockType.AIR)
            world.setBlock(x, 5, zCenter, BlockType.AIR)
        }
        // Clear sky shaft at x=50 from y=4 up to top of world
        for (y in 4 until world.sizeY) {
            world.setBlock(50, y, zCenter, BlockType.AIR)
        }
        repeat(8) { world.stepLighting() }

        // Surface above terrain must be +16 bright
        assertEquals(16.toByte(), world.getIntendedLight(BlockPos(50, 20, zCenter)))
        assertEquals(16.toByte(), world.getIntendedLight(BlockPos(50, 5, zCenter)))

        // Stepping deeper inside enclosed roof drops monotonically via Manhattan distance (+16 -> -16 over 32 blocks)
        val atDepth1 = world.getIntendedLight(BlockPos(49, 5, zCenter)).toInt()
        val atDepth4 = world.getIntendedLight(BlockPos(46, 5, zCenter)).toInt()
        val deepCave = world.getIntendedLight(BlockPos(10, 5, zCenter)).toInt()
        assertTrue("Surface light edge must exceed interior ($atDepth1 > $atDepth4)", atDepth1 > atDepth4)
        assertTrue("Enclosed cave entrance should be dimmer than sky ($atDepth4 in 1..15)", atDepth4 in 1..15)
        assertEquals("Deep unlit underground chamber (>32 blocks in) must reach absolute darkness (-16)", -16, deepCave)
    }

    @Test
    fun `placing and breaking a torch illuminates dark chamber and cleanly recedes without blind subtraction`() {
        val world = createDarkStoneWorld(chunksX = 2, chunksY = 1, chunksZ = 2)
        world.lightQueue.maxBudgetNs = Long.MAX_VALUE
        // Carve a sealed dark room from (4..24, 6..10, 6..10)
        for (x in 4..24) {
            for (y in 6..10) {
                for (z in 6..10) {
                    world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }
        // Re-lock light arrays dark after carving stone
        world.getChunk(0, 0, 0)?.markCompletelyDark()
        world.getChunk(1, 0, 0)?.markCompletelyDark()
        world.lightQueue.clear()

        assertEquals(Chunk.MIN_LIGHT.toByte(), world.getIntendedLight(BlockPos(10, 7, 8)))

        // Place first torch at (10, 7, 8)
        world.setBlock(10, 7, 8, BlockType.TORCH)
        assertEquals(15.toByte(), world.getIntendedLight(BlockPos(10, 7, 8)))
        assertEquals(14.toByte(), world.getIntendedLight(BlockPos(11, 7, 8)))
        assertEquals(12.toByte(), world.getIntendedLight(BlockPos(13, 7, 8)))

        // Place second torch at (16, 7, 8) so overlapping light cones test non-blind removal
        world.setBlock(16, 7, 8, BlockType.TORCH)
        assertEquals(15.toByte(), world.getIntendedLight(BlockPos(16, 7, 8)))

        // Step Global World.lightQueue until rendered light matches intended and chunks flag dirty
        var steps = 0
        while (world.lightQueue.hasPendingWork() && steps < 40) {
            world.stepLighting()
            steps++
        }
        assertFalse(world.lightQueue.hasPendingWork())
        assertEquals(15, world.getRenderedLight(10, 7, 8).toInt())
        assertEquals(15, world.getRenderedLight(16, 7, 8).toInt())

        val chunk0 = world.getChunk(0, 0, 0)!!
        assertTrue("Updating lightRendered must mark affected chunk mesh dirty", chunk0.needsMeshRebuild)

        // Break first torch: near (10,7,8) falls smoothly to torch2's contribution (15 - 6 = 9), NOT broken black!
        world.setBlock(10, 7, 8, BlockType.AIR)
        assertEquals(
            "Removing torch 1 must preserve surviving illumination from torch 2 at (10,7,8)",
            9.toByte(),
            world.getIntendedLight(BlockPos(10, 7, 8))
        )

        // Break second torch: room returns completely to -16 absolute darkness
        world.setBlock(16, 7, 8, BlockType.AIR)
        while (world.lightQueue.hasPendingWork()) {
            world.stepLighting()
        }
        assertEquals((-16).toByte(), world.getIntendedLight(BlockPos(10, 7, 8)))
        assertEquals((-16).toByte(), world.getIntendedLight(BlockPos(16, 7, 8)))
        assertEquals(-16, world.getRenderedLight(10, 7, 8).toInt())
        assertEquals(-16, world.getRenderedLight(16, 7, 8).toInt())
    }

    @Test
    fun `light queue enforces 500 updates per tick cap cleanly`() {
        val world = createDarkStoneWorld(chunksX = 3, chunksY = 1, chunksZ = 3)
        // Hollow large cavern (1..46, 2..14, 1..46) in dark rock so a torch floods > 1000 air nodes
        for (x in 1..46) {
            for (y in 2..14) {
                for (z in 1..46) {
                    world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }
        for (cx in 0..2) for (cz in 0..2) world.getChunk(cx, 0, cz)?.markCompletelyDark()
        world.lightQueue.clear()

        assertEquals(500, LightEngine.DEFAULT_MAX_NODES_PER_FRAME)
        world.lightQueue.maxBudgetNs = Long.MAX_VALUE // Test node cap deterministically regardless of CI CPU jitter
        world.setBlock(24, 8, 24, BlockType.TORCH)

        val tick1Nodes = world.lightQueue.step(world)
        assertTrue("First tick must process multiple nodes bounded by <= 500", tick1Nodes in 1..500)
        assertTrue("Large cavern light flood must leave pending work for subsequent ticks", world.lightQueue.hasPendingWork())

        var safety = 0
        while (world.lightQueue.hasPendingWork() && safety < 200) {
            val count = world.lightQueue.step(world)
            assertTrue(count <= 500)
            safety++
        }
        assertFalse(world.lightQueue.hasPendingWork())
        assertEquals(15, world.getRenderedLight(24, 8, 24).toInt())
    }

    // =========================================================================
    // TDD-019.5 ACCEPTANCE TESTS: CROSS MODEL & TORCH
    // =========================================================================

    @Test
    fun `torch definition, transparent asset texture, give command, cross mesh, and zero-hardness mining`() {
        // 1. Definition & JSON schema checks
        val torchBlock = BlockRegistry.TORCH
        assertEquals("cross", torchBlock.model)
        assertTrue(torchBlock.isCrossModel)
        assertEquals(15.toByte(), torchBlock.lightEmission)
        assertEquals(0.0f, torchBlock.hardness, 1e-5f)
        assertFalse(torchBlock.isSolid)
        assertTrue(torchBlock.isTransparent)
        assertTrue(torchBlock.isMineable)
        assertNotNull(ItemRegistry.get("tessellate:torch"))

        // 2. Verify block_torch.png exists and has genuine alpha transparency + zero magenta
        val torchBmp = context.assets.open(TextureConstants.getAssetPath(TextureConstants.BLOCK_TORCH)).use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inScaled = false })
        }!!
        var hasTransparentPx = false
        var hasOpaquePx = false
        for (y in 0 until torchBmp.height) {
            for (x in 0 until torchBmp.width) {
                val px = torchBmp.getPixel(x, y)
                val a = Color.alpha(px)
                if (a == 0) hasTransparentPx = true
                if (a == 255) {
                    hasOpaquePx = true
                    val isMagenta = Color.red(px) == 255 && Color.green(px) == 0 && Color.blue(px) == 255
                    assertFalse("Torch PNG must not contain magenta fallback pixels", isMagenta)
                }
            }
        }
        torchBmp.recycle()
        assertTrue("Torch texture must have transparent surround pixels", hasTransparentPx)
        assertTrue("Torch texture must have opaque stick/flame pixels", hasOpaquePx)

        // 3. /give tessellate:torch 1 & placement & cross-model double-sided X-quad generation
        val scene = WorldScene()
        scene.startSession(WorldSession(name = "TorchTest", gamemode = GameMode.SURVIVAL, worldType = WorldType.FLAT, seed = 9L))
        val giveResult = CommandConsole.execute("/give tessellate:torch 1", scene)
        assertTrue("/give tessellate:torch 1 must succeed", giveResult.success)

        // Select given torch and verify world mesh generates double-sided X-quads (4 quads = 24 indices)
        val isolatedWorld = createDarkStoneWorld(chunksX = 1, chunksY = 1, chunksZ = 1)
        val chunk = isolatedWorld.getChunk(0, 0, 0)!!
        chunk.fill(BlockType.AIR)
        isolatedWorld.setBlock(8, 8, 8, BlockType.TORCH)

        val pipeline = TexturePipeline(context)
        pipeline.createAtlasBitmap().recycle()
        val torchMesh = ChunkMesh(chunk)
        torchMesh.generateMeshData(isolatedWorld, pipeline)

        assertEquals(0, torchMesh.opaqueIndexCount)
        assertEquals(
            "Single cross-model torch must emit 2 diagonals * 2 sides = 4 quads (24 indices)",
            24,
            torchMesh.transparentIndexCount
        )
        assertEquals(4, torchMesh.transparentQuadCount)

        // 4. Survival mining a 0.0-hardness torch breaks immediately and spawns torch drop
        val torchPos = BlockPos(12, 8, 12)
        scene.world.setBlock(torchPos, BlockType.TORCH)
        val hit = RaycastHit(torchPos, BlockType.TORCH, Direction.UP, 2.0f)
        assertTrue(scene.startMining(torchPos))
        assertTrue("Zero-hardness torch must break cleanly on updateMining", scene.updateMining(0.016f, hit))
        assertEquals(BlockType.AIR, scene.world.getBlock(torchPos))

        scene.destroySession()
    }

    // =========================================================================
    // ENGINE OPTIMIZATION VERIFICATION TESTS
    // =========================================================================

    @Test
    fun `greedy meshing collapses flat coplanar faces, 12B vertex stride, and CPU array trimming`() {
        assertEquals(12, ChunkMesh.VERTEX_STRIDE_BYTES)

        val world = createDarkStoneWorld(chunksX = 1, chunksY = 1, chunksZ = 1)
        val chunk = world.getChunk(0, 0, 0)!!
        chunk.fill(BlockType.AIR)
        chunk.markCompletelyLit()

        // Create a 4x4 flat stone floor in uniform +16 skylight
        for (x in 4..7) {
            for (z in 4..7) {
                world.setBlock(x, 4, z, BlockType.STONE)
            }
        }

        val pipeline = TexturePipeline(context)
        pipeline.createAtlasBitmap().recycle()
        val mesh = ChunkMesh(chunk)
        mesh.generateMeshData(world, pipeline)

        // Unmerged 4x4 slab has: 16 top + 16 bottom + 4*4 side faces = 48 logical exposed faces!
        assertEquals(48, mesh.opaqueFacesCount)
        // Greedy meshing merges the 4x4 top into 1 quad, 4x4 bottom into 1 quad, and 4 sides into 1 quad each = 6 quads!
        assertEquals("Uniformly lit 4x4x1 stone slab must greedy-merge to 6 quads (1 per face)", 6, mesh.opaqueQuadCount)
        assertEquals(36, mesh.opaqueIndexCount)

        // Uploading (even headlessly without active GL context) trims CPU vertex/index buffers cleanly
        mesh.uploadCpuMeshToGpu()
        assertTrue("CPU working arrays must be trimmed after uploadCpuMeshToGpu()", mesh.hasTrimmedCpuArrays)
        assertEquals(36, mesh.opaqueIndexCount)
    }

    @Test
    fun `world renderer skips empty chunks and sorts visible chunks front-to-back cleanly`() {
        val world = World(chunksX = 4, chunksY = 2, chunksZ = 1)
        // Make chunk (0,1,0)..(3,1,0) completely empty sky, keep y=0 terrain solid
        for (cx in 0..3) {
            world.getChunk(cx, 1, 0)?.let {
                it.fill(BlockType.AIR)
                it.recalculateEmpty()
            }
        }
        val renderer = WorldRenderer(world)
        val camera = Camera3D(fovDegrees = 65f, nearPlane = 0.1f, farPlane = 200f)
        camera.updateProjection(16f / 9f)
        // Stand at x=4 looking toward +X (chunks cx=0,1,2,3 all ahead)
        camera.setFirstPersonView(posX = 4f, posY = 12f, posZ = 8f, playerPitch = 0f, playerYaw = 90f)

        val visible = renderer.computeVisibleChunks(
            viewProjectionMatrix = camera.viewProjectionMatrix,
            cameraX = camera.eyeX,
            cameraY = camera.eyeY,
            cameraZ = camera.eyeZ,
            sortFrontToBack = true
        )

        assertTrue("Empty sky chunks must be skipped early", renderer.emptySkippedChunksCount >= 4)
        assertTrue(visible.none { it.chunk.isEmpty })
        assertTrue("Must have multiple visible terrain chunks to verify ordering", visible.size >= 2)
        for (i in 1 until visible.size) {
            assertTrue(
                "Visible chunks must be sorted front-to-back (${visible[i - 1].sortDistSq} <= ${visible[i].sortDistSq})",
                visible[i - 1].sortDistSq <= visible[i].sortDistSq + 1e-4f
            )
        }
    }

    @Test
    fun `entity renderer frustum-culls and groups by texture, and pickups skip sleeping items`() {
        // 1. Entity frustum culling & texture grouping
        val renderer = EntityRenderer()
        val camera = Camera3D(fovDegrees = 60f, nearPlane = 0.1f, farPlane = 120f)
        camera.updateProjection(16f / 9f)
        camera.setFirstPersonView(posX = 0f, posY = 5f, posZ = 10f, playerPitch = 0f, playerYaw = 0f) // looking -Z

        val stoneA = ItemDropEntity(Vec3(0f, 5f, 0f), itemId = "tessellate:stone")
        val glass = ItemDropEntity(Vec3(1f, 5f, 0f), itemId = "tessellate:glass")
        val stoneB = ItemDropEntity(Vec3(-1f, 5f, 0f), itemId = "tessellate:stone")
        val behindCamera = ItemDropEntity(Vec3(0f, 5f, 35f), itemId = "tessellate:dirt")

        val sorted = renderer.prepareVisibleEntitiesGrouped(
            entities = listOf(stoneA, glass, stoneB, behindCamera),
            viewProjMatrix = camera.viewProjectionMatrix
        ) { entity ->
            when ((entity as ItemDropEntity).itemId) {
                "tessellate:stone" -> 10
                "tessellate:glass" -> 20
                else -> 30
            }
        }

        assertEquals(1, renderer.lastCulledEntityCount)
        assertEquals(3, sorted.size)
        assertFalse(sorted.contains(behindCamera))
        // Both stones (key 10) must appear consecutively before glass (key 20)
        assertEquals("tessellate:stone", (sorted[0] as ItemDropEntity).itemId)
        assertEquals("tessellate:stone", (sorted[1] as ItemDropEntity).itemId)
        assertEquals("tessellate:glass", (sorted[2] as ItemDropEntity).itemId)

        // Also check TDD-019 light scaling curve (-16 -> 0.0, 0 -> 0.5, +16 -> 1.0)
        assertEquals(0.0f, EntityRenderer.lightToMultiplier((-16).toByte()), 1e-5f)
        assertEquals(0.5f, EntityRenderer.lightToMultiplier(0.toByte()), 1e-5f)
        assertEquals(1.0f, EntityRenderer.lightToMultiplier(16.toByte()), 1e-5f)

        // 2. checkItemPickups skips sleeping items even if player position overlaps
        val scene = WorldScene()
        scene.startSession(WorldSession(name = "SleepPickup", gamemode = GameMode.SURVIVAL, worldType = WorldType.FLAT, seed = 1L))
        val sleepingDrop = ItemDropEntity(Vec3(scene.player.posX, scene.player.posY + 0.5f, scene.player.posZ), "tessellate:stone")
        sleepingDrop.isSleeping = true
        scene.world.spawnEntity(sleepingDrop)
        scene.checkItemPickups()
        assertFalse("Sleeping item drop must not be picked up until woken", sleepingDrop.isRemoved)
        scene.destroySession()
    }

    @Test
    fun `ui batch renderer collapses multi-glyph text into single batch flush`() {
        val font = BitmapFontRenderer(context)
        val batcher = UiBatchRenderer()
        val ortho = Matrix4.create()
        Matrix4.ortho(ortho, 0f, 1280f, 720f, 0f, -1f, 1f)

        batcher.begin(ortho)
        val pushed = font.pushTextQuadsToBatcher(
            batcher = batcher,
            text = "TESSELLATE",
            x = 100f,
            y = 100f,
            scale = 3,
            shadow = true,
            pageHandleResolver = { 42 } // All ASCII glyphs share page 0 texture handle
        )
        // Multiple solid outline/quad elements batched together afterwards
        batcher.pushSolidQuad(10f, 10f, 100f, 4f, 1f, 1f, 1f, 1f)
        batcher.pushSolidQuad(10f, 50f, 100f, 4f, 1f, 1f, 1f, 1f)
        batcher.endFrame()

        assertEquals("10 characters * (shadow + main) = 20 glyph quads pushed", 20, pushed)
        assertEquals(22, batcher.lastFrameBatchedQuadCount)
        assertEquals("20 glyph quads + 2 solid quads must flush in strictly 2 draw batches", 2, batcher.lastFrameFlushCount)
    }

    private fun createDarkStoneWorld(chunksX: Int, chunksY: Int, chunksZ: Int): World {
        val world = World(chunksX = chunksX, chunksY = chunksY, chunksZ = chunksZ)
        for (cx in 0 until chunksX) {
            for (cy in 0 until chunksY) {
                for (cz in 0 until chunksZ) {
                    world.getChunk(cx, cy, cz)?.let {
                        it.fill(BlockType.STONE)
                        it.markCompletelyDark()
                    }
                }
            }
        }
        world.lightQueue.clear()
        return world
    }
}
