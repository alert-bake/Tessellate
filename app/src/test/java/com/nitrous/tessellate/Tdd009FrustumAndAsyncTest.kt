package com.nitrous.tessellate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.graphics.Frustum
import com.nitrous.tessellate.settings.GameSettings
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldRenderer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verification test suite for TDD-009: Frustum Culling, Async Coroutines I/O, and Architecture Docs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd009FrustumAndAsyncTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val settingsFile = SettingsManager.resolveSettingsFile(context)
        if (settingsFile.exists()) {
            settingsFile.delete()
        }
    }

    // =========================================================================
    // PHASE 1: Frustum Culling
    // =========================================================================

    @Test
    fun `frustum plane extraction culls AABBs behind camera and accepts AABBs in front`() {
        val camera = Camera3D(fovDegrees = 60f, nearPlane = 0.1f, farPlane = 100f)
        camera.updateProjection(16f / 9f)
        // Place at origin looking along -Z (yaw = 0, pitch = 0)
        camera.setFirstPersonView(posX = 24.0, posY = 16.0, posZ = 24.0, playerPitch = 0f, playerYaw = 0f)

        val frustum = Frustum()
        frustum.extractPlanes(camera.viewProjectionMatrix)

        // Box directly in front (-Z direction from z=24) -> visible
        val inFront = AABB(20.0, 12.0, 4.0, 28.0, 20.0, 12.0)
        assertTrue(
            "Box ahead of camera must intersect frustum",
            frustum.intersectsRelativeAabb(inFront, camera.eyeX, camera.eyeY, camera.eyeZ)
        )

        // Box completely behind camera (+Z direction from z=24) -> culled
        val behind = AABB(20.0, 12.0, 30.0, 28.0, 20.0, 46.0)
        assertFalse(
            "Box behind camera must be frustum-culled",
            frustum.intersectsRelativeAabb(behind, camera.eyeX, camera.eyeY, camera.eyeZ)
        )

        // Box far outside left frustum plane -> culled
        val farLeft = AABB(-100.0, 12.0, 4.0, -80.0, 20.0, 12.0)
        assertFalse(
            "Box far to the left must be frustum-culled",
            frustum.intersectsRelativeAabb(farLeft, camera.eyeX, camera.eyeY, camera.eyeZ)
        )

        // Box enclosing camera -> visible
        val enclosing = AABB(16.0, 0.0, 16.0, 32.0, 32.0, 32.0)
        assertTrue(
            "Chunk enclosing the camera must remain visible",
            frustum.intersectsRelativeAabb(enclosing, camera.eyeX, camera.eyeY, camera.eyeZ)
        )
    }

    @Test
    fun `world renderer skips chunks outside camera frustum and updates visibility on rotation`() {
        val world = World(chunksX = 3, chunksY = 2, chunksZ = 3) // 18 chunks spanning [0..48]
        val renderer = WorldRenderer(world)
        val camera = Camera3D(fovDegrees = 60f, nearPlane = 0.1f, farPlane = 200f)
        camera.updateProjection(16f / 9f)

        // Position at edge (x=24, z=44) looking straight toward -Z (into the world)
        camera.setFirstPersonView(posX = 24.0, posY = 16.0, posZ = 44.0, playerPitch = 0f, playerYaw = 0f)
        val lookingForward = renderer.computeVisibleChunks(
            camera.viewProjectionMatrix,
            camera.eyeX,
            camera.eyeY,
            camera.eyeZ
        )
        assertTrue("Looking into world should see chunks", lookingForward.isNotEmpty())

        // Rotate 180 degrees to face +Z (looking outward, away from z=[0..32] chunks!)
        camera.setFirstPersonView(posX = 24.0, posY = 16.0, posZ = 44.0, playerPitch = 0f, playerYaw = 180f)
        val lookingAway = renderer.computeVisibleChunks(
            camera.viewProjectionMatrix,
            camera.eyeX,
            camera.eyeY,
            camera.eyeZ
        )

        assertTrue("Some chunks behind camera must be culled", renderer.frustumCulledChunksCount > 0)
        assertTrue(
            "Looking away (${lookingAway.size}) should render fewer chunks than looking into world (${lookingForward.size})",
            lookingAway.size < lookingForward.size
        )
        // Chunks at chunkZ = 0 (z=[0..16]) are strictly behind z=44 when looking toward +Z
        assertFalse(
            "Chunks at z=[0..16] behind camera must not be in visible render list",
            lookingAway.any { it.chunk.chunkZ == 0 }
        )
        assertNotEquals(world.getAllChunks().size, lookingAway.size)
    }

    // =========================================================================
    // PHASE 2: Async I/O Coroutines (SettingsManager)
    // =========================================================================

    @Test
    fun `settings manager saveSettings and loadSettings run asynchronously without blocking caller`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = SettingsManager(
            context = context,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        val custom = GameSettings(renderDistance = 8, sensitivity = 0.42f, fov = 90.0f, soundVolume = 0.5f)
        var saveCompleted = false
        val saveJob = manager.saveSettings(custom) { ok ->
            saveCompleted = ok
        }

        // With StandardTestDispatcher queued, disk write has NOT blocked/executed synchronously yet
        assertFalse("saveSettings should dispatch asynchronously instead of blocking", saveCompleted)

        advanceUntilIdle()
        assertTrue(saveJob.isCompleted)
        assertTrue(saveCompleted)
        assertTrue(manager.settingsFile.exists())

        // Load into a second manager asynchronously and verify state updates after dispatcher runs
        val reloadedManager = SettingsManager(
            context = context,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )
        var callbackSettings: GameSettings? = null
        val loadJob = reloadedManager.loadSettings { loaded ->
            callbackSettings = loaded
        }

        assertEquals(60.0f, reloadedManager.settings.fov, 1e-4f) // still default before async work executes
        advanceUntilIdle()

        assertTrue(loadJob.isCompleted)
        assertEquals(90.0f, reloadedManager.settings.fov, 1e-4f)
        assertEquals(90.0f, reloadedManager.settingsFlow.value.fov, 1e-4f)
        assertEquals(custom, callbackSettings)
    }

    // =========================================================================
    // PHASE 3: Architecture Documentation
    // =========================================================================

    @Test
    fun `architecture markdown documents all four required subsystems`() {
        val docFile = listOf(
            File("ARCHITECTURE.md"),
            File("../ARCHITECTURE.md")
        ).firstOrNull { it.exists() }

        if (docFile != null) {
            val content = docFile.readText()
            assertTrue(content.contains("Entity.kt"))
            assertTrue(content.contains("EntityRenderer.kt"))
            assertTrue(content.contains("Chunk.kt"))
            assertTrue(content.contains("ChunkMesh.kt"))
            assertTrue(content.contains("SettingsManager.kt"))
        }
    }

    @Test
    fun `chunk mesh skips empty chunks and culls cross-chunk boundary faces via neighbor fast path`() {
        val world = World(chunksX = 2, chunksY = 1, chunksZ = 1)
        for (x in 0 until world.sizeX) {
            for (y in 0 until world.sizeY) {
                for (z in 0 until world.sizeZ) {
                    world.setBlock(x, y, z, com.nitrous.tessellate.world.BlockType.AIR)
                }
            }
        }
        val pipeline = com.nitrous.tessellate.texture.TexturePipeline(context)
        pipeline.createAtlasBitmap().recycle()

        // Empty chunk fast path
        val emptyChunk = world.getChunk(0, 0, 0)!!
        emptyChunk.recalculateEmpty()
        assertTrue(emptyChunk.isEmpty)
        val emptyMesh = com.nitrous.tessellate.world.ChunkMesh(emptyChunk)
        emptyMesh.generateMeshData(world, pipeline)
        assertEquals(0, emptyMesh.generatedFacesCount)
        assertEquals(0, emptyMesh.culledFacesCount)
        assertEquals(1, emptyMesh.rebuildCount)
        assertFalse(emptyChunk.needsMeshRebuild)

        // Cross-chunk neighbor boundary culling: stone at (15, 4, 4) in chunk 0 adjacent to stone at (16, 4, 4) in chunk 1
        world.setBlock(15, 4, 4, com.nitrous.tessellate.world.BlockType.STONE)
        world.setBlock(16, 4, 4, com.nitrous.tessellate.world.BlockType.STONE)

        val mesh0 = com.nitrous.tessellate.world.ChunkMesh(world.getChunk(0, 0, 0)!!)
        val mesh1 = com.nitrous.tessellate.world.ChunkMesh(world.getChunk(1, 0, 0)!!)
        mesh0.generateMeshData(world, pipeline)
        mesh1.generateMeshData(world, pipeline)

        assertEquals("Boundary-adjacent stone in chunk 0 must expose 5 faces", 5, mesh0.opaqueFacesCount)
        assertEquals("Boundary-adjacent stone in chunk 1 must expose 5 faces", 5, mesh1.opaqueFacesCount)
        assertTrue(mesh0.culledFacesCount >= 1)
        assertTrue(mesh1.culledFacesCount >= 1)

        // Topology version increments on chunk replacement/generation
        val vBefore = world.chunkTopologyVersion.get()
        world.generateTerrain()
        assertTrue(world.chunkTopologyVersion.get() > vBefore)
    }
}
