package com.nitrous.tessellate.ui

import android.graphics.RectF
import android.view.MotionEvent
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.settings.ReferenceActionResult
import com.nitrous.tessellate.settings.SettingCategory
import com.nitrous.tessellate.settings.SettingControlType
import com.nitrous.tessellate.settings.SettingDefinition
import com.nitrous.tessellate.settings.SettingScreenTarget
import kotlin.math.roundToInt

/**
 * UI representation of a single data-driven row built from a [SettingDefinition].
 */
class SettingRowUi(
    val definition: SettingDefinition,
    val minusButton: UiButton? = null,
    val plusButton: UiButton? = null,
    val actionButton: UiButton? = null,
    val sliderTrackRect: RectF = RectF(),
    val rowRect: RectF = RectF()
)

/**
 * Data-driven Settings & Debug Options UI screen.
 * Generates tabs, toggles, sliders, selectors, buttons, inline confirmations, and mod permission
 * prompts purely from [TessellateEngine.settingsRegistry] without hardcoded setting logic.
 */
class SettingsScreen(
    private val engine: TessellateEngine
) {
    // Compatibility handles aligned dynamically with the "tessellate:fov" data-driven row
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

    val fovSliderRect = RectF()

    var activeScreenTarget: SettingScreenTarget = SettingScreenTarget.SETTINGS
        private set

    var activeCategoryId: String? = null
        private set

    var pendingConfirmationSetting: SettingDefinition? = null
        private set

    var statusBannerText: String? = null
        private set

    private var surfaceWidth: Int = 1920
    private var surfaceHeight: Int = 1080
    private var scrollOffsetRows: Int = 0
    private var maxVisibleRows: Int = 4

    private val categoryTabButtons = mutableListOf<Pair<SettingCategory, UiButton>>()
    private val rowItems = mutableListOf<SettingRowUi>()
    private val interactiveButtons = mutableListOf<UiButton>()

    private val confirmAcceptButton = UiButton(MenuButtonId.SETTINGS_DYNAMIC, "Confirm", actionKey = "confirm_accept")
    private val confirmCancelButton = UiButton(MenuButtonId.SETTINGS_DYNAMIC, "Cancel", actionKey = "confirm_cancel")
    private val permApproveButton = UiButton(MenuButtonId.SETTINGS_DYNAMIC, "Allow Mod", actionKey = "perm_approve")
    private val permDenyButton = UiButton(MenuButtonId.SETTINGS_DYNAMIC, "Deny Mod", actionKey = "perm_deny")
    private val scrollUpButton = UiButton(MenuButtonId.SETTINGS_DYNAMIC, "^", actionKey = "scroll_up")
    private val scrollDownButton = UiButton(MenuButtonId.SETTINGS_DYNAMIC, "v", actionKey = "scroll_down")

    private var draggingSliderRow: SettingRowUi? = null

    var titleCenterX: Float = 960f
        private set
    var titleCenterY: Float = 84f
        private set
    var titleScale: Int = 3
        private set
    var buttonFontScale: Int = 2
        private set

    fun setScreenTarget(target: SettingScreenTarget) {
        if (activeScreenTarget != target) {
            activeScreenTarget = target
            activeCategoryId = null
            scrollOffsetRows = 0
            pendingConfirmationSetting = null
            statusBannerText = null
        } else {
            pendingConfirmationSetting = null
        }
        rebuildLayout()
    }

    fun selectCategory(categoryId: String) {
        activeCategoryId = categoryId
        scrollOffsetRows = 0
        pendingConfirmationSetting = null
        rebuildLayout()
    }

    fun refreshDynamicState() {
        rebuildLayout()
    }

    fun getInteractiveButtons(): List<UiButton> = interactiveButtons.toList()

    fun getVisibleRows(): List<SettingRowUi> = rowItems.toList()

    fun getCategoryTabs(): List<SettingCategory> = categoryTabButtons.map { it.first }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        rebuildLayout()
    }

    fun rebuildLayout() {
        val w = surfaceWidth.toFloat().coerceAtLeast(1f)
        val h = surfaceHeight.toFloat().coerceAtLeast(1f)

        titleScale = if (h >= 800f && w >= 1200f) 4 else 3
        buttonFontScale = if (h >= 800f && w >= 1200f) 2 else 2

        titleCenterX = w * 0.5f
        titleCenterY = (h * 0.08f).coerceAtLeast(36f)

        val registry = engine.settingsRegistry
        val categories = registry.getCategories(activeScreenTarget, includeEmpty = false)

        if (categories.isNotEmpty() && categories.none { it.id == activeCategoryId }) {
            activeCategoryId = categories.first().id
            scrollOffsetRows = 0
        } else if (categories.isEmpty()) {
            activeCategoryId = null
        }

        // Build category tabs row
        categoryTabButtons.clear()
        val tabTop = (titleCenterY + 34f).coerceAtLeast(66f)
        val tabHeight = (h * 0.075f).coerceIn(48f, 56f)
        val tabGap = 10f
        if (categories.isNotEmpty()) {
            val maxTabsWidth = (w * 0.84f).coerceAtLeast(320f)
            val tabWidth = ((maxTabsWidth - (categories.size - 1) * tabGap) / categories.size)
                .coerceIn(110f, 240f)
            val totalTabW = categories.size * tabWidth + (categories.size - 1) * tabGap
            var curX = (w - totalTabW) * 0.5f
            for (cat in categories) {
                val btn = UiButton(
                    id = MenuButtonId.SETTINGS_DYNAMIC,
                    label = cat.label,
                    enabled = true,
                    isSelected = (cat.id == activeCategoryId),
                    actionKey = "cat:${cat.id}"
                )
                btn.rect.set(curX, tabTop, curX + tabWidth, tabTop + tabHeight)
                categoryTabButtons.add(cat to btn)
                curX += tabWidth + tabGap
            }
        }

        // Bottom bar: Back button + optional scroll arrows
        val footerHeight = (h * 0.085f).coerceIn(48f, 62f)
        val footerTop = h - footerHeight - 18f
        val backWidth = (w * 0.24f).coerceIn(200f, 340f)
        backButton.rect.set((w - backWidth) * 0.5f, footerTop, (w + backWidth) * 0.5f, footerTop + footerHeight)

        // Content area bounds
        val pendingConfirm = pendingConfirmationSetting
        val pendingPerm = if (activeScreenTarget == SettingScreenTarget.SETTINGS) {
            engine.luaModRuntimeOrNull()?.permissionManager?.getPendingApprovalRecords()?.firstOrNull()
        } else null

        val extraBannerSpace = when {
            pendingConfirm != null -> 88f
            pendingPerm != null -> 96f
            statusBannerText != null -> 38f
            else -> 0f
        }

        val listTop = tabTop + tabHeight + 18f
        val listBottom = (footerTop - extraBannerSpace - 12f).coerceAtLeast(listTop + 60f)
        val availableHeight = listBottom - listTop

        val rowHeight = (h * 0.09f).coerceIn(48f, 64f)
        val rowGap = 12f
        val stride = rowHeight + rowGap
        maxVisibleRows = ((availableHeight + rowGap) / stride).toInt().coerceIn(1, 7)

        val defs = activeCategoryId?.let { registry.getSettingsForCategory(it, visibleOnly = true) }.orEmpty()
        val maxScroll = (defs.size - maxVisibleRows).coerceAtLeast(0)
        scrollOffsetRows = scrollOffsetRows.coerceIn(0, maxScroll)

        val visibleDefs = defs.drop(scrollOffsetRows).take(maxVisibleRows)

        val contentWidth = (w * 0.72f).coerceIn(460f, 900f)
        val contentLeft = (w - contentWidth) * 0.5f

        rowItems.clear()
        interactiveButtons.clear()
        fovMinusButton.rect.setEmpty()
        fovPlusButton.rect.setEmpty()
        fovSliderRect.setEmpty()

        for ((index, def) in visibleDefs.withIndex()) {
            val rTop = listTop + index * stride
            val rBottom = rTop + rowHeight
            val rowRect = RectF(contentLeft, rTop, contentLeft + contentWidth, rBottom)

            val controlAreaWidth = (contentWidth * 0.48f).coerceIn(220f, 440f)
            val controlLeft = rowRect.right - controlAreaWidth

            val rowUi = when (def.controlType) {
                SettingControlType.SLIDER -> {
                    val stepSize = rowHeight.coerceAtLeast(48f)
                    val gap = 8f
                    val trackWidth = (controlAreaWidth - stepSize * 2f - gap * 2f).coerceAtLeast(96f)
                    val isFov = (def.id == "tessellate:fov")
                    val minusBtn = if (isFov) {
                        fovMinusButton.apply {
                            rect.set(controlLeft, rTop, controlLeft + stepSize, rBottom)
                            actionKey = "slider_minus:${def.id}"
                        }
                    } else {
                        UiButton(
                            id = MenuButtonId.SETTINGS_DYNAMIC,
                            label = "-",
                            actionKey = "slider_minus:${def.id}"
                        ).apply {
                            rect.set(controlLeft, rTop, controlLeft + stepSize, rBottom)
                        }
                    }
                    val trackLeft = controlLeft + stepSize + gap
                    val trackRect = if (isFov) {
                        fovSliderRect.apply {
                            set(trackLeft, rTop, trackLeft + trackWidth, rBottom)
                        }
                    } else {
                        RectF(trackLeft, rTop, trackLeft + trackWidth, rBottom)
                    }
                    val plusLeft = trackRect.right + gap
                    val plusBtn = if (isFov) {
                        fovPlusButton.apply {
                            rect.set(plusLeft, rTop, plusLeft + stepSize, rBottom)
                            actionKey = "slider_plus:${def.id}"
                        }
                    } else {
                        UiButton(
                            id = MenuButtonId.SETTINGS_DYNAMIC,
                            label = "+",
                            actionKey = "slider_plus:${def.id}"
                        ).apply {
                            rect.set(plusLeft, rTop, plusLeft + stepSize, rBottom)
                        }
                    }
                    interactiveButtons.add(minusBtn)
                    interactiveButtons.add(plusBtn)
                    SettingRowUi(
                        definition = def,
                        minusButton = minusBtn,
                        plusButton = plusBtn,
                        sliderTrackRect = trackRect,
                        rowRect = rowRect
                    )
                }

                SettingControlType.TOGGLE,
                SettingControlType.SELECTOR -> {
                    val valueText = registry.formatValueDisplay(def.id)
                    val btn = UiButton(
                        id = MenuButtonId.SETTINGS_DYNAMIC,
                        label = valueText,
                        actionKey = "control:${def.id}"
                    ).apply {
                        rect.set(controlLeft, rTop, rowRect.right, rBottom)
                    }
                    interactiveButtons.add(btn)
                    SettingRowUi(definition = def, actionButton = btn, rowRect = rowRect)
                }

                SettingControlType.BUTTON -> {
                    val btnWidth = (contentWidth * 0.56f).coerceAtLeast(240f)
                    val btnLeft = rowRect.centerX() - btnWidth * 0.5f
                    val btn = UiButton(
                        id = MenuButtonId.SETTINGS_DYNAMIC,
                        label = def.label,
                        actionKey = "control:${def.id}"
                    ).apply {
                        rect.set(btnLeft, rTop, btnLeft + btnWidth, rBottom)
                    }
                    interactiveButtons.add(btn)
                    SettingRowUi(definition = def, actionButton = btn, rowRect = rowRect)
                }
            }
            rowItems.add(rowUi)
        }

        // Add tabs & footer & scroll buttons
        for ((_, tabBtn) in categoryTabButtons) {
            interactiveButtons.add(tabBtn)
        }
        if (defs.size > maxVisibleRows) {
            val scrollSize = 48f
            val scrollLeft = (contentLeft + contentWidth + 14f).coerceAtMost(w - scrollSize - 10f)
            scrollUpButton.enabled = scrollOffsetRows > 0
            scrollUpButton.rect.set(scrollLeft, listTop, scrollLeft + scrollSize, listTop + scrollSize)
            scrollDownButton.enabled = scrollOffsetRows < maxScroll
            scrollDownButton.rect.set(scrollLeft, listBottom - scrollSize, scrollLeft + scrollSize, listBottom)
            interactiveButtons.add(scrollUpButton)
            interactiveButtons.add(scrollDownButton)
        } else {
            scrollUpButton.rect.setEmpty()
            scrollDownButton.rect.setEmpty()
        }

        // Optional confirmation or permission prompt buttons
        if (pendingConfirm != null) {
            val bannerTop = footerTop - 68f
            val btnW = 160f
            val btnH = 48f
            confirmAcceptButton.rect.set(w * 0.5f - btnW - 12f, bannerTop, w * 0.5f - 12f, bannerTop + btnH)
            confirmCancelButton.rect.set(w * 0.5f + 12f, bannerTop, w * 0.5f + btnW + 12f, bannerTop + btnH)
            interactiveButtons.add(confirmAcceptButton)
            interactiveButtons.add(confirmCancelButton)
        } else if (pendingPerm != null) {
            val bannerTop = footerTop - 64f
            val btnW = 160f
            val btnH = 48f
            permApproveButton.rect.set(w * 0.5f - btnW - 12f, bannerTop, w * 0.5f - 12f, bannerTop + btnH)
            permDenyButton.rect.set(w * 0.5f + 12f, bannerTop, w * 0.5f + btnW + 12f, bannerTop + btnH)
            interactiveButtons.add(permApproveButton)
            interactiveButtons.add(permDenyButton)
        }

        interactiveButtons.add(backButton)
    }

    fun clearHoverStates() {
        interactiveButtons.forEach { it.isHovered = false }
        draggingSliderRow = null
    }

    fun handleButtonClick(button: UiButton): Boolean {
        if (button.id == MenuButtonId.SETTINGS_BACK) {
            pendingConfirmationSetting = null
            engine.navigateBackFromSettings()
            return true
        }
        val key = button.actionKey
        if (key != null) {
            return handleDynamicActionKey(key)
        }
        return when (button.id) {
            MenuButtonId.SETTINGS_FOV_MINUS -> handleDynamicActionKey("slider_minus:tessellate:fov")
            MenuButtonId.SETTINGS_FOV_PLUS -> handleDynamicActionKey("slider_plus:tessellate:fov")
            else -> false
        }
    }

    fun handleButtonClick(id: MenuButtonId): Boolean {
        return when (id) {
            MenuButtonId.SETTINGS_FOV_MINUS -> handleDynamicActionKey("slider_minus:tessellate:fov")
            MenuButtonId.SETTINGS_FOV_PLUS -> handleDynamicActionKey("slider_plus:tessellate:fov")
            MenuButtonId.SETTINGS_BACK -> {
                pendingConfirmationSetting = null
                engine.navigateBackFromSettings()
                true
            }
            else -> false
        }
    }

    fun handleDynamicActionKey(key: String): Boolean {
        val registry = engine.settingsRegistry
        return when {
            key.startsWith("cat:") -> {
                selectCategory(key.removePrefix("cat:"))
                true
            }
            key == "scroll_up" -> {
                scrollOffsetRows = (scrollOffsetRows - 1).coerceAtLeast(0)
                rebuildLayout()
                true
            }
            key == "scroll_down" -> {
                scrollOffsetRows++
                rebuildLayout()
                true
            }
            key == "confirm_accept" -> {
                confirmPendingAction()
                true
            }
            key == "confirm_cancel" -> {
                cancelPendingAction()
                true
            }
            key == "perm_approve" -> {
                val pm = engine.luaModRuntimeOrNull()?.permissionManager
                val pending = pm?.getPendingApprovalRecords()?.firstOrNull()
                if (pending != null) {
                    pm.approveModPermissions(pending.modId)
                    statusBannerText = "Approved permissions for ${pending.displayName}"
                    rebuildLayout()
                }
                true
            }
            key == "perm_deny" -> {
                val pm = engine.luaModRuntimeOrNull()?.permissionManager
                val pending = pm?.getPendingApprovalRecords()?.firstOrNull()
                if (pending != null) {
                    pm.denyModPermissions(pending.modId)
                    statusBannerText = "Denied permissions for ${pending.displayName}"
                    rebuildLayout()
                }
                true
            }
            key.startsWith("slider_minus:") -> {
                val settingId = key.removePrefix("slider_minus:")
                stepSlider(settingId, -1)
                true
            }
            key.startsWith("slider_plus:") -> {
                val settingId = key.removePrefix("slider_plus:")
                stepSlider(settingId, +1)
                true
            }
            key.startsWith("control:") -> {
                val settingId = key.removePrefix("control:")
                val def = registry.getSetting(settingId) ?: return false
                when (def.controlType) {
                    SettingControlType.TOGGLE -> {
                        registry.toggleValue(settingId)
                        statusBannerText = null
                        rebuildLayout()
                    }
                    SettingControlType.SELECTOR -> {
                        registry.cycleSelector(settingId, 1)
                        statusBannerText = null
                        rebuildLayout()
                    }
                    SettingControlType.BUTTON -> {
                        triggerButtonSetting(def)
                    }
                    SettingControlType.SLIDER -> Unit
                }
                true
            }
            else -> false
        }
    }

    fun triggerButtonSetting(def: SettingDefinition): ReferenceActionResult? {
        if (def.requiresConfirmation) {
            pendingConfirmationSetting = def
            statusBannerText = def.confirmationMessage ?: "Confirm ${def.label}?"
            rebuildLayout()
            return null
        }
        val res = engine.settingsRegistry.invokeAction(def.id)
        statusBannerText = res.message.takeIf { it.isNotBlank() }
        rebuildLayout()
        return res
    }

    fun confirmPendingAction(): ReferenceActionResult? {
        val def = pendingConfirmationSetting ?: return null
        pendingConfirmationSetting = null
        val res = engine.settingsRegistry.invokeAction(def.id)
        statusBannerText = res.message.takeIf { it.isNotBlank() }
        rebuildLayout()
        return res
    }

    fun cancelPendingAction() {
        pendingConfirmationSetting = null
        statusBannerText = "Cancelled."
        rebuildLayout()
    }

    private fun stepSlider(settingId: String, direction: Int) {
        val def = engine.settingsRegistry.getSetting(settingId) ?: return
        val min = def.min ?: 0.0
        val max = def.max ?: 100.0
        val step = def.step ?: ((max - min) / 10.0).coerceAtLeast(0.01)
        val current = (engine.settingsRegistry.getValue(settingId) as? Number)?.toDouble() ?: (def.defaultValue as? Number)?.toDouble() ?: min
        val next = (current + direction * step).coerceIn(min, max)
        engine.settingsRegistry.setValue(settingId, next, persist = true)
        rebuildLayout()
    }

    // Backwards-compatible helpers matching earlier callers
    fun adjustFovBy(delta: Float) {
        setFov(engine.currentSettings.fov + delta)
    }

    fun setFov(targetFov: Float) {
        engine.settingsRegistry.setValue("tessellate:fov", targetFov.toDouble(), persist = true)
        rebuildLayout()
    }

    fun handleSliderTouch(x: Float, y: Float, actionMasked: Int): Boolean {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val hitRow = rowItems.firstOrNull {
                    it.definition.controlType == SettingControlType.SLIDER &&
                        !it.sliderTrackRect.isEmpty &&
                        it.sliderTrackRect.contains(x, y)
                } ?: return false
                draggingSliderRow = hitRow
                updateSliderFromX(hitRow, x)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val active = draggingSliderRow ?: return false
                updateSliderFromX(active, x)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = draggingSliderRow != null
                draggingSliderRow = null
                wasDragging
            }
            else -> false
        }
    }

    private fun updateSliderFromX(row: SettingRowUi, x: Float) {
        val rect = row.sliderTrackRect
        if (rect.width() <= 0f) return
        val fraction = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val def = row.definition
        val min = def.min ?: 0.0
        val max = def.max ?: 100.0
        val rawValue = min + fraction * (max - min)
        engine.settingsRegistry.setValue(def.id, rawValue, persist = true)
        rebuildLayout()
    }

    fun render(
        shader: Shader,
        orthoMatrix: FloatArray,
        quadVaoId: Int,
        fontRenderer: BitmapFontRenderer,
        drawButtonFn: (UiButton, Int) -> Unit,
        drawSliderTrackFn: (RectF, Float) -> Unit
    ) {
        val headerTitle = if (activeScreenTarget == SettingScreenTarget.DEBUG_OPTIONS) {
            "DEBUG OPTIONS"
        } else {
            "SETTINGS"
        }

        fontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = headerTitle,
            centerX = titleCenterX,
            centerY = titleCenterY,
            scale = titleScale,
            r = 0.95f,
            g = 0.96f,
            b = 0.98f,
            a = 1f
        )

        // Category tabs
        for ((_, tabBtn) in categoryTabButtons) {
            drawButtonFn(tabBtn, buttonFontScale)
        }

        // Data-driven setting rows
        val registry = engine.settingsRegistry
        for (row in rowItems) {
            val def = row.definition
            when (def.controlType) {
                SettingControlType.SLIDER -> {
                    val labelX = row.rowRect.left + 8f
                    val labelY = row.rowRect.centerY() - fontRenderer.measureHeight(buttonFontScale) * 0.5f
                    fontRenderer.drawText(
                        shader = shader,
                        orthoMatrix = orthoMatrix,
                        quadVaoId = quadVaoId,
                        text = def.label,
                        x = labelX,
                        y = labelY,
                        scale = buttonFontScale,
                        r = 0.94f, g = 0.96f, b = 0.95f, a = 1f,
                        shadow = true
                    )

                    val min = def.min ?: 0.0
                    val max = def.max ?: 100.0
                    val current = (registry.getValue(def.id) as? Number)?.toDouble() ?: min
                    val fraction = ((current - min) / (max - min).coerceAtLeast(1e-5)).toFloat().coerceIn(0f, 1f)

                    row.minusButton?.let { drawButtonFn(it, buttonFontScale) }
                    drawSliderTrackFn(row.sliderTrackRect, fraction)
                    val formatted = registry.formatValueDisplay(def.id)
                    val sliderText = if (def.id == "tessellate:fov") "FOV: $formatted" else formatted
                    fontRenderer.drawTextCentered(
                        shader = shader,
                        orthoMatrix = orthoMatrix,
                        quadVaoId = quadVaoId,
                        text = sliderText,
                        centerX = row.sliderTrackRect.centerX(),
                        centerY = row.sliderTrackRect.centerY(),
                        scale = buttonFontScale,
                        r = 1f, g = 1f, b = 1f, a = 1f
                    )
                    row.plusButton?.let { drawButtonFn(it, buttonFontScale) }
                }

                SettingControlType.TOGGLE,
                SettingControlType.SELECTOR -> {
                    val labelX = row.rowRect.left + 8f
                    val labelY = row.rowRect.centerY() - fontRenderer.measureHeight(buttonFontScale) * 0.5f
                    fontRenderer.drawText(
                        shader = shader,
                        orthoMatrix = orthoMatrix,
                        quadVaoId = quadVaoId,
                        text = def.label,
                        x = labelX,
                        y = labelY,
                        scale = buttonFontScale,
                        r = 0.94f, g = 0.96f, b = 0.95f, a = 1f,
                        shadow = true
                    )
                    row.actionButton?.let { drawButtonFn(it, buttonFontScale) }
                }

                SettingControlType.BUTTON -> {
                    row.actionButton?.let { drawButtonFn(it, buttonFontScale) }
                }
            }
        }

        // Scroll buttons if overflowing
        if (!scrollUpButton.rect.isEmpty) {
            drawButtonFn(scrollUpButton, buttonFontScale)
        }
        if (!scrollDownButton.rect.isEmpty) {
            drawButtonFn(scrollDownButton, buttonFontScale)
        }

        // Confirmation dialog or Mod Permission Prompt or Status banner
        val pendingConfirm = pendingConfirmationSetting
        val pendingPerm = if (activeScreenTarget == SettingScreenTarget.SETTINGS) {
            engine.luaModRuntimeOrNull()?.permissionManager?.getPendingApprovalRecords()?.firstOrNull()
        } else null

        if (pendingConfirm != null) {
            fontRenderer.drawTextCentered(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = pendingConfirm.confirmationMessage ?: "Confirm ${pendingConfirm.label}?",
                centerX = titleCenterX,
                centerY = confirmAcceptButton.rect.top - 18f,
                scale = 2,
                r = 1.0f, g = 0.78f, b = 0.35f, a = 1f
            )
            drawButtonFn(confirmAcceptButton, buttonFontScale)
            drawButtonFn(confirmCancelButton, buttonFontScale)
        } else if (pendingPerm != null) {
            fontRenderer.drawTextCentered(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = pendingPerm.userPromptSummary.take(64),
                centerX = titleCenterX,
                centerY = permApproveButton.rect.top - 18f,
                scale = 2,
                r = 0.98f, g = 0.88f, b = 0.45f, a = 1f
            )
            drawButtonFn(permApproveButton, buttonFontScale)
            drawButtonFn(permDenyButton, buttonFontScale)
        } else if (!statusBannerText.isNullOrBlank()) {
            fontRenderer.drawTextCentered(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = statusBannerText!!.take(56),
                centerX = titleCenterX,
                centerY = backButton.rect.top - 20f,
                scale = 2,
                r = 0.58f, g = 0.94f, b = 0.68f, a = 1f
            )
        }

        drawButtonFn(backButton, buttonFontScale)
    }

    companion object {
        const val MIN_FOV = 30f
        const val MAX_FOV = 110f
        const val FOV_STEP = 5f
    }
}
