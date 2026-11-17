package com.nitrous.tessellate.ui

import android.graphics.RectF

/**
 * Visual interaction states corresponding to the three bar textures in UI_gui_widgets.png.
 */
enum class UiButtonState {
    NORMAL,
    HOVERED,
    DISABLED
}

/**
 * Identifiers for menu, world selection, world creation, pause, and settings buttons.
 */
enum class MenuButtonId {
    SINGLEPLAYER,
    MULTIPLAYER,
    SETTINGS,
    QUIT,
    SETTINGS_FOV_MINUS,
    SETTINGS_FOV_PLUS,
    SETTINGS_BACK,
    SETTINGS_DYNAMIC,
    WORLD_SELECT_ENTRY_0,
    WORLD_SELECT_ENTRY_1,
    WORLD_SELECT_ENTRY_2,
    WORLD_SELECT_ENTRY_3,
    WORLD_SELECT_ENTRY_4,
    WORLD_SELECT_PLAY,
    WORLD_SELECT_CREATE_NEW,
    WORLD_SELECT_DELETE,
    WORLD_SELECT_BACK,
    CREATE_WORLD_GAMEMODE,
    CREATE_WORLD_TYPE,
    CREATE_WORLD_CREATE,
    CREATE_WORLD_CANCEL,
    PAUSE_RESUME,
    PAUSE_SETTINGS,
    PAUSE_SAVE_AND_QUIT
}

/**
 * Interactive textured UI button backed by UI_gui_widgets.png bars and BitmapFontRenderer labels.
 */
data class UiButton(
    val id: MenuButtonId,
    var label: String,
    val rect: RectF = RectF(),
    var enabled: Boolean = true,
    var isHovered: Boolean = false,
    var isSelected: Boolean = false,
    var actionKey: String? = null
) {
    val visualState: UiButtonState
        get() = when {
            !enabled -> UiButtonState.DISABLED
            isHovered || isSelected -> UiButtonState.HOVERED
            else -> UiButtonState.NORMAL
        }
}
