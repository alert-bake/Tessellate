package com.nitrous.tessellate.ui

import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.item.CraftingRecipe
import com.nitrous.tessellate.item.Inventory
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.item.RecipeRegistry
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full OpenGL 2D inventory & 3x3 crafting overlay rendered during `AppState.INVENTORY`.
 *
 * Maps exact pixel/UV coordinates from `UI_gui_inventory.png` via `ui_layout.json`,
 * keeps its bottom hotbar aligned with the HUD hotbar, and supports both tap-to-pick/place
 * and single-gesture drag-and-drop alongside darkened-scrim tap-to-close.
 */
class InventoryScreen(
    private val engine: TessellateEngine
) {
    enum class SlotSection {
        CRAFTING_GRID,
        CRAFTING_OUTPUT,
        MAIN_INVENTORY,
        HOTBAR
    }

    data class SlotTarget(
        val section: SlotSection,
        val index: Int
    )

    data class SlotVisualRects(
        val target: SlotTarget,
        val outerRect: RectF,
        val innerRect: RectF
    )

    data class LayoutSnapshot(
        val screenWidth: Int = 1920,
        val screenHeight: Int = 1080,
        val scale: Float = 4f,
        val panelRect: RectF = RectF(),
        val dismissSafeRect: RectF = RectF(),
        val hudHotspotRect: RectF = RectF(),
        val craftingPatchRect: RectF = RectF(),
        val craftingGridSlots: List<SlotVisualRects> = emptyList(),
        val craftingOutputSlot: SlotVisualRects? = null,
        val mainInventorySlots: List<SlotVisualRects> = emptyList(),
        val hotbarSlots: List<SlotVisualRects> = emptyList()
    )

    @Volatile
    private var layoutSnapshot = LayoutSnapshot()

    private val craftLock = Any()
    private val craftingGrid = arrayOfNulls<ItemStack>(CRAFTING_GRID_SIZE)

    @Volatile
    var matchedRecipe: CraftingRecipe? = null
        private set

    @Volatile
    var heldItem: ItemStack? = null
        private set

    @Volatile
    var cursorX: Float = 0f
        private set

    @Volatile
    var cursorY: Float = 0f
        private set

    private var activePointerId: Int? = null
    private var openedAtMs: Long = 0L
    private var pressedOutsidePanel: Boolean = false
    private var pressedSlot: SlotTarget? = null
    private var pickedUpOnDown: Boolean = false
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var hasDragged: Boolean = false

    val panelRect: RectF
        get() = RectF(layoutSnapshot.panelRect)

    val currentLayoutScale: Float
        get() = layoutSnapshot.scale

    fun getSnapshot(): LayoutSnapshot = layoutSnapshot

    fun getCraftingSlotRects(): List<RectF> = layoutSnapshot.craftingGridSlots.map { RectF(it.outerRect) }
    fun getCraftingOutputSlotRect(): RectF? = layoutSnapshot.craftingOutputSlot?.outerRect?.let { RectF(it) }
    fun getMainInventorySlotRects(): List<RectF> = layoutSnapshot.mainInventorySlots.map { RectF(it.outerRect) }
    fun getHotbarSlotRects(): List<RectF> = layoutSnapshot.hotbarSlots.map { RectF(it.outerRect) }

    fun getCraftingGridSlot(index: Int): ItemStack? = synchronized(craftLock) {
        if (index !in craftingGrid.indices) return null
        return craftingGrid[index]?.let { ItemStack(it.itemId, it.count) }
    }

    fun setCraftingGridSlot(index: Int, stack: ItemStack?) {
        synchronized(craftLock) {
            if (index !in craftingGrid.indices) return
            craftingGrid[index] = if (stack != null && stack.count > 0) ItemStack(stack.itemId, stack.count) else null
            recomputeRecipeLocked()
        }
    }

    fun getCraftResultPreview(): ItemStack? = synchronized(craftLock) {
        val recipe = matchedRecipe ?: return null
        return ItemStack(recipe.resultItemId, recipe.resultCount)
    }

    fun onSurfaceChanged(
        width: Int,
        height: Int,
        invLayout: InventoryUiLayout,
        hudHotbarBounds: RectF,
        hudHotbarSlots: List<RectF>,
        hudHotbarScale: Float,
        hudInventoryHotspotRect: RectF = RectF()
    ) {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val panelW = invLayout.panelWidth.coerceAtLeast(32f)
        val panelH = invLayout.panelHeight.coerceAtLeast(32f)

        val hudCenterX = if (!hudHotbarBounds.isEmpty) hudHotbarBounds.centerX() else safeW * 0.5f
        val hudCenterY = if (!hudHotbarBounds.isEmpty) hudHotbarBounds.centerY() else safeH - 60f
        val hudSlotStride = if (hudHotbarSlots.isNotEmpty()) {
            hudHotbarSlots[0].width()
        } else {
            20f * hudHotbarScale
        }

        val hotbarStridePx = invLayout.hotbar.slotStride.coerceAtLeast(4f)
        val desiredScale = hudSlotStride / hotbarStridePx
        val maxScaleW = (safeW - 24f).coerceAtLeast(64f) / panelW
        val maxScaleH = (safeH - 24f).coerceAtLeast(64f) / panelH
        val scale = minOf(desiredScale, maxScaleW, maxScaleH).coerceAtLeast(1f)

        val scaledW = panelW * scale
        val scaledH = panelH * scale

        val hotbarPanelCenterOffsetX =
            invLayout.hotbar.startX + (invLayout.hotbar.cols * invLayout.hotbar.slotStride) * 0.5f
        val hotbarPanelCenterOffsetY =
            invLayout.hotbar.startY + invLayout.hotbar.slotStride * 0.5f

        val maxLeft = (safeW - scaledW - 8f).coerceAtLeast(8f)
        val maxTop = (safeH - scaledH - 8f).coerceAtLeast(8f)
        val panelLeft = (hudCenterX - hotbarPanelCenterOffsetX * scale).coerceIn(8f, maxLeft)
        val panelTop = (hudCenterY - hotbarPanelCenterOffsetY * scale).coerceIn(8f, maxTop)
        val pRect = RectF(panelLeft, panelTop, panelLeft + scaledW, panelTop + scaledH)

        val patch = invLayout.craftingPatch
        val patchRect = RectF(
            panelLeft + patch.x * scale,
            panelTop + patch.y * scale,
            panelLeft + (patch.x + patch.width) * scale,
            panelTop + (patch.y + patch.height) * scale
        )

        val craftingSlots = buildGridSlots(
            section = SlotSection.CRAFTING_GRID,
            spec = invLayout.craftingGrid,
            panelLeft = panelLeft,
            panelTop = panelTop,
            scale = scale
        )
        val outSpec = invLayout.craftingOutput
        val outLeft = panelLeft + outSpec.x * scale
        val outTop = panelTop + outSpec.y * scale
        val outSize = outSpec.slotSize * scale
        val outInset = outSpec.innerInset * scale
        val outInner = outSpec.innerSize * scale
        val outSlot = SlotVisualRects(
            target = SlotTarget(SlotSection.CRAFTING_OUTPUT, 0),
            outerRect = RectF(outLeft, outTop, outLeft + outSize, outTop + outSize),
            innerRect = RectF(outLeft + outInset, outTop + outInset, outLeft + outInset + outInner, outTop + outInset + outInner)
        )
        val mainSlots = buildGridSlots(
            section = SlotSection.MAIN_INVENTORY,
            spec = invLayout.mainInventory,
            panelLeft = panelLeft,
            panelTop = panelTop,
            scale = scale
        )
        val hotbarSlots = buildGridSlots(
            section = SlotSection.HOTBAR,
            spec = invLayout.hotbar,
            panelLeft = panelLeft,
            panelTop = panelTop,
            scale = scale
        )

        val safeMargin = (10f * scale).coerceAtLeast(24f)
        val safeRect = RectF(
            pRect.left - safeMargin,
            pRect.top - safeMargin,
            pRect.right + safeMargin,
            pRect.bottom + safeMargin
        )

        layoutSnapshot = LayoutSnapshot(
            screenWidth = safeW,
            screenHeight = safeH,
            scale = scale,
            panelRect = pRect,
            dismissSafeRect = safeRect,
            hudHotspotRect = RectF(hudInventoryHotspotRect),
            craftingPatchRect = patchRect,
            craftingGridSlots = craftingSlots,
            craftingOutputSlot = outSlot,
            mainInventorySlots = mainSlots,
            hotbarSlots = hotbarSlots
        )
    }

    private fun buildGridSlots(
        section: SlotSection,
        spec: InventoryGridSpec,
        panelLeft: Float,
        panelTop: Float,
        scale: Float
    ): List<SlotVisualRects> {
        val list = ArrayList<SlotVisualRects>(spec.rows * spec.cols)
        val stride = spec.slotStride * scale
        val inset = spec.innerInset * scale
        val innerSize = spec.innerSize * scale
        var idx = 0
        for (row in 0 until spec.rows) {
            for (col in 0 until spec.cols) {
                val outerLeft = panelLeft + (spec.startX + col * spec.slotStride) * scale
                val outerTop = panelTop + (spec.startY + row * spec.slotStride) * scale
                list.add(
                    SlotVisualRects(
                        target = SlotTarget(section, idx),
                        outerRect = RectF(outerLeft, outerTop, outerLeft + stride, outerTop + stride),
                        innerRect = RectF(
                            outerLeft + inset,
                            outerTop + inset,
                            outerLeft + inset + innerSize,
                            outerTop + inset + innerSize
                        )
                    )
                )
                idx++
            }
        }
        return list
    }

    fun onOpened() {
        synchronized(craftLock) {
            val snap = layoutSnapshot
            openedAtMs = SystemClock.uptimeMillis()
            cursorX = snap.panelRect.centerX()
            cursorY = snap.panelRect.centerY()
            activePointerId = null
            pressedOutsidePanel = false
            pressedSlot = null
            pickedUpOnDown = false
            hasDragged = false
            recomputeRecipeLocked()
        }
    }

    /**
     * Safely returns any held cursor item and any unconsumed crafting grid items back to
     * the player's inventory (or drops true overflow into the world) before closing.
     */
    fun returnAllItemsAndReset() {
        val leftoversToDrop = mutableListOf<ItemStack>()
        val playerInv = engine.worldScene.player.inventory

        synchronized(craftLock) {
            heldItem?.let { stack ->
                if (stack.count > 0) {
                    val remaining = playerInv.addItem(stack.itemId, stack.count)
                    if (remaining > 0) {
                        leftoversToDrop.add(ItemStack(stack.itemId, remaining))
                    }
                }
                heldItem = null
            }

            for (i in craftingGrid.indices) {
                val stack = craftingGrid[i]
                if (stack != null && stack.count > 0) {
                    val remaining = playerInv.addItem(stack.itemId, stack.count)
                    if (remaining > 0) {
                        leftoversToDrop.add(ItemStack(stack.itemId, remaining))
                    }
                }
                craftingGrid[i] = null
            }
            matchedRecipe = null
            activePointerId = null
            pressedOutsidePanel = false
            pressedSlot = null
            pickedUpOnDown = false
            hasDragged = false
        }

        if (leftoversToDrop.isNotEmpty() && !engine.worldScene.world.isDestroyed) {
            val p = engine.worldScene.player
            for (dropStack in leftoversToDrop) {
                repeat(dropStack.count) {
                    val entity = com.nitrous.tessellate.entity.ItemDropEntity(
                        position = com.nitrous.tessellate.math.Vec3(p.posX, p.posY + 1.0f, p.posZ),
                        itemId = dropStack.itemId
                    )
                    engine.worldScene.world.spawnEntity(entity)
                }
            }
        }
        engine.worldScene.syncSelectedItemFromInventory()
        engine.uiRenderer.invalidateHud()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount <= 0) return false
        val action = event.actionMasked
        val actionIndex = event.actionIndex
        if (actionIndex !in 0 until event.pointerCount) return false
        val pointerId = event.getPointerId(actionIndex)

        val snap = layoutSnapshot

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId != null) return true
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                activePointerId = pointerId
                dragStartX = x
                dragStartY = y
                hasDragged = false
                synchronized(craftLock) {
                    cursorX = x
                    cursorY = y
                }

                if (!snap.panelRect.contains(x, y)) {
                    val elapsedSinceOpen = SystemClock.uptimeMillis() - openedAtMs
                    val inSafeZone = isInDismissExclusionZone(snap, x, y)
                    pressedOutsidePanel = !inSafeZone && elapsedSinceOpen >= OPEN_DISMISS_COOLDOWN_MS
                    pressedSlot = null
                    pickedUpOnDown = false
                    return true
                }

                pressedOutsidePanel = false
                val hit = findSlotAt(snap, x, y)
                pressedSlot = hit
                pickedUpOnDown = false

                if (hit != null) {
                    if (hit.section == SlotSection.CRAFTING_OUTPUT) {
                        handleCraftOutputTap()
                    } else {
                        pickedUpOnDown = handleNormalSlotPress(hit)
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val trackedId = activePointerId
                val idx = if (trackedId != null) event.findPointerIndex(trackedId) else 0
                if (idx in 0 until event.pointerCount) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    synchronized(craftLock) {
                        cursorX = x
                        cursorY = y
                    }
                    if (!hasDragged && hypot(x - dragStartX, y - dragStartY) > DRAG_SLOP_PX) {
                        hasDragged = true
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (pointerId != activePointerId) return true
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                synchronized(craftLock) {
                    cursorX = x
                    cursorY = y
                }

                val wasOutside = pressedOutsidePanel
                val wasPickedOnDown = pickedUpOnDown
                val didDrag = hasDragged
                val startSlot = pressedSlot

                activePointerId = null
                pressedOutsidePanel = false
                pressedSlot = null
                pickedUpOnDown = false
                hasDragged = false

                if (wasOutside) {
                    val elapsedSinceOpen = SystemClock.uptimeMillis() - openedAtMs
                    val dragDist = hypot(x - dragStartX, y - dragStartY)
                    if (!isInDismissExclusionZone(snap, x, y) &&
                        elapsedSinceOpen >= OPEN_DISMISS_COOLDOWN_MS &&
                        dragDist <= CLOSE_TAP_MAX_DRAG_PX
                    ) {
                        engine.closeInventory()
                    }
                    return true
                }

                if (wasPickedOnDown && didDrag) {
                    val releaseSlot = findSlotAt(snap, x, y)
                    if (releaseSlot != null &&
                        releaseSlot.section != SlotSection.CRAFTING_OUTPUT &&
                        releaseSlot != startSlot
                    ) {
                        placeHeldIntoSlot(releaseSlot)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = null
                pressedOutsidePanel = false
                pressedSlot = null
                pickedUpOnDown = false
                hasDragged = false
                return true
            }
        }
        return false
    }

    private fun handleCraftOutputTap(): Boolean {
        val playerInv = engine.worldScene.player.inventory
        var crafted = false
        synchronized(craftLock) {
            val recipe = matchedRecipe ?: return false
            val canAddToInventory = playerInv.canFit(recipe.resultItemId, recipe.resultCount)
            val currentHeld = heldItem
            val canAddToHeld = !canAddToInventory && canMergeIntoHeld(currentHeld, recipe.resultItemId, recipe.resultCount)

            if (!canAddToInventory && !canAddToHeld) {
                return false
            }
            if (!RecipeRegistry.consumeIngredients(craftingGrid, recipe)) {
                recomputeRecipeLocked()
                return false
            }

            if (canAddToInventory) {
                val leftover = playerInv.addItem(recipe.resultItemId, recipe.resultCount)
                if (leftover > 0) {
                    heldItem = mergeIntoStack(currentHeld, recipe.resultItemId, leftover)
                }
            } else {
                heldItem = mergeIntoStack(currentHeld, recipe.resultItemId, recipe.resultCount)
            }
            recomputeRecipeLocked()
            crafted = true
        }
        if (crafted) {
            engine.worldScene.syncSelectedItemFromInventory()
            engine.uiRenderer.invalidateHud()
        }
        return crafted
    }

    private fun canMergeIntoHeld(current: ItemStack?, itemId: String, amount: Int): Boolean {
        val maxStack = ItemRegistry.get(itemId)?.maxStackSize ?: Inventory.DEFAULT_MAX_STACK
        if (current == null) return amount <= maxStack
        return isSameItem(current.itemId, itemId) && (current.count + amount) <= maxStack
    }

    private fun mergeIntoStack(current: ItemStack?, itemId: String, amount: Int): ItemStack {
        return if (current == null) {
            ItemStack(itemId, amount)
        } else {
            ItemStack(current.itemId, current.count + amount)
        }
    }

    /**
     * Handles press on an interactive inventory/hotbar/crafting grid slot.
     * Returns true if an item was picked up from empty-hand state (so release after drag can drop it).
     */
    private fun handleNormalSlotPress(slot: SlotTarget): Boolean {
        var pickedUp = false
        synchronized(craftLock) {
            val held = heldItem
            val existing = getSlotStackLocked(slot)
            if (held == null) {
                if (existing != null && existing.count > 0) {
                    heldItem = ItemStack(existing.itemId, existing.count)
                    setSlotStackLocked(slot, null)
                    pickedUp = true
                }
            } else {
                placeHeldIntoSlotLocked(slot)
            }
            if (slot.section == SlotSection.CRAFTING_GRID) {
                recomputeRecipeLocked()
            }
        }
        if (slot.section == SlotSection.HOTBAR) {
            engine.worldScene.syncSelectedItemFromInventory()
            engine.uiRenderer.invalidateHud()
        }
        return pickedUp
    }

    private fun placeHeldIntoSlot(slot: SlotTarget) {
        synchronized(craftLock) {
            placeHeldIntoSlotLocked(slot)
            if (slot.section == SlotSection.CRAFTING_GRID) {
                recomputeRecipeLocked()
            }
        }
        if (slot.section == SlotSection.HOTBAR) {
            engine.worldScene.syncSelectedItemFromInventory()
            engine.uiRenderer.invalidateHud()
        }
    }

    private fun placeHeldIntoSlotLocked(slot: SlotTarget) {
        val held = heldItem ?: return
        val existing = getSlotStackLocked(slot)
        if (existing == null || existing.count <= 0) {
            setSlotStackLocked(slot, ItemStack(held.itemId, held.count))
            heldItem = null
            return
        }

        if (isSameItem(existing.itemId, held.itemId)) {
            val maxStack = (ItemRegistry.get(existing.itemId)?.maxStackSize ?: Inventory.DEFAULT_MAX_STACK).coerceAtLeast(1)
            val space = (maxStack - existing.count).coerceAtLeast(0)
            if (space > 0) {
                val moved = minOf(space, held.count)
                setSlotStackLocked(slot, ItemStack(existing.itemId, existing.count + moved))
                val rem = held.count - moved
                heldItem = if (rem > 0) ItemStack(held.itemId, rem) else null
                return
            }
        }

        // Different item or full matching stack -> swap
        setSlotStackLocked(slot, ItemStack(held.itemId, held.count))
        heldItem = ItemStack(existing.itemId, existing.count)
    }

    private fun getSlotStackLocked(slot: SlotTarget): ItemStack? {
        val playerInv = engine.worldScene.player.inventory
        return when (slot.section) {
            SlotSection.CRAFTING_GRID -> craftingGrid.getOrNull(slot.index)
            SlotSection.CRAFTING_OUTPUT -> matchedRecipe?.let { ItemStack(it.resultItemId, it.resultCount) }
            SlotSection.MAIN_INVENTORY -> playerInv.getSlot(Inventory.HOTBAR_SIZE + slot.index)
            SlotSection.HOTBAR -> playerInv.getSlot(slot.index)
        }
    }

    private fun setSlotStackLocked(slot: SlotTarget, stack: ItemStack?) {
        val playerInv = engine.worldScene.player.inventory
        when (slot.section) {
            SlotSection.CRAFTING_GRID -> {
                if (slot.index in craftingGrid.indices) {
                    craftingGrid[slot.index] = stack
                }
            }
            SlotSection.CRAFTING_OUTPUT -> Unit
            SlotSection.MAIN_INVENTORY -> {
                playerInv.setSlot(Inventory.HOTBAR_SIZE + slot.index, stack)
            }
            SlotSection.HOTBAR -> {
                playerInv.setSlot(slot.index, stack)
            }
        }
    }

    private fun recomputeRecipeLocked() {
        matchedRecipe = RecipeRegistry.findMatch(craftingGrid)
    }

    private fun findSlotAt(snap: LayoutSnapshot, x: Float, y: Float): SlotTarget? {
        for (s in snap.craftingGridSlots) {
            if (s.outerRect.contains(x, y)) return s.target
        }
        snap.craftingOutputSlot?.let { out ->
            if (out.outerRect.contains(x, y)) return out.target
        }
        for (s in snap.mainInventorySlots) {
            if (s.outerRect.contains(x, y)) return s.target
        }
        for (s in snap.hotbarSlots) {
            if (s.outerRect.contains(x, y)) return s.target
        }
        return null
    }

    private fun isInDismissExclusionZone(snap: LayoutSnapshot, x: Float, y: Float): Boolean {
        if (snap.panelRect.contains(x, y) || snap.dismissSafeRect.contains(x, y)) return true
        if (!snap.hudHotspotRect.isEmpty && snap.hudHotspotRect.contains(x, y)) return true
        return false
    }

    private fun isSameItem(a: String, b: String): Boolean {
        val idA = com.nitrous.tessellate.registry.Identifier.parseOrNull(a)?.normalizedString ?: a.lowercase()
        val idB = com.nitrous.tessellate.registry.Identifier.parseOrNull(b)?.normalizedString ?: b.lowercase()
        return idA == idB
    }

    fun render(
        shader: Shader,
        orthoMatrix: FloatArray,
        quadVaoId: Int,
        fontRenderer: BitmapFontRenderer,
        invTexHandle: Int,
        invLayout: InventoryUiLayout,
        resolveItemTexHandleFn: (String) -> Int,
        drawQuadFn: (Float, Float, Float, Float, Float, Float, Float, Float) -> Unit,
        drawOutlineFn: (Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit,
        drawTexturedSubQuadFn: (Int, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        val snap = layoutSnapshot

        // 1. Darkened full-screen scrim (tapping anywhere here closes inventory -> AppState.PLAYING)
        drawQuadFn(0f, 0f, snap.screenWidth.toFloat(), snap.screenHeight.toFloat(), 0.02f, 0.03f, 0.04f, 0.62f)

        val pRect = snap.panelRect
        if (pRect.isEmpty) return

        // 2. Inventory panel background texture (from UI_gui_inventory.png)
        if (invTexHandle != 0) {
            val panelUv = invLayout.panel
            drawTexturedSubQuadFn(
                invTexHandle,
                pRect.left, pRect.top, pRect.width(), pRect.height(),
                panelUv.u1, panelUv.v1, panelUv.u2, panelUv.v2,
                1f, 1f, 1f, 1f
            )

            // Patch over baked 2x2 grid cleanly using background swatch from UI_gui_inventory.png
            if (!snap.craftingPatchRect.isEmpty) {
                val fillUv = invLayout.panelFill
                drawTexturedSubQuadFn(
                    invTexHandle,
                    snap.craftingPatchRect.left,
                    snap.craftingPatchRect.top,
                    snap.craftingPatchRect.width(),
                    snap.craftingPatchRect.height(),
                    fillUv.u1, fillUv.v1, fillUv.u2, fillUv.v2,
                    1f, 1f, 1f, 1f
                )
            }

            // Draw 3x3 bevelled crafting grid slot tiles
            val slotUv = invLayout.slot
            for (slotRect in snap.craftingGridSlots) {
                drawTexturedSubQuadFn(
                    invTexHandle,
                    slotRect.outerRect.left,
                    slotRect.outerRect.top,
                    slotRect.outerRect.width(),
                    slotRect.outerRect.height(),
                    slotUv.u1, slotUv.v1, slotUv.u2, slotUv.v2,
                    1f, 1f, 1f, 1f
                )
            }
        } else {
            drawQuadFn(pRect.left, pRect.top, pRect.width(), pRect.height(), 0.65f, 0.74f, 0.65f, 1f)
        }

        // Snapshot mutable inventory/crafting/cursor state safely
        val gridSnap = arrayOfNulls<ItemStack>(CRAFTING_GRID_SIZE)
        val mainSnap = arrayOfNulls<ItemStack>(Inventory.MAIN_INVENTORY_SIZE)
        val hotbarSnap = arrayOfNulls<ItemStack>(Inventory.HOTBAR_SIZE)
        val outputSnap: ItemStack?
        val cursorHeldSnap: ItemStack?
        val cx: Float
        val cy: Float
        val activeSlotIdx: Int

        val playerInv = engine.worldScene.player.inventory
        activeSlotIdx = playerInv.selectedSlot
        for (i in 0 until Inventory.HOTBAR_SIZE) {
            hotbarSnap[i] = playerInv.getSlot(i)?.let { ItemStack(it.itemId, it.count) }
        }
        for (i in 0 until Inventory.MAIN_INVENTORY_SIZE) {
            mainSnap[i] = playerInv.getSlot(Inventory.HOTBAR_SIZE + i)?.let { ItemStack(it.itemId, it.count) }
        }

        synchronized(craftLock) {
            for (i in 0 until CRAFTING_GRID_SIZE) {
                gridSnap[i] = craftingGrid[i]?.let { ItemStack(it.itemId, it.count) }
            }
            outputSnap = matchedRecipe?.let { ItemStack(it.resultItemId, it.resultCount) }
            cursorHeldSnap = heldItem?.let { ItemStack(it.itemId, it.count) }
            cx = cursorX
            cy = cursorY
        }

        val digitScale = (snap.scale * 0.45f).roundToInt().coerceAtLeast(2)

        // Highlight active hotbar slot subtly
        if (activeSlotIdx in snap.hotbarSlots.indices) {
            val selOuter = snap.hotbarSlots[activeSlotIdx].outerRect
            drawOutlineFn(
                selOuter.left, selOuter.top, selOuter.width(), selOuter.height(),
                (snap.scale * 0.75f).coerceAtLeast(2f),
                0.35f, 0.90f, 0.35f, 0.95f
            )
        }

        // Highlight crafting output slot when a recipe is ready to collect
        if (outputSnap != null) {
            snap.craftingOutputSlot?.let { outSlot ->
                drawQuadFn(
                    outSlot.innerRect.left,
                    outSlot.innerRect.top,
                    outSlot.innerRect.width(),
                    outSlot.innerRect.height(),
                    0.35f, 0.95f, 0.45f, 0.20f
                )
                drawOutlineFn(
                    outSlot.outerRect.left,
                    outSlot.outerRect.top,
                    outSlot.outerRect.width(),
                    outSlot.outerRect.height(),
                    (snap.scale * 0.65f).coerceAtLeast(2f),
                    0.45f, 0.96f, 0.52f, 0.95f
                )
            }
        }

        // Highlight active pressed slot or hovered target slot when holding an item
        val highlightSlot = pressedSlot ?: if (cursorHeldSnap != null) findSlotAt(snap, cx, cy) else null
        if (highlightSlot != null && highlightSlot.section != SlotSection.CRAFTING_OUTPUT) {
            val rect = findVisualRectForTarget(snap, highlightSlot)?.innerRect
            if (rect != null) {
                drawQuadFn(rect.left, rect.top, rect.width(), rect.height(), 1f, 1f, 1f, 0.28f)
            }
        }

        // 3. Draw item icons + stack counts across crafting grid, output slot, main inventory, hotbar
        for (i in snap.craftingGridSlots.indices) {
            val stack = gridSnap[i] ?: continue
            drawSlotStack(
                stack = stack,
                innerRect = snap.craftingGridSlots[i].innerRect,
                digitScale = digitScale,
                scale = snap.scale,
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                fontRenderer = fontRenderer,
                resolveItemTexHandleFn = resolveItemTexHandleFn,
                drawTexturedSubQuadFn = drawTexturedSubQuadFn
            )
        }

        snap.craftingOutputSlot?.let { outSlot ->
            if (outputSnap != null) {
                drawSlotStack(
                    stack = outputSnap,
                    innerRect = outSlot.innerRect,
                    digitScale = digitScale,
                    scale = snap.scale,
                    shader = shader,
                    orthoMatrix = orthoMatrix,
                    quadVaoId = quadVaoId,
                    fontRenderer = fontRenderer,
                    resolveItemTexHandleFn = resolveItemTexHandleFn,
                    drawTexturedSubQuadFn = drawTexturedSubQuadFn
                )
            }
        }

        for (i in snap.mainInventorySlots.indices) {
            val stack = mainSnap.getOrNull(i) ?: continue
            drawSlotStack(
                stack = stack,
                innerRect = snap.mainInventorySlots[i].innerRect,
                digitScale = digitScale,
                scale = snap.scale,
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                fontRenderer = fontRenderer,
                resolveItemTexHandleFn = resolveItemTexHandleFn,
                drawTexturedSubQuadFn = drawTexturedSubQuadFn
            )
        }

        for (i in snap.hotbarSlots.indices) {
            val stack = hotbarSnap.getOrNull(i) ?: continue
            drawSlotStack(
                stack = stack,
                innerRect = snap.hotbarSlots[i].innerRect,
                digitScale = digitScale,
                scale = snap.scale,
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                fontRenderer = fontRenderer,
                resolveItemTexHandleFn = resolveItemTexHandleFn,
                drawTexturedSubQuadFn = drawTexturedSubQuadFn
            )
        }

        // Top banner hint / held item status pill cleanly above panel
        val hintScale = (snap.scale * 0.45f).roundToInt().coerceIn(2, 3)
        val bannerText = when {
            cursorHeldSnap != null && cursorHeldSnap.count > 0 -> {
                val itemName = ItemRegistry.get(cursorHeldSnap.itemId)?.displayName ?: cursorHeldSnap.itemId
                "Holding: $itemName x${cursorHeldSnap.count}"
            }
            outputSnap != null -> {
                val outName = ItemRegistry.get(outputSnap.itemId)?.displayName ?: outputSnap.itemId
                "Tap output to craft: $outName x${outputSnap.count}"
            }
            else -> "Tap outside to close"
        }
        val textW = fontRenderer.measureWidth(bannerText, hintScale)
        val textH = fontRenderer.measureHeight(hintScale)
        val pillW = textW + 24f
        val pillH = textH + 14f
        val pillY = (pRect.top - pillH - 10f).coerceAtLeast(10f)
        val pillX = (pRect.centerX() - pillW * 0.5f).coerceIn(12f, (snap.screenWidth - pillW - 12f).coerceAtLeast(12f))
        drawQuadFn(pillX, pillY, pillW, pillH, 0.05f, 0.08f, 0.07f, 0.82f)
        drawOutlineFn(pillX, pillY, pillW, pillH, 2f, 0.36f, 0.62f, 0.42f, 0.85f)
        fontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = bannerText,
            centerX = pillX + pillW * 0.5f,
            centerY = pillY + pillH * 0.5f,
            scale = hintScale,
            r = 0.94f, g = 0.98f, b = 0.94f, a = 1f,
            shadow = true
        )

        // 4. Floating cursor-held item on top with crisp dark framing badge
        if (cursorHeldSnap != null && cursorHeldSnap.count > 0) {
            val iconSize = (18f * snap.scale).coerceAtLeast(28f)
            val half = iconSize * 0.5f
            val cursorRect = RectF(cx - half, cy - half, cx + half, cy + half)
            drawQuadFn(cursorRect.left, cursorRect.top, cursorRect.width(), cursorRect.height(), 0.06f, 0.09f, 0.08f, 0.78f)
            drawOutlineFn(cursorRect.left, cursorRect.top, cursorRect.width(), cursorRect.height(), 2f, 0.85f, 0.95f, 0.55f, 0.95f)
            drawSlotStack(
                stack = cursorHeldSnap,
                innerRect = cursorRect,
                digitScale = digitScale,
                scale = snap.scale,
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                fontRenderer = fontRenderer,
                resolveItemTexHandleFn = resolveItemTexHandleFn,
                drawTexturedSubQuadFn = drawTexturedSubQuadFn
            )
        }
    }

    private fun drawSlotStack(
        stack: ItemStack,
        innerRect: RectF,
        digitScale: Int,
        scale: Float,
        shader: Shader,
        orthoMatrix: FloatArray,
        quadVaoId: Int,
        fontRenderer: BitmapFontRenderer,
        resolveItemTexHandleFn: (String) -> Int,
        drawTexturedSubQuadFn: (Int, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        if (stack.count <= 0) return
        val inset = scale * 0.75f
        val iconW = (innerRect.width() - inset * 2f).coerceAtLeast(4f)
        val iconH = (innerRect.height() - inset * 2f).coerceAtLeast(4f)
        val texHandle = resolveItemTexHandleFn(stack.itemId)

        drawTexturedSubQuadFn(
            texHandle,
            innerRect.left + inset,
            innerRect.top + inset,
            iconW,
            iconH,
            0f, 0f, 1f, 1f,
            1f, 1f, 1f, 1f
        )

        if (stack.count > 1) {
            fontRenderer.drawTextRightAligned(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = stack.count.toString(),
                rightX = innerRect.right - 0.5f * scale,
                bottomY = innerRect.bottom - 0.25f * scale,
                scale = digitScale,
                r = 1f, g = 1f, b = 1f, a = 1f,
                shadow = true
            )
        }
    }

    private fun findVisualRectForTarget(snap: LayoutSnapshot, target: SlotTarget): SlotVisualRects? {
        return when (target.section) {
            SlotSection.CRAFTING_GRID -> snap.craftingGridSlots.getOrNull(target.index)
            SlotSection.CRAFTING_OUTPUT -> snap.craftingOutputSlot
            SlotSection.MAIN_INVENTORY -> snap.mainInventorySlots.getOrNull(target.index)
            SlotSection.HOTBAR -> snap.hotbarSlots.getOrNull(target.index)
        }
    }

    companion object {
        const val CRAFTING_GRID_SIZE = 9
        const val OPEN_DISMISS_COOLDOWN_MS = 320L
        private const val DRAG_SLOP_PX = 14f
        private const val CLOSE_TAP_MAX_DRAG_PX = 48f
    }
}
