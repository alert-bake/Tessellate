package com.nitrous.tessellate

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.SceneMode
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.item.Inventory
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.ui.UiLayoutLoader
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Direction
import com.nitrous.tessellate.world.RaycastHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verification test suite for TDD-010: Inventory UI & Pickup Loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd010InventoryAndPickupTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val overrideLayout = File(context.filesDir, UiLayoutLoader.LAYOUT_FILE_NAME)
        if (overrideLayout.exists()) {
            overrideLayout.delete()
        }
    }

    // =========================================================================
    // 1. Asset & Data-Driven UI Layout (ui_layout.json + UI_gui_widgets.png)
    // =========================================================================

    @Test
    fun `ui widget atlas exists and ui_layout json loads hotbar background and selection UVs`() {
        assertTrue(TextureConstants.ALL_UI_IDS.contains(TextureConstants.UI_GUI_WIDGETS))
        assertTrue(TextureConstants.ALL_ASSET_IDS.contains(TextureConstants.UI_GUI_WIDGETS))

        val assetPath = TextureConstants.getAssetPath(TextureConstants.UI_GUI_WIDGETS)
        context.assets.open(assetPath).use { stream ->
            assertTrue("UI_gui_widgets.png must be non-empty", stream.available() > 0)
        }

        val layout = UiLayoutLoader.load(context)
        val bg = layout.hotbar.background
        val sel = layout.hotbar.selection

        assertEquals(0.0f, bg.u1, 1e-4f)
        assertEquals(0.0f, bg.v1, 1e-4f)
        assertEquals(182f / 256f, bg.u2, 1e-4f)
        assertEquals(22f / 256f, bg.v2, 1e-4f)

        assertEquals(0.0f, sel.u1, 1e-4f)
        assertEquals(22f / 256f, sel.v1, 1e-4f)
        assertEquals(24f / 256f, sel.u2, 1e-4f)
        assertEquals(46f / 256f, sel.v2, 1e-4f)

        assertTrue(layout.hotbar.sliceU > 0f)
        assertTrue(layout.hotbar.sliceV > 0f)
    }

    @Test
    fun `modder ui_layout json override in filesDir takes precedence over bundled asset`() {
        val overrideFile = File(context.filesDir, UiLayoutLoader.LAYOUT_FILE_NAME)
        overrideFile.writeText(
            """
            {
              "hotbar": {
                "background": { "u1": 0.1, "v1": 0.1, "u2": 0.8, "v2": 0.2 },
                "selection": { "u1": 0.05, "v1": 0.25, "u2": 0.15, "v2": 0.35 }
              }
            }
            """.trimIndent()
        )

        val engine = TessellateEngine(context)
        val loaded = engine.uiRenderer.uiLayout
        assertEquals(0.1f, loaded.hotbar.background.u1, 1e-4f)
        assertEquals(0.8f, loaded.hotbar.background.u2, 1e-4f)
        assertEquals(0.05f, loaded.hotbar.selection.u1, 1e-4f)
        assertEquals(0.35f, loaded.hotbar.selection.v2, 1e-4f)
    }

    // =========================================================================
    // 2. Inventory Data Structure (9 slots, ItemStack, default selectedSlot = 0)
    // =========================================================================

    @Test
    fun `player inventory has 9 slots defaults to slot 0 and stacks matching itemIds`() {
        val inventory = Inventory()
        assertEquals(9, inventory.size)
        assertEquals(0, inventory.selectedSlot)
        for (i in 0 until 9) {
            assertNull(inventory.getSlot(i))
        }

        // Case-insensitive matching stacks "tessellate:dirt" and "Tessellate:dirt" together
        assertEquals(0, inventory.addItem("tessellate:dirt", 1))
        assertEquals(0, inventory.addItem("Tessellate:dirt", 2))

        val slot0 = inventory.getSlot(0)
        assertNotNull(slot0)
        assertEquals(ItemStack("Tessellate:dirt", 3), slot0)
        assertNull(inventory.getSlot(1))

        // Different item goes into next empty slot
        assertEquals(0, inventory.addItem("tessellate:stone", 5))
        assertEquals(ItemStack("Tessellate:stone", 5), inventory.getSlot(1))

        // Overflow past maxStackSize (64) spills into slot 2
        assertEquals(0, inventory.addItem("tessellate:dirt", 63))
        assertEquals(64, inventory.getSlot(0)?.count)
        assertEquals(ItemStack("Tessellate:dirt", 2), inventory.getSlot(2))
    }

    // =========================================================================
    // 3. Pickup Loop: Mine Dirt -> Drop Entity -> Walk Over -> Stack 1 -> 2 -> 3
    // =========================================================================

    @Test
    fun `mining dirt spawns ItemDropEntity and walking over drops stacks 1 to 3 in slot 0`() {
        val engine = TessellateEngine(context)
        val scene = engine.worldScene
        scene.world.clearEntities()

        // Create a clean flat stone floor at y=5 and place 3 mineable dirt blocks at y=6
        for (x in 18..26) {
            for (z in 18..26) {
                scene.world.setBlock(x, 5, z, BlockType.STONE)
                for (y in 6..15) {
                    scene.world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }
        val dirtPositions = listOf(
            BlockPos(20, 6, 20),
            BlockPos(22, 6, 20),
            BlockPos(24, 6, 20)
        )
        dirtPositions.forEach { scene.world.setBlock(it, BlockType.DIRT) }

        // Place player away from dirt blocks at (21.5, 6.0, 24.5)
        scene.player.posX = 21.5
        scene.player.posY = 6.0
        scene.player.posZ = 24.5
        scene.player.updateBoundingBox()

        // Mine all 3 dirt blocks (dirt hardness = 0.5f)
        for (pos in dirtPositions) {
            val hit = RaycastHit(pos, BlockType.DIRT, Direction.SOUTH, 3.0f)
            assertTrue(scene.startMining(pos))
            assertTrue(scene.updateMining(0.55f, hit))
            assertEquals(BlockType.AIR, scene.world.getBlock(pos))
        }

        // Verify 3 tiny dirt ItemDropEntities spawned in the world
        val drops = scene.world.entities.filterIsInstance<ItemDropEntity>()
        assertEquals(3, drops.size)
        assertTrue(drops.all { it.itemId == "Tessellate:dirt" })

        // Let gravity settle the drops onto the y=6 floor
        repeat(45) {
            scene.update(1f / 60f)
        }
        assertEquals(0, scene.player.inventory.getTotalCount("tessellate:dirt"))

        // Walk player over drop #1, drop #2, drop #3 sequentially and verify count updates 1 -> 2 -> 3
        scene.player.posX = 20.5
        scene.player.posZ = 20.5
        scene.update(1f / 60f)
        assertTrue(drops[0].isRemoved)
        assertEquals(1, scene.player.inventory.getSlot(0)?.count)

        scene.player.posX = 22.5
        scene.player.posZ = 20.5
        scene.update(1f / 60f)
        assertTrue(drops[1].isRemoved)
        assertEquals(2, scene.player.inventory.getSlot(0)?.count)

        scene.player.posX = 24.5
        scene.player.posZ = 20.5
        scene.update(1f / 60f)
        assertTrue(drops[2].isRemoved)
        assertEquals(3, scene.player.inventory.getSlot(0)?.count)
        assertEquals(0, scene.world.entities.size)
        assertEquals(ItemRegistry.DIRT, scene.selectedItem)
    }

    // =========================================================================
    // 4. Touch Input: 9-Slot Hotbar Centered + Tap Slot 2 + Jump Button Works
    // =========================================================================

    @Test
    fun `centered 9-slot hotbar switches selectedSlot on tap without interfering with jump or camera`() {
        val engine = TessellateEngine(context)
        engine.setAppState(AppState.PLAYING)
        engine.switchSceneMode(SceneMode.WORLD)
        engine.touchController.onSurfaceChanged(1920, 1080)
        engine.uiRenderer.onSurfaceChanged(1920, 1080)

        val slots = engine.uiRenderer.getHotbarSlotRects()
        assertEquals(9, slots.size)
        assertEquals(0, engine.worldScene.player.inventory.selectedSlot)

        // Centered horizontally at the bottom of the screen
        val bounds = engine.uiRenderer.hotbarBoundsRect
        assertEquals(960f, bounds.centerX(), 1.0f)
        assertTrue(bounds.bottom <= 1080f)
        assertTrue(
            "Hotbar must not overlap left D-pad",
            bounds.left >= engine.touchController.dpadRect.right
        )
        assertTrue(
            "Hotbar must not overlap right Jump button",
            bounds.right <= engine.uiRenderer.jumpButtonRect.left
        )

        val initialYaw = engine.worldScene.player.yaw

        // Tap the second hotbar slot (index 1)
        val slot1 = slots[1]
        val t0 = SystemClock.uptimeMillis()
        val downSlot1 = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, slot1.centerX(), slot1.centerY(), 0)
        assertTrue(engine.onTouchEvent(downSlot1))
        downSlot1.recycle()

        val upSlot1 = MotionEvent.obtain(t0, t0 + 20, MotionEvent.ACTION_UP, slot1.centerX(), slot1.centerY(), 0)
        assertTrue(engine.onTouchEvent(upSlot1))
        upSlot1.recycle()

        assertEquals(1, engine.worldScene.player.inventory.selectedSlot)
        assertEquals(initialYaw, engine.worldScene.player.yaw, 1e-4f)

        // Tapping jump button still triggers player jump cleanly
        val player = engine.worldScene.player
        player.spawnAtSurface(engine.worldScene.world, 24.5, 24.5)
        assertTrue(player.isGrounded)

        val jumpRect = engine.uiRenderer.jumpButtonRect
        val downJump = MotionEvent.obtain(t0 + 50, t0 + 50, MotionEvent.ACTION_DOWN, jumpRect.centerX(), jumpRect.centerY(), 0)
        assertTrue(engine.onTouchEvent(downJump))
        downJump.recycle()

        assertTrue("Jump button must still make player jump", player.velY > 0.0)
        assertFalse(player.isGrounded)
    }
}
