package com.nitrous.tessellate

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.SceneMode
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.input.TouchInputController
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.settings.GameSettings
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.ChunkMesh
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldRenderer
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Verification test suite for TDD-008:
 * - Phase 1: Jump Button Hitbox (interception before camera drag)
 * - Phase 2: Settings Backend (JSON persistence via Context.filesDir)
 * - Phase 3: Dirty Chunk Optimization (needsMeshRebuild gating)
 * - Phase 4: Transparent Block Support (2-pass meshing & glass culling rules)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd008PolishAndRenderingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val settingsFile = SettingsManager.resolveSettingsFile(context)
        if (settingsFile.exists()) {
            settingsFile.delete()
        }
        ShadowLog.clear()
    }

    // =========================================================================
    // PHASE 1 & ACCEPTANCE TEST 1: Jump Button Hitbox
    // =========================================================================

    @Test
    fun `tapping jump button triggers jump without rotating camera while dragging outside rotates camera`() {
        val engine = TessellateEngine(context)
        engine.setAppState(AppState.PLAYING)
        engine.switchSceneMode(SceneMode.WORLD)
        engine.touchController.onSurfaceChanged(1920, 1080)
        engine.uiRenderer.onSurfaceChanged(1920, 1080)

        val scene = engine.worldScene
        scene.player.spawnAtSurface(scene.world, 24.5, 24.5)
        val initialYaw = scene.player.yaw
        val initialPitch = scene.player.pitch
        val startY = scene.player.posY

        val jumpRect = engine.uiRenderer.jumpButtonRect
        assertFalse(jumpRect.isEmpty)

        // 1. Tap and drag inside jump button -> must jump, must NOT rotate camera
        val t0 = SystemClock.uptimeMillis()
        val downJump = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, jumpRect.centerX(), jumpRect.centerY(), 0)
        assertTrue(engine.onTouchEvent(downJump))
        downJump.recycle()

        assertTrue("Jump must set positive upward velocity", scene.player.velY > 0.0)
        assertFalse("Player must leave grounded state when jumping", scene.player.isGrounded)

        val moveJump = MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, jumpRect.centerX() + 40f, jumpRect.centerY() - 40f, 0)
        assertTrue(engine.onTouchEvent(moveJump))
        moveJump.recycle()

        val upJump = MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_UP, jumpRect.centerX() + 40f, jumpRect.centerY() - 40f, 0)
        assertTrue(engine.onTouchEvent(upJump))
        upJump.recycle()

        assertEquals("Tapping/dragging jump button must not alter yaw", initialYaw, scene.player.yaw, 1e-4f)
        assertEquals("Tapping/dragging jump button must not alter pitch", initialPitch, scene.player.pitch, 1e-4f)
        assertNull("Jump touch must not claim lookPointerId", engine.touchController.lookPointerId)

        // Advance physics one frame and verify player moves upward
        scene.player.update(0.03f, scene.world, 0f, 0f, false)
        assertTrue("Player Y must rise after jumping", scene.player.posY > startY)

        // Mid-air jump should be ignored until landing
        assertFalse(scene.player.jump())

        // 2. Tap and drag outside jump button (center-right gameplay area) -> must drag camera
        val lookStartX = 1100f
        val lookStartY = 400f
        val downLook = MotionEvent.obtain(t0 + 100, t0 + 100, MotionEvent.ACTION_DOWN, lookStartX, lookStartY, 0)
        assertTrue(engine.onTouchEvent(downLook))
        downLook.recycle()

        val moveLook = MotionEvent.obtain(t0 + 100, t0 + 120, MotionEvent.ACTION_MOVE, lookStartX + 120f, lookStartY - 60f, 0)
        assertTrue(engine.onTouchEvent(moveLook))
        moveLook.recycle()

        val upLook = MotionEvent.obtain(t0 + 100, t0 + 140, MotionEvent.ACTION_UP, lookStartX + 120f, lookStartY - 60f, 0)
        engine.onTouchEvent(upLook)
        upLook.recycle()

        assertTrue("Dragging outside jump button must rotate camera yaw", scene.player.yaw != initialYaw)
        assertTrue("Dragging outside jump button must rotate camera pitch", scene.player.pitch != initialPitch)
    }

    @Test
    fun `touch input controller directly intercepts jump button before camera drag`() {
        val world = World(chunksX = 2, chunksY = 2, chunksZ = 2)
        val player = Player()
        player.spawnAtSurface(world, 12.5, 12.5)
        val initialYaw = player.yaw

        val tc = TouchInputController()
        tc.onSurfaceChanged(1920, 1080)

        val now = SystemClock.uptimeMillis()
        val jumpDown = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, tc.jumpButtonRect.centerX(), tc.jumpButtonRect.centerY(), 0)
        assertTrue(tc.handleTouchEvent(jumpDown, player, isWorldMode = true))
        jumpDown.recycle()

        assertTrue(player.velY > 0.0)
        assertNull(tc.lookPointerId)

        val jumpDrag = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, tc.jumpButtonRect.centerX() + 50f, tc.jumpButtonRect.centerY(), 0)
        assertTrue(tc.handleTouchEvent(jumpDrag, player, isWorldMode = true))
        jumpDrag.recycle()

        assertEquals(initialYaw, player.yaw, 1e-4f)
    }

    // =========================================================================
    // PHASE 2 & ACCEPTANCE TEST 2: Settings Backend (JSON)
    // =========================================================================

    @Test
    fun `settings manager saves and loads JSON from filesDir and logs confirmation`() {
        val manager = SettingsManager(context)
        assertEquals(File(WorldSaveManager.getBaseFilesDir(context), "settings.json").absolutePath, manager.settingsFile.absolutePath)

        // Missing file -> defaults
        awaitJobWithMainLooper(manager.loadSettings())
        val initial = manager.settings
        assertEquals(4, initial.renderDistance)
        assertEquals(0.22f, initial.sensitivity, 1e-4f)
        assertEquals(60.0f, initial.fov, 1e-4f)
        assertEquals(1.0f, initial.soundVolume, 1e-4f)

        // Change FOV and save asynchronously
        val updated = GameSettings(renderDistance = 6, sensitivity = 0.35f, fov = 85.0f, soundVolume = 0.7f)
        var saveResult: Boolean? = null
        val saveJob = manager.saveSettings(updated) { ok -> saveResult = ok }
        awaitJobWithMainLooper(saveJob)
        assertEquals(true, saveResult)
        assertTrue(manager.settingsFile.exists())

        val logs = ShadowLog.getLogsForTag(SettingsManager.TAG)
        assertTrue(
            "Expected save confirmation log",
            logs.any { it.msg.contains("Settings saved successfully.") }
        )

        // Simulate app restart with a fresh TessellateEngine instance
        val restartedEngine = TessellateEngine(context)
        awaitJobWithMainLooper(restartedEngine.initialSettingsLoadJob)
        assertEquals(85.0f, restartedEngine.camera.fovDegrees, 1e-4f)
        assertEquals(0.35f, restartedEngine.touchController.lookSensitivityX, 1e-4f)
        assertEquals(6, restartedEngine.currentSettings.renderDistance)
        assertEquals(0.7f, restartedEngine.currentSettings.soundVolume, 1e-4f)
    }

    @Test
    fun `settings manager falls back to defaults when settings json is corrupted`() {
        val manager = SettingsManager(context)
        manager.settingsFile.writeText("{not valid json!!!")

        awaitJobWithMainLooper(manager.loadSettings())
        assertEquals(GameSettings(), manager.settings)
    }

    private fun awaitJobWithMainLooper(job: Job, timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("Expected coroutine Job to complete within ${timeoutMs}ms", job.isCompleted)
    }

    // =========================================================================
    // PHASE 3 & ACCEPTANCE TEST 3: Dirty Chunk Optimization
    // =========================================================================

    @Test
    fun `dirty chunk optimization only rebuilds chunks marked needsMeshRebuild`() {
        val world = World(chunksX = 3, chunksY = 2, chunksZ = 3)
        val renderer = WorldRenderer(world)
        val pipeline = TexturePipeline(context)

        // Initial generation: all chunks need rebuild
        assertTrue(world.getAllChunks().all { it.needsMeshRebuild })

        renderer.updateMeshes(pipeline, uploadToGpu = false)

        val meshes = renderer.getChunkMeshes()
        assertTrue(meshes.all { !it.chunk.needsMeshRebuild })
        assertTrue(meshes.all { it.rebuildCount == 1 })

        // Subsequent unchanged frames must NOT rebuild any chunk
        repeat(5) {
            renderer.updateMeshes(pipeline, uploadToGpu = false)
        }
        assertTrue(meshes.all { it.rebuildCount == 1 })

        // Break an interior block in chunk (1, 0, 1) at local (5, 5, 5) -> world (21, 5, 21)
        val targetChunk = world.getChunk(1, 0, 1)!!
        world.setBlock(21, 5, 21, BlockType.AIR)
        assertTrue(targetChunk.needsMeshRebuild)

        renderer.updateMeshes(pipeline, uploadToGpu = false)

        val rebuiltMeshes = meshes.filter { it.rebuildCount == 2 }
        assertEquals("Only the modified chunk should rebuild for an interior block", 1, rebuiltMeshes.size)
        assertEquals(targetChunk, rebuiltMeshes.first().chunk)

        // Modify a block on the +X boundary of chunk (0, 0, 1) at local (15, 6, 8) -> world (15, 6, 24)
        val boundaryChunk = world.getChunk(0, 0, 1)!!
        val neighborChunk = world.getChunk(1, 0, 1)!!
        world.setBlock(15, 6, 24, BlockType.AIR)
        assertTrue(boundaryChunk.needsMeshRebuild)
        assertTrue(neighborChunk.needsMeshRebuild)

        renderer.updateMeshes(pipeline, uploadToGpu = false)
        assertFalse(boundaryChunk.needsMeshRebuild)
        assertFalse(neighborChunk.needsMeshRebuild)
    }

    // =========================================================================
    // PHASE 4 & ACCEPTANCE TEST 4: Transparent Rendering & Glass Culling
    // =========================================================================

    @Test
    fun `glass block separates into transparent pass and respects opaque and identical culling rules`() {
        assertFalse(BlockType.GLASS.isOpaque)
        assertTrue(BlockType.GLASS.isTransparent)
        assertTrue(BlockType.GLASS.isSolid)

        val world = createEmptyWorld()
        val pipeline = TexturePipeline(context)
        val chunk = world.getChunk(0, 0, 0)!!
        val mesh = ChunkMesh(chunk)

        // 1. Single Stone block at (4, 4, 4) -> 6 opaque faces, 0 transparent
        world.setBlock(4, 4, 4, BlockType.STONE)
        mesh.generateMeshData(world, pipeline)
        assertEquals(6, mesh.opaqueFacesCount)
        assertEquals(0, mesh.transparentFacesCount)

        // 2. Place Glass at (5, 4, 4) adjacent to Stone -> Glass MUST NOT cull Stone's +X face!
        // Stone still has all 6 opaque faces. Glass has 5 exposed faces (its -X face touches opaque Stone).
        world.setBlock(5, 4, 4, BlockType.GLASS)
        mesh.generateMeshData(world, pipeline)
        assertEquals("Glass must NOT cull adjacent opaque block face", 6, mesh.opaqueFacesCount)
        assertEquals("Single glass adjacent to stone exposes 5 transparent faces", 5, mesh.transparentFacesCount)
        assertEquals(6 * 6, mesh.opaqueIndexCount)
        assertEquals(5 * 6, mesh.transparentIndexCount)

        // 3. Test two Glass blocks side-by-side in isolation -> inner faces MUST be culled (10 faces total, not 12)
        world.setBlock(4, 4, 4, BlockType.AIR)
        world.setBlock(5, 4, 4, BlockType.AIR)
        world.setBlock(8, 8, 8, BlockType.GLASS)
        world.setBlock(9, 8, 8, BlockType.GLASS)
        mesh.generateMeshData(world, pipeline)

        assertEquals(0, mesh.opaqueFacesCount)
        assertEquals("Two adjacent glass blocks must cull 2 inner faces -> 10 faces", 10, mesh.transparentFacesCount)
        assertTrue("Inner touching glass faces must be counted as culled", mesh.culledFacesCount >= 2)
    }

    @Test
    fun `player collides solidly with transparent glass blocks`() {
        val world = createEmptyWorld()
        for (x in 5..12) {
            for (z in 5..12) {
                world.setBlock(x, 3, z, BlockType.STONE)
            }
        }
        // Wall of glass at z = 7
        for (x in 5..12) {
            world.setBlock(x, 4, 7, BlockType.GLASS)
            world.setBlock(x, 5, 7, BlockType.GLASS)
        }

        val player = Player(posX = 8.5, posY = 4.0, posZ = 9.0)
        player.yaw = 0f // Facing -Z toward the glass wall

        repeat(20) {
            player.update(0.05f, world, moveForward = 1.0f, moveStrafe = 0f, crouchInput = false)
        }

        assertTrue("Player must not walk through solid glass blocks", player.posZ >= 8.29)
    }

    @Test
    fun `grass side texture loads cleanly in ARGB_8888 format for alpha rendering`() {
        val pipeline = TexturePipeline(context)
        val loaded = pipeline.loadTexture("proto_grass_side", "textures/proto_grass_side.png")
        assertTrue("proto_grass_side.png must load cleanly", loaded)
        val diag = pipeline.getDiagnostic("proto_grass_side")
        assertTrue(diag.isSuccess)
        assertTrue(diag.message.contains("ARGB_8888"))
    }

    private fun createEmptyWorld(): World {
        val world = World(chunksX = 2, chunksY = 2, chunksZ = 2)
        for (x in 0 until world.sizeX) {
            for (z in 0 until world.sizeZ) {
                for (y in 0 until world.sizeY) {
                    world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }
        return world
    }
}
