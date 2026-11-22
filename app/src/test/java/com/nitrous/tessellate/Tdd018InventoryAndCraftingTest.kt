package com.nitrous.tessellate

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.item.Inventory
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.item.RecipeRegistry
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.ui.InventoryScreen
import com.nitrous.tessellate.ui.UiLayoutLoader
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import kotlinx.coroutines.Job
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import org.junit.After
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
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Comprehensive verification test suite for TDD-018:
 * Inventory UI & 3x3 Crafting Loop, RecipeRegistry, Invisible 10th-Slot Hitbox,
 * Hotbar Alignment, World Pause under AppState.INVENTORY, Drag & Drop, and Persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd018InventoryAndCraftingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cleanupFiles()
        RegistryBootstrap.initialize(context = context, forceReinitialize = true)
    }

    @After
    fun tearDown() {
        cleanupFiles()
    }

    private fun cleanupFiles() {
        SettingsManager.resolveSettingsFile(context).delete()
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
        File(context.filesDir, "content").deleteRecursively()
        File(WorldSaveManager.getBaseFilesDir(context), "mods").deleteRecursively()
    }

    @Test
    fun `recipe registry loads oak_planks json at startup, freezes, matches shapelessly, and supports mod recipes`() {
        assertTrue("RecipeRegistry must freeze after RegistryBootstrap", RecipeRegistry.isFrozen)
        val planksRecipe = RecipeRegistry.get("tessellate:oak_planks")
        assertNotNull("tessellate:oak_planks recipe must be loaded from assets/content/recipes/", planksRecipe)
        assertEquals(1, planksRecipe!!.ingredients.size)
        assertEquals("tessellate:oak_log", planksRecipe.ingredients[0].normalizedItemId)
        assertEquals(1, planksRecipe.ingredients[0].count)
        assertEquals("tessellate:oak_planks", planksRecipe.resultItemId.lowercase())
        assertEquals(4, planksRecipe.resultCount)
        assertNotNull("tessellate:oak_planks item must be registered", ItemRegistry.get("tessellate:oak_planks"))

        // Shapeless match across every one of the 9 crafting grid positions
        for (slotIdx in 0 until InventoryScreen.CRAFTING_GRID_SIZE) {
            val grid = arrayOfNulls<ItemStack>(InventoryScreen.CRAFTING_GRID_SIZE)
            grid[slotIdx] = ItemStack("tessellate:oak_log", 1)
            val matched = RecipeRegistry.findMatch(grid)
            assertEquals("Recipe should match shapelessly at slot $slotIdx", planksRecipe.identifier, matched?.identifier)
        }

        // Extraneous ingredient must NOT match shapeless recipe
        val pollutedGrid = arrayOfNulls<ItemStack>(InventoryScreen.CRAFTING_GRID_SIZE)
        pollutedGrid[0] = ItemStack("tessellate:oak_log", 1)
        pollutedGrid[4] = ItemStack("tessellate:dirt", 1)
        assertNull("Extra unrelated item in 3x3 grid must not match oak_planks", RecipeRegistry.findMatch(pollutedGrid))

        // Post-freeze registration must fail safely
        val postFreeze = runCatching {
            RecipeRegistry.register(planksRecipe.copy())
        }
        assertTrue("Registering into frozen RecipeRegistry must throw", postFreeze.isFailure)

        // Mod recipe JSON under filesDir/content/recipes/ loads cleanly on bootstrap
        val modRecipesDir = File(context.filesDir, "content/recipes").apply { mkdirs() }
        File(modRecipesDir, "cobble_to_stone.json").writeText(
            """
            {
              "id": "example:cobble_to_stone",
              "ingredients": [
                { "item": "tessellate:cobblestone", "count": 2 }
              ],
              "result": { "item": "tessellate:stone", "count": 1 }
            }
            """.trimIndent()
        )
        RegistryBootstrap.initialize(context = context, forceReinitialize = true)
        val modRecipe = RecipeRegistry.get("example:cobble_to_stone")
        assertNotNull("Custom recipe JSON must load during bootstrap", modRecipe)
        assertEquals("tessellate:stone", modRecipe!!.resultItemId.lowercase())
    }

    @Test
    fun `UI_gui_inventory png texture and ui_layout json inventory coordinates load accurately`() {
        assertTrue(TextureConstants.ALL_UI_IDS.contains(TextureConstants.UI_GUI_INVENTORY))
        val path = TextureConstants.getAssetPath(TextureConstants.UI_GUI_INVENTORY)
        assertEquals("textures/UI_gui_inventory.png", path)

        val bmp = context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        assertNotNull("UI_gui_inventory.png must decode cleanly from assets", bmp)
        assertEquals(256, bmp!!.width)
        assertEquals(256, bmp.height)
        bmp.recycle()

        val layout = UiLayoutLoader.load(context).inventory
        assertEquals(176f, layout.panelWidth, 0.01f)
        assertEquals(166f, layout.panelHeight, 0.01f)
        assertEquals(3, layout.craftingGrid.rows)
        assertEquals(3, layout.craftingGrid.cols)
        assertEquals(3, layout.mainInventory.rows)
        assertEquals(9, layout.mainInventory.cols)
        assertEquals(1, layout.hotbar.rows)
        assertEquals(9, layout.hotbar.cols)
        assertTrue(layout.panel.u2 > layout.panel.u1 && layout.panel.v2 > layout.panel.v1)
        assertTrue(layout.slot.u2 > layout.slot.u1 && layout.slot.v2 > layout.slot.v1)
        assertTrue(layout.craftingOutput.slotSize > 0f && layout.craftingOutput.innerSize > 0f)
    }

    @Test
    fun `invisible 10th hotbar slot opens AppState INVENTORY, aligns hotbar, pauses 3D world, and scrim tap closes`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        try {
            engine.startWorldSession(WorldSession("InvWorld", GameMode.SURVIVAL, WorldType.FLAT, 123L))
            engine.onSurfaceChanged(1920, 1080)

            val ui = engine.uiRenderer
            val hudHotbar = ui.getHotbarSlotRects()
            assertEquals(Inventory.HOTBAR_SIZE, hudHotbar.size)

            // Verify invisible 10th-slot hitbox sits right next to slot 8 & is NOT in visual button lists
            val slot8 = hudHotbar[8]
            val hotspot = ui.inventoryHotspotRect
            assertFalse("Invisible 10th hotbar slot hitbox must be computed", hotspot.isEmpty)
            assertEquals(slot8.right, hotspot.left, 1.0f)
            assertEquals(slot8.top, hotspot.top, 1.0f)
            assertEquals(slot8.bottom, hotspot.bottom, 1.0f)
            assertTrue("HUD mode must have no active menu buttons", ui.getActiveMenuButtons().isEmpty())

            // Tap invisible hitbox -> switches to AppState.INVENTORY
            tapPoint(engine, hotspot.centerX(), hotspot.centerY())
            assertEquals(AppState.INVENTORY, engine.currentAppState)

            // Verify inventory screen slot counts & HUD-aligned hotbar coordinates
            val invScreen = ui.inventoryScreen
            assertEquals(9, invScreen.getCraftingSlotRects().size)
            assertNotNull(invScreen.getCraftingOutputSlotRect())
            assertEquals(27, invScreen.getMainInventorySlotRects().size)
            val invHotbar = invScreen.getHotbarSlotRects()
            assertEquals(9, invHotbar.size)
            for (i in 0 until Inventory.HOTBAR_SIZE) {
                assertTrue(
                    "Inventory hotbar slot $i centerX must align with HUD hotbar slot",
                    abs(hudHotbar[i].centerX() - invHotbar[i].centerX()) <= 1.5f
                )
                assertTrue(
                    "Inventory hotbar slot $i centerY must align with HUD hotbar slot",
                    abs(hudHotbar[i].centerY() - invHotbar[i].centerY()) <= 1.5f
                )
            }

            // Verify 3D world and player physics pause while AppState.INVENTORY is open
            val player = engine.worldScene.player
            player.posY += 3.0f
            player.velY = -1.0f
            val frozenX = player.posX
            val frozenY = player.posY
            val frozenZ = player.posZ
            val drop = ItemDropEntity(position = Vec3(player.posX + 1.5f, player.posY, player.posZ), itemId = "tessellate:dirt")
            engine.worldScene.world.spawnEntity(drop)
            val dropYBefore = drop.position.y

            repeat(5) {
                Thread.sleep(10L)
                engine.onDrawFrame()
            }
            assertEquals(frozenX, player.posX, 1e-5f)
            assertEquals(frozenY, player.posY, 1e-5f)
            assertEquals(frozenZ, player.posZ, 1e-5f)
            assertEquals(dropYBefore, drop.position.y, 1e-5f)
            assertEquals(0, drop.tickCount)

            // Put an item in crafting grid so we verify closing via scrim returns it safely
            invScreen.setCraftingGridSlot(0, ItemStack("tessellate:dirt", 3))

            val panel = invScreen.panelRect
            val outsideX = (panel.left * 0.5f).coerceAtLeast(4f)
            val outsideY = 20f
            assertFalse(panel.contains(outsideX, outsideY))

            // Double-tapping / bouncing right on the open-inventory hotspot or outside right after opening must NOT close
            tapPoint(engine, hotspot.centerX(), hotspot.centerY())
            assertEquals("Re-tapping open hotspot right away must keep inventory open", AppState.INVENTORY, engine.currentAppState)
            tapPoint(engine, outsideX, outsideY)
            assertEquals("Outside tap within open cooldown must not accidentally close inventory", AppState.INVENTORY, engine.currentAppState)

            // Advance past open cooldown; tapping on hotspot right edge or tight panel border must STILL keep inventory open
            ShadowSystemClock.advanceBy(InventoryScreen.OPEN_DISMISS_COOLDOWN_MS + 100L, TimeUnit.MILLISECONDS)
            tapPoint(engine, hotspot.right - 4f, hotspot.centerY())
            assertEquals("Re-tapping open hotspot outside panel border must not close inventory", AppState.INVENTORY, engine.currentAppState)
            tapPoint(engine, panel.left - 8f, panel.centerY())
            assertEquals("Grazing panel edge inside forgiving bezel must not close inventory", AppState.INVENTORY, engine.currentAppState)

            // Tapping well outside the safe bezel in darkened scrim after cooldown closes inventory cleanly
            tapPoint(engine, outsideX, outsideY)

            assertEquals(AppState.PLAYING, engine.currentAppState)
            assertNull(invScreen.getCraftingGridSlot(0))
            assertEquals("tessellate:dirt", player.inventory.getSlot(0)?.itemId?.lowercase())
            assertEquals(3, player.inventory.getSlot(0)?.count)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `tap to pick and place, cursor tracking, stack merge, swap, and drag-and-drop move items across all slots`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        try {
            engine.startWorldSession(WorldSession("DragWorld", GameMode.SURVIVAL, WorldType.FLAT, 456L))
            engine.onSurfaceChanged(1920, 1080)

            val playerInv = engine.worldScene.player.inventory
            assertEquals(Inventory.TOTAL_SIZE, playerInv.size) // 36 slots: 0..8 hotbar, 9..35 main

            playerInv.setSlot(0, ItemStack("tessellate:oak_log", 10))
            playerInv.setSlot(9, ItemStack("tessellate:dirt", 5)) // Main inventory index 0
            playerInv.setSlot(2, ItemStack("tessellate:stone", 3)) // Hotbar slot 2

            engine.openInventory()
            assertEquals(AppState.INVENTORY, engine.currentAppState)
            val invScreen = engine.uiRenderer.inventoryScreen
            val hotbarRects = invScreen.getHotbarSlotRects()
            val mainRects = invScreen.getMainInventorySlotRects()
            val craftRects = invScreen.getCraftingSlotRects()

            // 1. Tap-to-pick hotbar slot 0 -> item moves to held cursor stack
            tapPoint(engine, hotbarRects[0].centerX(), hotbarRects[0].centerY())
            assertNull(playerInv.getSlot(0))
            assertEquals("tessellate:oak_log", invScreen.heldItem?.itemId?.lowercase())
            assertEquals(10, invScreen.heldItem?.count)

            // 2. Move pointer across screen & tap empty main inventory slot 1 (inventory index 10) -> places item
            val targetMain1 = mainRects[1]
            tapPoint(engine, targetMain1.centerX(), targetMain1.centerY())
            assertNull(invScreen.heldItem)
            assertEquals("tessellate:oak_log", playerInv.getSlot(10)?.itemId?.lowercase())
            assertEquals(10, playerInv.getSlot(10)?.count)
            assertEquals(targetMain1.centerX(), invScreen.cursorX, 1f)

            // 3. Single-gesture Drag & Drop from main inventory slot 0 (dirt x5) -> hotbar slot 4
            val srcMain0 = mainRects[0]
            val dstHotbar4 = hotbarRects[4]
            val t0 = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, srcMain0.centerX(), srcMain0.centerY(), 0)
            engine.onTouchEvent(down)
            down.recycle()
            assertEquals("tessellate:dirt", invScreen.heldItem?.itemId?.lowercase())
            assertNull(playerInv.getSlot(9))

            val midX = (srcMain0.centerX() + dstHotbar4.centerX()) * 0.5f
            val midY = (srcMain0.centerY() + dstHotbar4.centerY()) * 0.5f
            val move = MotionEvent.obtain(t0, t0 + 25, MotionEvent.ACTION_MOVE, midX, midY, 0)
            engine.onTouchEvent(move)
            move.recycle()
            assertEquals(midX, invScreen.cursorX, 1f)
            assertEquals(midY, invScreen.cursorY, 1f)

            val up = MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_UP, dstHotbar4.centerX(), dstHotbar4.centerY(), 0)
            engine.onTouchEvent(up)
            up.recycle()
            assertNull(invScreen.heldItem)
            assertEquals("tessellate:dirt", playerInv.getSlot(4)?.itemId?.lowercase())
            assertEquals(5, playerInv.getSlot(4)?.count)

            // 4. Swap different items: pick stone x3 from hotbar 2 -> tap oak_log x10 in main 1 -> swaps cleanly
            tapPoint(engine, hotbarRects[2].centerX(), hotbarRects[2].centerY())
            assertEquals("tessellate:stone", invScreen.heldItem?.itemId?.lowercase())
            tapPoint(engine, targetMain1.centerX(), targetMain1.centerY())
            assertEquals("tessellate:stone", playerInv.getSlot(10)?.itemId?.lowercase())
            assertEquals(3, playerInv.getSlot(10)?.count)
            assertEquals("tessellate:oak_log", invScreen.heldItem?.itemId?.lowercase())
            assertEquals(10, invScreen.heldItem?.count)

            // Place held oak_log into crafting grid slot 4
            tapPoint(engine, craftRects[4].centerX(), craftRects[4].centerY())
            assertNull(invScreen.heldItem)
            assertEquals("tessellate:oak_log", invScreen.getCraftingGridSlot(4)?.itemId?.lowercase())
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `crafting loop turns oak_log in 3x3 grid into 4 oak_planks and consumes ingredient on output tap`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        try {
            engine.startWorldSession(WorldSession("CraftWorld", GameMode.SURVIVAL, WorldType.FLAT, 789L))
            engine.onSurfaceChanged(1920, 1080)

            val playerInv = engine.worldScene.player.inventory
            playerInv.setSlot(0, ItemStack("tessellate:oak_log", 2))

            engine.openInventory()
            val invScreen = engine.uiRenderer.inventoryScreen
            val hotbarRects = invScreen.getHotbarSlotRects()
            val craftRects = invScreen.getCraftingSlotRects()
            val outputRect = invScreen.getCraftingOutputSlotRect()!!

            // Move 2x oak_log from hotbar 0 into crafting grid slot 4 (center)
            tapPoint(engine, hotbarRects[0].centerX(), hotbarRects[0].centerY())
            tapPoint(engine, craftRects[4].centerX(), craftRects[4].centerY())

            // Matched recipe preview appears in output slot
            val preview = invScreen.getCraftResultPreview()
            assertNotNull("3x3 grid with oak_log must match oak_planks recipe", preview)
            assertEquals("tessellate:oak_planks", preview!!.itemId.lowercase())
            assertEquals(4, preview.count)

            // First output tap: consumes 1 oak_log (leaving 1) and adds 4 oak_planks to inventory
            tapPoint(engine, outputRect.centerX(), outputRect.centerY())
            assertEquals(1, invScreen.getCraftingGridSlot(4)?.count)
            assertEquals(4, playerInv.getSlot(0)?.count)
            assertEquals("tessellate:oak_planks", playerInv.getSlot(0)?.itemId?.lowercase())
            assertNotNull("Second oak_log remaining in grid keeps preview active", invScreen.getCraftResultPreview())

            // Second output tap: consumes final oak_log and stacks total 8 oak_planks in inventory
            tapPoint(engine, outputRect.centerX(), outputRect.centerY())
            assertNull(invScreen.getCraftingGridSlot(4))
            assertNull(invScreen.getCraftResultPreview())
            assertEquals("tessellate:oak_planks", playerInv.getSlot(0)?.itemId?.lowercase())
            assertEquals(8, playerInv.getSlot(0)?.count)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `36-slot inventory including main 27-slot storage persists cleanly across world save and reload`() {
        val session = WorldSession("InvPersistWorld", GameMode.SURVIVAL, WorldType.FLAT, 321L)
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        try {
            engine.startWorldSession(session)
            val inv = engine.worldScene.player.inventory
            inv.setSlot(3, ItemStack("tessellate:oak_log", 12))
            inv.setSlot(25, ItemStack("tessellate:oak_planks", 16))
            engine.saveAndQuitToWorldSelect()
        } finally {
            engine.worldScene.destroySession()
        }

        val reloadedEngine = TessellateEngine(context)
        awaitJob(reloadedEngine.initialSettingsLoadJob)
        try {
            val saves = WorldSaveManager.listSavedWorlds(context)
            val saved = saves.first { it.worldName == "InvPersistWorld" }
            reloadedEngine.loadSavedWorld(saved)

            val loadedInv = reloadedEngine.worldScene.player.inventory
            assertEquals("tessellate:oak_log", loadedInv.getSlot(3)?.itemId?.lowercase())
            assertEquals(12, loadedInv.getSlot(3)?.count)
            assertEquals("tessellate:oak_planks", loadedInv.getSlot(25)?.itemId?.lowercase())
            assertEquals(16, loadedInv.getSlot(25)?.count)
        } finally {
            reloadedEngine.worldScene.destroySession()
        }
    }

    private fun tapPoint(engine: TessellateEngine, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0)
        engine.onTouchEvent(down)
        engine.onTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun awaitJob(job: Job, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("Expected coroutine Job to finish within ${timeoutMs}ms", job.isCompleted)
    }
}
