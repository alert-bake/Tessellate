package com.nitrous.tessellate.ui

import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.graphics.Shader

/**
 * In-game Pause Menu overlay rendered when [TessellateEngine.isPaused] is true in [AppState.PLAYING].
 * Provides Resume, Settings, and "Save and Quit" (saves and destroys the session, returning to [AppState.WORLD_SELECT]).
 */
class PauseScreen(
    private val engine: TessellateEngine
) {
    val resumeButton = UiButton(
        id = MenuButtonId.PAUSE_RESUME,
        label = "Resume",
        enabled = true
    )

    val settingsButton = UiButton(
        id = MenuButtonId.PAUSE_SETTINGS,
        label = "Settings",
        enabled = true
    )

    val saveAndQuitButton = UiButton(
        id = MenuButtonId.PAUSE_SAVE_AND_QUIT,
        label = "Save and Quit",
        enabled = true
    )

    val buttons: List<UiButton> = listOf(
        resumeButton,
        settingsButton,
        saveAndQuitButton
    )

    var titleCenterX: Float = 960f
        private set
    var titleCenterY: Float = 150f
        private set
    var titleScale: Int = 4
        private set
    var buttonFontScale: Int = 2
        private set

    fun onSurfaceChanged(width: Int, height: Int) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        titleScale = if (h >= 720f && w >= 1000f) 4 else 3
        titleCenterX = w * 0.5f
        titleCenterY = (h * 0.17f).coerceAtLeast(52f)

        val btnH = (h * 0.095f).coerceIn(52f, 76f)
        val btnW = (w * 0.36f).coerceIn(320f, 540f)
        val gap = (h * 0.028f).coerceIn(14f, 24f)
        buttonFontScale = if (btnW >= 420f && btnH >= 64f) 3 else 2

        val totalStackH = buttons.size * btnH + (buttons.size - 1) * gap
        val startY = ((h - totalStackH) * 0.54f).coerceAtLeast(titleCenterY + 54f)
        val left = (w - btnW) * 0.5f

        for (i in buttons.indices) {
            val top = startY + i * (btnH + gap)
            buttons[i].rect.set(left, top, left + btnW, top + btnH)
        }
    }

    fun clearHoverStates() {
        buttons.forEach { it.isHovered = false }
    }

    fun handleButtonClick(id: MenuButtonId): Boolean {
        return when (id) {
            MenuButtonId.PAUSE_RESUME -> {
                engine.setPaused(false)
                true
            }
            MenuButtonId.PAUSE_SETTINGS -> {
                engine.openSettingsFrom(AppState.PLAYING)
                true
            }
            MenuButtonId.PAUSE_SAVE_AND_QUIT -> {
                engine.saveAndQuitToWorldSelect()
                true
            }
            else -> false
        }
    }

    fun render(
        shader: Shader,
        orthoMatrix: FloatArray,
        quadVaoId: Int,
        fontRenderer: BitmapFontRenderer,
        drawButtonFn: (UiButton, Int) -> Unit
    ) {
        fontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = "PAUSED",
            centerX = titleCenterX,
            centerY = titleCenterY,
            scale = titleScale,
            r = 0.96f,
            g = 0.98f,
            b = 0.96f,
            a = 1f,
            shadow = true
        )

        val session = engine.worldScene.currentSession
        if (session != null) {
            fontRenderer.drawTextCentered(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = "${session.name} - ${session.gamemode.displayName} (${session.worldType.displayName})",
                centerX = titleCenterX,
                centerY = titleCenterY + fontRenderer.measureHeight(titleScale) * 0.95f,
                scale = 2,
                r = 0.78f,
                g = 0.85f,
                b = 0.80f,
                a = 0.95f,
                shadow = true
            )
        }

        for (button in buttons) {
            drawButtonFn(button, buttonFontScale)
        }
    }
}
