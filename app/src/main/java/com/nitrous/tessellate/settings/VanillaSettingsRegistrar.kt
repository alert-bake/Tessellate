package com.nitrous.tessellate.settings

import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.item.ItemRegistry
import kotlin.math.roundToInt

/**
 * Registers all built-in Vanilla Settings, Debug Options, and their strongly typed References
 * (Properties, Actions, Commands) into [SettingsRegistry].
 */
object VanillaSettingsRegistrar {

    const val CAT_VIDEO = "video"
    const val CAT_CONTROLS = "controls"
    const val CAT_GENERAL = "general"
    const val CAT_DEBUG_RENDERING = "debug_rendering"
    const val CAT_PLAYER_CHEATS = "player_cheats"
    const val CAT_DEBUG_ACTIONS = "debug_actions"

    fun registerAll(engine: TessellateEngine, registry: SettingsRegistry) {
        val refs = registry.references

        // 1. Register Categories
        registry.registerCategory(SettingCategory(CAT_VIDEO, "Video", screen = SettingScreenTarget.SETTINGS, order = 10))
        registry.registerCategory(SettingCategory(CAT_CONTROLS, "Controls", screen = SettingScreenTarget.SETTINGS, order = 20))
        registry.registerCategory(SettingCategory(CAT_GENERAL, "General", screen = SettingScreenTarget.SETTINGS, order = 30))

        registry.registerCategory(SettingCategory(CAT_DEBUG_RENDERING, "Debug Rendering", screen = SettingScreenTarget.DEBUG_OPTIONS, order = 10))
        registry.registerCategory(SettingCategory(CAT_PLAYER_CHEATS, "Player Cheats", screen = SettingScreenTarget.DEBUG_OPTIONS, order = 20))
        registry.registerCategory(SettingCategory(CAT_DEBUG_ACTIONS, "Debug Actions", screen = SettingScreenTarget.DEBUG_OPTIONS, order = 30))

        // 2. Register Explicit Non-Reflection References
        refs.registerProperty(
            id = "Camera3D.fov",
            valueType = ReferenceValueType.NUMBER,
            getter = { engine.currentSettings.fov.toDouble() },
            setter = { raw ->
                val v = (raw as Number).toFloat().coerceIn(30f, 110f).roundToInt().toFloat()
                engine.updateAndPersistSettings { it.copy(fov = v) }
            }
        )

        refs.registerProperty(
            id = "World.renderDistance",
            valueType = ReferenceValueType.NUMBER,
            getter = { engine.currentSettings.renderDistance.toDouble() },
            setter = { raw ->
                val v = (raw as Number).toDouble().roundToInt().coerceIn(1, 16)
                engine.updateAndPersistSettings { it.copy(renderDistance = v) }
            }
        )

        refs.registerProperty(
            id = "Renderer.maxFps",
            valueType = ReferenceValueType.NUMBER,
            getter = { engine.currentSettings.maxFps.toDouble() },
            setter = { raw ->
                val parsed = when (raw) {
                    is Number -> raw.toInt()
                    is String -> raw.toIntOrNull() ?: 60
                    else -> 60
                }
                val v = if (parsed in GameSettings.VALID_MAX_FPS) parsed else 60
                engine.updateAndPersistSettings { it.copy(maxFps = v) }
            }
        )

        refs.registerProperty(
            id = "Camera3D.viewBobbing",
            valueType = ReferenceValueType.BOOLEAN,
            getter = { engine.currentSettings.viewBobbing },
            setter = { raw ->
                val v = raw as Boolean
                engine.updateAndPersistSettings { it.copy(viewBobbing = v) }
            }
        )

        refs.registerProperty(
            id = "Ui.scale",
            valueType = ReferenceValueType.NUMBER,
            getter = { engine.currentSettings.uiScale.toDouble() },
            setter = { raw ->
                val v = ((raw as Number).toDouble().coerceIn(0.75, 1.25) * 20.0).roundToInt() / 20.0f
                engine.updateAndPersistSettings { it.copy(uiScale = v) }
            }
        )

        refs.registerProperty(
            id = "TouchInputController.sensitivity",
            valueType = ReferenceValueType.NUMBER,
            getter = { engine.currentSettings.sensitivity.toDouble() },
            setter = { raw ->
                val rounded = (((raw as Number).toDouble() * 100.0).roundToInt() / 100.0f).coerceIn(0.05f, 2.0f)
                engine.updateAndPersistSettings { it.copy(sensitivity = rounded) }
            }
        )

        refs.registerProperty(
            id = "TouchInputController.invertYAxis",
            valueType = ReferenceValueType.BOOLEAN,
            getter = { engine.currentSettings.invertYAxis },
            setter = { raw ->
                val v = raw as Boolean
                engine.updateAndPersistSettings { it.copy(invertYAxis = v) }
            }
        )

        refs.registerProperty(
            id = "Player.autoJump",
            valueType = ReferenceValueType.BOOLEAN,
            getter = { engine.currentSettings.autoJump },
            setter = { raw ->
                val v = raw as Boolean
                engine.updateAndPersistSettings { it.copy(autoJump = v) }
            }
        )

        refs.registerProperty(
            id = "Hud.showCoordinates",
            valueType = ReferenceValueType.BOOLEAN,
            getter = { engine.currentSettings.showCoordinates },
            setter = { raw ->
                val v = raw as Boolean
                engine.updateAndPersistSettings { it.copy(showCoordinates = v) }
            }
        )

        refs.registerAction(id = "System.openAppFolder") {
            engine.openAppFolder()
        }

        refs.registerAction(id = "System.clearCache") {
            val res = engine.clearCache()
            ReferenceActionResult(
                true,
                "Cleared ${res.deletedFiles} cached files (${res.freedBytes} B) & queued GPU flush"
            )
        }

        refs.registerAction(id = "Settings.resetDefaults") {
            engine.resetAllSettingsToDefaults()
            ReferenceActionResult(true, "Settings reset to defaults")
        }

        refs.registerProperty(
            id = "App.enableDebugOptions",
            valueType = ReferenceValueType.BOOLEAN,
            getter = { engine.currentSettings.enableDebugOptions },
            setter = { raw ->
                val v = raw as Boolean
                engine.updateAndPersistSettings { it.copy(enableDebugOptions = v) }
            }
        )

        refs.registerAction(id = "App.openDebugOptions") {
            if (!engine.currentSettings.enableDebugOptions) {
                ReferenceActionResult(false, "Debug Options are disabled")
            } else {
                engine.setAppState(AppState.DEBUG_OPTIONS)
                ReferenceActionResult(true, "Opened Debug Options")
            }
        }

        // Debug Options References
        refs.registerProperty(
            id = "Hud.showDebug",
            valueType = ReferenceValueType.BOOLEAN,
            getter = { engine.currentSettings.showDebugHud },
            setter = { raw ->
                val v = raw as Boolean
                engine.updateAndPersistSettings { it.copy(showDebugHud = v) }
            }
        )

        refs.registerProperty(
            id = "WorldRenderer.showHitboxes",
            valueType = ReferenceValueType.BOOLEAN,
            getter = { engine.currentSettings.showHitboxes },
            setter = { raw ->
                val v = raw as Boolean
                engine.updateAndPersistSettings { it.copy(showHitboxes = v) }
            }
        )

        refs.registerProperty(
            id = "Profiler.nanoTime",
            valueType = ReferenceValueType.BOOLEAN,
            getter = { engine.currentSettings.enableNanoTimeProfiler },
            setter = { raw ->
                val v = raw as Boolean
                engine.updateAndPersistSettings { it.copy(enableNanoTimeProfiler = v) }
            }
        )

        refs.registerCommand(
            id = "Command.fly",
            commandLine = "/fly",
            stateType = ReferenceValueType.BOOLEAN,
            stateGetter = { engine.worldScene.player.isFlying },
            stateSetter = { desired ->
                val target = desired as? Boolean ?: false
                if (engine.worldScene.player.isFlying != target) {
                    engine.executeCommand("/fly")
                }
            },
            commandExecutor = { cmd -> engine.executeCommand(cmd) }
        )

        refs.registerCommand(
            id = "Command.instamine",
            commandLine = "/instamine",
            stateType = ReferenceValueType.BOOLEAN,
            stateGetter = { engine.worldScene.player.isInstaMine },
            stateSetter = { desired ->
                val target = desired as? Boolean ?: false
                if (engine.worldScene.player.isInstaMine != target) {
                    engine.executeCommand("/instamine")
                }
            },
            commandExecutor = { cmd -> engine.executeCommand(cmd) }
        )

        refs.registerAction(id = "Debug.give64Dirt") {
            val dirtId = ItemRegistry.DIRT.identifier.toCanonicalString()
            val leftover = engine.worldScene.player.inventory.addItem(dirtId, 64)
            engine.worldScene.syncSelectedItemFromInventory()
            engine.uiRenderer.invalidateHud()
            val added = 64 - leftover
            if (leftover == 0) {
                ReferenceActionResult(true, "Added 64 Dirt to inventory")
            } else if (added > 0) {
                ReferenceActionResult(true, "Added $added Dirt ($leftover did not fit)")
            } else {
                ReferenceActionResult(false, "Inventory is full")
            }
        }

        refs.registerAction(id = "Debug.vaporizeChunk") {
            val removedPos = engine.worldScene.vaporizeTargetedChunk(engine.camera)
            if (removedPos != null) {
                engine.uiRenderer.invalidateHud()
                ReferenceActionResult(true, "Vaporized chunk (${removedPos.x}, ${removedPos.y}, ${removedPos.z})")
            } else {
                ReferenceActionResult(false, "No targeted chunk available")
            }
        }

        refs.registerAction(id = "Renderer.flushGPU") {
            engine.worldScene.worldRenderer.requestSafeGpuFlush()
            ReferenceActionResult(true, "Requested safe GPU resource cleanup")
        }

        // 3. Register Vanilla Settings Definitions (ONLY existing engine functionality)
        val defaults = GameSettings()

        // --- Video ---
        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:fov",
                label = "Field of View",
                description = "Camera horizontal/vertical field of view in degrees",
                controlType = SettingControlType.SLIDER,
                referenceId = "Camera3D.fov",
                defaultValue = defaults.fov.toDouble(),
                categoryId = CAT_VIDEO,
                min = 30.0,
                max = 110.0,
                step = 5.0,
                screen = SettingScreenTarget.SETTINGS,
                order = 10,
                valueFormatter = { v -> "${(v as? Number)?.toDouble()?.roundToInt() ?: 60}" }
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:render_distance",
                label = "Render Distance",
                description = "Radius of streamed voxel chunks",
                controlType = SettingControlType.SLIDER,
                referenceId = "World.renderDistance",
                defaultValue = defaults.renderDistance.toDouble(),
                categoryId = CAT_VIDEO,
                min = 1.0,
                max = 16.0,
                step = 1.0,
                screen = SettingScreenTarget.SETTINGS,
                order = 20,
                valueFormatter = { v -> "${(v as? Number)?.toDouble()?.roundToInt() ?: 4} ch" }
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:max_fps",
                label = "Max FPS",
                description = "Frame rate cap (Unlimited = uncapped)",
                controlType = SettingControlType.SELECTOR,
                referenceId = "Renderer.maxFps",
                defaultValue = defaults.maxFps.toString(),
                categoryId = CAT_VIDEO,
                options = listOf(
                    SelectorOption("30", "30 FPS"),
                    SelectorOption("60", "60 FPS"),
                    SelectorOption("120", "120 FPS"),
                    SelectorOption("0", "Unlimited")
                ),
                screen = SettingScreenTarget.SETTINGS,
                order = 30
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:view_bobbing",
                label = "View Bobbing",
                description = "Subtle walking camera bob while grounded",
                controlType = SettingControlType.TOGGLE,
                referenceId = "Camera3D.viewBobbing",
                defaultValue = defaults.viewBobbing,
                categoryId = CAT_VIDEO,
                screen = SettingScreenTarget.SETTINGS,
                order = 40
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:ui_scale",
                label = "UI Scale",
                description = "HUD and touch control scaling multiplier",
                controlType = SettingControlType.SLIDER,
                referenceId = "Ui.scale",
                defaultValue = defaults.uiScale.toDouble(),
                categoryId = CAT_VIDEO,
                min = 0.75,
                max = 1.25,
                step = 0.05,
                screen = SettingScreenTarget.SETTINGS,
                order = 50,
                valueFormatter = { v -> "${(((v as? Number)?.toDouble() ?: 1.0) * 100.0).roundToInt()}%" }
            )
        )

        // --- Controls ---
        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:sensitivity",
                label = "Sensitivity",
                description = "Touch look rotation speed",
                controlType = SettingControlType.SLIDER,
                referenceId = "TouchInputController.sensitivity",
                defaultValue = defaults.sensitivity.toDouble(),
                categoryId = CAT_CONTROLS,
                min = 0.05,
                max = 2.0,
                step = 0.05,
                screen = SettingScreenTarget.SETTINGS,
                order = 10,
                valueFormatter = { v -> "%.2f".format((v as? Number)?.toDouble() ?: 0.22) }
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:invert_y",
                label = "Invert Y-Axis",
                description = "Inverts vertical touch camera pitch",
                controlType = SettingControlType.TOGGLE,
                referenceId = "TouchInputController.invertYAxis",
                defaultValue = defaults.invertYAxis,
                categoryId = CAT_CONTROLS,
                screen = SettingScreenTarget.SETTINGS,
                order = 20
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:auto_jump",
                label = "Auto Jump",
                description = "Automatically step-jump onto 1-block ledges",
                controlType = SettingControlType.TOGGLE,
                referenceId = "Player.autoJump",
                defaultValue = defaults.autoJump,
                categoryId = CAT_CONTROLS,
                screen = SettingScreenTarget.SETTINGS,
                order = 30
            )
        )

        // --- General ---
        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:show_coordinates",
                label = "Show Coordinates",
                description = "Displays XYZ coordinates in the HUD",
                controlType = SettingControlType.TOGGLE,
                referenceId = "Hud.showCoordinates",
                defaultValue = defaults.showCoordinates,
                categoryId = CAT_GENERAL,
                screen = SettingScreenTarget.SETTINGS,
                order = 10
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:open_app_folder",
                label = "Open App Folder",
                description = "Opens Android/data/com.nitrous.tessellate/files/",
                controlType = SettingControlType.BUTTON,
                referenceId = "System.openAppFolder",
                categoryId = CAT_GENERAL,
                persist = false,
                screen = SettingScreenTarget.SETTINGS,
                order = 20
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:clear_cache",
                label = "Clear Cache",
                description = "Clears temporary cached meshes/textures safely",
                controlType = SettingControlType.BUTTON,
                referenceId = "System.clearCache",
                categoryId = CAT_GENERAL,
                persist = false,
                screen = SettingScreenTarget.SETTINGS,
                order = 30
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:reset_settings",
                label = "Reset Settings",
                description = "Restores all vanilla settings to defaults",
                controlType = SettingControlType.BUTTON,
                referenceId = "Settings.resetDefaults",
                categoryId = CAT_GENERAL,
                persist = false,
                screen = SettingScreenTarget.SETTINGS,
                order = 40
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:enable_debug_options",
                label = "Enable Debug Options",
                description = "Reveals Debug Options in General Settings",
                controlType = SettingControlType.TOGGLE,
                referenceId = "App.enableDebugOptions",
                defaultValue = defaults.enableDebugOptions,
                categoryId = CAT_GENERAL,
                screen = SettingScreenTarget.SETTINGS,
                order = 50
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:open_debug_options",
                label = "Debug Options",
                description = "Opens advanced rendering and cheat tools",
                controlType = SettingControlType.BUTTON,
                referenceId = "App.openDebugOptions",
                categoryId = CAT_GENERAL,
                persist = false,
                screen = SettingScreenTarget.SETTINGS,
                order = 60,
                visibleWhen = { engine.currentSettings.enableDebugOptions }
            )
        )

        // --- Debug Options: Debug Rendering ---
        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:debug_hud",
                label = "Show Debug HUD",
                controlType = SettingControlType.TOGGLE,
                referenceId = "Hud.showDebug",
                defaultValue = defaults.showDebugHud,
                categoryId = CAT_DEBUG_RENDERING,
                screen = SettingScreenTarget.DEBUG_OPTIONS,
                order = 10
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:debug_hitboxes",
                label = "Show Hitboxes",
                controlType = SettingControlType.TOGGLE,
                referenceId = "WorldRenderer.showHitboxes",
                defaultValue = defaults.showHitboxes,
                categoryId = CAT_DEBUG_RENDERING,
                screen = SettingScreenTarget.DEBUG_OPTIONS,
                order = 20
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:debug_nanotime",
                label = "NanoTime Profiler",
                description = "Shows live nanoTime stage timings in HUD & Logcat",
                controlType = SettingControlType.TOGGLE,
                referenceId = "Profiler.nanoTime",
                defaultValue = defaults.enableNanoTimeProfiler,
                categoryId = CAT_DEBUG_RENDERING,
                screen = SettingScreenTarget.DEBUG_OPTIONS,
                order = 30
            )
        )

        // --- Debug Options: Player Cheats ---
        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:debug_flight",
                label = "Flight",
                controlType = SettingControlType.TOGGLE,
                referenceId = "Command.fly",
                defaultValue = false,
                categoryId = CAT_PLAYER_CHEATS,
                persist = false,
                screen = SettingScreenTarget.DEBUG_OPTIONS,
                order = 10
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:debug_instamine",
                label = "Instamine",
                controlType = SettingControlType.TOGGLE,
                referenceId = "Command.instamine",
                defaultValue = false,
                categoryId = CAT_PLAYER_CHEATS,
                persist = false,
                screen = SettingScreenTarget.DEBUG_OPTIONS,
                order = 20
            )
        )

        // --- Debug Options: Debug Actions ---
        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:debug_give_dirt",
                label = "Give 64 Dirt",
                controlType = SettingControlType.BUTTON,
                referenceId = "Debug.give64Dirt",
                categoryId = CAT_DEBUG_ACTIONS,
                persist = false,
                screen = SettingScreenTarget.DEBUG_OPTIONS,
                order = 10
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:debug_vaporize_chunk",
                label = "Vaporize Chunk",
                controlType = SettingControlType.BUTTON,
                referenceId = "Debug.vaporizeChunk",
                categoryId = CAT_DEBUG_ACTIONS,
                requiresConfirmation = true,
                confirmationMessage = "Confirm: Vaporize targeted chunk?",
                persist = false,
                screen = SettingScreenTarget.DEBUG_OPTIONS,
                order = 20
            )
        )

        registry.registerSetting(
            SettingDefinition(
                id = "tessellate:debug_flush_gpu",
                label = "Flush GPU?",
                controlType = SettingControlType.BUTTON,
                referenceId = "Renderer.flushGPU",
                categoryId = CAT_DEBUG_ACTIONS,
                persist = false,
                screen = SettingScreenTarget.DEBUG_OPTIONS,
                order = 30
            )
        )
    }
}
