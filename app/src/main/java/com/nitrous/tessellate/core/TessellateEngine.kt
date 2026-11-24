package com.nitrous.tessellate.core

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.content.Intent
import com.nitrous.tessellate.command.CommandConsole
import com.nitrous.tessellate.command.CommandResult
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.graphics.OpenGlEs3Backend
import com.nitrous.tessellate.input.TouchInputController
import com.nitrous.tessellate.modding.LuaModRuntime
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.save.WorldSaveData
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.save.WorldSaveStorage
import com.nitrous.tessellate.scene.VoxelScene
import com.nitrous.tessellate.scene.WorldScene
import com.nitrous.tessellate.settings.CacheClearResult
import com.nitrous.tessellate.settings.GameSettings
import com.nitrous.tessellate.settings.ReferenceActionResult
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.settings.SettingsRegistry
import com.nitrous.tessellate.settings.SystemSettingsActions
import com.nitrous.tessellate.settings.VanillaSettingsRegistrar
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.ui.MenuButtonId
import com.nitrous.tessellate.ui.TexturedUiRenderer
import com.nitrous.tessellate.world.WorldSession
import kotlinx.coroutines.Job
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Scene execution mode for Tessellate.
 */
enum class SceneMode {
    WORLD,      // First-person voxel world gameplay
    SHOWCASE    // Diagnostic orbit showcase for block and texture inspection
}

/**
 * Primary engine runtime for Tessellate.
 * Coordinates OpenGL ES 3.0 graphics loop, first-person player physics,
 * multitouch input controller, data-driven settings & modding, and in-game custom textured UI.
 */
class TessellateEngine(
    private val context: Context
) : IGraphicsRuntime, GLSurfaceView.Renderer {

    val appContext: Context
        get() = context

    override val backend = OpenGlEs3Backend()
    override val texturePipeline = TexturePipeline(context)
    override val camera = Camera3D()

    val settingsManager = SettingsManager(context)
    val settingsRegistry = SettingsRegistry()

    @Volatile
    var currentSettings: GameSettings = GameSettings()
        private set

    // 1-4. Initialize registries, load JSON content definitions, resolve relationships, and freeze
    private val contentInitialized: Boolean = run {
        AndroidRegistryBootstrap.initialize(context)
        true
    }

    // 5. Initialize the World after registries are loaded & frozen
    val worldScene = WorldScene()
    val showcaseScene = VoxelScene()
    val touchController = TouchInputController()

    @Volatile
    var currentAppState: AppState = AppState.MENU
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    @Volatile
    var settingsReturnState: AppState = AppState.MENU
        private set

    @Volatile
    var currentSceneMode: SceneMode = SceneMode.WORLD
        private set

    private val modeLock = Any()
    private val settingsLock = Any()

    var onAppStateChanged: ((AppState) -> Unit)? = null
    var onPauseStateChanged: ((Boolean) -> Unit)? = null
    var onSurfaceLayoutChanged: ((Int, Int) -> Unit)? = null
    var quitHandler: () -> Unit = { System.exit(0) }
    var appFolderIntentLauncher: ((Intent) -> Boolean)? = null

    val uiRenderer: TexturedUiRenderer

    private var _luaModRuntime: LuaModRuntime? = null
    val luaModRuntime: LuaModRuntime
        get() = _luaModRuntime ?: error("LuaModRuntime is not initialized yet")

    fun luaModRuntimeOrNull(): LuaModRuntime? = _luaModRuntime

    val initialSettingsLoadJob: Job

    init {
        VanillaSettingsRegistrar.registerAll(this, settingsRegistry)
        uiRenderer = TexturedUiRenderer(context, this)
        _luaModRuntime = LuaModRuntime(this, settingsRegistry)

        settingsRegistry.onPersistSetting = { def, sanitizedValue ->
            if (def.isModSetting) {
                synchronized(settingsLock) {
                    val updatedMap = currentSettings.modValues.toMutableMap()
                    if (sanitizedValue != null) {
                        updatedMap[def.id] = sanitizedValue.toString()
                    }
                    val updated = currentSettings.copy(modValues = updatedMap)
                    currentSettings = updated
                    settingsManager.saveSettings(updated)
                }
            }
        }
        settingsRegistry.onResetDefaults = {
            synchronized(settingsLock) {
                val defaults = GameSettings(modValues = currentSettings.modValues)
                applySettings(defaults)
                settingsManager.saveSettings(defaults)
            }
        }

        applySettings(currentSettings)
        _luaModRuntime?.loadModsFromDisk()

        initialSettingsLoadJob = settingsManager.loadSettings { loaded ->
            applySettings(loaded)
            for ((modSettingId, rawVal) in loaded.modValues) {
                if (settingsRegistry.getSetting(modSettingId)?.isModSetting == true) {
                    settingsRegistry.setValue(modSettingId, rawVal, persist = false)
                }
            }
            uiRenderer.settingsScreen.rebuildLayout()
        }
    }

    fun applySettings(settings: GameSettings) {
        val sanitized = synchronized(settingsLock) {
            val clean = settingsManager.sanitize(settings)
            currentSettings = clean
            clean
        }

        synchronized(camera) {
            camera.fovDegrees = sanitized.fov
            camera.viewBobbingEnabled = sanitized.viewBobbing
            camera.farPlane = maxOf(250f, sanitized.renderDistance * 16f * 1.6f)
            camera.updateProjection()
        }

        touchController.lookSensitivityX = sanitized.sensitivity
        touchController.lookSensitivityY = sanitized.sensitivity
        touchController.invertYAxis = sanitized.invertYAxis
        touchController.uiScale = sanitized.uiScale
        touchController.recomputeControlBounds()

        synchronized(modeLock) {
            worldScene.player.autoJump = sanitized.autoJump
            worldScene.configuredRenderDistance = sanitized.renderDistance
            worldScene.asyncMeshPrebuildEnabled = sanitized.asyncMeshPrebuild
            worldScene.worldRenderer.showHitboxes = sanitized.showHitboxes
            worldScene.worldRenderer.useNativeMemoryPooling = sanitized.useNativeMemoryPooling
            worldScene.worldRenderer.parallelMeshingEnabled = sanitized.parallelChunkMeshing
            worldScene.profiler.enabled = sanitized.enableNanoTimeProfiler

            if (!worldScene.world.isDestroyed && worldScene.world.isInfinite &&
                worldScene.world.renderDistance != sanitized.renderDistance
            ) {
                worldScene.world.updateRenderDistance(
                    newDistance = sanitized.renderDistance,
                    playerX = worldScene.player.posX,
                    playerZ = worldScene.player.posZ,
                    meshPrebuilder = if (sanitized.asyncMeshPrebuild) texturePipeline else null
                )
            }

            uiRenderer.showCoordinates = sanitized.showCoordinates
            uiRenderer.showDebugHud = sanitized.showDebugHud
            val prevScale = uiRenderer.uiScale
            uiRenderer.uiScale = sanitized.uiScale
            if (prevScale == uiRenderer.uiScale) {
                uiRenderer.settingsScreen.refreshDynamicState()
            } else {
                uiRenderer.invalidateHud()
            }
        }
    }

    fun updateAndPersistSettings(transform: (GameSettings) -> GameSettings): GameSettings {
        val updated = synchronized(settingsLock) {
            val candidate = settingsManager.sanitize(transform(currentSettings))
            if (candidate == currentSettings) {
                return currentSettings
            }
            applySettings(candidate)
            settingsManager.saveSettings(candidate)
            candidate
        }
        return updated
    }

    fun openAppFolder(): ReferenceActionResult {
        return SystemSettingsActions.openAppFolder(context, appFolderIntentLauncher)
    }

    fun clearCache(): CacheClearResult {
        return SystemSettingsActions.clearCache(context, worldScene.worldRenderer, texturePipeline)
    }

    fun resetAllSettingsToDefaults() {
        synchronized(settingsLock) {
            val defaults = GameSettings(modValues = currentSettings.modValues)
            applySettings(defaults)
            settingsManager.saveSettings(defaults)
        }
        uiRenderer.settingsScreen.rebuildLayout()
    }

    /**
     * Parses and executes a slash command string against the active world scene.
     */
    fun executeCommand(rawInput: String): CommandResult {
        val res = CommandConsole.execute(rawInput, worldScene, camera)
        settingsRegistry.notifyListeners()
        uiRenderer.settingsScreen.refreshDynamicState()
        return res
    }

    // Frame timing & FPS calculation
    private var lastFrameTimeNs: Long = 0
    private var frameCount: Int = 0
    private var fpsTimerNs: Long = 0

    @Volatile
    var currentFrameIndex: Long = 0L
        private set

    // Touch interaction state for showcase mode
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var isDraggingCamera: Boolean = false

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (currentSceneMode == SceneMode.SHOWCASE) {
                val scaleFactor = detector.scaleFactor
                val deltaDistance = (1.0f - scaleFactor) * 6.0f
                camera.zoom(deltaDistance)
                return true
            }
            return false
        }
    })

    fun snapshotModeAndHotbarSlots(): Pair<SceneMode, List<android.graphics.RectF>> = synchronized(modeLock) {
        Pair(currentSceneMode, uiRenderer.getHotbarSlotRects())
    }

    fun setAppState(newState: AppState) {
        var pauseChanged = false
        var leftInventory = false
        synchronized(modeLock) {
            if (currentAppState == newState) return
            if (newState == AppState.DEBUG_OPTIONS && !currentSettings.enableDebugOptions) {
                return
            }
            if (currentAppState == AppState.INVENTORY && newState != AppState.INVENTORY) {
                leftInventory = true
            }
            currentAppState = newState
            if (newState != AppState.PLAYING &&
                newState != AppState.INVENTORY &&
                newState != AppState.SETTINGS &&
                newState != AppState.DEBUG_OPTIONS &&
                isPaused
            ) {
                isPaused = false
                pauseChanged = true
            }
            touchController.resetAllInputs()
            uiRenderer.onAppStateChanged(newState)
            if (newState == AppState.PLAYING) {
                lastFrameTimeNs = 0L
            }
        }
        if (leftInventory) {
            uiRenderer.inventoryScreen.returnAllItemsAndReset()
        }
        if (pauseChanged) {
            onPauseStateChanged?.invoke(false)
        }
        onAppStateChanged?.invoke(newState)
        Log.i(TAG, "Switched AppState to: $newState")
    }

    fun openInventory() {
        synchronized(modeLock) {
            if (currentAppState != AppState.PLAYING || isPaused || currentSceneMode != SceneMode.WORLD) {
                return
            }
            currentAppState = AppState.INVENTORY
            touchController.resetAllInputs()
            uiRenderer.onAppStateChanged(AppState.INVENTORY)
        }
        onAppStateChanged?.invoke(AppState.INVENTORY)
        Log.i(TAG, "Opened Inventory -> AppState.INVENTORY")
    }

    fun closeInventory() {
        var closed = false
        synchronized(modeLock) {
            if (currentAppState != AppState.INVENTORY) return
            currentAppState = AppState.PLAYING
            lastFrameTimeNs = 0L
            touchController.resetAllInputs()
            uiRenderer.onAppStateChanged(AppState.PLAYING)
            closed = true
        }
        if (closed) {
            uiRenderer.inventoryScreen.returnAllItemsAndReset()
            onAppStateChanged?.invoke(AppState.PLAYING)
            Log.i(TAG, "Closed Inventory -> AppState.PLAYING")
        }
    }

    fun setPaused(paused: Boolean) {
        var shouldSave = false
        synchronized(modeLock) {
            if (currentAppState != AppState.PLAYING || isPaused == paused) return
            isPaused = paused
            shouldSave = paused
            touchController.resetAllInputs()
            uiRenderer.onPauseStateChanged(paused)
            if (!paused) {
                lastFrameTimeNs = 0L
            }
        }
        if (shouldSave) {
            worldScene.saveCurrentWorld(blocking = false)
        }
        onPauseStateChanged?.invoke(paused)
        Log.i(TAG, "Set paused state to: $paused")
    }

    fun openSettingsFrom(fromState: AppState) {
        synchronized(modeLock) {
            settingsReturnState = fromState
        }
        setAppState(AppState.SETTINGS)
    }

    fun navigateBackFromSettings() {
        val stateNow = synchronized(modeLock) { currentAppState }
        if (stateNow == AppState.DEBUG_OPTIONS) {
            setAppState(AppState.SETTINGS)
            return
        }
        val target = synchronized(modeLock) { settingsReturnState }
        setAppState(target)
    }

    fun openCreateWorldScreen() {
        uiRenderer.createWorldScreen.resetDefaults()
        setAppState(AppState.CREATE_WORLD)
    }

    /**
     * Initializes a new [World] from [session], provisions its save folder in `files/saves/`,
     * starts chunk streaming, and transitions to [AppState.PLAYING].
     */
    fun startWorldSession(session: WorldSession) {
        val worldDir = WorldSaveManager.resolveNewWorldFolder(context, session.name)
        val storage = WorldSaveStorage(worldDir)
        synchronized(modeLock) {
            isPaused = false
            currentSceneMode = SceneMode.WORLD
            worldScene.startSession(session, camera, texturePipeline, storage)
            touchController.resetAllInputs()
            uiRenderer.onSceneModeChanged(SceneMode.WORLD)
            currentAppState = AppState.PLAYING
            uiRenderer.onAppStateChanged(AppState.PLAYING)
            lastFrameTimeNs = 0L
        }
        onPauseStateChanged?.invoke(false)
        onAppStateChanged?.invoke(AppState.PLAYING)
        Log.i(TAG, "Started world session: ${session.name} (dir=${worldDir.name}, mode=${session.gamemode}, type=${session.worldType}, seed=${session.seed})")
    }

    /**
     * Loads a persisted world from `files/saves/<folderName>/` and transitions to [AppState.PLAYING].
     */
    fun loadSavedWorld(saveData: WorldSaveData) {
        val worldDir = WorldSaveManager.resolveExistingWorldFolder(
            context = context,
            folderName = saveData.folderName.ifBlank { saveData.worldName }
        )
        val storage = WorldSaveStorage(worldDir)
        synchronized(modeLock) {
            isPaused = false
            currentSceneMode = SceneMode.WORLD
            worldScene.loadSavedWorld(saveData, storage, camera, texturePipeline)
            touchController.resetAllInputs()
            uiRenderer.onSceneModeChanged(SceneMode.WORLD)
            currentAppState = AppState.PLAYING
            uiRenderer.onAppStateChanged(AppState.PLAYING)
            lastFrameTimeNs = 0L
        }
        onPauseStateChanged?.invoke(false)
        onAppStateChanged?.invoke(AppState.PLAYING)
        Log.i(TAG, "Loaded saved world: ${saveData.worldName} (dir=${worldDir.name})")
    }

    /**
     * Saves the active [World] session synchronously, frees chunk/entity memory, and returns to [AppState.WORLD_SELECT].
     */
    fun saveAndQuitToWorldSelect() {
        synchronized(modeLock) {
            isPaused = false
            worldScene.saveCurrentWorld(blocking = true)
            worldScene.destroySession()
            touchController.resetAllInputs()
            currentAppState = AppState.WORLD_SELECT
            uiRenderer.onAppStateChanged(AppState.WORLD_SELECT)
        }
        onPauseStateChanged?.invoke(false)
        onAppStateChanged?.invoke(AppState.WORLD_SELECT)
        Log.i(TAG, "Saved & quit world session -> WORLD_SELECT")
    }

    fun handleMenuButtonAction(buttonId: MenuButtonId) {
        when (buttonId) {
            MenuButtonId.SINGLEPLAYER -> setAppState(AppState.WORLD_SELECT)
            MenuButtonId.MULTIPLAYER -> Unit // Disabled: intentionally does nothing
            MenuButtonId.SETTINGS -> openSettingsFrom(AppState.MENU)
            MenuButtonId.QUIT -> quitHandler.invoke()
            MenuButtonId.SETTINGS_FOV_MINUS,
            MenuButtonId.SETTINGS_FOV_PLUS,
            MenuButtonId.SETTINGS_BACK -> uiRenderer.settingsScreen.handleButtonClick(buttonId)
            MenuButtonId.SETTINGS_DYNAMIC -> Unit
            MenuButtonId.WORLD_SELECT_ENTRY_0,
            MenuButtonId.WORLD_SELECT_ENTRY_1,
            MenuButtonId.WORLD_SELECT_ENTRY_2,
            MenuButtonId.WORLD_SELECT_ENTRY_3,
            MenuButtonId.WORLD_SELECT_ENTRY_4,
            MenuButtonId.WORLD_SELECT_PLAY,
            MenuButtonId.WORLD_SELECT_CREATE_NEW,
            MenuButtonId.WORLD_SELECT_DELETE,
            MenuButtonId.WORLD_SELECT_BACK -> uiRenderer.worldSelectScreen.handleButtonClick(buttonId)
            MenuButtonId.CREATE_WORLD_GAMEMODE,
            MenuButtonId.CREATE_WORLD_TYPE,
            MenuButtonId.CREATE_WORLD_CREATE,
            MenuButtonId.CREATE_WORLD_CANCEL -> uiRenderer.createWorldScreen.handleButtonClick(buttonId)
            MenuButtonId.PAUSE_RESUME,
            MenuButtonId.PAUSE_SETTINGS,
            MenuButtonId.PAUSE_SAVE_AND_QUIT -> uiRenderer.pauseScreen.handleButtonClick(buttonId)
        }
    }

    fun switchSceneMode(newMode: SceneMode) {
        synchronized(modeLock) {
            uiRenderer.onSceneModeChanged(newMode)
            when (newMode) {
                SceneMode.WORLD -> {
                    val p = worldScene.player
                    camera.setFirstPersonView(p.posX, p.eyeY, p.posZ, p.pitch, p.yaw)
                }
                SceneMode.SHOWCASE -> {
                    camera.minDistance = 1.2f
                    camera.maxDistance = 25f
                    camera.setTarget(0f, 0f, 0f, dist = 4.2f, p = 28f, y = 45f)
                }
            }
            currentSceneMode = newMode
            touchController.resetAllInputs()
        }
        Log.i(TAG, "Switched Scene Mode to: $newMode")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i(TAG, "onSurfaceCreated: Initializing Tessellate Graphics Runtime")
        backend.init()

        // Initialize texture pipeline on the active GL thread
        texturePipeline.initGl()

        // Load prototype and controller textures from assets
        for (id in TextureConstants.ALL_ASSET_IDS) {
            val path = TextureConstants.getAssetPath(id)
            val success = texturePipeline.loadTexture(id, path)
            if (!success) {
                Log.w(TAG, "Failed initial load for asset: $path")
            }
        }

        // Build 64x64 texture atlas for world chunk single-draw-call rendering
        texturePipeline.buildAtlas()

        // Initialize World Scene with Player controller
        worldScene.init(this)

        // Initialize Preserved Diagnostic Showcase Scene
        showcaseScene.init(this)

        // Initialize 2D UI & HUD buffers & shaders on the active GL thread
        uiRenderer.initGl()

        // Default to First-Person World Mode
        switchSceneMode(SceneMode.WORLD)

        // Reset timer
        lastFrameTimeNs = SystemClock.elapsedRealtimeNanos()
        fpsTimerNs = lastFrameTimeNs
        frameCount = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged: ${width}x${height} (Landscape)")
        backend.setViewport(0, 0, width, height)

        val aspect = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f
        camera.updateProjection(aspect)
        touchController.onSurfaceChanged(width, height)
        uiRenderer.onSurfaceChanged(width, height)
        onSurfaceLayoutChanged?.invoke(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNs = SystemClock.elapsedRealtimeNanos()
        val currentNs = frameStartNs
        if (lastFrameTimeNs == 0L) lastFrameTimeNs = currentNs
        val deltaSec = ((currentNs - lastFrameTimeNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
        lastFrameTimeNs = currentNs

        // FPS Calculation
        frameCount++
        if (currentNs - fpsTimerNs >= 1_000_000_000L) {
            uiRenderer.currentFps = frameCount
            uiRenderer.currentFrameTimeMs = 1000f / frameCount.coerceAtLeast(1)
            frameCount = 0
            fpsTimerNs = currentNs
        }

        synchronized(modeLock) {
            val activeFrameIndex = ++currentFrameIndex
            worldScene.renderFrameIndex = activeFrameIndex
            worldScene.worldRenderer.nativeBufferPool.floodGcTick(activeFrameIndex)

            // Flush any UI-thread texture filter mode toggles or deferred GPU cleanup requests safely on the GL thread
            texturePipeline.applyPendingFilterMode()
            if (gl != null) {
                worldScene.worldRenderer.processPendingGpuFlushIfSafe(texturePipeline, isGlThread = true)
            }

            val frameAppState = currentAppState
            val frameMode = currentSceneMode
            val framePaused = isPaused

            when {
                frameAppState == AppState.INVENTORY -> {
                    // Pause 3D world physics/ticking while rendering the frozen 3D world behind the inventory overlay
                    backend.clear(0.45f, 0.65f, 1.0f, 1.0f)
                    when (frameMode) {
                        SceneMode.WORLD -> worldScene.render(this)
                        SceneMode.SHOWCASE -> showcaseScene.render(this)
                    }
                    uiRenderer.renderUi(this, frameMode, AppState.INVENTORY, isPaused = false)
                }
                frameAppState != AppState.PLAYING -> {
                    // Pause 3D world physics/ticking and render 2D UI over a solid background color
                    backend.clear(0.09f, 0.11f, 0.14f, 1.0f)
                    uiRenderer.renderUi(this, frameMode, frameAppState, isPaused = false)
                }
                else -> {
                    // Clear framebuffer to sky blue before rendering 3D world geometry
                    backend.clear(0.45f, 0.65f, 1.0f, 1.0f)

                    // Update and Render Active 3D Scene (pause updates when Pause Menu is open)
                    when (frameMode) {
                        SceneMode.WORLD -> {
                            if (!framePaused) {
                                worldScene.updatePlayer(deltaSec, touchController, camera)
                                worldScene.update(deltaSec)
                            }
                            worldScene.render(this)
                        }
                        SceneMode.SHOWCASE -> {
                            if (!framePaused) {
                                showcaseScene.update(deltaSec)
                            }
                            showcaseScene.render(this)
                        }
                    }

                    // Render In-Game Textured UI, Multitouch D-Pad, and HUD (or Pause Menu overlay)
                    uiRenderer.renderUi(this, frameMode, frameAppState, isPaused = framePaused)
                }
            }
        }
        applyFrameRateCap(gl, frameStartNs)
    }

    private fun applyFrameRateCap(gl: GL10?, frameStartNs: Long) {
        if (gl == null) return
        val maxFps = currentSettings.maxFps
        if (maxFps <= 0) return
        val targetFrameNs = 1_000_000_000L / maxFps
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - frameStartNs
        val remainingMs = (targetFrameNs - elapsedNs) / 1_000_000L
        if (remainingMs in 1L..50L) {
            try {
                Thread.sleep(remainingMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val state = currentAppState
        val paused = isPaused

        if (state == AppState.INVENTORY) {
            return uiRenderer.onInventoryTouchEvent(event)
        }

        // Route menu screens & pause overlay directly through TouchInputController
        if (state != AppState.PLAYING || paused) {
            return touchController.handleTouchEvent(
                event = event,
                player = worldScene.player,
                isWorldMode = false,
                worldScene = null,
                appState = state,
                menuButtons = uiRenderer.getActiveMenuButtons(state, paused),
                onButtonClick = { buttonId -> handleMenuButtonAction(buttonId) },
                onMenuSliderTouch = { x, y, action -> uiRenderer.handleSettingsSliderTouch(x, y, action) },
                isPausedOverlay = paused,
                onUiButtonClick = { button -> uiRenderer.settingsScreen.handleButtonClick(button) }
            )
        }

        // In Showcase mode: pinch zoom and orbit dragging
        if (currentSceneMode == SceneMode.SHOWCASE) {
            scaleGestureDetector.onTouchEvent(event)
            if (scaleGestureDetector.isInProgress) {
                isDraggingCamera = false
                return true
            }

            if (uiRenderer.onTouchEvent(event, this)) {
                isDraggingCamera = false
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isDraggingCamera = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingCamera && event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        lastTouchX = event.x
                        lastTouchY = event.y

                        camera.orbit(-dy * 0.35f, dx * 0.35f)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingCamera = false
                }
            }
            return false
        }

        // In World mode: First-Person Multitouch Player Controls & HUD
        // 1. Check HUD buttons and hotbars
        if (uiRenderer.onTouchEvent(event, this)) {
            return true
        }

        // 2. Multitouch D-pad movement, direct camera look, and touchscreen mining/placement
        return touchController.handleTouchEvent(
            event = event,
            player = worldScene.player,
            isWorldMode = true,
            worldScene = worldScene,
            appState = AppState.PLAYING
        )
    }

    override fun onSurfaceCreated() {
        onSurfaceCreated(null, null)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        onSurfaceChanged(null, width, height)
    }

    override fun onDrawFrame() {
        onDrawFrame(null)
    }

    override fun onPause() {
        Log.i(TAG, "TessellateEngine: onPause")
        synchronized(modeLock) {
            touchController.resetAllInputs()
            if (currentAppState == AppState.PLAYING || currentAppState == AppState.INVENTORY) {
                worldScene.saveCurrentWorld(blocking = false)
            }
        }
    }

    override fun onResume() {
        Log.i(TAG, "TessellateEngine: onResume")
        touchController.resetAllInputs()
    }

    override fun onDestroy() {
        Log.i(TAG, "TessellateEngine: onDestroy - Releasing resources")
        synchronized(modeLock) {
            touchController.resetAllInputs()
            if (currentAppState == AppState.PLAYING || currentAppState == AppState.INVENTORY) {
                if (currentAppState == AppState.INVENTORY) {
                    uiRenderer.inventoryScreen.returnAllItemsAndReset()
                }
                worldScene.saveCurrentWorld(blocking = true)
            }
            worldScene.worldRenderer.parallelMesher.shutdown()
            worldScene.worldRenderer.nativeBufferPool.release()
            worldScene.release()
            showcaseScene.release()
            uiRenderer.release()
            texturePipeline.release()
            backend.release()
        }
    }

    companion object {
        private const val TAG = "Tessellate.Engine"
    }
}
