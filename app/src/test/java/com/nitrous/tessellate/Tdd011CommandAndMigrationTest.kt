package com.nitrous.tessellate

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.SceneMode
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.registry.GameRegistries
import com.nitrous.tessellate.registry.Namespaces
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.ChunkMesh
import com.nitrous.tessellate.world.Direction
import com.nitrous.tessellate.world.RaycastHit
import com.nitrous.tessellate.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/**
 * Verification test suite for TDD-011:
 * - Namespace Migration ("The Proto Purge" -> prototesselate:stone)
 * - Real Block Registry & Textures (stone, cobblestone, sand, glass)
 * - Strict Inventory Placement ("The Ghost Inventory Fix")
 * - Block Breaking Drops & Pickup Roundtrip
 * - Developer Command Console (/give, /clear, /spawn + Activity UI overlay)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd011CommandAndMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // =========================================================================
    // ACCEPTANCE TEST 6 & REGISTRY / TEXTURES
    // =========================================================================

    @Test
    fun `old stone block is migrated under prototesselate stone and terrain uses it`() {
        assertEquals("prototesselate", Namespaces.PROTOTESSELLATE)
        assertTrue(Namespaces.isBuiltIn("prototesselate"))

        val protoStone = BlockType.fromIdentifier("prototesselate:stone")
        assertEquals(BlockType.PROTO_STONE, protoStone)
        assertEquals("prototesselate:stone", BlockType.PROTO_STONE.stringId)
        assertEquals(TextureConstants.PROTO_STONE, BlockType.PROTO_STONE.topTexture)

        assertNotNull(GameRegistries.BLOCKS.get("prototesselate:stone"))
        assertNotNull(GameRegistries.ITEMS.get("prototesselate:stone"))

        // Legacy world-load migration helper maps old stone to prototesselate:stone
        assertEquals(BlockType.PROTO_STONE, BlockType.migrateLegacyWorldBlockId("Tessellate:stone"))
        assertEquals(BlockType.PROTO_STONE, BlockType.migrateLegacyWorldBlockId("STONE"))

        // Placeholder terrain uses flat tessellate:stone plane (TDD-012.7)
        val world = World(chunksX = 3, chunksY = 2, chunksZ = 3)
        assertEquals(BlockType.STONE, world.getBlock(20, 2, 20))
    }

    @Test
    fun `real tessellate blocks and block textures load and register with exact properties`() {
        val pipeline = TexturePipeline(context)
        for (id in TextureConstants.ALL_BLOCK_IDS) {
            val path = TextureConstants.getAssetPath(id)
            assertTrue("Texture asset $path must load cleanly", pipeline.loadTexture(id, path))
            val diag = pipeline.getDiagnostic(id)
            assertTrue(diag.isSuccess)
            assertFalse(diag.isFallback)
        }

        // tessellate:glass -> block_glass.png, transparent, isSimple: false
        assertEquals("Tessellate:glass", BlockType.GLASS.stringId)
        assertEquals(TextureConstants.BLOCK_GLASS, BlockType.GLASS.topTexture)
        assertTrue(BlockType.GLASS.isTransparent)
        assertFalse(BlockType.GLASS.isOpaque)
        assertFalse(BlockType.GLASS.isSimple)

        // tessellate:sand -> block_sand.png, solid, isSimple: true
        assertEquals("Tessellate:sand", BlockType.SAND.stringId)
        assertEquals(TextureConstants.BLOCK_SAND, BlockType.SAND.topTexture)
        assertTrue(BlockType.SAND.isSolid)
        assertTrue(BlockType.SAND.isOpaque)
        assertTrue(BlockType.SAND.isSimple)

        // tessellate:cobblestone -> block_cobblestone.png, solid, isSimple: true
        assertEquals("Tessellate:cobblestone", BlockType.COBBLESTONE.stringId)
        assertEquals(TextureConstants.BLOCK_COBBLESTONE, BlockType.COBBLESTONE.topTexture)
        assertTrue(BlockType.COBBLESTONE.isSolid)
        assertTrue(BlockType.COBBLESTONE.isOpaque)
        assertTrue(BlockType.COBBLESTONE.isSimple)

        // tessellate:stone -> block_stone.png, solid, isSimple: true
        assertEquals("Tessellate:stone", BlockType.STONE.stringId)
        assertEquals(TextureConstants.BLOCK_STONE, BlockType.STONE.topTexture)
        assertTrue(BlockType.STONE.isSolid)
        assertTrue(BlockType.STONE.isOpaque)
        assertTrue(BlockType.STONE.isSimple)

        // Corresponding items exist
        assertEquals(BlockType.GLASS, ItemRegistry.get("tessellate:glass")?.associatedBlock)
        assertEquals(BlockType.SAND, ItemRegistry.get("tessellate:sand")?.associatedBlock)
        assertEquals(BlockType.COBBLESTONE, ItemRegistry.get("tessellate:cobblestone")?.associatedBlock)
        assertEquals(BlockType.STONE, ItemRegistry.get("tessellate:stone")?.associatedBlock)
    }

    // =========================================================================
    // ACCEPTANCE TESTS 1, 2, 3, 5: CONSOLE UI -> GIVE -> PLACE -> BREAK -> PICKUP -> EMPTY SLOT
    // =========================================================================

    @Test
    fun `command console give placement break pickup and empty slot workflow`() {
        // Acceptance Test 1: Open Activity command console, type /give tessellate:cobblestone 5 -> Slot 0 updates
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val engine = activity.surfaceView!!.engine
        val scene = engine.worldScene
        scene.world.clearEntities()

        // Ensure flat stone platform and open space in front of player at (20.5, 6.0, 20.5)
        for (x in 16..24) {
            for (z in 16..24) {
                scene.world.setBlock(x, 5, z, BlockType.STONE)
                for (y in 6..15) {
                    scene.world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }
        scene.player.posX = 20.5f
        scene.player.posY = 6.0f
        scene.player.posZ = 20.5f
        scene.player.yaw = 0f // Facing -Z
        scene.player.pitch = -35f
        scene.player.updateBoundingBox()

        assertEquals(View.GONE, activity.consoleOverlayContainer.visibility)
        activity.consoleToggleButton.performClick()
        assertEquals(View.VISIBLE, activity.consoleOverlayContainer.visibility)

        activity.consoleInputEditText.setText("/give tessellate:cobblestone 5")
        activity.consoleRunButton.performClick()

        assertEquals(ItemStack("Tessellate:cobblestone", 5), scene.player.inventory.getSlot(0))
        assertEquals(ItemRegistry.COBBLESTONE, scene.selectedItem)

        // Acceptance Test 2: Tap world -> Cobblestone is placed adjacent to hit face and count drops to 4
        val floorPos = BlockPos(20, 5, 18)
        val expectedPlacedPos = floorPos.offset(Direction.UP) // (20, 6, 18)
        val hit = RaycastHit(floorPos, BlockType.STONE, Direction.UP, 2.5f)
        // Drive targeting via camera look onto (20, 5, 18)
        engine.camera.setFirstPersonView(
            scene.player.posX,
            scene.player.eyeY,
            scene.player.posZ,
            playerPitch = -42f,
            playerYaw = 0f
        )
        scene.updateCameraRaycast(engine.camera)
        assertNotNull(scene.currentTargetHit)
        val targetPos = scene.currentTargetHit!!.blockPos.offset(scene.currentTargetHit!!.face)

        // Simulate quick tap on gameplay area of TouchInputController
        engine.setAppState(AppState.PLAYING)
        engine.switchSceneMode(SceneMode.WORLD)
        engine.touchController.onSurfaceChanged(1920, 1080)
        engine.uiRenderer.onSurfaceChanged(1920, 1080)

        val t0 = SystemClock.uptimeMillis()
        val tapDown = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 960f, 540f, 0)
        assertTrue(engine.onTouchEvent(tapDown))
        tapDown.recycle()
        val tapUp = MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_UP, 960f, 540f, 0)
        assertTrue(engine.onTouchEvent(tapUp))
        tapUp.recycle()

        assertEquals(BlockType.COBBLESTONE, scene.world.getBlock(targetPos))
        assertEquals(4, scene.player.inventory.getSlot(0)?.count)

        // Acceptance Test 3: Break the cobblestone -> drops as entity -> walk over -> count goes back to 5
        val placedHit = RaycastHit(targetPos, BlockType.COBBLESTONE, Direction.SOUTH, 2.0f)
        assertTrue(scene.startMining(targetPos))
        assertTrue(scene.updateMining(BlockType.COBBLESTONE.hardness + 0.1f, placedHit))
        assertEquals(BlockType.AIR, scene.world.getBlock(targetPos))

        val drops = scene.world.entities.filterIsInstance<ItemDropEntity>()
        assertEquals(1, drops.size)
        assertEquals("Tessellate:cobblestone", drops[0].itemId)

        // Let drop settle and walk player onto it
        repeat(45) { scene.update(1f / 60f) }
        scene.player.posX = drops[0].position.x
        scene.player.posZ = drops[0].position.z
        scene.update(1f / 60f)

        assertTrue(drops[0].isRemoved)
        assertEquals(5, scene.player.inventory.getSlot(0)?.count)

        // Acceptance Test 5: Empty slot completely -> tapping world does nothing
        val clearResult = engine.executeCommand("/clear")
        assertTrue(clearResult.success)
        assertNull(scene.player.inventory.getSlot(0))
        assertEquals(ItemRegistry.AIR, scene.selectedItem)

        // Re-aim at floor block (20, 5, 18) -> tapping world must NOT place any block!
        scene.player.posX = 20.5f
        scene.player.posZ = 20.5f
        scene.player.updateBoundingBox()
        engine.camera.setFirstPersonView(
            scene.player.posX, scene.player.eyeY, scene.player.posZ, -42f, 0f
        )
        scene.updateCameraRaycast(engine.camera)
        val blockedTarget = scene.currentTargetHit!!.blockPos.offset(scene.currentTargetHit!!.face)
        assertEquals(BlockType.AIR, scene.world.getBlock(blockedTarget))

        val t1 = SystemClock.uptimeMillis() + 500
        val emptyTapDown = MotionEvent.obtain(t1, t1, MotionEvent.ACTION_DOWN, 960f, 540f, 0)
        engine.onTouchEvent(emptyTapDown)
        emptyTapDown.recycle()
        val emptyTapUp = MotionEvent.obtain(t1, t1 + 30, MotionEvent.ACTION_UP, 960f, 540f, 0)
        engine.onTouchEvent(emptyTapUp)
        emptyTapUp.recycle()

        assertEquals(
            "Tapping world with an empty hotbar slot must place nothing",
            BlockType.AIR,
            scene.world.getBlock(blockedTarget)
        )
        assertFalse(scene.placeBlockAtTarget())
    }

    // =========================================================================
    // ACCEPTANCE TEST 4: /give tessellate:glass 10 + TRANSPARENCY BEHIND BLOCKS
    // =========================================================================

    @Test
    fun `giving and placing tessellate glass preserves neighbor opaque faces behind glass`() {
        val engine = TessellateEngine(context)
        val scene = engine.worldScene

        // Clear chunk (0, 0, 0)
        for (x in 0..15) {
            for (y in 0..15) {
                for (z in 0..15) {
                    scene.world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }

        val giveRes = engine.executeCommand("/give tessellate:glass 10")
        assertTrue(giveRes.success)
        assertEquals(ItemStack("Tessellate:glass", 10), scene.player.inventory.getSlot(0))

        // Place an opaque stone block at eye level behind a glass block and place glass via placeBlockAtTarget
        scene.world.setBlock(6, 6, 6, BlockType.STONE)
        scene.player.posX = 10.5f
        scene.player.posY = 5.0f
        scene.player.posZ = 6.5f
        scene.player.updateBoundingBox()

        engine.camera.setFirstPersonView(scene.player.posX, scene.player.eyeY, scene.player.posZ, 0f, -90f)
        scene.updateCameraRaycast(engine.camera)
        assertEquals(BlockPos(6, 6, 6), scene.currentTargetHit?.blockPos)
        assertEquals(Direction.EAST, scene.currentTargetHit?.face)

        assertTrue(scene.placeBlockAtTarget())
        assertEquals(BlockType.GLASS, scene.world.getBlock(7, 6, 6))
        assertEquals(9, scene.player.inventory.getSlot(0)?.count)

        val mesh = ChunkMesh(scene.world.getChunk(0, 0, 0)!!)
        val pipeline = TexturePipeline(context)
        mesh.generateMeshData(scene.world, pipeline)

        // Stone keeps all 6 opaque faces visible through the adjacent glass; glass emits 5 transparent faces
        assertEquals(6, mesh.opaqueFacesCount)
        assertEquals(5, mesh.transparentFacesCount)
    }

    // =========================================================================
    // COMMAND CONSOLE: /spawn <entity_id> & INVALID COMMANDS
    // =========================================================================

    @Test
    fun `spawn command spawns entity 3 blocks in front of player and handles invalid inputs`() {
        val engine = TessellateEngine(context)
        val scene = engine.worldScene
        scene.world.clearEntities()

        scene.player.posX = 24.5f
        scene.player.posY = 10.0f
        scene.player.posZ = 24.5f
        scene.player.yaw = 0f
        scene.player.pitch = 0f
        engine.camera.setFirstPersonView(
            scene.player.posX, scene.player.eyeY, scene.player.posZ,
            playerPitch = 0f, playerYaw = 0f
        )

        val spawnRes = engine.executeCommand("/spawn item_drop")
        assertTrue(spawnRes.success)
        assertEquals(1, scene.world.entities.size)

        val spawned = scene.world.entities.first() as ItemDropEntity
        val dx = spawned.position.x - scene.player.posX
        val dy = spawned.position.y - scene.player.eyeY
        val dz = spawned.position.z - scene.player.posZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        assertEquals(3.0f, dist, 0.05f)
        assertEquals(21.5f, spawned.position.z, 0.05f)

        // Unknown command / invalid item return clean errors
        assertFalse(engine.executeCommand("/unknown").success)
        assertFalse(engine.executeCommand("/give tessellate:does_not_exist 5").success)
        assertFalse(engine.executeCommand("/spawn invalid_mob").success)
    }

    @Test
    fun `single-L tesselate and double-L tessellate spellings resolve identically across registries and commands`() {
        assertEquals(BlockType.COBBLESTONE, BlockType.fromIdentifier("tesselate:cobblestone"))
        assertEquals(BlockType.COBBLESTONE, BlockType.fromIdentifier("tessellate:cobblestone"))
        assertEquals(BlockType.PROTO_STONE, BlockType.fromIdentifier("prototesselate:stone"))
        assertEquals(BlockType.PROTO_STONE, BlockType.fromIdentifier("prototessellate:stone"))

        val engine = TessellateEngine(context)
        assertTrue(engine.executeCommand("/give tesselate:sand 3").success)
        assertTrue(engine.executeCommand("/give tessellate:sand 2").success)
        assertEquals(5, engine.worldScene.player.inventory.getSlot(0)?.count)
        assertEquals("Tessellate:sand", engine.worldScene.player.inventory.getSlot(0)?.itemId)
    }
}
