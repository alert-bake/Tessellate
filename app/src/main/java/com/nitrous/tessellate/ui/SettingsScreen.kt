package com.nitrous.tessellate.ui

import android.graphics.RectF
import android.view.MotionEvent
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.graphics.Shader
import kotlin.math.roundToInt

/**
 * Settings screen UI & persistence wiring.
 * Provides an interactive FOV slider + "-"/ "+" stepper buttons and a "Back" button,
 * persisting changes asynchronously through [TessellateEngine.settingsManager].
 */
class SettingsScreen(
    private val engine: TessellateEngine
) {
    val fovMinusButton = UiButton(
        id = MenuButtonId.SETTINGS_FOV_MINUS,
        label = "-",
        enabled = true
    )

    val fovPlusButton = UiButton(
        id = MenuButtonId.SETTINGS_FOV_PLUS,
        label = "+",
        enabled = true
    )

    val backButton = UiButton(
        id = MenuButtonId.SETTINGS_BACK,
        label = "Back",
        enabled = true
    )

    val buttons: List<UiButton> = listOf(fovMinusButton, fovPlusButton, backButton)

    val fovSliderRect = RectF()

    var titleCenterX: Float = 960f
        private set
    var titleCenterY: Float = 140f
        private set
    var titleScale: Int = 4
        private set
    var buttonFontScale: Int = 2
        private set

    private var isDraggingSlider = false

    fun onSurfaceChanged(width: Int, height: Int) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        titleScale = if (h >= 800f && w >= 1200f) 4 else 3
        buttonFontScale = if (h >= 800f && w >= 1200f) 3 else 2

        titleCenterX = w * 0.5f
        titleCenterY = (h * 0.16f).coerceAtLeast(64f)

        val rowHeight = (h * 0.095f).coerceIn(54f, 76f)
        val stepBtnSize = rowHeight.coerceAtLeast(56f)
        val totalRowWidth = (w * 0.52f).coerceIn(360f, 680f)
        val gap = (w * 0.015f).coerceIn(10f, 20f)
        val sliderWidth = (totalRowWidth - stepBtnSize * 2f - gap * 2f).coerceAtLeast(180f)

        val rowLeft = (w - (stepBtnSize * 2f + gap * 2f + sliderWidth)) * 0.5f
        val rowTop = (h * 0.38f).coerceAtLeast(titleCenterY + 64f)

        fovMinusButton.rect.set(rowLeft, rowTop, rowLeft + stepBtnSize, rowTop + rowHeight)

        val sliderLeft = fovMinusButton.rect.right + gap
        fovSliderRect.set(sliderLeft, rowTop, sliderLeft + sliderWidth, rowTop + rowHeight)

        val plusLeft = fovSliderRect.right + gap
        fovPlusButton.rect.set(plusLeft, rowTop, plusLeft + stepBtnSize, rowTop + rowHeight)

        val backWidth = (w * 0.30f).coerceIn(260f, 440f)
        val backLeft = (w - backWidth) * 0.5f
        val backTop = (rowTop + rowHeight + (h * 0.10f).coerceIn(32f, 72f))
            .coerceAtMost(h - rowHeight - 24f)
        backButton.rect.set(backLeft, backTop, backLeft + backWidth, backTop + rowHeight)
    }

    fun clearHoverStates() {
        buttons.forEach { it.isHovered = false }
        isDraggingSlider = false
    }

    fun handleButtonClick(id: MenuButtonId): Boolean {
        return when (id) {
            MenuButtonId.SETTINGS_FOV_MINUS -> {
                adjustFovBy(-FOV_STEP)
                true
            }
            MenuButtonId.SETTINGS_FOV_PLUS -> {
                adjustFovBy(FOV_STEP)
                true
            }
            MenuButtonId.SETTINGS_BACK -> {
                engine.navigateBackFromSettings()
                true
            }
            else -> false
        }
    }

    fun adjustFovBy(delta: Float) {
        setFov(engine.currentSettings.fov + delta)
    }

    fun setFov(targetFov: Float) {
        val clamped = targetFov.coerceIn(MIN_FOV, MAX_FOV).roundToInt().toFloat()
        if (clamped == engine.currentSettings.fov) return
        val updated = engine.currentSettings.copy(fov = clamped)
        engine.applySettings(updated)
        engine.settingsManager.saveSettings(updated)
    }

    fun handleSliderTouch(x: Float, y: Float, actionMasked: Int): Boolean {
        if (fovSliderRect.isEmpty) return false
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (fovSliderRect.contains(x, y)) {
                    isDraggingSlider = true
                    updateFovFromSliderX(x)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingSlider) {
                    updateFovFromSliderX(x)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDraggingSlider
                isDraggingSlider = false
                wasDragging
            }
            else -> false
        }
    }

    private fun updateFovFromSliderX(x: Float) {
        val fraction = ((x - fovSliderRect.left) / fovSliderRect.width()).coerceIn(0f, 1f)
        val newFov = MIN_FOV + fraction * (MAX_FOV - MIN_FOV)
        setFov(newFov)
    }

    fun render(
        shader: Shader,
        orthoMatrix: FloatArray,
        quadVaoId: Int,
        fontRenderer: BitmapFontRenderer,
        drawButtonFn: (UiButton, Int) -> Unit,
        drawSliderTrackFn: (RectF, Float) -> Unit
    ) {
        fontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = "SETTINGS",
            centerX = titleCenterX,
            centerY = titleCenterY,
            scale = titleScale,
            r = 0.95f,
            g = 0.96f,
            b = 0.98f,
            a = 1f
        )

        val currentFov = engine.currentSettings.fov.roundToInt()
        val fovFraction = ((engine.currentSettings.fov - MIN_FOV) / (MAX_FOV - MIN_FOV)).coerceIn(0f, 1f)

        drawButtonFn(fovMinusButton, buttonFontScale)
        drawSliderTrackFn(fovSliderRect, fovFraction)
        fontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = "FOV: $currentFov",
            centerX = fovSliderRect.centerX(),
            centerY = fovSliderRect.centerY(),
            scale = buttonFontScale,
            r = 1f,
            g = 1f,
            b = 1f,
            a = 1f
        )
        drawButtonFn(fovPlusButton, buttonFontScale)
        drawButtonFn(backButton, buttonFontScale)
    }

    companion object {
        const val MIN_FOV = 30f
        const val MAX_FOV = 110f
        const val FOV_STEP = 5f
    }
}
