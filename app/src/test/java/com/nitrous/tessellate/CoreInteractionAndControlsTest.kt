package com.nitrous.tessellate

import android.os.SystemClock
import com.nitrous.tessellate.input.TouchInputController
import com.nitrous.tessellate.item.BlockItem
import com.nitrous.tessellate.item.Item
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemType
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.registry.DuplicateIdentifierException
import com.nitrous.tessellate.registry.Identifier
import com.nitrous.tessellate.registry.Namespaces
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.registry.RegistryFrozenException
import com.nitrous.tessellate.scene.WorldScene
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Direction
import com.nitrous.tessellate.world.RaycastHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification test suite for TDD-005: Core Interaction & Controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoreInteractionAndControlsTest {

    // =========================================================================
    // 1. Item Registration
    // =========================================================================

    @Test
    fun `items can be registered through centralized registry with namespaced identifiers`() {
        assertNotNull(ItemRegistry.get(Identifier.of(Namespaces.TESSELLATE, "grass")))
        assertNotNull(ItemRegistry.get(Identifier.of(Namespaces.TESSELLATE, "dirt")))
        assertNotNull(ItemRegistry.get(Identifier.of(Namespaces.TESSELLATE, "stone")))
        assertNotNull(ItemRegistry.get(Identifier.of(Namespaces.DEBUG, "debugbug")))
        assertNotNull(ItemRegistry.get(Identifier.of(Namespaces.DEVELOPER, "thing_that_goes_brrr")))

        val customId = Identifier.of("ModdedContent", "ruby_gem")
        val customItemJson = """
            {
              "id": "ModdedContent:ruby_gem",
              "display_name": "Ruby Gem",
              "max_stack_size": 32,
              "item_type": "MATERIAL"
            }
        """.trimIndent()
        try {
            AndroidRegistryBootstrap.initialize(
                extraItemJsons = listOf(customItemJson),
                forceReinitialize = true
            )
            val loaded = ItemRegistry.get(customId)
            assertNotNull(loaded)
            assertEquals("Ruby Gem", loaded?.displayName)
            assertEquals(32, loaded?.maxStackSize)
            assertEquals(ItemType.MATERIAL, loaded?.itemType)
        } finally {
            AndroidRegistryBootstrap.initialize(forceReinitialize = true)
        }
    }

    @Test
    fun `case-insensitive item lookup preserves canonical identifier`() {
        val stoneItem = ItemRegistry.get("tessellate:stone")
        assertNotNull(stoneItem)
        assertEquals("Stone", stoneItem?.displayName)
        assertEquals("Tessellate:stone", stoneItem?.identifier?.toCanonicalString())

        val upperItem = ItemRegistry.get("TESSELLATE:STONE")
        assertEquals(stoneItem, upperItem)

        val debugItem = ItemRegistry.get("debug:debugbug")
        assertNotNull(debugItem)
        assertEquals("Debug:debugbug", debugItem?.identifier?.toCanonicalString())
    }

    @Test
    fun `duplicate item registrations are rejected before freeze and mutations throw after freeze`() {
        // Post-freeze registration must fail cleanly
        assertThrows(RegistryFrozenException::class.java) {
            ItemRegistry.register(Item(Identifier.of(Namespaces.TESSELLATE, "late_item"), "Late Item"))
        }

        // Duplicate / case-only collisions are rejected during bootstrap before freeze
        try {
            assertThrows(DuplicateIdentifierException::class.java) {
                AndroidRegistryBootstrap.initialize(
                    forceReinitialize = true,
                    preFreezeHook = {
                        ItemRegistry.register(Item(Identifier.of(Namespaces.TESSELLATE, "stone"), "Duplicate Stone"))
                    }
                )
            }
            assertThrows(DuplicateIdentifierException::class.java) {
                AndroidRegistryBootstrap.initialize(
                    forceReinitialize = true,
                    preFreezeHook = {
                        ItemRegistry.register(Item(Identifier.of("tessellate", "STONE"), "Capitalized Stone"))
                    }
                )
            }
        } finally {
            AndroidRegistryBootstrap.initialize(forceReinitialize = true)
        }
    }

    @Test
    fun `item properties are fully accessible through registry`() {
        val pickaxe = ItemRegistry.get("Tessellate:iron_pickaxe")
        assertNotNull(pickaxe)
        assertEquals("Iron Pickaxe", pickaxe?.displayName)
        assertEquals(1, pickaxe?.maxStackSize)
        assertEquals(ItemType.TOOL, pickaxe?.itemType)
        assertFalse(pickaxe?.isPlaceable ?: true)

        val grassBlockItem = ItemRegistry.getItemForBlock(BlockType.GRASS)
        assertNotNull(grassBlockItem)
        assertEquals("Grass Block", grassBlockItem?.displayName)
        assertEquals(64, grassBlockItem?.maxStackSize)
        assertEquals(BlockType.GRASS, grassBlockItem?.associatedBlock)
        assertTrue(grassBlockItem?.isPlaceable ?: false)
        assertTrue(grassBlockItem?.isBlock ?: false)
    }

    // =========================================================================
    // 2. Mining System
    // =========================================================================

    @Test
    fun `holding initiates mining and progress accumulates proportionally to hardness`() {
        val scene = WorldScene()
        val pos = BlockPos(10, 5, 10)
        scene.world.setBlock(pos, BlockType.DIRT) // hardness = 0.5f

        val hit = RaycastHit(pos, BlockType.DIRT, Direction.UP, 2.0f)
        assertTrue(scene.startMining(pos))
        assertTrue(scene.isMining)
        assertEquals(0f, scene.miningProgress, 0.001f)

        // Simulate 0.25s of mining (half of 0.5s hardness -> 50% progress)
        val brokenHalf = scene.updateMining(0.25f, hit)
        assertFalse(brokenHalf)
        assertTrue(scene.isMining)
        assertEquals(0.5f, scene.miningProgress, 0.05f)

        // Complete remaining 0.25s
        val brokenFull = scene.updateMining(0.25f, hit)
        assertTrue("Block must break when progress reaches 100%", brokenFull)
        assertFalse(scene.isMining)
        assertEquals(BlockType.AIR, scene.world.getBlock(pos))
    }

    @Test
    fun `mining harder blocks requires proportionally more time`() {
        val scene = WorldScene()
        val stonePos = BlockPos(12, 5, 12)
        scene.world.setBlock(stonePos, BlockType.STONE) // hardness = 1.5f
        val hit = RaycastHit(stonePos, BlockType.STONE, Direction.NORTH, 2.0f)

        scene.startMining(stonePos)
        // 0.5s mining on Stone (1.5s hardness) -> ~33% progress
        scene.updateMining(0.5f, hit)
        assertEquals(0.5f / 1.5f, scene.miningProgress, 0.05f)
        assertFalse(scene.world.getBlock(stonePos) == BlockType.AIR)

        // Another 1.0s mining on Stone -> completes (1.5s total)
        val broken = scene.updateMining(1.0f, hit)
        assertTrue(broken)
        assertEquals(BlockType.AIR, scene.world.getBlock(stonePos))
    }

    @Test
    fun `releasing interaction cancels incomplete mining and preserves block`() {
        val scene = WorldScene()
        val pos = BlockPos(14, 5, 14)
        scene.world.setBlock(pos, BlockType.GRASS)
        val hit = RaycastHit(pos, BlockType.GRASS, Direction.UP, 2.0f)

        scene.startMining(pos)
        scene.updateMining(0.3f, hit) // incomplete (0.3s of 0.6s)
        assertTrue(scene.miningProgress > 0f)

        // Cancel / Release
        scene.cancelMining()
        assertFalse(scene.isMining)
        assertEquals(0f, scene.miningProgress, 0.001f)
        assertNull(scene.miningBlockPos)
        assertEquals(BlockType.GRASS, scene.world.getBlock(pos))
    }

    @Test
    fun `mining cancels when crosshair target moves away`() {
        val scene = WorldScene()
        val posA = BlockPos(10, 5, 10)
        val posB = BlockPos(11, 5, 10)
        scene.world.setBlock(posA, BlockType.DIRT)
        scene.world.setBlock(posB, BlockType.STONE)

        val hitA = RaycastHit(posA, BlockType.DIRT, Direction.UP, 2.0f)
        val hitB = RaycastHit(posB, BlockType.STONE, Direction.UP, 2.0f)

        scene.startMining(posA)
        scene.updateMining(0.2f, hitA)
        assertTrue(scene.isMining)

        // Crosshair moves to posB: mining on posA must be cancelled!
        val broken = scene.updateMining(0.1f, hitB)
        assertFalse(broken)
        assertFalse(scene.isMining)
        assertEquals(BlockType.DIRT, scene.world.getBlock(posA))
    }

    // =========================================================================
    // 3. Placement System
    // =========================================================================

    @Test
    fun `block placement targets adjacent face and places selected item block`() {
        val scene = WorldScene()
        val originPos = BlockPos(20, 5, 20)
        scene.world.setBlock(originPos, BlockType.STONE)
        val placeTargetPos = originPos.offset(Direction.UP) // (20, 6, 20)
        scene.world.setBlock(placeTargetPos, BlockType.AIR)

        scene.selectedItem = ItemRegistry.DIRT
        val hit = RaycastHit(originPos, BlockType.STONE, Direction.UP, 2.0f)

        val placed = scene.placeBlockAt(hit, scene.selectedPlacementBlock)
        assertTrue(placed)
        assertEquals(BlockType.DIRT, scene.world.getBlock(placeTargetPos))
    }

    @Test
    fun `block placement is rejected inside player bounding box`() {
        val scene = WorldScene()
        val player = scene.player
        player.posX = 15.5
        player.posY = 5.0
        player.posZ = 15.5
        player.updateBoundingBox()

        // Place target directly inside player's feet at (15, 5, 15)
        val baseBlock = BlockPos(15, 4, 15)
        scene.world.setBlock(baseBlock, BlockType.STONE)
        scene.world.setBlock(15, 5, 15, BlockType.AIR)
        val hit = RaycastHit(baseBlock, BlockType.STONE, Direction.UP, 2.0f)

        val placed = scene.placeBlockAt(hit, BlockType.STONE)
        assertFalse("Placement inside player must be rejected", placed)
        assertEquals(BlockType.AIR, scene.world.getBlock(15, 5, 15))
    }

    @Test
    fun `block placement is rejected when target is outside world boundaries`() {
        val scene = WorldScene()
        // Top edge of world (y = 31)
        val topPos = BlockPos(10, scene.world.sizeY - 1, 10)
        scene.world.setBlock(topPos, BlockType.STONE)
        val hit = RaycastHit(topPos, BlockType.STONE, Direction.UP, 2.0f)

        val placed = scene.placeBlockAt(hit, BlockType.DIRT)
        assertFalse("Placement above world ceiling must be rejected", placed)
    }

    @Test
    fun `block placement is rejected if adjacent block is already opaque`() {
        val scene = WorldScene()
        val posA = BlockPos(10, 5, 10)
        val posB = BlockPos(10, 6, 10)
        scene.world.setBlock(posA, BlockType.STONE)
        scene.world.setBlock(posB, BlockType.STONE) // Already occupied!

        val hit = RaycastHit(posA, BlockType.STONE, Direction.UP, 2.0f)
        val placed = scene.placeBlockAt(hit, BlockType.GRASS)
        assertFalse("Placement into existing solid block must be rejected", placed)
    }

    // =========================================================================
    // 4. First-Person Camera & Multitouch Input
    // =========================================================================

    @Test
    fun `camera look has zero inertia and stops rotating immediately when finger stops`() {
        val tc = TouchInputController()
        val player = Player()
        player.yaw = 0f
        player.pitch = 0f

        tc.simulateLookTouch(pointerId = 1, startX = 500f, startY = 300f)

        // Move finger +100px horizontally, -50px vertically
        tc.simulateLookDrag(x = 600f, y = 250f, player = player)
        val expectedYaw = 100f * tc.lookSensitivityX
        val expectedPitch = 50f * tc.lookSensitivityY
        assertEquals(expectedYaw, player.yaw, 0.01f)
        assertEquals(expectedPitch, player.pitch, 0.01f)

        // Finger stops moving (no delta) -> rotation must be exactly zero!
        tc.simulateLookDrag(x = 600f, y = 250f, player = player)
        assertEquals("Yaw must not drift when finger stops", expectedYaw, player.yaw, 0.01f)
        assertEquals("Pitch must not drift when finger stops", expectedPitch, player.pitch, 0.01f)

        // Release look pointer
        tc.simulateLookRelease()
        assertNull(tc.lookPointerId)
    }

    @Test
    fun `camera pitch clamping prevents flipping`() {
        val tc = TouchInputController()
        val player = Player()
        player.pitch = 0f

        tc.simulateLookTouch(pointerId = 1, startX = 500f, startY = 500f)

        // Drag down excessively
        tc.simulateLookDrag(x = 500f, y = 2000f, player = player)
        assertEquals(-89f, player.pitch, 0.01f)

        // Drag up excessively
        tc.simulateLookDrag(x = 500f, y = -2000f, player = player)
        assertEquals(89f, player.pitch, 0.01f)
    }

    @Test
    fun `multitouch handles dpad and camera look independently without camera jumps`() {
        val tc = TouchInputController()
        tc.onSurfaceChanged(1920, 1080)
        val player = Player()

        // 1. Pointer 0 touches D-Pad (Up)
        tc.simulateDpadTouch(pointerId = 0, nx = 0.5f, ny = 0.1f)
        assertTrue(tc.isUpPressed)
        assertEquals(1.0f, tc.moveForward, 0.01f)

        // 2. Pointer 1 touches right-side camera look
        tc.simulateLookTouch(pointerId = 1, startX = 1200f, startY = 500f)
        assertEquals(1, tc.lookPointerId)

        // 3. Move camera look pointer
        tc.simulateLookDrag(x = 1250f, y = 500f, player = player)
        assertTrue(player.yaw > 0f)

        // 4. Release D-Pad pointer: look pointer must remain undisturbed!
        tc.simulateDpadRelease(0)
        assertFalse(tc.isUpPressed)
        assertEquals(1, tc.lookPointerId)

        // 5. Subsequent camera movement continues cleanly with no jump
        val yawBefore = player.yaw
        tc.simulateLookDrag(x = 1260f, y = 500f, player = player)
        assertEquals(yawBefore + 10f * tc.lookSensitivityX, player.yaw, 0.01f)
    }
}
