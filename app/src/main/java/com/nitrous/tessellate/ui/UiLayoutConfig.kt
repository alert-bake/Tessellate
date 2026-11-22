package com.nitrous.tessellate.ui

import android.content.Context
import android.util.Log
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Normalized [0..1] UV rectangle inside UI_gui_widgets.png.
 */
@JsonClass(generateAdapter = true)
data class UiUvRect(
    val u1: Float = 0f,
    val v1: Float = 0f,
    val u2: Float = 1f,
    val v2: Float = 1f
)

/**
 * Hotbar layout & 9-slice border configuration loaded from ui_layout.json.
 */
@JsonClass(generateAdapter = true)
data class HotbarUiLayout(
    val background: UiUvRect = UiUvRect(0.0f, 0.0f, 0.7109375f, 0.0859375f),
    val selection: UiUvRect = UiUvRect(0.0f, 0.0859375f, 0.09375f, 0.1796875f),
    val sliceU: Float = 0.01171875f,
    val sliceV: Float = 0.01171875f
)

/**
 * Button bar 9-slice UV configuration from UI_gui_widgets.png (200x20 bars at y=46, 66, 86).
 */
@JsonClass(generateAdapter = true)
data class ButtonUiLayout(
    val disabled: UiUvRect = UiUvRect(0.0f, 0.1796875f, 0.78125f, 0.2578125f),
    val normal: UiUvRect = UiUvRect(0.0f, 0.2578125f, 0.78125f, 0.3359375f),
    val hovered: UiUvRect = UiUvRect(0.0f, 0.3359375f, 0.78125f, 0.4140625f),
    val sliceU: Float = 0.01171875f,
    val sliceV: Float = 0.01171875f
)

/**
 * Pixel region inside the inventory panel sprite (in texture/panel pixels).
 */
@JsonClass(generateAdapter = true)
data class InventoryPatchSpec(
    val x: Float = 94f,
    val y: Float = 14f,
    val width: Float = 40f,
    val height: Float = 42f
)

/**
 * Grid layout specification for inventory slot grids in texture/panel pixels.
 */
@JsonClass(generateAdapter = true)
data class InventoryGridSpec(
    val startX: Float = 7f,
    val startY: Float = 83f,
    val slotStride: Float = 18f,
    val innerInset: Float = 1f,
    val innerSize: Float = 16f,
    val cols: Int = 9,
    val rows: Int = 3
)

/**
 * Single slot coordinate specification in texture/panel pixels.
 */
@JsonClass(generateAdapter = true)
data class InventorySlotSpec(
    val x: Float = 153f,
    val y: Float = 27f,
    val slotSize: Float = 18f,
    val innerInset: Float = 1f,
    val innerSize: Float = 16f
)

/**
 * Data-driven Inventory GUI layout & UVs mapping `UI_gui_inventory.png` (256x256).
 */
@JsonClass(generateAdapter = true)
data class InventoryUiLayout(
    val panelWidth: Float = 176f,
    val panelHeight: Float = 166f,
    val panel: UiUvRect = UiUvRect(0.0f, 0.0f, 0.6875f, 0.6484375f),
    val slot: UiUvRect = UiUvRect(0.02734375f, 0.32421875f, 0.09765625f, 0.39453125f),
    val panelFill: UiUvRect = UiUvRect(0.546875f, 0.1953125f, 0.65625f, 0.296875f),
    val craftingPatch: InventoryPatchSpec = InventoryPatchSpec(94f, 14f, 40f, 42f),
    val craftingGrid: InventoryGridSpec = InventoryGridSpec(
        startX = 78f, startY = 6f, slotStride = 18f, innerInset = 1f, innerSize = 16f, cols = 3, rows = 3
    ),
    val craftingOutput: InventorySlotSpec = InventorySlotSpec(
        x = 153f, y = 27f, slotSize = 18f, innerInset = 1f, innerSize = 16f
    ),
    val mainInventory: InventoryGridSpec = InventoryGridSpec(
        startX = 7f, startY = 83f, slotStride = 18f, innerInset = 1f, innerSize = 16f, cols = 9, rows = 3
    ),
    val hotbar: InventoryGridSpec = InventoryGridSpec(
        startX = 7f, startY = 141f, slotStride = 18f, innerInset = 1f, innerSize = 16f, cols = 9, rows = 1
    )
)

/**
 * Root data-driven UI definition parsed from ui_layout.json.
 */
@JsonClass(generateAdapter = true)
data class UiLayoutConfig(
    val hotbar: HotbarUiLayout = HotbarUiLayout(),
    val buttons: ButtonUiLayout = ButtonUiLayout(),
    val inventory: InventoryUiLayout = InventoryUiLayout()
)

/**
 * Loads [UiLayoutConfig] from modder override in `filesDir/ui_layout.json` if present,
 * otherwise from bundled `assets/ui_layout.json`, falling back safely to defaults.
 */
object UiLayoutLoader {
    private const val TAG = "Tessellate.UiLayout"
    const val LAYOUT_FILE_NAME = "ui_layout.json"

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(UiLayoutConfig::class.java)

    fun load(context: Context): UiLayoutConfig {
        val overrideFile = File(context.filesDir, LAYOUT_FILE_NAME)
        if (overrideFile.exists() && overrideFile.isFile) {
            try {
                parseJson(overrideFile.readText(), "filesDir/$LAYOUT_FILE_NAME")?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read override $LAYOUT_FILE_NAME; falling back.", e)
            }
        }

        return try {
            val json = context.assets.open(LAYOUT_FILE_NAME).bufferedReader().use { it.readText() }
            parseJson(json, "assets/$LAYOUT_FILE_NAME") ?: UiLayoutConfig()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open $LAYOUT_FILE_NAME from assets; using default layout.", e)
            UiLayoutConfig()
        }
    }

    fun parseJson(json: String, sourceLabel: String = "json"): UiLayoutConfig? {
        return try {
            if (json.isBlank()) return null
            val parsed = adapter.fromJson(json) ?: return null
            sanitize(parsed)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed UI layout in $sourceLabel; falling back.", e)
            null
        }
    }

    private fun sanitize(config: UiLayoutConfig): UiLayoutConfig {
        val hotbarDefaults = HotbarUiLayout()
        val bg = sanitizeRect(config.hotbar.background, hotbarDefaults.background)
        val sel = sanitizeRect(config.hotbar.selection, hotbarDefaults.selection)
        val bgSpanU = (bg.u2 - bg.u1).coerceAtLeast(1e-4f)
        val bgSpanV = (bg.v2 - bg.v1).coerceAtLeast(1e-4f)
        val su = if (config.hotbar.sliceU.isFinite()) config.hotbar.sliceU.coerceIn(0f, bgSpanU * 0.45f) else hotbarDefaults.sliceU
        val sv = if (config.hotbar.sliceV.isFinite()) config.hotbar.sliceV.coerceIn(0f, bgSpanV * 0.45f) else hotbarDefaults.sliceV

        val btnDefaults = ButtonUiLayout()
        val dis = sanitizeRect(config.buttons.disabled, btnDefaults.disabled)
        val norm = sanitizeRect(config.buttons.normal, btnDefaults.normal)
        val hov = sanitizeRect(config.buttons.hovered, btnDefaults.hovered)
        val btnSpanU = (norm.u2 - norm.u1).coerceAtLeast(1e-4f)
        val btnSpanV = (norm.v2 - norm.v1).coerceAtLeast(1e-4f)
        val bsu = if (config.buttons.sliceU.isFinite()) config.buttons.sliceU.coerceIn(0f, btnSpanU * 0.45f) else btnDefaults.sliceU
        val bsv = if (config.buttons.sliceV.isFinite()) config.buttons.sliceV.coerceIn(0f, btnSpanV * 0.45f) else btnDefaults.sliceV

        val invDefaults = InventoryUiLayout()
        val inv = config.inventory
        val panelW = if (inv.panelWidth.isFinite() && inv.panelWidth > 16f) inv.panelWidth else invDefaults.panelWidth
        val panelH = if (inv.panelHeight.isFinite() && inv.panelHeight > 16f) inv.panelHeight else invDefaults.panelHeight
        val sanitizedInv = InventoryUiLayout(
            panelWidth = panelW,
            panelHeight = panelH,
            panel = sanitizeRect(inv.panel, invDefaults.panel),
            slot = sanitizeRect(inv.slot, invDefaults.slot),
            panelFill = sanitizeRect(inv.panelFill, invDefaults.panelFill),
            craftingPatch = InventoryPatchSpec(
                x = if (inv.craftingPatch.x.isFinite()) inv.craftingPatch.x else invDefaults.craftingPatch.x,
                y = if (inv.craftingPatch.y.isFinite()) inv.craftingPatch.y else invDefaults.craftingPatch.y,
                width = if (inv.craftingPatch.width.isFinite() && inv.craftingPatch.width >= 0f) inv.craftingPatch.width else invDefaults.craftingPatch.width,
                height = if (inv.craftingPatch.height.isFinite() && inv.craftingPatch.height >= 0f) inv.craftingPatch.height else invDefaults.craftingPatch.height
            ),
            craftingGrid = sanitizeGrid(inv.craftingGrid, invDefaults.craftingGrid),
            craftingOutput = InventorySlotSpec(
                x = if (inv.craftingOutput.x.isFinite()) inv.craftingOutput.x else invDefaults.craftingOutput.x,
                y = if (inv.craftingOutput.y.isFinite()) inv.craftingOutput.y else invDefaults.craftingOutput.y,
                slotSize = if (inv.craftingOutput.slotSize.isFinite() && inv.craftingOutput.slotSize > 2f) inv.craftingOutput.slotSize else invDefaults.craftingOutput.slotSize,
                innerInset = if (inv.craftingOutput.innerInset.isFinite() && inv.craftingOutput.innerInset >= 0f) inv.craftingOutput.innerInset else invDefaults.craftingOutput.innerInset,
                innerSize = if (inv.craftingOutput.innerSize.isFinite() && inv.craftingOutput.innerSize > 2f) inv.craftingOutput.innerSize else invDefaults.craftingOutput.innerSize
            ),
            mainInventory = sanitizeGrid(inv.mainInventory, invDefaults.mainInventory),
            hotbar = sanitizeGrid(inv.hotbar, invDefaults.hotbar)
        )

        return UiLayoutConfig(
            hotbar = HotbarUiLayout(
                background = bg,
                selection = sel,
                sliceU = su,
                sliceV = sv
            ),
            buttons = ButtonUiLayout(
                disabled = dis,
                normal = norm,
                hovered = hov,
                sliceU = bsu,
                sliceV = bsv
            ),
            inventory = sanitizedInv
        )
    }

    private fun sanitizeGrid(grid: InventoryGridSpec, fallback: InventoryGridSpec): InventoryGridSpec {
        return InventoryGridSpec(
            startX = if (grid.startX.isFinite()) grid.startX else fallback.startX,
            startY = if (grid.startY.isFinite()) grid.startY else fallback.startY,
            slotStride = if (grid.slotStride.isFinite() && grid.slotStride > 2f) grid.slotStride else fallback.slotStride,
            innerInset = if (grid.innerInset.isFinite() && grid.innerInset >= 0f) grid.innerInset else fallback.innerInset,
            innerSize = if (grid.innerSize.isFinite() && grid.innerSize > 2f) grid.innerSize else fallback.innerSize,
            cols = grid.cols.coerceIn(1, 18),
            rows = grid.rows.coerceIn(1, 9)
        )
    }

    private fun sanitizeRect(rect: UiUvRect, fallback: UiUvRect): UiUvRect {
        if (!rect.u1.isFinite() || !rect.v1.isFinite() || !rect.u2.isFinite() || !rect.v2.isFinite()) {
            return fallback
        }
        val u1 = rect.u1.coerceIn(0f, 1f)
        val v1 = rect.v1.coerceIn(0f, 1f)
        val u2 = rect.u2.coerceIn(0f, 1f)
        val v2 = rect.v2.coerceIn(0f, 1f)
        return if (u2 > u1 && v2 > v1) UiUvRect(u1, v1, u2, v2) else fallback
    }
}
