package com.nitrous.tessellate.ui

import android.graphics.RectF
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import kotlin.random.Random

enum class CreateWorldField {
    NONE,
    WORLD_NAME,
    SEED
}

/**
 * Create New World screen configuring the 4 world creation settings:
 * 1. World Name (default: "New World")
 * 2. Gamemode toggle (Survival / Creative)
 * 3. World Type toggle (Default / Flat / Debug)
 * 4. Seed (default: blank / random)
 * Plus "Create" and "Cancel" buttons.
 */
class CreateWorldScreen(
    private val engine: TessellateEngine
) {
    @Volatile
    var worldName: String = WorldSession.DEFAULT_WORLD_NAME

    @Volatile
    var gamemode: GameMode = GameMode.SURVIVAL
        private set

    @Volatile
    var worldType: WorldType = WorldType.DEFAULT
        private set

    @Volatile
    var seedText: String = ""

    @Volatile
    var useNativeTextInputs: Boolean = false

    @Volatile
    var focusedField: CreateWorldField = CreateWorldField.NONE

    @Volatile
    var cursorIndex: Int = 0

    val worldNameRect = RectF()
    val seedRect = RectF()

    val gamemodeButton = UiButton(
        id = MenuButtonId.CREATE_WORLD_GAMEMODE,
        label = gamemode.displayName,
        enabled = true
    )

    val worldTypeButton = UiButton(
        id = MenuButtonId.CREATE_WORLD_TYPE,
        label = worldType.displayName,
        enabled = true
    )

    val createButton = UiButton(
        id = MenuButtonId.CREATE_WORLD_CREATE,
        label = "Create",
        enabled = true
    )

    val cancelButton = UiButton(
        id = MenuButtonId.CREATE_WORLD_CANCEL,
        label = "Cancel",
        enabled = true
    )

    val buttons: List<UiButton> = listOf(
        gamemodeButton,
        worldTypeButton,
        createButton,
        cancelButton
    )

    var titleCenterX: Float = 960f
        private set
    var titleCenterY: Float = 86f
        private set
    var titleScale: Int = 4
        private set
    var labelFontScale: Int = 2
        private set
    var buttonFontScale: Int = 2
        private set

    private var labelColumnLeft: Float = 200f
    private var rowYCenters = FloatArray(4)

    fun resetDefaults() {
        worldName = WorldSession.DEFAULT_WORLD_NAME
        gamemode = GameMode.SURVIVAL
        worldType = WorldType.DEFAULT
        seedText = ""
        focusedField = CreateWorldField.NONE
        cursorIndex = 0
        gamemodeButton.label = gamemode.displayName
        worldTypeButton.label = worldType.displayName
        clearHoverStates()
    }

    fun setGamemode(mode: GameMode) {
        gamemode = mode
        gamemodeButton.label = mode.displayName
    }

    fun toggleGamemode(): GameMode {
        setGamemode(gamemode.next())
        return gamemode
    }

    fun setWorldType(type: WorldType) {
        worldType = type
        worldTypeButton.label = type.displayName
    }

    fun toggleWorldType(): WorldType {
        setWorldType(worldType.next())
        return worldType
    }

    fun buildSession(randomProvider: () -> Long = { Random.nextLong() }): WorldSession {
        val cleanName = worldName.trim().ifEmpty { WorldSession.DEFAULT_WORLD_NAME }
        val resolvedSeed = WorldSession.parseSeed(seedText, randomProvider)
        return WorldSession(
            name = cleanName,
            gamemode = gamemode,
            worldType = worldType,
            seed = resolvedSeed
        )
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        titleScale = if (h >= 720f && w >= 1000f) 4 else 3
        labelFontScale = if (h >= 760f && w >= 1100f) 3 else 2
        buttonFontScale = if (h >= 760f && w >= 1100f) 3 else 2

        titleCenterX = w * 0.5f
        titleCenterY = (h * 0.105f).coerceAtLeast(38f)

        val formWidth = (w * 0.68f).coerceIn(460f, 840f)
        val formLeft = (w - formWidth) * 0.5f
        val formRight = formLeft + formWidth

        labelColumnLeft = formLeft
        val controlWidth = (formWidth * 0.62f).coerceAtLeast(240f)
        val controlLeft = formRight - controlWidth

        val rowHeight = (h * 0.095f).coerceIn(48f, 68f)
        val rowGap = (h * 0.022f).coerceIn(10f, 20f)
        val actionGap = (h * 0.042f).coerceIn(18f, 36f)

        val totalContentHeight = rowHeight * 5f + rowGap * 3f + actionGap
        val minStartTop = titleCenterY + 38f
        val startTop = ((h - totalContentHeight) * 0.54f).coerceIn(
            minStartTop,
            (h - totalContentHeight - 16f).coerceAtLeast(minStartTop)
        )

        val row0Top = startTop
        val row1Top = row0Top + rowHeight + rowGap
        val row2Top = row1Top + rowHeight + rowGap
        val row3Top = row2Top + rowHeight + rowGap
        val actionTop = row3Top + rowHeight + actionGap

        worldNameRect.set(controlLeft, row0Top, formRight, row0Top + rowHeight)
        gamemodeButton.rect.set(controlLeft, row1Top, formRight, row1Top + rowHeight)
        worldTypeButton.rect.set(controlLeft, row2Top, formRight, row2Top + rowHeight)
        seedRect.set(controlLeft, row3Top, formRight, row3Top + rowHeight)

        rowYCenters[0] = worldNameRect.centerY()
        rowYCenters[1] = gamemodeButton.rect.centerY()
        rowYCenters[2] = worldTypeButton.rect.centerY()
        rowYCenters[3] = seedRect.centerY()

        val buttonGap = (w * 0.024f).coerceIn(16f, 32f)
        val halfW = (formWidth - buttonGap) * 0.5f
        createButton.rect.set(formLeft, actionTop, formLeft + halfW, actionTop + rowHeight)
        cancelButton.rect.set(createButton.rect.right + buttonGap, actionTop, formRight, actionTop + rowHeight)
    }

    fun clearHoverStates() {
        buttons.forEach { it.isHovered = false }
    }

    fun handleButtonClick(id: MenuButtonId): Boolean {
        return when (id) {
            MenuButtonId.CREATE_WORLD_GAMEMODE -> {
                toggleGamemode()
                true
            }
            MenuButtonId.CREATE_WORLD_TYPE -> {
                toggleWorldType()
                true
            }
            MenuButtonId.CREATE_WORLD_CREATE -> {
                val session = buildSession()
                engine.startWorldSession(session)
                true
            }
            MenuButtonId.CREATE_WORLD_CANCEL -> {
                engine.setAppState(AppState.WORLD_SELECT)
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
        drawInputBoxFn: (RectF, String, String, Boolean, Int) -> Unit
    ) {
        fontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = "CREATE NEW WORLD",
            centerX = titleCenterX,
            centerY = titleCenterY,
            scale = titleScale,
            r = 0.96f,
            g = 0.98f,
            b = 0.96f,
            a = 1f,
            shadow = true
        )

        val rowLabels = arrayOf("World Name", "Gamemode", "World Type", "Seed")
        val labelH = fontRenderer.measureHeight(labelFontScale)
        for (i in rowLabels.indices) {
            fontRenderer.drawText(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = rowLabels[i],
                x = labelColumnLeft,
                y = rowYCenters[i] - labelH * 0.5f,
                scale = labelFontScale,
                r = 0.90f,
                g = 0.94f,
                b = 0.91f,
                a = 1f,
                shadow = true
            )
        }

        // Row 1: World Name input (rendered in BitmapFontRenderer app font)
        drawInputBoxFn(
            worldNameRect,
            worldName,
            WorldSession.DEFAULT_WORLD_NAME,
            focusedField == CreateWorldField.WORLD_NAME,
            cursorIndex
        )

        // Row 2: Gamemode toggle button
        drawButtonFn(gamemodeButton, buttonFontScale)

        // Row 3: World Type toggle button
        drawButtonFn(worldTypeButton, buttonFontScale)

        // Row 4: Seed input (rendered in BitmapFontRenderer app font)
        drawInputBoxFn(
            seedRect,
            seedText,
            "(Random)",
            focusedField == CreateWorldField.SEED,
            cursorIndex
        )

        // Bottom actions
        drawButtonFn(createButton, buttonFontScale)
        drawButtonFn(cancelButton, buttonFontScale)
    }
}
