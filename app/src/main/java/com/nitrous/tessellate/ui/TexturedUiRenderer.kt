package com.nitrous.tessellate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
import android.view.MotionEvent
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.IGraphicsRuntime
import com.nitrous.tessellate.core.SceneMode
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.graphics.Matrix4
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.item.Inventory
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TexturePipeline
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Custom textured UI, Main Menu, Settings, & HUD renderer for Tessellate.
 * Operates purely within OpenGL ES 3.0 using [BitmapFontRenderer] and `UI_gui_widgets.png`
 * 9-slice widget bars.
 */
class TexturedUiRenderer(
    private val context: Context,
    private val engine: TessellateEngine
) {

    private var uiShader: Shader? = null
    private var quadVaoId: Int = 0
    private var quadVboId: Int = 0

    private val orthoMatrix = Matrix4.create()
    private val modelMatrix = Matrix4.create()
    private val mvpMatrix = Matrix4.create()

    private var screenWidth: Int = 1920
    private var screenHeight: Int = 1080

    // Data-driven UI UV layout
    var uiLayout: UiLayoutConfig = UiLayoutLoader.load(context)
        private set

    // Data-driven crisp bitmap font renderer (loads textures/UI_font.json + UI_font_ascii.png)
    val bitmapFontRenderer = BitmapFontRenderer(context)

    // Settings screen UI & persistence wiring
    val settingsScreen = SettingsScreen(engine)

    // World Select, Create World, & Pause screens
    val worldSelectScreen = WorldSelectScreen(context, engine)
    val createWorldScreen = CreateWorldScreen(engine)
    val pauseScreen = PauseScreen(engine)

    // Main Menu title & vertical button stack
    val singleplayerButton = UiButton(MenuButtonId.SINGLEPLAYER, "Singleplayer", enabled = true)
    val multiplayerButton = UiButton(MenuButtonId.MULTIPLAYER, "Multiplayer", enabled = false)
    val settingsButton = UiButton(MenuButtonId.SETTINGS, "Settings", enabled = true)
    val quitButton = UiButton(MenuButtonId.QUIT, "Quit", enabled = true)

    val mainMenuButtons: List<UiButton> = listOf(
        singleplayerButton,
        multiplayerButton,
        settingsButton,
        quitButton
    )

    var menuTitleCenterX: Float = 960f
        private set
    var menuTitleCenterY: Float = 130f
        private set
    var menuTitleScale: Int = 4
        private set
    var menuButtonFontScale: Int = 2
        private set

    // Dynamic HUD canvas & GL texture (compact landscape size)
    private var hudBitmap: Bitmap? = null
    private var hudCanvas: Canvas? = null
    private var hudTextureHandle: Int = 0
    private var hudTextureAllocated: Boolean = false
    private val hudWidth: Int = 600
    private val hudHeight: Int = 200
    private var lastHudUpdateMs: Long = 0
    private val hudUpdateIntervalMs: Long = 100

    @Volatile
    private var hudDirty: Boolean = true

    // Telemetry & HUD visibility flags
    @Volatile
    var currentFps: Int = 60
    @Volatile
    var currentFrameTimeMs: Float = 16.6f
    @Volatile
    var showCoordinates: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidateHud()
            }
        }
    @Volatile
    var showDebugHud: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidateHud()
            }
        }
    @Volatile
    var uiScale: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.75f, 1.25f)
            if (field != clamped) {
                field = clamped
                computeLayout(engine.currentSceneMode)
                invalidateHud()
            }
        }

    private data class HotbarLayoutSnapshot(
        val bounds: RectF = RectF(),
        val slots: List<RectF> = emptyList(),
        val scale: Float = 3.5f
    )

    // Hotbar layout & slots (bottom center) - published atomically per SceneMode
    val hotbarBoundsRect = RectF()
    @Volatile
    private var hotbarSlotRects: List<RectF> = emptyList()
    @Volatile
    private var currentHotbarScale: Float = 3.5f
    @Volatile
    private var worldHotbarSnapshot = HotbarLayoutSnapshot()
    @Volatile
    private var showcaseHotbarSnapshot = HotbarLayoutSnapshot()

    fun getHotbarSlotRects(): List<RectF> = hotbarSlotRects

    private val showcaseTextureIds = listOf(
        TextureConstants.PROTO_GRASS,
        TextureConstants.PROTO_GRASS_SIDE,
        TextureConstants.PROTO_DIRT,
        TextureConstants.PROTO_STONE,
        TextureConstants.PROTO_DEBUG_BUG,
        "proto_invalid_diagnostic_test"
    )
    @Volatile
    private var selectedShowcaseIndex: Int = 0

    // Minimal in-game top-right utility buttons (no obsolete debug/orbit/teleport buttons)
    private val btnMenuRect = RectF()
    private val btnRegenRect = RectF()
    private var activeMenuHudPointerId: Int? = null
    private var activeRegenHudPointerId: Int? = null
    private var isMenuHudHovered: Boolean = false
    private var isRegenHudHovered: Boolean = false

    // Jump button hit area (bottom right in landscape, matching D-pad size and bottom margin)
    val jumpButtonRect = RectF()

    // Track active pointer IDs pressing the jump button or hotbar so camera drag is never triggered
    private val activeJumpPointers: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val activeHotbarPointers: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())

    // Paints for diagnostic HUD
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        typeface = Typeface.MONOSPACE
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(240, 200, 80) // Gold
        textSize = 26f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val bgPaint = Paint().apply {
        color = Color.argb(195, 14, 18, 26)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.argb(210, 70, 130, 240)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    init {
        computeLayout(engine.currentSceneMode)
    }

    fun reloadUiLayout() {
        uiLayout = UiLayoutLoader.load(context)
    }

    fun initGl() {
        release()
        reloadUiLayout()

        val vao = IntArray(1)
        val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)

        quadVaoId = vao[0]
        quadVboId = vbo[0]

        // Unit quad: [0, 1] x [0, 1]
        val quadVertices = floatArrayOf(
            0f, 0f, 0f, 0f,
            1f, 0f, 1f, 0f,
            1f, 1f, 1f, 1f,
            0f, 0f, 0f, 0f,
            1f, 1f, 1f, 1f,
            0f, 1f, 0f, 1f
        )

        val buffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadVertices)
                position(0)
            }

        GLES30.glBindVertexArray(quadVaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVertices.size * 4, buffer, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, 0)

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val vertexSource = """
            #version 300 es
            layout (location = 0) in vec2 aPosition;
            layout (location = 1) in vec2 aTexCoord;

            uniform mat4 uMVPMatrix;
            uniform vec4 uUvRect;
            out vec2 vTexCoord;

            void main() {
                vTexCoord = mix(uUvRect.xy, uUvRect.zw, aTexCoord);
                gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentSource = """
            #version 300 es
            precision mediump float;

            in vec2 vTexCoord;

            uniform sampler2D uTexture;
            uniform vec4 uColor;
            uniform int uUseTexture;
            uniform int uWhiteOverlay;

            out vec4 fragColor;

            void main() {
                if (uUseTexture == 1) {
                    vec4 texColor = texture(uTexture, vTexCoord);
                    if (uWhiteOverlay == 1) {
                        // Render texture alpha mask as white overlay with preserved transparency
                        fragColor = vec4(uColor.rgb, texColor.a * uColor.a);
                    } else {
                        fragColor = texColor * uColor;
                    }
                } else {
                    fragColor = uColor;
                }
            }
        """.trimIndent()

        uiShader = Shader(vertexSource, fragmentSource).apply {
            compile()
        }

        initHudTexture()
        bitmapFontRenderer.initGl()
    }

    private fun initHudTexture() {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        hudTextureHandle = tex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hudTextureHandle)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        if (hudBitmap == null || hudBitmap?.isRecycled == true) {
            hudBitmap = Bitmap.createBitmap(hudWidth, hudHeight, Bitmap.Config.ARGB_8888)
            hudCanvas = Canvas(hudBitmap!!)
        }
        hudTextureAllocated = false
        updateHudBitmap(engine.currentSceneMode)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun invalidateHud() {
        hudDirty = true
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)

        Matrix4.ortho(orthoMatrix, 0f, screenWidth.toFloat(), screenHeight.toFloat(), 0f, -1f, 1f)
        computeLayout(engine.currentSceneMode)
        invalidateHud()
    }

    fun onSceneModeChanged(newMode: SceneMode) {
        clearInGamePointers()
        computeLayout(newMode)
        invalidateHud()
    }

    fun onAppStateChanged(newState: AppState) {
        clearInGamePointers()
        mainMenuButtons.forEach { it.isHovered = false }
        settingsScreen.clearHoverStates()
        worldSelectScreen.clearHoverStates()
        createWorldScreen.clearHoverStates()
        pauseScreen.clearHoverStates()
        if (newState == AppState.WORLD_SELECT) {
            worldSelectScreen.refreshWorlds()
        }
        if (newState == AppState.SETTINGS) {
            settingsScreen.setScreenTarget(com.nitrous.tessellate.settings.SettingScreenTarget.SETTINGS)
        } else if (newState == AppState.DEBUG_OPTIONS) {
            settingsScreen.setScreenTarget(com.nitrous.tessellate.settings.SettingScreenTarget.DEBUG_OPTIONS)
        }
        if (newState == AppState.PLAYING) {
            invalidateHud()
        }
    }

    fun onPauseStateChanged(paused: Boolean) {
        clearInGamePointers()
        pauseScreen.clearHoverStates()
        if (!paused) {
            invalidateHud()
        }
    }

    private fun clearInGamePointers() {
        activeJumpPointers.clear()
        activeHotbarPointers.clear()
        activeMenuHudPointerId = null
        activeRegenHudPointerId = null
        isMenuHudHovered = false
        isRegenHudHovered = false
    }

    fun getActiveMenuButtons(
        appState: AppState = engine.currentAppState,
        isPaused: Boolean = engine.isPaused
    ): List<UiButton> {
        return when (appState) {
            AppState.MENU -> mainMenuButtons
            AppState.WORLD_SELECT -> worldSelectScreen.buttons
            AppState.CREATE_WORLD -> createWorldScreen.buttons
            AppState.SETTINGS, AppState.DEBUG_OPTIONS -> settingsScreen.getInteractiveButtons()
            AppState.PLAYING -> if (isPaused) pauseScreen.buttons else emptyList()
        }
    }

    fun handleSettingsSliderTouch(x: Float, y: Float, actionMasked: Int): Boolean {
        return settingsScreen.handleSliderTouch(x, y, actionMasked)
    }

    private fun computeLayout(sceneMode: SceneMode = engine.currentSceneMode) {
        computeMainMenuLayout()
        settingsScreen.onSurfaceChanged(screenWidth, screenHeight)
        worldSelectScreen.onSurfaceChanged(screenWidth, screenHeight)
        createWorldScreen.onSurfaceChanged(screenWidth, screenHeight)
        pauseScreen.onSurfaceChanged(screenWidth, screenHeight)

        val isWorld = sceneMode == SceneMode.WORLD
        val worldSlots = buildWorldHotbarSnapshot()
        val showcaseSlots = buildShowcaseHotbarSnapshot()
        worldHotbarSnapshot = worldSlots
        showcaseHotbarSnapshot = showcaseSlots

        val active = if (isWorld) worldSlots else showcaseSlots
        currentHotbarScale = active.scale
        hotbarBoundsRect.set(active.bounds)
        hotbarSlotRects = active.slots

        // Minimal top-right buttons in PLAYING mode scaled cleanly with UI Scale (>= 48px touch floor)
        val scaleFactor = uiScale.coerceIn(0.75f, 1.25f)
        val btnHeight = (52f * scaleFactor).coerceAtLeast(48f)
        val btnWidth = (112f * scaleFactor).coerceAtLeast(88f)
        val gap = 12f * scaleFactor
        val topMargin = 16f
        val rightMargin = 20f

        var curRight = screenWidth - rightMargin
        btnMenuRect.set(curRight - btnWidth, topMargin, curRight, topMargin + btnHeight)
        curRight -= (btnWidth + gap)

        if (isWorld) {
            btnRegenRect.set(curRight - btnWidth, topMargin, curRight, topMargin + btnHeight)
        } else {
            btnRegenRect.setEmpty()
        }

        // Jump Button: positioned at bottom-right corner, scaled to match on-screen size of D-pad elements
        val dpadSize = ((screenHeight * 0.48f).coerceIn(180f, 270f)) * scaleFactor
        val jumpSize = dpadSize * 0.56f
        val jumpMargin = 36f
        val bottom = screenHeight - 24f
        val top = bottom - jumpSize
        val right = screenWidth - jumpMargin
        val left = right - jumpSize
        jumpButtonRect.set(left, top, right, bottom)
    }

    private fun computeMainMenuLayout() {
        val w = screenWidth.toFloat()
        val h = screenHeight.toFloat()

        // Title "TESSELATE" at top center with integer scale 3x or 4x
        menuTitleScale = if (h >= 720f && w >= 1000f) 4 else 3
        menuTitleCenterX = w * 0.5f
        menuTitleCenterY = (h * 0.14f).coerceAtLeast(48f)

        val titleH = bitmapFontRenderer.measureHeight(menuTitleScale)
        val titleBottom = menuTitleCenterY + titleH * 0.5f

        val btnH = (h * 0.095f).coerceIn(52f, 76f)
        val btnW = (w * 0.34f).coerceIn(300f, 540f)
        val gap = (h * 0.026f).coerceIn(12f, 24f)

        // Integer font scaling (2x or 3x)
        menuButtonFontScale = if (btnW >= 420f && btnH >= 64f) 3 else 2

        val stackCount = mainMenuButtons.size
        val totalStackH = stackCount * btnH + (stackCount - 1) * gap
        val availableTop = titleBottom + 24f
        val centeredStartY = (h - totalStackH) * 0.56f
        val startY = centeredStartY.coerceIn(availableTop, (h - totalStackH - 16f).coerceAtLeast(availableTop))
        val left = (w - btnW) * 0.5f

        for (i in mainMenuButtons.indices) {
            val top = startY + i * (btnH + gap)
            mainMenuButtons[i].rect.set(left, top, left + btnW, top + btnH)
        }
    }

    private fun buildWorldHotbarSnapshot(): HotbarLayoutSnapshot {
        val scaleFactor = uiScale.coerceIn(0.75f, 1.25f)
        val dpadSize = ((screenHeight * 0.48f).coerceIn(180f, 270f)) * scaleFactor
        val jumpSize = dpadSize * 0.56f
        val jumpMargin = 36f
        val reservedLeft = 36f + dpadSize + 20f
        val reservedRight = jumpMargin + jumpSize + 20f
        val availableCenterWidth = (screenWidth - reservedLeft - reservedRight).coerceAtLeast(182f * 1.2f)
        val hotbarScale = minOf(
            ((screenHeight / 250f) * scaleFactor).coerceIn(1.2f, 4.5f),
            availableCenterWidth / 182f
        )

        val hotbarW = 182f * hotbarScale
        val hotbarH = 22f * hotbarScale
        val maxHotbarLeft = (screenWidth - reservedRight - hotbarW).coerceAtLeast(reservedLeft)
        val hotbarLeft = ((screenWidth - hotbarW) / 2f).coerceIn(reservedLeft, maxHotbarLeft)
        val hotbarTop = screenHeight - hotbarH - 20f
        val bounds = RectF(hotbarLeft, hotbarTop, hotbarLeft + hotbarW, hotbarTop + hotbarH)

        val slots = ArrayList<RectF>(Inventory.HOTBAR_SIZE)
        for (i in 0 until Inventory.HOTBAR_SIZE) {
            val left = hotbarLeft + (1f + i * 20f) * hotbarScale
            val top = hotbarTop + 1f * hotbarScale
            val slotDim = 20f * hotbarScale
            slots.add(RectF(left, top, left + slotDim, top + slotDim))
        }
        return HotbarLayoutSnapshot(bounds = bounds, slots = slots, scale = hotbarScale)
    }

    private fun buildShowcaseHotbarSnapshot(): HotbarLayoutSnapshot {
        val slotCount = showcaseTextureIds.size
        val slotSize = 64f
        val spacing = 10f
        val totalWidth = slotCount * slotSize + (slotCount - 1) * spacing
        val startX = (screenWidth - totalWidth) / 2f
        val startY = screenHeight - slotSize - 20f
        val bounds = RectF(startX, startY, startX + totalWidth, startY + slotSize)

        val slots = ArrayList<RectF>(slotCount)
        for (i in 0 until slotCount) {
            val left = startX + i * (slotSize + spacing)
            slots.add(RectF(left, startY, left + slotSize, startY + slotSize))
        }
        return HotbarLayoutSnapshot(bounds = bounds, slots = slots, scale = 3.5f)
    }

    fun renderUi(
        runtime: IGraphicsRuntime,
        sceneMode: SceneMode = engine.currentSceneMode,
        appState: AppState = engine.currentAppState,
        isPaused: Boolean = engine.isPaused
    ) {
        val shader = uiShader ?: return
        if (shader.programId == 0) return
        val pipeline = runtime.texturePipeline

        runtime.backend.setDepthTest(false)
        runtime.backend.setCullFace(false)
        runtime.backend.setBlending(true)

        shader.bind()

        when (appState) {
            AppState.MENU -> renderMainMenu(shader, pipeline)
            AppState.WORLD_SELECT -> renderWorldSelectMenu(shader, pipeline)
            AppState.CREATE_WORLD -> renderCreateWorldMenu(shader, pipeline)
            AppState.SETTINGS, AppState.DEBUG_OPTIONS -> renderSettingsMenu(shader, pipeline)
            AppState.PLAYING -> {
                if (isPaused) {
                    renderPauseMenu(shader, pipeline)
                } else {
                    renderInGameHud(shader, pipeline, sceneMode)
                }
            }
        }

        shader.unbind()
        GLES30.glBindVertexArray(0)
    }

    private fun renderMainMenu(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        // Title: "TESSELATE" at top center using integer scale (3x or 4x)
        bitmapFontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = "TESSELATE",
            centerX = menuTitleCenterX,
            centerY = menuTitleCenterY,
            scale = menuTitleScale,
            r = 0.96f,
            g = 0.98f,
            b = 0.96f,
            a = 1f,
            shadow = true
        )

        // Centered vertical stack of UI_gui_widgets.png buttons
        for (button in mainMenuButtons) {
            drawWidgetButton(shader, pipeline, button, menuButtonFontScale)
        }
    }

    private fun renderSettingsMenu(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        settingsScreen.render(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            fontRenderer = bitmapFontRenderer,
            drawButtonFn = { button, fontScale ->
                drawWidgetButton(shader, pipeline, button, fontScale)
            },
            drawSliderTrackFn = { rect, fraction ->
                drawSettingsSliderBar(shader, pipeline, rect, fraction)
            }
        )
    }

    private fun renderWorldSelectMenu(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        worldSelectScreen.render(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            fontRenderer = bitmapFontRenderer,
            drawButtonFn = { button, fontScale ->
                drawWidgetButton(shader, pipeline, button, fontScale)
            },
            drawPanelFn = { panelRect ->
                drawMenuPanel(shader, panelRect)
            }
        )
    }

    private fun renderCreateWorldMenu(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        createWorldScreen.render(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            fontRenderer = bitmapFontRenderer,
            drawButtonFn = { button, fontScale ->
                drawWidgetButton(shader, pipeline, button, fontScale)
            },
            drawInputBoxFn = { rect, text, placeholder, isFocused, cursorPos ->
                drawTextInputBox(
                    shader = shader,
                    rect = rect,
                    text = text,
                    placeholder = placeholder,
                    isFocused = isFocused,
                    cursorPos = cursorPos,
                    fontScale = createWorldScreen.buttonFontScale
                )
            }
        )
    }

    private fun renderPauseMenu(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        drawQuad(shader, 0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), 0.04f, 0.06f, 0.08f, 0.68f)
        pauseScreen.render(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            fontRenderer = bitmapFontRenderer,
            drawButtonFn = { button, fontScale ->
                drawWidgetButton(shader, pipeline, button, fontScale)
            }
        )
    }

    private fun drawMenuPanel(shader: Shader, rect: RectF) {
        if (rect.isEmpty) return
        drawQuad(shader, rect.left, rect.top, rect.width(), rect.height(), 0.05f, 0.07f, 0.08f, 0.88f)
        drawOutline(shader, rect.left, rect.top, rect.width(), rect.height(), 3f, 0.25f, 0.42f, 0.30f, 0.95f)
    }

    private fun drawTextInputBox(
        shader: Shader,
        rect: RectF,
        text: String,
        placeholder: String,
        isFocused: Boolean,
        cursorPos: Int,
        fontScale: Int
    ) {
        if (rect.isEmpty) return
        val safeScale = fontScale.coerceAtLeast(1)
        drawQuad(shader, rect.left, rect.top, rect.width(), rect.height(), 0.05f, 0.07f, 0.09f, 0.96f)

        val (br, bg, bb) = if (isFocused) {
            Triple(0.48f, 0.90f, 0.56f)
        } else {
            Triple(0.36f, 0.50f, 0.41f)
        }
        drawOutline(shader, rect.left, rect.top, rect.width(), rect.height(), 3f, br, bg, bb, 1f)

        val paddingX = 16f
        val maxTextW = (rect.width() - paddingX * 2f).coerceAtLeast(16f)
        val textH = bitmapFontRenderer.measureHeight(safeScale)
        val textY = rect.centerY() - textH * 0.5f
        val showBlinkCaret = isFocused && ((SystemClock.uptimeMillis() % 1000L) < 500L)
        val caretWidth = safeScale.toFloat().coerceAtLeast(2f)
        val caretHeight = textH + safeScale * 2f

        if (text.isEmpty()) {
            if (placeholder.isNotEmpty()) {
                bitmapFontRenderer.drawText(
                    shader = shader,
                    orthoMatrix = orthoMatrix,
                    quadVaoId = quadVaoId,
                    text = placeholder,
                    x = rect.left + paddingX,
                    y = textY,
                    scale = safeScale,
                    r = 0.55f, g = 0.62f, b = 0.58f, a = 1f,
                    shadow = true
                )
            }
            if (showBlinkCaret) {
                drawQuad(
                    shader = shader,
                    x = rect.left + paddingX,
                    y = rect.centerY() - caretHeight * 0.5f,
                    width = caretWidth,
                    height = caretHeight,
                    r = 0.85f, g = 1.0f, b = 0.88f, a = 1f
                )
            }
            return
        }

        val clampedCursor = cursorPos.coerceIn(0, text.length)
        var startIdx = 0
        while (startIdx < clampedCursor &&
            bitmapFontRenderer.measureWidth(text.substring(startIdx, clampedCursor), safeScale) > maxTextW - caretWidth - 4f
        ) {
            startIdx++
        }

        var endIdx = text.length
        while (endIdx > startIdx &&
            bitmapFontRenderer.measureWidth(text.substring(startIdx, endIdx), safeScale) > maxTextW
        ) {
            endIdx--
        }

        val visibleText = text.substring(startIdx, endIdx)
        if (visibleText.isNotEmpty()) {
            bitmapFontRenderer.drawText(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = visibleText,
                x = rect.left + paddingX,
                y = textY,
                scale = safeScale,
                r = 1f, g = 1f, b = 1f, a = 1f,
                shadow = true
            )
        }

        if (showBlinkCaret) {
            val safeBeforeEnd = clampedCursor.coerceIn(startIdx, endIdx)
            val beforeCursor = text.substring(startIdx, safeBeforeEnd)
            val caretOffset = if (beforeCursor.isEmpty()) {
                0f
            } else {
                bitmapFontRenderer.measureWidth(beforeCursor, safeScale) + safeScale.toFloat()
            }
            drawQuad(
                shader = shader,
                x = rect.left + paddingX + caretOffset,
                y = rect.centerY() - caretHeight * 0.5f,
                width = caretWidth,
                height = caretHeight,
                r = 0.85f, g = 1.0f, b = 0.88f, a = 1f
            )
        }
    }

    private fun renderInGameHud(
        shader: Shader,
        pipeline: com.nitrous.tessellate.core.ITexturePipeline,
        sceneMode: SceneMode
    ) {
        // 1. Crosshair in center
        drawCrosshair(shader, sceneMode)

        // 2. Multitouch directional D-pad and jump button
        if (sceneMode == SceneMode.WORLD) {
            drawDpad(shader, pipeline)
            drawJumpButton(shader, pipeline)
        }

        // 3. Hotbar Slots (Bottom Center)
        drawHotbar(shader, pipeline, sceneMode)

        // 4. Minimal Top-Right Utility Buttons (Menu & Regen)
        drawInGameButtons(shader, pipeline, sceneMode)

        // 5. Diagnostic HUD / Coordinates / NanoTime overlay (Top Left)
        if (showDebugHud) {
            drawDiagnosticHud(shader)
        } else {
            var nextOverlayY = 16f
            if (showCoordinates && sceneMode == SceneMode.WORLD) {
                val p = engine.worldScene.player
                val coordText = "XYZ: %.1f, %.1f, %.1f".format(p.posX, p.posY, p.posZ)
                drawQuad(shader, 16f, nextOverlayY, 290f, 36f, 0.05f, 0.07f, 0.10f, 0.72f)
                bitmapFontRenderer.drawText(
                    shader = shader,
                    orthoMatrix = orthoMatrix,
                    quadVaoId = quadVaoId,
                    text = coordText,
                    x = 24f,
                    y = nextOverlayY + 10f,
                    scale = 2,
                    r = 0.95f, g = 0.98f, b = 0.96f, a = 1f,
                    shadow = true
                )
                nextOverlayY += 42f
            }
            if (engine.worldScene.profiler.enabled && sceneMode == SceneMode.WORLD) {
                val nanoText = engine.worldScene.profiler.formatCompactHudSummary()
                val boxW = (bitmapFontRenderer.measureWidth(nanoText, 2) + 18f).coerceAtLeast(300f)
                drawQuad(shader, 16f, nextOverlayY, boxW, 34f, 0.05f, 0.08f, 0.12f, 0.78f)
                bitmapFontRenderer.drawText(
                    shader = shader,
                    orthoMatrix = orthoMatrix,
                    quadVaoId = quadVaoId,
                    text = nanoText,
                    x = 24f,
                    y = nextOverlayY + 9f,
                    scale = 2,
                    r = 0.48f, g = 0.92f, b = 1.0f, a = 1f,
                    shadow = true
                )
            }
        }
    }

    private fun drawWidgetButton(
        shader: Shader,
        pipeline: com.nitrous.tessellate.core.ITexturePipeline,
        button: UiButton,
        fontScale: Int
    ) {
        if (button.rect.isEmpty) return
        val widgetsHandle = pipeline.getTextureHandle(TextureConstants.UI_GUI_WIDGETS)
        val btnLayout = uiLayout.buttons
        val uv = when (button.visualState) {
            UiButtonState.DISABLED -> btnLayout.disabled
            UiButtonState.HOVERED -> btnLayout.hovered
            UiButtonState.NORMAL -> btnLayout.normal
        }

        if (widgetsHandle != 0) {
            val borderPx = (button.rect.height() * 0.14f).coerceIn(4f, 10f)
            drawNineSliceTexturedQuad(
                shader = shader,
                textureHandle = widgetsHandle,
                rect = button.rect,
                uv = uv,
                sliceU = btnLayout.sliceU,
                sliceV = btnLayout.sliceV,
                borderPx = borderPx
            )
        } else {
            val (r, g, b) = when (button.visualState) {
                UiButtonState.DISABLED -> Triple(0.06f, 0.10f, 0.07f)
                UiButtonState.HOVERED -> Triple(0.18f, 0.42f, 0.24f)
                UiButtonState.NORMAL -> Triple(0.13f, 0.28f, 0.17f)
            }
            drawQuad(shader, button.rect.left, button.rect.top, button.rect.width(), button.rect.height(), r, g, b, 0.96f)
            drawOutline(shader, button.rect.left, button.rect.top, button.rect.width(), button.rect.height(), 2f, 0.4f, 0.7f, 0.45f, 1f)
        }

        if (button.isSelected && button.enabled) {
            drawOutline(shader, button.rect.left, button.rect.top, button.rect.width(), button.rect.height(), 3f, 0.52f, 0.92f, 0.58f, 1f)
        }

        val (tr, tg, tb) = when (button.visualState) {
            UiButtonState.DISABLED -> Triple(0.55f, 0.58f, 0.55f)
            UiButtonState.HOVERED -> Triple(0.95f, 1.0f, 0.90f)
            UiButtonState.NORMAL -> Triple(1.0f, 1.0f, 1.0f)
        }

        bitmapFontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = button.label,
            centerX = button.rect.centerX(),
            centerY = button.rect.centerY(),
            scale = fontScale.coerceAtLeast(1),
            r = tr,
            g = tg,
            b = tb,
            a = 1f,
            shadow = true
        )
    }

    private fun drawSettingsSliderBar(
        shader: Shader,
        pipeline: com.nitrous.tessellate.core.ITexturePipeline,
        rect: RectF,
        fraction: Float
    ) {
        if (rect.isEmpty) return
        val widgetsHandle = pipeline.getTextureHandle(TextureConstants.UI_GUI_WIDGETS)
        val btnLayout = uiLayout.buttons

        if (widgetsHandle != 0) {
            val borderPx = (rect.height() * 0.14f).coerceIn(4f, 10f)
            drawNineSliceTexturedQuad(
                shader = shader,
                textureHandle = widgetsHandle,
                rect = rect,
                uv = btnLayout.disabled,
                sliceU = btnLayout.sliceU,
                sliceV = btnLayout.sliceV,
                borderPx = borderPx
            )
        } else {
            drawQuad(shader, rect.left, rect.top, rect.width(), rect.height(), 0.08f, 0.12f, 0.10f, 0.95f)
        }

        // Visual slider fill indicator inside track
        val innerPad = 6f
        val maxFillW = (rect.width() - innerPad * 2f).coerceAtLeast(0f)
        val fillW = maxFillW * fraction.coerceIn(0f, 1f)
        if (fillW > 0f) {
            drawQuad(
                shader = shader,
                x = rect.left + innerPad,
                y = rect.top + innerPad,
                width = fillW,
                height = (rect.height() - innerPad * 2f).coerceAtLeast(4f),
                r = 0.24f, g = 0.56f, b = 0.34f, a = 0.72f
            )
        }
    }

    private fun drawInGameButtons(
        shader: Shader,
        pipeline: com.nitrous.tessellate.core.ITexturePipeline,
        sceneMode: SceneMode
    ) {
        val widgetsHandle = pipeline.getTextureHandle(TextureConstants.UI_GUI_WIDGETS)
        val btnLayout = uiLayout.buttons

        if (sceneMode == SceneMode.WORLD && !btnRegenRect.isEmpty) {
            val regenUv = if (isRegenHudHovered) btnLayout.hovered else btnLayout.normal
            drawSmallBarButton(shader, widgetsHandle, btnRegenRect, regenUv, btnLayout, "REGEN")
        }
        if (!btnMenuRect.isEmpty) {
            val menuUv = if (isMenuHudHovered) btnLayout.hovered else btnLayout.normal
            drawSmallBarButton(shader, widgetsHandle, btnMenuRect, menuUv, btnLayout, "MENU")
        }
    }

    private fun drawSmallBarButton(
        shader: Shader,
        widgetsHandle: Int,
        rect: RectF,
        uv: UiUvRect,
        btnLayout: ButtonUiLayout,
        label: String
    ) {
        if (widgetsHandle != 0) {
            drawNineSliceTexturedQuad(
                shader = shader,
                textureHandle = widgetsHandle,
                rect = rect,
                uv = uv,
                sliceU = btnLayout.sliceU,
                sliceV = btnLayout.sliceV,
                borderPx = 6f
            )
        } else {
            drawQuad(shader, rect.left, rect.top, rect.width(), rect.height(), 0.15f, 0.28f, 0.18f, 0.9f)
        }
        bitmapFontRenderer.drawTextCentered(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = label,
            centerX = rect.centerX(),
            centerY = rect.centerY(),
            scale = 2,
            r = 1f, g = 1f, b = 1f, a = 1f,
            shadow = true
        )
    }

    private fun drawDpad(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        val rect = engine.touchController.dpadRect
        val tc = engine.touchController

        // 1. D-pad Base: Permanent black control silhouettes
        val baseHandle = pipeline.getTextureHandle(TextureConstants.PROTO_BASE_CONTR)
        drawTexturedQuad(
            shader = shader,
            textureHandle = baseHandle,
            x = rect.left,
            y = rect.top,
            width = rect.width(),
            height = rect.height(),
            r = 0.05f, g = 0.05f, b = 0.08f, a = 0.85f,
            whiteOverlay = false
        )

        if (tc.isUpPressed) {
            drawTexturedQuad(
                shader, pipeline.getTextureHandle(TextureConstants.PROTO_CONTR_UP),
                rect.left, rect.top, rect.width(), rect.height(), 1f, 1f, 1f, 1f, whiteOverlay = true
            )
        }
        if (tc.isDownPressed) {
            drawTexturedQuad(
                shader, pipeline.getTextureHandle(TextureConstants.PROTO_CONTR_DOWN),
                rect.left, rect.top, rect.width(), rect.height(), 1f, 1f, 1f, 1f, whiteOverlay = true
            )
        }
        if (tc.isLeftPressed) {
            drawTexturedQuad(
                shader, pipeline.getTextureHandle(TextureConstants.PROTO_CONTR_LEFT),
                rect.left, rect.top, rect.width(), rect.height(), 1f, 1f, 1f, 1f, whiteOverlay = true
            )
        }
        if (tc.isRightPressed) {
            drawTexturedQuad(
                shader, pipeline.getTextureHandle(TextureConstants.PROTO_CONTR_RIGHT),
                rect.left, rect.top, rect.width(), rect.height(), 1f, 1f, 1f, 1f, whiteOverlay = true
            )
        }
        if (tc.isCrouchPressed || engine.worldScene.player.isCrouching) {
            drawTexturedQuad(
                shader, pipeline.getTextureHandle(TextureConstants.PROTO_CONTR_CROUCH),
                rect.left, rect.top, rect.width(), rect.height(), 1f, 1f, 1f, 1f, whiteOverlay = true
            )
        }
    }

    private fun drawJumpButton(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        if (jumpButtonRect.isEmpty) return
        val jumpHandle = pipeline.getTextureHandle(TextureConstants.PROTO_CONTR_JUMP)
        drawTexturedQuad(
            shader = shader,
            textureHandle = jumpHandle,
            x = jumpButtonRect.left,
            y = jumpButtonRect.top,
            width = jumpButtonRect.width(),
            height = jumpButtonRect.height(),
            r = 1f, g = 1f, b = 1f, a = 0.85f,
            whiteOverlay = false
        )
    }

    private fun drawCrosshair(shader: Shader, sceneMode: SceneMode) {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val size = 16f
        val thickness = 3f

        val hasTarget = sceneMode == SceneMode.WORLD && engine.worldScene.currentTargetHit != null
        val (r, g, b) = if (hasTarget) Triple(0.2f, 1.0f, 0.4f) else Triple(1.0f, 1.0f, 1.0f)

        drawQuad(shader, cx - size, cy - thickness / 2f, size * 2f, thickness, r, g, b, 0.85f)
        drawQuad(shader, cx - thickness / 2f, cy - size, thickness, size * 2f, r, g, b, 0.85f)
        drawQuad(shader, cx - 2f, cy - 2f, 4f, 4f, 1f, 0.85f, 0.2f, 0.95f)

        if (sceneMode == SceneMode.WORLD && engine.worldScene.isMining) {
            val progress = engine.worldScene.miningProgress.coerceIn(0f, 1f)
            val barW = 56f
            val barH = 7f
            val barX = cx - barW / 2f
            val barY = cy + size + 8f

            drawQuad(shader, barX, barY, barW, barH, 0.08f, 0.1f, 0.15f, 0.85f)
            drawOutline(shader, barX, barY, barW, barH, 1.5f, 0.8f, 0.85f, 0.95f, 0.9f)
            val fillW = barW * progress
            if (fillW > 0f) {
                drawQuad(shader, barX, barY, fillW, barH, 1.0f, 0.72f, 0.18f, 0.95f)
            }
        }
    }

    private fun drawHotbar(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline, sceneMode: SceneMode) {
        if (sceneMode == SceneMode.WORLD) {
            drawWorldHotbar(shader, pipeline)
        } else {
            drawShowcaseHotbar(shader, pipeline)
        }
    }

    private fun drawWorldHotbar(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        val snapshot = worldHotbarSnapshot
        val slots = snapshot.slots
        val bounds = snapshot.bounds
        val scale = snapshot.scale
        if (bounds.isEmpty || slots.isEmpty()) return

        val widgetsHandle = pipeline.getTextureHandle(TextureConstants.UI_GUI_WIDGETS)
        val hotbarLayout = uiLayout.hotbar

        // 1. 9-slice scaled hotbar background from UI_gui_widgets.png using data-driven UVs
        if (widgetsHandle != 0) {
            drawNineSliceTexturedQuad(
                shader = shader,
                textureHandle = widgetsHandle,
                rect = bounds,
                uv = hotbarLayout.background,
                sliceU = hotbarLayout.sliceU,
                sliceV = hotbarLayout.sliceV,
                borderPx = 3f * scale
            )
        } else {
            drawQuad(
                shader, bounds.left, bounds.top,
                bounds.width(), bounds.height(),
                0.08f, 0.16f, 0.10f, 0.85f
            )
        }

        // 2. Item icons and bottom-right integer-scaled BitmapFontRenderer stack counts
        val inventory = engine.worldScene.player.inventory
        val iconInset = 2f * scale
        val digitScale = (scale * 0.55f).roundToInt().coerceAtLeast(2)

        for (i in 0 until minOf(slots.size, inventory.size)) {
            val stack = inventory.getSlot(i) ?: continue
            if (stack.count <= 0) continue

            val slotRect = slots[i]
            val texId = resolveItemTextureId(stack.itemId)
            val texHandle = pipeline.getTextureHandle(texId)

            drawTexturedSubQuad(
                shader = shader,
                textureHandle = texHandle,
                x = slotRect.left + iconInset,
                y = slotRect.top + iconInset,
                width = (slotRect.width() - iconInset * 2f).coerceAtLeast(1f),
                height = (slotRect.height() - iconInset * 2f).coerceAtLeast(1f),
                u1 = 0f, v1 = 0f, u2 = 1f, v2 = 1f,
                r = 1f, g = 1f, b = 1f, a = 1f,
                whiteOverlay = false,
                forceNearest = true
            )

            bitmapFontRenderer.drawTextRightAligned(
                shader = shader,
                orthoMatrix = orthoMatrix,
                quadVaoId = quadVaoId,
                text = stack.count.toString(),
                rightX = slotRect.right - 2f * scale,
                bottomY = slotRect.bottom - 1.5f * scale,
                scale = digitScale,
                r = 1f, g = 1f, b = 1f, a = 1f,
                shadow = true
            )
        }

        // 3. Green selection highlight over active hotbar slot
        val selSlot = inventory.selectedSlot
        if (selSlot in slots.indices) {
            val selRect = slots[selSlot]
            val selExpand = 2f * scale
            if (widgetsHandle != 0) {
                val selUv = hotbarLayout.selection
                drawTexturedSubQuad(
                    shader = shader,
                    textureHandle = widgetsHandle,
                    x = selRect.left - selExpand,
                    y = selRect.top - selExpand,
                    width = selRect.width() + selExpand * 2f,
                    height = selRect.height() + selExpand * 2f,
                    u1 = selUv.u1, v1 = selUv.v1, u2 = selUv.u2, v2 = selUv.v2,
                    r = 1f, g = 1f, b = 1f, a = 1f,
                    whiteOverlay = false,
                    forceNearest = true
                )
            } else {
                drawOutline(
                    shader, selRect.left, selRect.top, selRect.width(), selRect.height(),
                    3.5f, 0.35f, 0.9f, 0.3f, 0.95f
                )
            }
        }
    }

    private fun drawShowcaseHotbar(shader: Shader, pipeline: com.nitrous.tessellate.core.ITexturePipeline) {
        val slots = showcaseHotbarSnapshot.slots
        val count = minOf(slots.size, showcaseTextureIds.size)
        for (i in 0 until count) {
            val rect = slots[i]
            val isSelected = (i == selectedShowcaseIndex)
            val textureId = showcaseTextureIds[i]

            val bgAlpha = if (isSelected) 0.9f else 0.55f
            drawQuad(shader, rect.left, rect.top, rect.width(), rect.height(), 0.12f, 0.15f, 0.22f, bgAlpha)

            val borderWidth = if (isSelected) 3.5f else 1.5f
            val (r, g, b) = if (isSelected) Triple(1f, 0.84f, 0.1f) else Triple(0.35f, 0.45f, 0.65f)
            drawOutline(shader, rect.left, rect.top, rect.width(), rect.height(), borderWidth, r, g, b, 0.95f)

            val padding = 8f
            val texHandle = pipeline.getTextureHandle(textureId)
            drawTexturedQuad(
                shader = shader,
                textureHandle = texHandle,
                x = rect.left + padding,
                y = rect.top + padding,
                width = rect.width() - padding * 2,
                height = rect.height() - padding * 2,
                r = 1f, g = 1f, b = 1f, a = 1f,
                whiteOverlay = false
            )
        }
    }

    private fun resolveItemTextureId(itemId: String): String {
        val item = ItemRegistry.get(itemId)
        val block = item?.associatedBlock
        return if (block != null && block.topTexture.isNotEmpty()) {
            block.topTexture
        } else {
            TextureConstants.PROTO_DEBUG_BUG
        }
    }

    /**
     * Renders a 9-slice scaled quad from [textureHandle] preserving crisp unstretched corners
     * and tiled/stretched edges + center using UV coordinates from [uiLayout].
     */
    private fun drawNineSliceTexturedQuad(
        shader: Shader,
        textureHandle: Int,
        rect: RectF,
        uv: UiUvRect,
        sliceU: Float,
        sliceV: Float,
        borderPx: Float
    ) {
        val bx = borderPx.coerceAtMost(rect.width() * 0.45f)
        val by = borderPx.coerceAtMost(rect.height() * 0.45f)
        val su = sliceU.coerceAtMost((uv.u2 - uv.u1) * 0.45f)
        val sv = sliceV.coerceAtMost((uv.v2 - uv.v1) * 0.45f)

        val xs = floatArrayOf(rect.left, rect.left + bx, rect.right - bx, rect.right)
        val ys = floatArrayOf(rect.top, rect.top + by, rect.bottom - by, rect.bottom)
        val us = floatArrayOf(uv.u1, uv.u1 + su, uv.u2 - su, uv.u2)
        val vs = floatArrayOf(uv.v1, uv.v1 + sv, uv.v2 - sv, uv.v2)

        for (row in 0 until 3) {
            val h = ys[row + 1] - ys[row]
            if (h <= 0f) continue
            for (col in 0 until 3) {
                val w = xs[col + 1] - xs[col]
                if (w <= 0f) continue
                drawTexturedSubQuad(
                    shader = shader,
                    textureHandle = textureHandle,
                    x = xs[col],
                    y = ys[row],
                    width = w,
                    height = h,
                    u1 = us[col],
                    v1 = vs[row],
                    u2 = us[col + 1],
                    v2 = vs[row + 1],
                    r = 1f, g = 1f, b = 1f, a = 1f,
                    whiteOverlay = false,
                    forceNearest = true
                )
            }
        }
    }

    private fun drawDiagnosticHud(shader: Shader) {
        val now = System.currentTimeMillis()
        if (hudDirty || now - lastHudUpdateMs > hudUpdateIntervalMs) {
            lastHudUpdateMs = now
            hudDirty = false
            updateHudBitmap(engine.currentSceneMode)
        }

        if (hudTextureHandle != 0) {
            val scaleFactor = uiScale.coerceIn(0.75f, 1.25f)
            drawTexturedQuad(
                shader = shader,
                textureHandle = hudTextureHandle,
                x = 16f,
                y = 16f,
                width = hudWidth.toFloat() * scaleFactor,
                height = hudHeight.toFloat() * scaleFactor,
                r = 1f, g = 1f, b = 1f, a = 1f,
                whiteOverlay = false
            )
        }
    }

    /**
     * MUST only be called from the active GLThread (inside [initHudTexture] or [drawDiagnosticHud]).
     */
    private fun updateHudBitmap(sceneMode: SceneMode) {
        val bmp = hudBitmap ?: return
        if (bmp.isRecycled) return
        val canvas = hudCanvas ?: return
        if (hudTextureHandle == 0) return

        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val rect = RectF(0f, 0f, hudWidth.toFloat(), hudHeight.toFloat())
        canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
        canvas.drawRoundRect(rect, 10f, 10f, borderPaint)

        var y = 28f
        val lineSpacing = 26f

        if (sceneMode == SceneMode.WORLD) {
            val session = engine.worldScene.currentSession
            val header = if (session != null) {
                "TESSELLATE // ${session.name} [${session.gamemode.displayName}/${session.worldType.displayName}]"
            } else {
                "TESSELLATE // FIRST-PERSON"
            }
            canvas.drawText(header, 16f, y, titlePaint)
            y += lineSpacing

            textPaint.color = Color.WHITE
            val p = engine.worldScene.player
            if (showCoordinates) {
                val crouchStr = if (p.isCrouching) "CROUCH" else "STAND"
                val groundStr = if (p.isGrounded) "GROUNDED" else "AIRBORNE"
                canvas.drawText("Pos: %.1f, %.1f, %.1f | %s | %s".format(p.posX, p.posY, p.posZ, crouchStr, groundStr), 16f, y, textPaint)
                y += lineSpacing
            }

            canvas.drawText("FPS: $currentFps (%.1f ms) | Slot: ${p.inventory.selectedSlot + 1}/9".format(currentFrameTimeMs), 16f, y, textPaint)
            y += lineSpacing

            val hit = engine.worldScene.currentTargetHit
            if (hit != null) {
                textPaint.color = Color.rgb(255, 220, 80)
                canvas.drawText("Target: [${hit.blockPos.x}, ${hit.blockPos.y}, ${hit.blockPos.z}] on ${hit.face}", 16f, y, textPaint)
            } else {
                textPaint.color = Color.rgb(160, 170, 190)
                canvas.drawText("Target: None (Crosshair to aim)", 16f, y, textPaint)
            }
            y += lineSpacing

            textPaint.color = Color.rgb(100, 240, 160)
            val selStack = p.inventory.getSelectedStack()
            val selItemName = if (selStack != null) {
                "${ItemRegistry.get(selStack.itemId)?.displayName ?: selStack.itemId} x${selStack.count}"
            } else {
                engine.worldScene.selectedItem.displayName
            }
            val miningStr = if (engine.worldScene.isMining) " [MINING ${(engine.worldScene.miningProgress * 100).toInt()}%]" else ""
            canvas.drawText("Hand: $selItemName$miningStr", 16f, y, textPaint)
            y += lineSpacing

            if (engine.worldScene.profiler.enabled) {
                textPaint.color = Color.rgb(120, 225, 255)
                canvas.drawText(engine.worldScene.profiler.formatCompactHudSummary(), 16f, y, textPaint)
            }
        } else {
            canvas.drawText("TESSELLATE // SHOWCASE", 16f, y, titlePaint)
            y += lineSpacing

            textPaint.color = Color.WHITE
            canvas.drawText("FPS: $currentFps | Mode: ${engine.showcaseScene.renderMode}", 16f, y, textPaint)
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hudTextureHandle)
        if (!hudTextureAllocated) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            hudTextureAllocated = true
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun drawQuad(
        shader: Shader,
        x: Float, y: Float, width: Float, height: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        if (quadVaoId == 0) return
        Matrix4.setIdentity(modelMatrix)
        Matrix4.translate(modelMatrix, x, y, 0f)
        Matrix4.scale(modelMatrix, width, height, 1f)
        Matrix4.multiply(mvpMatrix, orthoMatrix, modelMatrix)

        shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)
        shader.setUniform4f("uUvRect", 0f, 0f, 1f, 1f)
        shader.setUniform4f("uColor", r, g, b, a)
        shader.setUniform1i("uUseTexture", 0)
        shader.setUniform1i("uWhiteOverlay", 0)

        GLES30.glBindVertexArray(quadVaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    private fun drawOutline(
        shader: Shader,
        x: Float, y: Float, width: Float, height: Float,
        borderThickness: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        drawQuad(shader, x, y, width, borderThickness, r, g, b, a)
        drawQuad(shader, x, y + height - borderThickness, width, borderThickness, r, g, b, a)
        drawQuad(shader, x, y, borderThickness, height, r, g, b, a)
        drawQuad(shader, x + width - borderThickness, y, borderThickness, height, r, g, b, a)
    }

    private fun drawTexturedQuad(
        shader: Shader,
        textureHandle: Int,
        x: Float, y: Float, width: Float, height: Float,
        r: Float, g: Float, b: Float, a: Float,
        whiteOverlay: Boolean
    ) {
        drawTexturedSubQuad(
            shader = shader,
            textureHandle = textureHandle,
            x = x, y = y, width = width, height = height,
            u1 = 0f, v1 = 0f, u2 = 1f, v2 = 1f,
            r = r, g = g, b = b, a = a,
            whiteOverlay = whiteOverlay,
            forceNearest = true
        )
    }

    private fun drawTexturedSubQuad(
        shader: Shader,
        textureHandle: Int,
        x: Float, y: Float, width: Float, height: Float,
        u1: Float, v1: Float, u2: Float, v2: Float,
        r: Float, g: Float, b: Float, a: Float,
        whiteOverlay: Boolean,
        forceNearest: Boolean = true
    ) {
        if (quadVaoId == 0 || textureHandle == 0) return
        Matrix4.setIdentity(modelMatrix)
        Matrix4.translate(modelMatrix, x, y, 0f)
        Matrix4.scale(modelMatrix, width, height, 1f)
        Matrix4.multiply(mvpMatrix, orthoMatrix, modelMatrix)

        shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)
        shader.setUniform4f("uUvRect", u1, v1, u2, v2)
        shader.setUniform4f("uColor", r, g, b, a)
        shader.setUniform1i("uUseTexture", 1)
        shader.setUniform1i("uWhiteOverlay", if (whiteOverlay) 1 else 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandle)
        if (forceNearest) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        }
        shader.setUniform1i("uTexture", 0)

        GLES30.glBindVertexArray(quadVaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    /**
     * Handles touch events for in-game HUD buttons, jump button, and hotbar slots (only when PLAYING).
     * Returns true if consumed.
     */
    fun onTouchEvent(event: MotionEvent, runtime: IGraphicsRuntime): Boolean {
        if (engine.currentAppState != AppState.PLAYING) return false
        if (event.pointerCount <= 0) return false
        val action = event.actionMasked
        val idx = event.actionIndex
        if (idx !in 0 until event.pointerCount) return false
        val pointerId = event.getPointerId(idx)

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                if (pointerId == activeMenuHudPointerId) {
                    isMenuHudHovered = btnMenuRect.contains(event.getX(idx), event.getY(idx))
                    return true
                }
                if (pointerId == activeRegenHudPointerId) {
                    isRegenHudHovered = btnRegenRect.contains(event.getX(idx), event.getY(idx))
                    return true
                }
                if (event.pointerCount == 1 && (activeJumpPointers.contains(pointerId) || activeHotbarPointers.contains(pointerId))) {
                    return true
                }
                return false
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (pointerId == activeMenuHudPointerId) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    activeMenuHudPointerId = null
                    isMenuHudHovered = false
                    if (!btnMenuRect.isEmpty && btnMenuRect.contains(x, y)) {
                        engine.setPaused(true)
                    }
                    return true
                }
                if (pointerId == activeRegenHudPointerId) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    activeRegenHudPointerId = null
                    isRegenHudHovered = false
                    if (!btnRegenRect.isEmpty && btnRegenRect.contains(x, y)) {
                        engine.worldScene.requestRegenerateWorld()
                        invalidateHud()
                    }
                    return true
                }
                val consumedJump = activeJumpPointers.remove(pointerId)
                if (consumedJump) {
                    engine.touchController.setJumpPointerActive(pointerId, false)
                }
                val consumedHotbar = activeHotbarPointers.remove(pointerId)
                return consumedJump || consumedHotbar
            }
            MotionEvent.ACTION_CANCEL -> {
                val hadActive = activeJumpPointers.isNotEmpty() ||
                    activeHotbarPointers.isNotEmpty() ||
                    activeMenuHudPointerId != null ||
                    activeRegenHudPointerId != null
                for (pid in activeJumpPointers) {
                    engine.touchController.setJumpPointerActive(pid, false)
                }
                clearInGamePointers()
                return hadActive
            }
        }

        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) {
            return false
        }

        val x = event.getX(idx)
        val y = event.getY(idx)
        val isWorld = engine.currentSceneMode == SceneMode.WORLD

        // 0. Check jump button hitbox BEFORE camera drag or gameplay interaction
        if (isWorld && !jumpButtonRect.isEmpty && jumpButtonRect.contains(x, y)) {
            activeJumpPointers.add(pointerId)
            engine.touchController.setJumpPointerActive(pointerId, true)
            engine.worldScene.player.jump()
            return true
        }

        // 1. Check hotbar slot selection
        val slots = hotbarSlotRects
        for (i in slots.indices) {
            if (slots[i].contains(x, y)) {
                activeHotbarPointers.add(pointerId)
                if (isWorld) {
                    engine.worldScene.selectHotbarSlot(i)
                } else if (i in showcaseTextureIds.indices) {
                    selectedShowcaseIndex = i
                    engine.showcaseScene.activeInspectedTextureId = showcaseTextureIds[i]
                }
                invalidateHud()
                return true
            }
        }

        // 2. Top-right MENU button (returns to Main Menu)
        if (!btnMenuRect.isEmpty && btnMenuRect.contains(x, y)) {
            activeMenuHudPointerId = pointerId
            isMenuHudHovered = true
            return true
        }

        // 3. Top-right REGEN button (World regeneration)
        if (isWorld && !btnRegenRect.isEmpty && btnRegenRect.contains(x, y)) {
            activeRegenHudPointerId = pointerId
            isRegenHudHovered = true
            return true
        }

        return false
    }

    fun release() {
        uiShader?.release()
        uiShader = null

        bitmapFontRenderer.release()

        if (quadVaoId != 0) {
            val vaoArr = intArrayOf(quadVaoId)
            GLES30.glDeleteVertexArrays(1, vaoArr, 0)
            quadVaoId = 0
        }

        if (quadVboId != 0) {
            val vboArr = intArrayOf(quadVboId)
            GLES30.glDeleteBuffers(1, vboArr, 0)
            quadVboId = 0
        }

        if (hudTextureHandle != 0) {
            val texArr = intArrayOf(hudTextureHandle)
            GLES30.glDeleteTextures(1, texArr, 0)
            hudTextureHandle = 0
            hudTextureAllocated = false
        }

        hudCanvas = null
        hudBitmap?.let { if (!it.isRecycled) it.recycle() }
        hudBitmap = null
    }
}
