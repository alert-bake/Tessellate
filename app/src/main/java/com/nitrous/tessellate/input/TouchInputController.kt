package com.nitrous.tessellate.input

import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.scene.WorldScene
import com.nitrous.tessellate.ui.MenuButtonId
import com.nitrous.tessellate.ui.UiButton
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Multitouch input controller for D-pad movement, first-person look, and world interaction.
 *
 * Responsibilities:
 * - Directional D-pad multitouch tracking with normalized diagonal movement
 * - Direct, responsive first-person camera look with ZERO inertia and zero slippage
 * - Consistent horizontal and vertical sensitivity
 * - Strict pitch clamping [-89..+89] degrees to prevent camera flipping
 * - Clean multitouch pointer ownership partitioning between D-pad, camera look, and world interaction
 * - Direct touchscreen block mining (touch-and-hold based on block hardness)
 * - Direct touchscreen block placement (touch tap adjacent to targeted face)
 * - Immune to camera jumps when pointers are added or removed
 */
class TouchInputController {

    // D-pad layout bounding box in screen pixels
    val dpadRect = RectF()

    // Jump button layout bounding box in screen pixels (bottom-right, synced with TexturedUiRenderer)
    val jumpButtonRect = RectF()

    private val inputLock = Any()

    // Active button states
    @Volatile
    var isUpPressed: Boolean = false
        private set
    @Volatile
    var isDownPressed: Boolean = false
        private set
    @Volatile
    var isLeftPressed: Boolean = false
        private set
    @Volatile
    var isRightPressed: Boolean = false
        private set
    @Volatile
    var isCrouchPressed: Boolean = false
        private set

    // Normalized directional movement inputs [-1.0..+1.0]
    @Volatile
    var moveForward: Float = 0f
        private set
    @Volatile
    var moveStrafe: Float = 0f
        private set

    // Multitouch D-pad tracking: pointerId -> (x, y)
    private val activeDpadPointers = mutableMapOf<Int, Pair<Float, Float>>()

    // Jump button pointer tracking (prevents camera drag on jump touches)
    private val activeJumpPointers = mutableSetOf<Int>()

    // First-person camera look tracking
    @Volatile
    var lookPointerId: Int? = null
        private set
    private var lastLookX: Float = 0f
    private var lastLookY: Float = 0f

    // Direct, consistent camera sensitivity without smoothing or lagging
    @Volatile
    var lookSensitivityX: Float = 0.22f
    @Volatile
    var lookSensitivityY: Float = 0.22f
    @Volatile
    var invertYAxis: Boolean = false
    @Volatile
    var uiScale: Float = 1.0f

    val isJumpHeld: Boolean
        get() = activeJumpPointers.isNotEmpty()

    fun setJumpPointerActive(pointerId: Int, active: Boolean) {
        if (active) {
            activeJumpPointers.add(pointerId)
        } else {
            activeJumpPointers.remove(pointerId)
        }
    }

    private var lastSurfaceWidth: Int = 1920
    private var lastSurfaceHeight: Int = 1080

    // Direct touchscreen block mining and placement tracking
    @Volatile
    var interactPointerId: Int? = null
        private set
    private var interactStartX: Float = 0f
    private var interactStartY: Float = 0f
    @Volatile
    private var interactDownTimeMs: Long = 0L
    @Volatile
    private var isInteractDragging: Boolean = false

    // Tuning constants
    val touchSlop: Float = 18f               // Movement pixels before considered camera look drag
    val holdMiningDelayMs: Long = 180L       // Hold duration before mining begins

    // Menu and settings button touch tracking
    private var activeMenuPointerId: Int? = null
    private var pressedMenuButton: UiButton? = null
    private var activeSliderPointerId: Int? = null

    fun onSurfaceChanged(width: Int, height: Int) {
        lastSurfaceWidth = width.coerceAtLeast(1)
        lastSurfaceHeight = height.coerceAtLeast(1)
        recomputeControlBounds()
    }

    fun recomputeControlBounds() {
        val width = lastSurfaceWidth
        val height = lastSurfaceHeight
        val scaleFactor = uiScale.coerceIn(0.75f, 1.25f)
        // Landscape layout: D-pad pinned to bottom-left corner with comfortable margins
        val dpadSize = ((height * 0.48f).coerceIn(180f, 270f)) * scaleFactor
        val left = 36f
        val bottom = height - 24f
        val top = bottom - dpadSize
        val right = left + dpadSize
        dpadRect.set(left, top, right, bottom)

        // Landscape layout: Jump button pinned to bottom-right corner matching TexturedUiRenderer
        val jumpSize = dpadSize * 0.56f
        val jumpMargin = 36f
        val jumpBottom = height - 24f
        val jumpTop = jumpBottom - jumpSize
        val jumpRight = width - jumpMargin
        val jumpLeft = jumpRight - jumpSize
        jumpButtonRect.set(jumpLeft, jumpTop, jumpRight, jumpBottom)
    }

    /**
     * Processes Android MotionEvents based on [appState]. Returns true if consumed.
     */
    fun handleTouchEvent(
        event: MotionEvent,
        player: Player,
        isWorldMode: Boolean,
        worldScene: WorldScene? = null,
        appState: AppState = AppState.PLAYING,
        menuButtons: List<UiButton> = emptyList(),
        onButtonClick: ((MenuButtonId) -> Unit)? = null,
        onMenuSliderTouch: ((Float, Float, Int) -> Boolean)? = null,
        isPausedOverlay: Boolean = false,
        onUiButtonClick: ((UiButton) -> Unit)? = null
    ): Boolean {
        if (appState != AppState.PLAYING || isPausedOverlay) {
            return handleMenuTouchEvent(
                event = event,
                appState = appState,
                buttons = menuButtons,
                onButtonClick = onButtonClick,
                onSliderTouch = onMenuSliderTouch,
                onUiButtonClick = onUiButtonClick
            )
        }

        val action = event.actionMasked
        val actionIndex = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)

                // 1. D-Pad pointer capture
                if (isWorldMode && dpadRect.contains(x, y)) {
                    activeDpadPointers[pointerId] = Pair(x, y)
                    recomputeDpadState()
                    return true
                }

                // 2. Jump button hitbox (must intercept BEFORE camera drag / interaction)
                if (isWorldMode && !jumpButtonRect.isEmpty && jumpButtonRect.contains(x, y)) {
                    activeJumpPointers.add(pointerId)
                    player.jump()
                    return true
                }

                // 3. Gameplay Area Pointer Capture (Camera Look and World Interaction)
                if (isWorldMode && x > dpadRect.right + 10f) {
                    val now = SystemClock.uptimeMillis()

                    if (lookPointerId == null) {
                        // First gameplay pointer: starts as potential camera look AND interaction
                        lookPointerId = pointerId
                        lastLookX = x
                        lastLookY = y

                        synchronized(inputLock) {
                            interactPointerId = pointerId
                            interactStartX = x
                            interactStartY = y
                            interactDownTimeMs = now
                            isInteractDragging = false
                        }
                        return true
                    } else if (interactPointerId == null && pointerId != lookPointerId) {
                        // Multitouch: A second gameplay finger is placed while another is looking.
                        // Assign dedicated interaction ownership so look and interaction do not conflict!
                        synchronized(inputLock) {
                            interactPointerId = pointerId
                            interactStartX = x
                            interactStartY = y
                            interactDownTimeMs = now
                            isInteractDragging = false
                        }
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                var handled = false
                val count = event.pointerCount

                for (i in 0 until count) {
                    val pId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)

                    if (activeDpadPointers.containsKey(pId)) {
                        activeDpadPointers[pId] = Pair(x, y)
                        handled = true
                    } else if (activeJumpPointers.contains(pId)) {
                        handled = true
                    } else if (pId == lookPointerId) {
                        // Check if this pointer is also the interaction pointer
                        var startedDrag = false
                        synchronized(inputLock) {
                            if (pId == interactPointerId) {
                                val dragDistance = hypot(x - interactStartX, y - interactStartY)
                                if (dragDistance > touchSlop) {
                                    isInteractDragging = true
                                    startedDrag = true
                                }
                            }
                        }
                        if (startedDrag) {
                            worldScene?.cancelMining()
                        }

                        // Apply direct camera rotation if dragging or not interacting
                        if (pId != interactPointerId || isInteractDragging) {
                            val dx = x - lastLookX
                            val dy = y - lastLookY
                            lastLookX = x
                            lastLookY = y

                            // Direct Minecraft-style camera rotation with NO inertia (invert Y reverses pitch)
                            val pitchSign = if (invertYAxis) 1f else -1f
                            player.yaw = (player.yaw + dx * lookSensitivityX) % 360f
                            player.pitch = (player.pitch + pitchSign * dy * lookSensitivityY).coerceIn(-89f, 89f)
                            handled = true
                        } else {
                            // Stationary interaction: maintain last position without rotation
                            lastLookX = x
                            lastLookY = y
                        }
                    } else if (pId == interactPointerId) {
                        // Secondary dedicated interaction pointer
                        var startedDrag = false
                        synchronized(inputLock) {
                            if (pId == interactPointerId) {
                                val dragDistance = hypot(x - interactStartX, y - interactStartY)
                                if (dragDistance > touchSlop) {
                                    isInteractDragging = true
                                    startedDrag = true
                                }
                            }
                        }
                        if (startedDrag) {
                            worldScene?.cancelMining()
                        }
                        handled = true
                    }
                }

                if (activeDpadPointers.isNotEmpty()) {
                    recomputeDpadState()
                }
                return handled
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                handlePointerRelease(pointerId, worldScene)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (action == MotionEvent.ACTION_UP) {
                    val pointerId = event.getPointerId(actionIndex)
                    handlePointerRelease(pointerId, worldScene)
                } else {
                    worldScene?.cancelMining()
                }
                resetAllInputs()
                return true
            }
        }
        return false
    }

    private fun handleMenuTouchEvent(
        event: MotionEvent,
        appState: AppState,
        buttons: List<UiButton>,
        onButtonClick: ((MenuButtonId) -> Unit)?,
        onSliderTouch: ((Float, Float, Int) -> Boolean)?,
        onUiButtonClick: ((UiButton) -> Unit)?
    ): Boolean {
        if (event.pointerCount <= 0) return false
        val action = event.actionMasked
        val actionIndex = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (actionIndex !in 0 until event.pointerCount) return false
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)

                if (activeMenuPointerId == null && activeSliderPointerId == null) {
                    // Check buttons first
                    for (button in buttons) {
                        if (button.rect.contains(x, y)) {
                            if (button.enabled) {
                                button.isHovered = true
                                pressedMenuButton = button
                                activeMenuPointerId = pointerId
                            }
                            // Consume taps even on disabled buttons (e.g., Multiplayer) without crashing
                            return true
                        }
                    }

                    // In SETTINGS or DEBUG_OPTIONS, allow slider/scroll interaction
                    val isSettingsState = (appState == AppState.SETTINGS || appState == AppState.DEBUG_OPTIONS)
                    if (isSettingsState && onSliderTouch != null && onSliderTouch(x, y, action)) {
                        activeSliderPointerId = pointerId
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val isSettingsState = (appState == AppState.SETTINGS || appState == AppState.DEBUG_OPTIONS)
                val sliderPid = activeSliderPointerId
                if (sliderPid != null && isSettingsState && onSliderTouch != null) {
                    val idx = event.findPointerIndex(sliderPid)
                    if (idx in 0 until event.pointerCount) {
                        onSliderTouch(event.getX(idx), event.getY(idx), MotionEvent.ACTION_MOVE)
                    }
                    return true
                }

                val menuPid = activeMenuPointerId ?: return false
                val pressed = pressedMenuButton ?: return false
                val idx = event.findPointerIndex(menuPid)
                if (idx in 0 until event.pointerCount) {
                    val x = event.getX(idx)
                    val y = event.getY(idx)
                    pressed.isHovered = pressed.enabled && pressed.rect.contains(x, y)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (actionIndex !in 0 until event.pointerCount) return false
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)

                if (pointerId == activeSliderPointerId) {
                    activeSliderPointerId = null
                    onSliderTouch?.invoke(x, y, action)
                    return true
                }

                if (pointerId == activeMenuPointerId) {
                    val pressed = pressedMenuButton
                    pressed?.isHovered = false
                    pressedMenuButton = null
                    activeMenuPointerId = null

                    if (pressed != null && pressed.enabled && pressed.rect.contains(x, y)) {
                        if (pressed.id == MenuButtonId.SETTINGS_DYNAMIC && onUiButtonClick != null) {
                            onUiButtonClick.invoke(pressed)
                        } else {
                            onButtonClick?.invoke(pressed.id)
                        }
                    }
                    return true
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedMenuButton?.isHovered = false
                pressedMenuButton = null
                activeMenuPointerId = null
                if (activeSliderPointerId != null) {
                    activeSliderPointerId = null
                    onSliderTouch?.invoke(0f, 0f, MotionEvent.ACTION_CANCEL)
                }
                return true
            }
        }
        return false
    }

    private fun handlePointerRelease(pointerId: Int, worldScene: WorldScene?) {
        // Jump release
        if (activeJumpPointers.remove(pointerId)) {
            return
        }

        // D-pad release
        if (activeDpadPointers.containsKey(pointerId)) {
            activeDpadPointers.remove(pointerId)
            recomputeDpadState()
        }

        // Interaction release
        val wasInteractPointer: Boolean
        val downTimeSnapshot: Long
        val wasDraggingSnapshot: Boolean
        synchronized(inputLock) {
            wasInteractPointer = (pointerId == interactPointerId)
            downTimeSnapshot = interactDownTimeMs
            wasDraggingSnapshot = isInteractDragging
            if (wasInteractPointer) {
                interactPointerId = null
                isInteractDragging = false
            }
        }
        if (wasInteractPointer) {
            val now = SystemClock.uptimeMillis()
            val duration = now - downTimeSnapshot

            if (!wasDraggingSnapshot && worldScene != null) {
                if (duration < holdMiningDelayMs && !worldScene.isMining) {
                    val heldBlock = worldScene.selectedItem.associatedBlock
                    val hasPlaceableBlock = heldBlock != null && heldBlock != com.nitrous.tessellate.world.BlockType.AIR
                    if (worldScene.isHoldingGreener()) {
                        worldScene.tapTargetedBlock()
                    } else if (worldScene.player.isInstaMine && !hasPlaceableBlock) {
                        val hit = worldScene.currentTargetHit
                        if (hit != null && worldScene.startMining(hit.blockPos)) {
                            worldScene.updateMining(0.001f, hit)
                        }
                    } else {
                        // Quick tap on targeted block: place selected block adjacent to face!
                        worldScene.placeBlockAtTarget()
                    }
                } else {
                    // Releasing hold: cancels incomplete mining
                    worldScene.cancelMining()
                }
            } else {
                worldScene?.cancelMining()
            }
        }

        // Camera look release
        if (pointerId == lookPointerId) {
            lookPointerId = null
        }
    }

    /**
     * Called every game frame to update hold-to-mine progress based on block hardness.
     */
    fun updateInteraction(deltaTimeSeconds: Float, worldScene: WorldScene) {
        val downTimeMs: Long
        synchronized(inputLock) {
            if (interactPointerId == null || isInteractDragging) return
            downTimeMs = interactDownTimeMs
        }

        val elapsed = SystemClock.uptimeMillis() - downTimeMs
        if (elapsed >= holdMiningDelayMs) {
            val hit = worldScene.currentTargetHit
            if (hit != null) {
                if (!worldScene.isMining) {
                    worldScene.startMining(hit.blockPos)
                }
                worldScene.updateMining(deltaTimeSeconds, hit)
            } else {
                worldScene.cancelMining()
            }
        }
    }

    fun recomputeDpadState() {
        var up = false
        var down = false
        var left = false
        var right = false
        var crouch = false

        for ((_, pos) in activeDpadPointers) {
            val (x, y) = pos
            val nx = ((x - dpadRect.left) / dpadRect.width()).coerceIn(0f, 1f)
            val ny = ((y - dpadRect.top) / dpadRect.height()).coerceIn(0f, 1f)

            // Center diamond region (Crouch)
            if (nx in 0.30f..0.70f && ny in 0.30f..0.70f) {
                crouch = true
            }

            // Directional zones & diagonal corners
            if (ny <= 0.42f) {
                up = true
            } else if (ny >= 0.58f) {
                down = true
            }

            if (nx <= 0.42f) {
                left = true
            } else if (nx >= 0.58f) {
                right = true
            }
        }

        isUpPressed = up
        isDownPressed = down
        isLeftPressed = left
        isRightPressed = right
        isCrouchPressed = crouch

        val forwardRaw = (if (up) 1f else 0f) - (if (down) 1f else 0f)
        val strafeRaw = (if (right) 1f else 0f) - (if (left) 1f else 0f)

        val mag = sqrt(forwardRaw * forwardRaw + strafeRaw * strafeRaw)
        if (mag > 1f) {
            moveForward = forwardRaw / mag
            moveStrafe = strafeRaw / mag
        } else {
            moveForward = forwardRaw
            moveStrafe = strafeRaw
        }
    }

    /**
     * Programmatic simulation helper for automated D-pad testing.
     */
    fun simulateDpadTouch(pointerId: Int, nx: Float, ny: Float) {
        val x = dpadRect.left + nx * dpadRect.width()
        val y = dpadRect.top + ny * dpadRect.height()
        activeDpadPointers[pointerId] = Pair(x, y)
        recomputeDpadState()
    }

    fun simulateDpadRelease(pointerId: Int) {
        activeDpadPointers.remove(pointerId)
        recomputeDpadState()
    }

    /**
     * Programmatic simulation helper for camera look dragging.
     */
    fun simulateLookTouch(pointerId: Int, startX: Float, startY: Float) {
        lookPointerId = pointerId
        lastLookX = startX
        lastLookY = startY
    }

    fun simulateLookDrag(x: Float, y: Float, player: Player) {
        val dx = x - lastLookX
        val dy = y - lastLookY
        lastLookX = x
        lastLookY = y
        val pitchSign = if (invertYAxis) 1f else -1f
        player.yaw = (player.yaw + dx * lookSensitivityX) % 360f
        player.pitch = (player.pitch + pitchSign * dy * lookSensitivityY).coerceIn(-89f, 89f)
    }

    fun simulateLookRelease() {
        lookPointerId = null
    }

    fun resetAllInputs() {
        activeDpadPointers.clear()
        activeJumpPointers.clear()
        lookPointerId = null
        synchronized(inputLock) {
            interactPointerId = null
            isInteractDragging = false
        }
        isUpPressed = false
        isDownPressed = false
        isLeftPressed = false
        isRightPressed = false
        isCrouchPressed = false
        moveForward = 0f
        moveStrafe = 0f
        pressedMenuButton?.isHovered = false
        pressedMenuButton = null
        activeMenuPointerId = null
        activeSliderPointerId = null
    }
}
