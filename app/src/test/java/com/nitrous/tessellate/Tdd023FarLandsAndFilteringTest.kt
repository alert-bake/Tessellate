package com.nitrous.tessellate

import android.content.Context
import android.opengl.GLES30
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.command.CommandConsole
import com.nitrous.tessellate.command.CommandRegistry
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.EntityRenderer
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.graphics.Frustum
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.settings.GameSettings
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.settings.VanillaSettingsRegistrar
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TextureFilterMode
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.ChunkMesh
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldRenderer
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import com.nitrous.tessellate.worldgen.PerlinNoise
import com.nitrous.tessellate.worldgen.SurfacePass
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.math.abs

/**
 * Verification test suite for TDD-023: Far Lands Fix (Precision, Camera-Relative Rendering,
 * Mipmapping/Anisotropic Filtering Settings, & /fpptest Command).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd023FarLandsAndFilteringTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        SettingsManager.resolveSettingsFile(context).delete()
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        SettingsManager.resolveSettingsFile(context).delete()
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
    }

    // =========================================================================
    // 1. CPU 64-BIT DOUBLE PRECISION & NOISE / PHYSICS / RAYCAST AT EXTREME COORDS
    // =========================================================================

    @Test
    fun `acceptance 1 player walks smoothly with double precision at 16 million blocks`() {
        val world = World(
            session = WorldSession("Far16M", GameMode.CREATIVE, WorldType.FLAT, 42L),
            renderDistance = 1,
            heightChunks = 8
        )
        try {
            val baseX = 16_000_000
            val baseZ = 16_000_000
            val cx = baseX shr 4
            val cz = baseZ shr 4
            for (dx in -1..1) {
                for (dz in -1..1) {
                    world.ensureChunkColumnLoadedSync(cx + dx, cz + dz)
                }
            }

            // Flat world grass floor is at Y=11 (WorldGenRegistry.FLAT_GRASS_Y) -> player stands cleanly at Y=12.0
            val player = Player(posX = baseX + 0.5, posY = 12.0, posZ = baseZ + 0.5)
            player.yaw = 90f // +X
            player.pitch = 0f

            val startX = player.posX
            repeat(10) {
                player.update(0.016f, world, moveForward = 1f, moveStrafe = 0f, crouchInput = false)
            }
            val moved = player.posX - startX
            assertTrue("Player must walk smoothly at 16M blocks (moved=$moved)", moved > 0.4)
            assertTrue("Player must remain grounded on flat terrain at 16M", player.isGrounded)
            assertEquals(12.0, player.posY, 1e-4)

            // Verify PerlinNoise retains fine sub-block Double variation at 16M and 500M
            val n1 = PerlinNoise.noise2D(seed = 1337L, x = 16_000_000.0 * 0.015, z = 16_000_000.0 * 0.015)
            val n2 = PerlinNoise.noise2D(seed = 1337L, x = 16_000_001.0 * 0.015, z = 16_000_000.0 * 0.015)
            assertNotEquals("PerlinNoise at 16M must vary across adjacent blocks", n1, n2, 1e-9)

            val heights500M = (0 until 32).map { dx ->
                SurfacePass.computeSurfaceY(1337L, 500_000_000 + dx, 24)
            }.toSet()
            assertTrue("Terrain heights at 500M blocks must still vary naturally", heights500M.size > 1)

            // Verify raycast hits block placed in air at 16,000,000+
            val targetPos = BlockPos(baseX + 3, 13, baseZ)
            assertTrue(world.setBlock(targetPos, BlockType.STONE))
            val hit = world.raycast(
                originX = baseX + 0.5,
                originY = 13.5,
                originZ = baseZ + 0.5,
                dirX = 1.0,
                dirY = 0.0,
                dirZ = 0.0,
                maxDistance = 6.0
            )
            assertNotNull("Raycast must succeed at 16M blocks", hit)
            assertEquals(targetPos, hit!!.blockPos)
        } finally {
            world.shutdownStreaming()
        }
    }

    @Test
    fun `extreme positive and negative chunk coordinates store blocks independently without key collision`() {
        val world = World(
            session = WorldSession("ExtremeKeys", GameMode.CREATIVE, WorldType.FLAT, 99L),
            renderDistance = 1,
            heightChunks = 8
        )
        try {
            val coords = listOf(
                0 to 0,
                1_000_000 to 1_000_000,
                -1_000_000 to -1_000_000,
                31_250_000 to 0,
                -31_250_000 to 0
            )
            val blocks = listOf(
                BlockType.STONE,
                BlockType.DIRT,
                BlockType.SAND,
                BlockType.OAK_LOG,
                BlockType.COBBLESTONE
            )
            coords.forEachIndexed { idx, (cx, cz) ->
                world.ensureChunkColumnLoadedSync(cx, cz)
                assertTrue(world.setBlock(BlockPos(cx * 16 + 3, 20, cz * 16 + 7), blocks[idx]))
            }
            coords.forEachIndexed { idx, (cx, cz) ->
                assertEquals(
                    "Chunk ($cx, $cz) must preserve its own block without key collision",
                    blocks[idx],
                    world.getBlock(BlockPos(cx * 16 + 3, 20, cz * 16 + 7))
                )
            }
        } finally {
            world.shutdownStreaming()
        }
    }

    // =========================================================================
    // 2. CAMERA-RELATIVE RENDERING AT ~500 MILLION BLOCKS
    // =========================================================================

    @Test
    fun `acceptance 2 camera-relative rendering keeps GPU viewMatrix at origin and chunk or entity offsets local at 500M`() {
        val extremeX = 522_816_000.5 // ~522.8M blocks (far past 32-bit float precision limit)
        val extremeZ = 24.5
        val eyeY = 13.62 // Standing eye height above FLAT_GRASS_Y (11)

        val camera = Camera3D(fovDegrees = 65f, nearPlane = 0.1f, farPlane = 180f)
        camera.updateProjection(16f / 9f)
        camera.setFirstPersonView(extremeX, eyeY, extremeZ, playerPitch = 0f, playerYaw = 90f) // looking +X

        // Double eye coordinates retain exact fractional 0.5 offset where Float would snap by 32+ blocks
        assertEquals(extremeX, camera.eyeX, 1e-9)
        assertEquals(eyeY, camera.eyeY, 1e-9)
        assertEquals(extremeZ, camera.eyeZ, 1e-9)

        // Compare viewMatrix against an identical orientation at (0, 0, 0) -> must match exactly!
        val originCam = Camera3D(fovDegrees = 65f, nearPlane = 0.1f, farPlane = 180f)
        originCam.updateProjection(16f / 9f)
        originCam.setFirstPersonView(0.0, 0.0, 0.0, playerPitch = 0f, playerYaw = 90f)
        for (i in 0 until 16) {
            assertEquals(
                "Camera-relative viewMatrix[$i] at 522M must equal origin viewMatrix[$i]",
                originCam.viewMatrix[i],
                camera.viewMatrix[i],
                1e-5f
            )
        }

        // Camera-relative frustum culling & chunk origin subtraction at 522M blocks
        val world = World(
            session = WorldSession("Far522M", GameMode.CREATIVE, WorldType.FLAT, 7L),
            renderDistance = 1,
            heightChunks = 8
        )
        try {
            val baseCx = (522_816_000 shr 4)
            val baseCz = (24 shr 4)
            world.ensureChunkColumnLoadedSync(baseCx + 1, baseCz)
            world.ensureChunkColumnLoadedSync(baseCx - 1, baseCz)

            val pipeline = TexturePipeline(context)
            val renderer = WorldRenderer(world)
            renderer.updateMeshes(pipeline, uploadToGpu = false)

            val visible = renderer.computeVisibleChunks(
                camera.viewProjectionMatrix,
                camera.eyeX,
                camera.eyeY,
                camera.eyeZ
            )
            assertTrue("Chunk ahead (+X) at 522M must pass camera-relative frustum culling", visible.any { it.chunk.chunkX == baseCx + 1 })
            assertFalse("Chunk behind (-X) at 522M must be frustum culled", visible.any { it.chunk.chunkX == baseCx - 1 })

            val frontMesh = visible.first { it.chunk.chunkX == baseCx + 1 }
            val relOriginX = (frontMesh.originX - camera.eyeX).toFloat()
            val relOriginY = (frontMesh.originY - camera.eyeY).toFloat()
            val relOriginZ = (frontMesh.originZ - camera.eyeZ).toFloat()
            assertTrue("Camera-relative chunk originX must be small local float ($relOriginX)", abs(relOriginX) < 64f)
            assertTrue("Camera-relative chunk originY must be small local float ($relOriginY)", abs(relOriginY) < 128f)
            assertTrue("Camera-relative chunk originZ must be small local float ($relOriginZ)", abs(relOriginZ) < 64f)

            // Verify exact sub-block precision is preserved in the local float offset
            val expectedRelX = ((baseCx + 1) * 16.0 - extremeX).toFloat()
            assertEquals(expectedRelX, relOriginX, 1e-5f)

            // Verify EntityRenderer instancing outputs small camera-relative entity coordinates at 522M
            val drop = ItemDropEntity(Vec3(extremeX + 2.25, 5.0, extremeZ - 1.5), itemId = "tessellate:stone")
            val entityRenderer = EntityRenderer()
            val count = entityRenderer.buildInstancedDropData(
                visibleEntities = listOf(drop),
                texturePipeline = pipeline,
                world = world,
                useAtlasUvs = true,
                cameraX = camera.eyeX,
                cameraY = camera.eyeY,
                cameraZ = camera.eyeZ
            )
            assertEquals(1, count)
            val instance = FloatArray(12)
            entityRenderer.getStagingInstanceValues(0, instance)
            assertEquals(2.25f, instance[0], 1e-4f)
            assertEquals(-1.5f, instance[2], 1e-4f)
        } finally {
            world.shutdownStreaming()
        }
    }

    // =========================================================================
    // 3. MIPMAPPING & ANISOTROPIC FILTERING + SETTINGS MENU
    // =========================================================================

    @Test
    fun `texture pipeline configures mipmapping and anisotropic filtering and settings toggles persist`() {
        assertEquals(GLES30.GL_LINEAR_MIPMAP_LINEAR, TexturePipeline.ATLAS_MIN_FILTER)
        assertEquals("GL_EXT_texture_filter_anisotropic", TexturePipeline.EXT_TEXTURE_FILTER_ANISOTROPIC)
        assertEquals(8.0f, TexturePipeline.TARGET_ANISOTROPY, 1e-4f)

        val pipeline = TexturePipeline(context)
        assertTrue(pipeline.mipmappingEnabled)
        assertTrue(pipeline.anisotropicFilteringEnabled)
        assertEquals(GLES30.GL_LINEAR_MIPMAP_LINEAR, pipeline.computeEffectiveAtlasMinFilter())

        // Disabling mipmapping switches atlas min filter to non-mipmapped filter mode
        pipeline.mipmappingEnabled = false
        pipeline.filterMode = TextureFilterMode.NEAREST
        assertEquals(GLES30.GL_NEAREST, pipeline.computeEffectiveAtlasMinFilter())
        pipeline.filterMode = TextureFilterMode.LINEAR
        assertEquals(GLES30.GL_LINEAR, pipeline.computeEffectiveAtlasMinFilter())

        // Anisotropy calculation clamps to hardware max cleanly when supported
        val supportedField = TexturePipeline::class.java.getDeclaredField("isAnisotropicSupported").apply {
            isAccessible = true
        }
        supportedField.setBoolean(pipeline, true)
        assertEquals(8.0f, pipeline.computeEffectiveAnisotropy(16.0f), 1e-4f)
        assertEquals(4.0f, pipeline.computeEffectiveAnisotropy(4.0f), 1e-4f)
        pipeline.anisotropicFilteringEnabled = false
        assertEquals(1.0f, pipeline.computeEffectiveAnisotropy(16.0f), 1e-4f)

        // Settings menu integration & persistence
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            assertTrue(engine.currentSettings.mipmapping)
            assertTrue(engine.currentSettings.anisotropicFiltering)
            assertTrue(engine.texturePipeline.mipmappingEnabled)
            assertTrue(engine.texturePipeline.anisotropicFilteringEnabled)

            engine.openSettingsFrom(AppState.MENU)
            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_VIDEO)
            val videoIds = screen.getVisibleRows().map { it.definition.id }
            assertTrue("Mipmapping toggle must appear in Video settings", videoIds.contains("tessellate:mipmapping"))
            assertTrue("Anisotropic Filtering toggle must appear in Video settings", videoIds.contains("tessellate:anisotropic_filtering"))

            assertTrue(engine.settingsRegistry.setValue("tessellate:mipmapping", false).isValid)
            assertTrue(engine.settingsRegistry.setValue("tessellate:anisotropic_filtering", false).isValid)
            assertFalse(engine.texturePipeline.mipmappingEnabled)
            assertFalse(engine.texturePipeline.anisotropicFilteringEnabled)
            engine.settingsManager.lastSaveJob?.let { awaitJob(it) }
        } finally {
            engine.worldScene.destroySession()
        }

        val restarted = TessellateEngine(context)
        awaitJob(restarted.initialSettingsLoadJob)
        try {
            assertFalse(restarted.currentSettings.mipmapping)
            assertFalse(restarted.currentSettings.anisotropicFiltering)
            assertFalse(restarted.texturePipeline.mipmappingEnabled)
            assertFalse(restarted.texturePipeline.anisotropicFilteringEnabled)
        } finally {
            restarted.worldScene.destroySession()
        }
    }

    // =========================================================================
    // 4. DEBUG COMMAND /fpptest
    // =========================================================================

    @Test
    fun `fpptest command multiplies 1 or 2 axes by 32676 and rejects invalid arguments`() {
        assertNotNull("fpptest command must be loaded from commands/fpptest.json", CommandRegistry.get("fpptest"))

        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        try {
            val player = engine.worldScene.player
            player.posX = 100.0
            player.posY = 70.0
            player.posZ = -50.0
            player.updateBoundingBox()

            // Single axis X
            val resX = engine.executeCommand("/fpptest X")
            assertTrue("Expected /fpptest X to succeed: ${resX.message}", resX.success)
            assertEquals(100.0 * 32676.0, player.posX, 1e-6)
            assertEquals(70.0, player.posY, 1e-6)
            assertEquals(-50.0, player.posZ, 1e-6)
            assertEquals(player.posX, engine.camera.eyeX, 1e-6)

            // Reset and test two axes (lowercase x z)
            player.posX = 16.0
            player.posY = 70.0
            player.posZ = -10.0
            val resXZ = engine.executeCommand("/fpptest x z")
            assertTrue("Expected /fpptest x z to succeed: ${resXZ.message}", resXZ.success)
            assertEquals(16.0 * 32676.0, player.posX, 1e-6)
            assertEquals(70.0, player.posY, 1e-6)
            assertEquals(-10.0 * 32676.0, player.posZ, 1e-6)

            // Y axis stays multiplied without being clamped to world height in infinite mode
            val resY = engine.executeCommand("/fpptest Y")
            assertTrue(resY.success)
            assertEquals(70.0 * 32676.0, player.posY, 1e-6)

            // Error cases leave player unchanged
            val snapX = player.posX
            val snapY = player.posY
            val snapZ = player.posZ
            assertFalse(engine.executeCommand("/fpptest").success)
            assertFalse(engine.executeCommand("/fpptest W").success)
            assertFalse(engine.executeCommand("/fpptest X X").success)
            assertFalse(engine.executeCommand("/fpptest X Y Z").success)
            assertEquals(snapX, player.posX, 1e-9)
            assertEquals(snapY, player.posY, 1e-9)
            assertEquals(snapZ, player.posZ, 1e-9)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    private fun awaitJob(job: Job, timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("Expected coroutine Job to finish within ${timeoutMs}ms", job.isCompleted)
    }
}
