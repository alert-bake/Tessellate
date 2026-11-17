package com.nitrous.tessellate

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.ResourcesCompat
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.graphics.TessellateSurfaceView
import com.nitrous.tessellate.ui.CreateWorldField

/**
 * Application entry point hosting the OpenGL ES 3.0 rendering surface, Android lifecycle hooks,
 * collapsible developer command console overlay, and text input bridges for Create World.
 */
class MainActivity : ComponentActivity() {

    var surfaceView: TessellateSurfaceView? = null
        private set

    lateinit var consoleToggleButton: Button
        private set
    lateinit var consoleOverlayContainer: LinearLayout
        private set
    lateinit var consoleInputEditText: EditText
        private set
    lateinit var consoleRunButton: Button
        private set
    lateinit var consoleCloseButton: Button
        private set
    lateinit var consoleStatusText: TextView
        private set

    lateinit var createWorldNameEditText: EditText
        private set
    lateinit var createWorldSeedEditText: EditText
        private set

    private var isSyncingCreateWorldInputs: Boolean = false
    private var lastObservedState: AppState? = null
    private lateinit var backPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock gameplay to landscape orientation for dual-thumb touch controls
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Keep screen on during rendering & configure window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Initialize OpenGL ES 3.0 surface view as the primary runtime container
        val glView = TessellateSurfaceView(this).also { view ->
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        surfaceView = glView
        rootContainer.addView(glView)

        buildCommandConsoleOverlay(rootContainer)
        buildCreateWorldInputsOverlay(rootContainer, glView)
        setContentView(rootContainer)

        rootContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (w > 0 && h > 0 && (w != oldW || h != oldH)) {
                if (glView.engine.currentAppState == AppState.CREATE_WORLD) {
                    positionCreateWorldInputs()
                }
            }
        }

        glView.engine.onAppStateChanged = { state ->
            runOnUiThread {
                updateUiOverlaysForState(state, glView.engine.isPaused)
            }
        }
        glView.engine.onPauseStateChanged = { paused ->
            runOnUiThread {
                updateUiOverlaysForState(glView.engine.currentAppState, paused)
            }
        }
        glView.engine.onSurfaceLayoutChanged = { _, _ ->
            runOnUiThread {
                if (glView.engine.currentAppState == AppState.CREATE_WORLD) {
                    positionCreateWorldInputs()
                }
            }
        }
        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                handleBackNavigation(this)
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        updateUiOverlaysForState(glView.engine.currentAppState, glView.engine.isPaused)
    }

    private fun updateUiOverlaysForState(state: AppState, isPaused: Boolean) {
        if (state == AppState.PLAYING && !isPaused) {
            if (consoleOverlayContainer.visibility != View.VISIBLE) {
                consoleToggleButton.visibility = View.VISIBLE
            }
        } else {
            if (consoleOverlayContainer.visibility == View.VISIBLE) {
                closeCommandConsole()
            }
            consoleToggleButton.visibility = View.GONE
        }

        val engine = surfaceView?.engine
        if (state == AppState.CREATE_WORLD && engine != null) {
            val createScreen = engine.uiRenderer.createWorldScreen
            if (lastObservedState != AppState.CREATE_WORLD) {
                isSyncingCreateWorldInputs = true
                createWorldNameEditText.setText(createScreen.worldName)
                createWorldNameEditText.setSelection(createWorldNameEditText.text?.length ?: 0)
                createWorldSeedEditText.setText(createScreen.seedText)
                createWorldSeedEditText.setSelection(createWorldSeedEditText.text?.length ?: 0)
                isSyncingCreateWorldInputs = false
            }
            positionCreateWorldInputs()
            createWorldNameEditText.visibility = View.VISIBLE
            createWorldSeedEditText.visibility = View.VISIBLE
        } else {
            if (createWorldNameEditText.visibility == View.VISIBLE || createWorldSeedEditText.visibility == View.VISIBLE) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(createWorldNameEditText.windowToken, 0)
                imm?.hideSoftInputFromWindow(createWorldSeedEditText.windowToken, 0)
                createWorldNameEditText.clearFocus()
                createWorldSeedEditText.clearFocus()
                engine?.uiRenderer?.createWorldScreen?.focusedField = CreateWorldField.NONE
                enableImmersiveMode()
            }
            createWorldNameEditText.visibility = View.GONE
            createWorldSeedEditText.visibility = View.GONE
        }

        lastObservedState = state
        refreshBackCallbackEnabled()
    }

    private fun resolvePixelTypeface(style: Int = Typeface.NORMAL): Typeface {
        val base = try {
            ResourcesCompat.getFont(this, R.font.silkscreen)
        } catch (_: Exception) {
            null
        } ?: Typeface.MONOSPACE
        return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
    }

    private fun buildCreateWorldInputsOverlay(rootContainer: FrameLayout, glView: TessellateSurfaceView) {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val createScreen = glView.engine.uiRenderer.createWorldScreen
        createScreen.useNativeTextInputs = true
        val pixelTypeface = resolvePixelTypeface()

        fun styleTransparentInputBridge(
            editText: EditText,
            hintRes: Int,
            tagValue: String,
            field: CreateWorldField
        ) {
            editText.apply {
                hint = getString(hintRes)
                contentDescription = getString(hintRes)
                tag = tagValue
                visibility = View.GONE
                // Transparent visual surface: OpenGL BitmapFontRenderer draws crisp app-font glyphs & caret underneath
                background = null
                setTextColor(Color.TRANSPARENT)
                setHintTextColor(Color.TRANSPARENT)
                highlightColor = Color.TRANSPARENT
                isCursorVisible = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = pixelTypeface
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_DONE
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minHeight = dp(48)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        createScreen.focusedField = field
                        createScreen.cursorIndex = selectionStart.coerceAtLeast(0)
                    } else if (createScreen.focusedField == field) {
                        createScreen.focusedField = CreateWorldField.NONE
                    }
                }
                setOnClickListener {
                    createScreen.focusedField = field
                    createScreen.cursorIndex = selectionStart.coerceAtLeast(0)
                }
                setOnEditorActionListener { v, actionId, event ->
                    val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT || isEnter) {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(v.windowToken, 0)
                        v.clearFocus()
                        createScreen.focusedField = CreateWorldField.NONE
                        enableImmersiveMode()
                        true
                    } else {
                        false
                    }
                }
            }
        }

        createWorldNameEditText = BridgeEditText(this).apply {
            onSelectionMoved = { pos ->
                if (!isSyncingCreateWorldInputs && createScreen.focusedField == CreateWorldField.WORLD_NAME) {
                    createScreen.cursorIndex = pos
                }
            }
        }
        styleTransparentInputBridge(
            createWorldNameEditText,
            R.string.create_world_name_hint,
            "create_world_name_input",
            CreateWorldField.WORLD_NAME
        )
        createWorldNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!isSyncingCreateWorldInputs) {
                    createScreen.worldName = s?.toString().orEmpty()
                    createScreen.cursorIndex = createWorldNameEditText.selectionStart.coerceAtLeast(0)
                }
            }
        })

        createWorldSeedEditText = BridgeEditText(this).apply {
            onSelectionMoved = { pos ->
                if (!isSyncingCreateWorldInputs && createScreen.focusedField == CreateWorldField.SEED) {
                    createScreen.cursorIndex = pos
                }
            }
        }
        styleTransparentInputBridge(
            createWorldSeedEditText,
            R.string.create_world_seed_hint,
            "create_world_seed_input",
            CreateWorldField.SEED
        )
        createWorldSeedEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!isSyncingCreateWorldInputs) {
                    createScreen.seedText = s?.toString().orEmpty()
                    createScreen.cursorIndex = createWorldSeedEditText.selectionStart.coerceAtLeast(0)
                }
            }
        })

        rootContainer.addView(
            createWorldNameEditText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
        rootContainer.addView(
            createWorldSeedEditText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun positionCreateWorldInputs() {
        val engine = surfaceView?.engine ?: return
        val createScreen = engine.uiRenderer.createWorldScreen
        val nameRect = createScreen.worldNameRect
        val seedRect = createScreen.seedRect

        if (!nameRect.isEmpty) {
            createWorldNameEditText.layoutParams = FrameLayout.LayoutParams(
                nameRect.width().toInt().coerceAtLeast(120),
                nameRect.height().toInt().coerceAtLeast(48)
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = nameRect.left.toInt()
                topMargin = nameRect.top.toInt()
            }
        }
        if (!seedRect.isEmpty) {
            createWorldSeedEditText.layoutParams = FrameLayout.LayoutParams(
                seedRect.width().toInt().coerceAtLeast(120),
                seedRect.height().toInt().coerceAtLeast(48)
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = seedRect.left.toInt()
                topMargin = seedRect.top.toInt()
            }
        }
    }

    private fun buildCommandConsoleOverlay(rootContainer: FrameLayout) {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val pixelTypeface = resolvePixelTypeface()
        val pixelBoldTypeface = resolvePixelTypeface(Typeface.BOLD)

        // 1. Compact semi-transparent debug toggle button at top-center
        consoleToggleButton = Button(this).apply {
            text = getString(R.string.console_toggle_label)
            contentDescription = getString(R.string.console_toggle_cd)
            tag = "console_toggle_button"
            setTextColor(Color.rgb(120, 230, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = pixelBoldTypeface
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(180, 14, 18, 28))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.argb(200, 70, 180, 240))
            }
            setOnClickListener { openCommandConsole() }
        }

        val toggleParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(8)
        }
        rootContainer.addView(consoleToggleButton, toggleParams)

        // 2. Semi-transparent top developer command console bar (initially hidden)
        consoleOverlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            tag = "console_overlay"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 12, 16, 24))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.argb(220, 90, 165, 250))
            }
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        consoleInputEditText = EditText(this).apply {
            hint = getString(R.string.console_input_hint)
            tag = "console_input"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(150, 180, 195, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = pixelTypeface
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(170, 24, 30, 44))
                cornerRadius = dp(6).toFloat()
            }
            setOnEditorActionListener { _, actionId, event ->
                val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND || isEnter) {
                    submitConsoleCommand()
                    true
                } else {
                    false
                }
            }
        }

        val inputParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
        inputRow.addView(consoleInputEditText, inputParams)

        consoleRunButton = Button(this).apply {
            text = getString(R.string.console_run_label)
            tag = "console_run_button"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = pixelBoldTypeface
            minWidth = dp(56)
            minHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(215, 42, 132, 92))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { submitConsoleCommand() }
        }
        val runParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(6)
        }
        inputRow.addView(consoleRunButton, runParams)

        consoleCloseButton = Button(this).apply {
            text = "X"
            contentDescription = getString(R.string.console_close_cd)
            tag = "console_close_button"
            setTextColor(Color.rgb(255, 190, 190))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = pixelBoldTypeface
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(190, 120, 40, 48))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { closeCommandConsole() }
        }
        inputRow.addView(consoleCloseButton)

        consoleOverlayContainer.addView(
            inputRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        consoleStatusText = TextView(this).apply {
            tag = "console_status"
            setTextColor(Color.rgb(160, 220, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = pixelTypeface
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        consoleOverlayContainer.addView(consoleStatusText)

        val overlayWidth = dp(460).coerceAtLeast(320)
        val overlayParams = FrameLayout.LayoutParams(
            overlayWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(8)
        }
        rootContainer.addView(consoleOverlayContainer, overlayParams)
    }

    fun openCommandConsole() {
        consoleToggleButton.visibility = View.GONE
        consoleOverlayContainer.visibility = View.VISIBLE
        refreshBackCallbackEnabled()
        consoleInputEditText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(consoleInputEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    fun closeCommandConsole() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(consoleInputEditText.windowToken, 0)
        consoleOverlayContainer.visibility = View.GONE
        consoleToggleButton.visibility = View.VISIBLE
        refreshBackCallbackEnabled()
        enableImmersiveMode()
    }

    fun submitConsoleCommand(): Boolean {
        val raw = consoleInputEditText.text?.toString().orEmpty()
        val engine = surfaceView?.engine ?: return false
        val result = engine.executeCommand(raw)

        consoleStatusText.visibility = View.VISIBLE
        consoleStatusText.text = result.message
        consoleStatusText.setTextColor(
            if (result.success) Color.rgb(110, 240, 165) else Color.rgb(255, 130, 130)
        )
        if (result.success) {
            consoleInputEditText.setText("")
        }
        return result.success
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun refreshBackCallbackEnabled() {
        if (!::backPressedCallback.isInitialized) return
        val engine = surfaceView?.engine
        val consoleOpen = ::consoleOverlayContainer.isInitialized && consoleOverlayContainer.visibility == View.VISIBLE
        backPressedCallback.isEnabled = consoleOpen || (engine != null && engine.currentAppState != AppState.MENU)
    }

    private fun handleBackNavigation(callback: OnBackPressedCallback) {
        val engine = surfaceView?.engine
        if (consoleOverlayContainer.visibility == View.VISIBLE) {
            closeCommandConsole()
            return
        }
        if (engine != null) {
            when (engine.currentAppState) {
                AppState.SETTINGS, AppState.DEBUG_OPTIONS -> {
                    engine.navigateBackFromSettings()
                    return
                }
                AppState.CREATE_WORLD -> {
                    engine.setAppState(AppState.WORLD_SELECT)
                    return
                }
                AppState.WORLD_SELECT -> {
                    engine.setAppState(AppState.MENU)
                    return
                }
                AppState.PLAYING -> {
                    engine.setPaused(!engine.isPaused)
                    return
                }
                AppState.MENU -> Unit
            }
        }
        callback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        refreshBackCallbackEnabled()
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        surfaceView?.handleResume()
    }

    override fun onPause() {
        surfaceView?.handlePause()
        super.onPause()
    }

    override fun onDestroy() {
        val engine = surfaceView?.engine
        if (engine != null && engine.currentAppState == AppState.PLAYING) {
            engine.worldScene.saveCurrentWorld(blocking = true)
        }
        surfaceView?.handleDestroy()
        surfaceView = null
        super.onDestroy()
    }

    private class BridgeEditText(context: Context) : EditText(context) {
        var onSelectionMoved: ((Int) -> Unit)? = null

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onSelectionMoved?.invoke(selStart.coerceAtLeast(0))
        }
    }
}
