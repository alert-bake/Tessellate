package com.nitrous.tessellate.ui

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.save.WorldSaveData
import com.nitrous.tessellate.save.WorldSaveManager

/**
 * World Selection menu screen.
 * Reads saved worlds from `files/saves/`, allows selecting & playing worlds,
 * creating new worlds, or deleting existing world folders.
 */
class WorldSelectScreen(
    private val context: Context,
    private val engine: TessellateEngine
) {
    val existingWorlds: MutableList<WorldSaveData> = mutableListOf()

    var selectedWorldIndex: Int = -1
        private set

    var scrollOffset: Int = 0
        private set

    val selectedWorld: WorldSaveData?
        get() = existingWorlds.getOrNull(selectedWorldIndex)

    private val entryButtonIds = listOf(
        MenuButtonId.WORLD_SELECT_ENTRY_0,
        MenuButtonId.WORLD_SELECT_ENTRY_1,
        MenuButtonId.WORLD_SELECT_ENTRY_2,
        MenuButtonId.WORLD_SELECT_ENTRY_3,
        MenuButtonId.WORLD_SELECT_ENTRY_4
    )

    val worldEntryButtons: List<UiButton> = entryButtonIds.map { id ->
        UiButton(id = id, label = "", enabled = false)
    }

    val playSelectedButton = UiButton(
        id = MenuButtonId.WORLD_SELECT_PLAY,
        label = "Play",
        enabled = false
    )

    val createNewWorldButton = UiButton(
        id = MenuButtonId.WORLD_SELECT_CREATE_NEW,
        label = "Create New World",
        enabled = true
    )

    val deleteWorldButton = UiButton(
        id = MenuButtonId.WORLD_SELECT_DELETE,
        label = "Delete World",
        enabled = false
    )

    val backButton = UiButton(
        id = MenuButtonId.WORLD_SELECT_BACK,
        label = "Back",
        enabled = true
    )

    val listPanelRect = RectF()

    val buttons: List<UiButton>
        get() = buildList {
            for (i in 0 until visibleRowCount()) {
                val btn = worldEntryButtons[i]
                if (btn.enabled && !btn.rect.isEmpty) {
                    add(btn)
                }
            }
            add(playSelectedButton)
            add(createNewWorldButton)
            add(deleteWorldButton)
            add(backButton)
        }

    var titleCenterX: Float = 960f
        private set
    var titleCenterY: Float = 110f
        private set
    var titleScale: Int = 4
        private set
    var buttonFontScale: Int = 2
        private set
    var subtitleFontScale: Int = 2
        private set

    private var lastClickedWorldIndex: Int = -1
    private var lastClickTimestampMs: Long = 0L

    init {
        refreshWorlds()
    }

    fun refreshWorlds() {
        val loaded = WorldSaveManager.listSavedWorlds(context)
        existingWorlds.clear()
        existingWorlds.addAll(loaded)

        if (existingWorlds.isEmpty()) {
            selectedWorldIndex = -1
            scrollOffset = 0
        } else {
            selectedWorldIndex = if (selectedWorldIndex < 0) 0 else selectedWorldIndex.coerceIn(0, existingWorlds.lastIndex)
            val maxScroll = (existingWorlds.size - MAX_VISIBLE_ROWS).coerceAtLeast(0)
            scrollOffset = scrollOffset.coerceIn(0, maxScroll)
            if (selectedWorldIndex < scrollOffset) {
                scrollOffset = selectedWorldIndex
            } else if (selectedWorldIndex >= scrollOffset + MAX_VISIBLE_ROWS) {
                scrollOffset = (selectedWorldIndex - MAX_VISIBLE_ROWS + 1).coerceAtLeast(0)
            }
        }
        syncRowButtons()
    }

    fun selectWorld(index: Int) {
        if (index !in existingWorlds.indices) {
            selectedWorldIndex = -1
        } else {
            selectedWorldIndex = index
        }
        syncRowButtons()
    }

    private fun visibleRowCount(): Int {
        return (existingWorlds.size - scrollOffset).coerceIn(0, MAX_VISIBLE_ROWS)
    }

    private fun syncRowButtons() {
        val hasSelection = selectedWorld != null
        playSelectedButton.enabled = hasSelection
        deleteWorldButton.enabled = hasSelection

        for (i in 0 until MAX_VISIBLE_ROWS) {
            val worldIndex = scrollOffset + i
            val btn = worldEntryButtons[i]
            val world = existingWorlds.getOrNull(worldIndex)
            if (world != null) {
                val isSel = worldIndex == selectedWorldIndex
                val prefix = if (isSel) "> " else ""
                val name = world.worldName.ifBlank { "World" }
                val gm = world.gamemode?.displayName ?: "Survival"
                val wt = world.worldType?.displayName ?: "Default"
                btn.label = "$prefix$name ($gm / $wt)"
                btn.enabled = true
                btn.isSelected = isSel
            } else {
                btn.label = ""
                btn.enabled = false
                btn.isSelected = false
                btn.isHovered = false
            }
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        titleScale = if (h >= 720f && w >= 1000f) 4 else 3
        buttonFontScale = if (h >= 760f && w >= 1100f) 3 else 2
        subtitleFontScale = 2

        titleCenterX = w * 0.5f
        titleCenterY = (h * 0.11f).coerceAtLeast(42f)

        val panelWidth = (w * 0.78f).coerceIn(480f, 1080f)
        val panelLeft = (w - panelWidth) * 0.5f
        val bottomBtnHeight = (h * 0.095f).coerceIn(52f, 72f)
        val bottomMargin = (h * 0.055f).coerceIn(18f, 48f)
        val bottomBtnTop = (h - bottomMargin - bottomBtnHeight).coerceAtLeast(titleCenterY + 150f)

        val panelTop = (h * 0.20f).coerceAtLeast(titleCenterY + 40f)
        val panelBottom = (bottomBtnTop - (h * 0.035f).coerceIn(16f, 32f)).coerceAtLeast(panelTop + 120f)
        listPanelRect.set(panelLeft, panelTop, panelLeft + panelWidth, panelBottom)

        val innerPad = 16f
        val rowGap = 10f
        val availH = (listPanelRect.height() - innerPad * 2f).coerceAtLeast(52f)
        val rowHeight = ((availH - rowGap * (MAX_VISIBLE_ROWS - 1)) / MAX_VISIBLE_ROWS)
            .coerceIn(48f, 74f)

        for (i in 0 until MAX_VISIBLE_ROWS) {
            val top = listPanelRect.top + innerPad + i * (rowHeight + rowGap)
            val bottom = top + rowHeight
            if (bottom <= listPanelRect.bottom - 8f) {
                worldEntryButtons[i].rect.set(
                    listPanelRect.left + innerPad,
                    top,
                    listPanelRect.right - innerPad,
                    bottom
                )
            } else {
                worldEntryButtons[i].rect.setEmpty()
            }
        }

        val gap = (w * 0.014f).coerceIn(10f, 22f)
        val totalGap = gap * 3f
        val usableW = (panelWidth - totalGap).coerceAtLeast(240f)
        val playWidth = usableW * 0.21f
        val createWidth = usableW * 0.34f
        val deleteWidth = usableW * 0.27f
        val backWidth = usableW * 0.18f

        var curX = panelLeft
        playSelectedButton.rect.set(curX, bottomBtnTop, curX + playWidth, bottomBtnTop + bottomBtnHeight)
        curX += playWidth + gap
        createNewWorldButton.rect.set(curX, bottomBtnTop, curX + createWidth, bottomBtnTop + bottomBtnHeight)
        curX += createWidth + gap
        deleteWorldButton.rect.set(curX, bottomBtnTop, curX + deleteWidth, bottomBtnTop + bottomBtnHeight)
        curX += deleteWidth + gap
        backButton.rect.set(curX, bottomBtnTop, curX + backWidth, bottomBtnTop + bottomBtnHeight)
    }

    fun clearHoverStates() {
        worldEntryButtons.forEach { it.isHovered = false }
        playSelectedButton.isHovered = false
        createNewWorldButton.isHovered = false
        deleteWorldButton.isHovered = false
        backButton.isHovered = false
    }

    fun handleButtonClick(id: MenuButtonId): Boolean {
        val entryIdx = entryButtonIds.indexOf(id)
        if (entryIdx >= 0) {
            val targetIdx = scrollOffset + entryIdx
            val targetWorld = existingWorlds.getOrNull(targetIdx) ?: return false
            val now = SystemClock.uptimeMillis()
            val isDoubleTap = (targetIdx == lastClickedWorldIndex && (now - lastClickTimestampMs) <= DOUBLE_TAP_WINDOW_MS)
            lastClickedWorldIndex = targetIdx
            lastClickTimestampMs = now
            selectWorld(targetIdx)
            if (isDoubleTap) {
                engine.loadSavedWorld(targetWorld)
            }
            return true
        }

        return when (id) {
            MenuButtonId.WORLD_SELECT_PLAY -> {
                val target = selectedWorld ?: return false
                engine.loadSavedWorld(target)
                true
            }
            MenuButtonId.WORLD_SELECT_CREATE_NEW -> {
                engine.openCreateWorldScreen()
                true
            }
            MenuButtonId.WORLD_SELECT_DELETE -> {
                val target = selectedWorld ?: return false
                WorldSaveManager.deleteWorld(context, target.folderName)
                refreshWorlds()
                true
            }
            MenuButtonId.WORLD_SELECT_BACK -> {
                engine.setAppState(AppState.MENU)
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
        drawButtonFn: (UiButton, Int) -> Unit,
        drawPanelFn: (RectF) -> Unit
    ) {
        fontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = "SELECT WORLD",
            centerX = titleCenterX,
            centerY = titleCenterY,
            scale = titleScale,
            r = 0.96f,
            g = 0.98f,
            b = 0.96f,
            a = 1f,
            shadow = true
        )

        drawPanelFn(listPanelRect)

        if (existingWorlds.isNotEmpty()) {
            val rowFontScale = subtitleFontScale
            for (i in 0 until visibleRowCount()) {
                val btn = worldEntryButtons[i]
                if (btn.enabled && !btn.rect.isEmpty) {
                    drawButtonFn(btn, rowFontScale)
                }
            }
        } else {
            fontRenderer.drawTextCentered(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = "No worlds found",
                centerX = listPanelRect.centerX(),
                centerY = listPanelRect.centerY(),
                scale = buttonFontScale,
                r = 0.75f,
                g = 0.78f,
                b = 0.76f,
                a = 1f,
                shadow = true
            )
        }

        val actionFontScale = 2
        drawButtonFn(playSelectedButton, actionFontScale)
        drawButtonFn(createNewWorldButton, actionFontScale)
        drawButtonFn(deleteWorldButton, actionFontScale)
        drawButtonFn(backButton, actionFontScale)
    }

    companion object {
        const val MAX_VISIBLE_ROWS = 5
        private const val DOUBLE_TAP_WINDOW_MS = 450L
    }
}
