package com.nitrous.tessellate.texture

/**
 * Built-in asset texture identifiers and asset path helpers.
 */
object TextureConstants {
    const val PROTO_GRASS = "proto_grass"
    const val PROTO_GRASS_SIDE = "proto_grass_side"
    const val PROTO_DIRT = "proto_dirt"
    const val PROTO_STONE = "proto_stone"
    const val PROTO_DEBUG_BUG = "proto_debugbug"
    const val PROTO_GLASS = "proto_glass"

    // World block textures (16x16)
    const val BLOCK_GLASS = "block_glass"
    const val BLOCK_SAND = "block_sand"
    const val BLOCK_COBBLESTONE = "block_cobblestone"
    const val BLOCK_STONE = "block_stone"
    const val BLOCK_OAK_LOG = "block_oak_log"
    const val BLOCK_LEAVES = "block_leaves"
    const val BLOCK_OAK_PLANKS = "block_oak_planks"

    // On-screen directional D-pad and action controller textures (128x128)
    const val PROTO_BASE_CONTR = "proto_base_contr"
    const val PROTO_CONTR_UP = "proto_contr_up"
    const val PROTO_CONTR_DOWN = "proto_contr_down"
    const val PROTO_CONTR_LEFT = "proto_contr_left"
    const val PROTO_CONTR_RIGHT = "proto_contr_right"
    const val PROTO_CONTR_CROUCH = "proto_contr_crouch"
    const val PROTO_CONTR_JUMP = "proto_contr_jump"

    // GUI widget atlas, inventory GUI texture, and bitmap font sheet (Nearest-Neighbor)
    const val UI_GUI_WIDGETS = "UI_gui_widgets"
    const val UI_GUI_INVENTORY = "UI_gui_inventory"
    const val UI_FONT_ASCII = "UI_font_ascii"

    val ALL_PROTOTYPE_IDS = listOf(
        PROTO_GRASS,
        PROTO_GRASS_SIDE,
        PROTO_DIRT,
        PROTO_STONE,
        PROTO_DEBUG_BUG
    )

    val ALL_BLOCK_IDS = listOf(
        BLOCK_GLASS,
        BLOCK_SAND,
        BLOCK_COBBLESTONE,
        BLOCK_STONE
    )

    val ALL_CONTROLLER_IDS = listOf(
        PROTO_BASE_CONTR,
        PROTO_CONTR_UP,
        PROTO_CONTR_DOWN,
        PROTO_CONTR_LEFT,
        PROTO_CONTR_RIGHT,
        PROTO_CONTR_CROUCH,
        PROTO_CONTR_JUMP
    )

    val ALL_UI_IDS = listOf(
        UI_GUI_WIDGETS,
        UI_GUI_INVENTORY,
        UI_FONT_ASCII
    )

    val ALL_ASSET_IDS = ALL_PROTOTYPE_IDS + ALL_BLOCK_IDS + ALL_CONTROLLER_IDS + ALL_UI_IDS

    fun getAssetPath(id: String): String {
        return "textures/$id.png"
    }

    fun getDescription(id: String): String = when (id) {
        PROTO_GRASS -> "Grass block top"
        PROTO_GRASS_SIDE -> "Grass block sides"
        PROTO_DIRT -> "Dirt block (and grass bottom)"
        PROTO_STONE -> "Prototype stone block"
        PROTO_DEBUG_BUG -> "Colored-noise debug texture with yellow/magenta border"
        BLOCK_GLASS -> "Transparent glass block"
        BLOCK_SAND -> "Sand block"
        BLOCK_COBBLESTONE -> "Cobblestone block"
        BLOCK_STONE -> "Stone block"
        BLOCK_OAK_LOG -> "Oak log bark block"
        BLOCK_LEAVES -> "Oak leaves canopy block"
        BLOCK_OAK_PLANKS -> "Oak planks wooden block"
        PROTO_CONTR_JUMP -> "Jump button controller texture"
        UI_GUI_WIDGETS -> "UI widgets texture atlas"
        UI_GUI_INVENTORY -> "Inventory & crafting GUI texture atlas"
        UI_FONT_ASCII -> "Data-driven UI bitmap font atlas"
        else -> "Custom / Unknown texture"
    }
}
