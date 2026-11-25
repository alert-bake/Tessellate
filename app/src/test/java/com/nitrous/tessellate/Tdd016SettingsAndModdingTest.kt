package com.nitrous.tessellate

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.math.Matrix4
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.modding.ModApprovalStatus
import com.nitrous.tessellate.modding.ModCodeInspector
import com.nitrous.tessellate.modding.ModPermissions
import com.nitrous.tessellate.modding.PermissionsYmlParser
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.settings.GameSettings
import com.nitrous.tessellate.settings.ReferenceOrigin
import com.nitrous.tessellate.settings.SettingCategory
import com.nitrous.tessellate.settings.SettingControlType
import com.nitrous.tessellate.settings.SettingDefinition
import com.nitrous.tessellate.settings.SettingScreenTarget
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.settings.VanillaSettingsRegistrar
import com.nitrous.tessellate.ui.BitmapFontRenderer
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Comprehensive verification test suite for TDD-016:
 * Settings System, Reference Registry, Debug Options, System Actions, & Lua Mod API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd016SettingsAndModdingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        cleanupFiles()
    }

    @After
    fun tearDown() {
        cleanupFiles()
    }

    private fun cleanupFiles() {
        SettingsManager.resolveSettingsFile(context).delete()
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
        File(WorldSaveManager.getBaseFilesDir(context), "mods").deleteRecursively()
        File(WorldSaveManager.getBaseFilesDir(context), "cache").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
    }

    // =========================================================================
    // ACCEPTANCE TESTS 1-6: DATA-DRIVEN VANILLA SETTINGS & PERSISTENCE
    // =========================================================================

    @Test
    fun `acceptance 1 & 5 settings load with per-field fallback and persist cleanly across restart`() {
        val settingsFile = SettingsManager.resolveSettingsFile(context)
        settingsFile.parentFile?.mkdirs()

        // Invalid/out-of-range fields fall back individually without erasing valid fields
        settingsFile.writeText(
            """
            {
              "fov": 95.0,
              "renderDistance": 999,
              "maxFps": 120,
              "viewBobbing": false,
              "uiScale": "bad_float",
              "sensitivity": 0.65,
              "invertYAxis": true,
              "autoJump": true,
              "showCoordinates": false,
              "enableDebugOptions": true
            }
            """.trimIndent()
        )

        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        try {
            assertEquals(95.0f, engine.currentSettings.fov, 1e-4f)
            assertEquals(95.0f, engine.camera.fovDegrees, 1e-4f)
            assertEquals(4, engine.currentSettings.renderDistance) // bad value fell back to default
            assertEquals(120, engine.currentSettings.maxFps)
            assertFalse(engine.currentSettings.viewBobbing)
            assertFalse(engine.camera.viewBobbingEnabled)
            assertEquals(1.0f, engine.currentSettings.uiScale, 1e-4f) // bad string fell back
            assertEquals(0.65f, engine.currentSettings.sensitivity, 1e-4f)
            assertTrue(engine.currentSettings.invertYAxis)
            assertTrue(engine.currentSettings.autoJump)
            assertFalse(engine.currentSettings.showCoordinates)
            assertTrue(engine.currentSettings.enableDebugOptions)

            // Verify NO nonexistent systems (audio / dynamic lighting) are exposed in SettingsRegistry
            val allLabelsAndIds = engine.settingsRegistry.getAllSettings().flatMap { listOf(it.id.lowercase(), it.label.lowercase()) }
            assertFalse(allLabelsAndIds.any { it.contains("audio") || it.contains("volume") || it.contains("sound") || it.contains("lighting") })

            // Modify settings via data-driven UI registry & verify immediate persistence across restart
            assertTrue(engine.settingsRegistry.setValue("tessellate:render_distance", 6).isValid)
            assertTrue(engine.settingsRegistry.setValue("tessellate:max_fps", "30").isValid)
            awaitLastSave(engine)
        } finally {
            engine.worldScene.destroySession()
        }

        val restarted = TessellateEngine(context)
        awaitJob(restarted.initialSettingsLoadJob)
        try {
            assertEquals(6, restarted.currentSettings.renderDistance)
            assertEquals(30, restarted.currentSettings.maxFps)
            assertEquals(95.0f, restarted.camera.fovDegrees, 1e-4f)
            assertTrue(restarted.touchController.invertYAxis)
            assertTrue(restarted.worldScene.player.autoJump)
        } finally {
            restarted.worldScene.destroySession()
        }
    }

    @Test
    fun `acceptance 2-4 changing FOV, render distance, sensitivity, invert Y, view bobbing, auto jump updates live engine`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            // Acceptance 2: Changing FOV immediately updates camera.fovDegrees & projectionMatrix
            engine.openSettingsFrom(AppState.MENU)
            val beforeProj = engine.camera.projectionMatrix.copyOf()
            val plusBtn = engine.uiRenderer.settingsScreen.fovPlusButton
            tapButton(engine, plusBtn.rect.centerX(), plusBtn.rect.centerY(), SystemClock.uptimeMillis())
            assertEquals(65.0f, engine.camera.fovDegrees, 1e-4f)
            assertFalse("Camera projection matrix must update immediately on FOV change", beforeProj.contentEquals(engine.camera.projectionMatrix))

            // Acceptance 3: Changing Render Distance safely updates streaming world chunks
            engine.startWorldSession(WorldSession("DistWorld", GameMode.CREATIVE, WorldType.FLAT, 42L))
            val world = engine.worldScene.world
            assertEquals(4, world.renderDistance)
            world.streamChunksAround(
                engine.worldScene.player.posX,
                engine.worldScene.player.posZ,
                engine.texturePipeline,
                forceCheck = true
            )?.let { awaitJob(it, timeoutMs = 15_000L) }
            // Ensure outer chunk column (4, 1) at distance 3 is loaded before reducing render distance to 2
            world.ensureChunkColumnLoadedSync(4, 1)
            assertTrue(world.hasChunk(4, 0, 1))
            val beforeReduceCount = world.getLoadedChunkCount()

            assertTrue(engine.settingsRegistry.setValue("tessellate:render_distance", 2).isValid)
            assertEquals(2, world.renderDistance)
            assertFalse(
                "Reducing render distance from 4 to 2 must unload outer chunk (4,0,1)",
                world.hasChunk(4, 0, 1)
            )
            assertTrue(
                "Reducing render distance from 4 to 2 must unload outer chunks cleanly",
                world.getLoadedChunkCount() < beforeReduceCount
            )
            val reducedCount = world.getLoadedChunkCount()

            assertTrue(engine.settingsRegistry.setValue("tessellate:render_distance", 5).isValid)
            assertEquals(5, world.renderDistance)
            world.streamChunksAround(
                engine.worldScene.player.posX,
                engine.worldScene.player.posZ,
                engine.texturePipeline,
                forceCheck = true
            )?.let { awaitJob(it, timeoutMs = 15_000L) }
            assertTrue(
                "Increasing render distance from 2 to 5 must stream outer chunk (4,0,1)",
                world.hasChunk(4, 0, 1)
            )
            assertTrue(
                "Increasing render distance from 2 to 5 must stream additional chunks",
                world.getLoadedChunkCount() > reducedCount
            )

            // Acceptance 4: Sensitivity & Invert Y update TouchInputController look behavior
            assertTrue(engine.settingsRegistry.setValue("tessellate:sensitivity", 0.50).isValid)
            assertEquals(0.50f, engine.touchController.lookSensitivityX, 1e-4f)
            assertEquals(0.50f, engine.touchController.lookSensitivityY, 1e-4f)

            val player = engine.worldScene.player
            player.yaw = 0f
            player.pitch = 0f
            engine.touchController.simulateLookTouch(1, 500f, 500f)
            engine.touchController.simulateLookDrag(520f, 510f, player) // +20x, +10y -> non-inverted pitch down (-5)
            assertEquals(10f, player.yaw, 1e-3f)
            assertEquals(-5f, player.pitch, 1e-3f)

            assertTrue(engine.settingsRegistry.setValue("tessellate:invert_y", true).isValid)
            player.pitch = 0f
            engine.touchController.simulateLookTouch(1, 500f, 500f)
            engine.touchController.simulateLookDrag(500f, 510f, player) // inverted pitch up (+5)
            assertEquals(5f, player.pitch, 1e-3f)
            engine.touchController.simulateLookRelease()

            // UI Scale updates HUD/D-pad bounds
            val dpadBefore = engine.touchController.dpadRect.width()
            assertTrue(engine.settingsRegistry.setValue("tessellate:ui_scale", 0.80).isValid)
            assertTrue(engine.touchController.dpadRect.width() < dpadBefore)

            // Auto Jump toggles Player.autoJump
            assertTrue(engine.settingsRegistry.setValue("tessellate:auto_jump", true).isValid)
            assertTrue(player.autoJump)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `acceptance 6 Reset Settings button in UI restores all vanilla defaults`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            assertTrue(engine.settingsRegistry.setValue("tessellate:fov", 100.0).isValid)
            assertTrue(engine.settingsRegistry.setValue("tessellate:render_distance", 8).isValid)
            assertTrue(engine.settingsRegistry.setValue("tessellate:invert_y", true).isValid)
            assertTrue(engine.settingsRegistry.setValue("tessellate:enable_debug_options", true).isValid)
            assertEquals(100f, engine.camera.fovDegrees, 1e-4f)

            engine.openSettingsFrom(AppState.MENU)
            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_GENERAL)

            val resetRow = screen.getVisibleRows().first { it.definition.id == "tessellate:reset_settings" }
            val btn = resetRow.actionButton!!
            tapButton(engine, btn.rect.centerX(), btn.rect.centerY(), SystemClock.uptimeMillis())
            awaitLastSave(engine)

            val defaults = GameSettings()
            assertEquals(defaults.fov, engine.currentSettings.fov, 1e-4f)
            assertEquals(defaults.fov, engine.camera.fovDegrees, 1e-4f)
            assertEquals(defaults.renderDistance, engine.currentSettings.renderDistance)
            assertEquals(defaults.invertYAxis, engine.currentSettings.invertYAxis)
            assertEquals(defaults.enableDebugOptions, engine.currentSettings.enableDebugOptions)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    // =========================================================================
    // ACCEPTANCE TESTS 7-15: DEBUG OPTIONS, CHEATS SYNC, & DEBUG ACTIONS
    // =========================================================================

    @Test
    fun `acceptance 7-10 Debug Options visibility gate, Show Debug HUD, and Show Hitboxes toggles`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            // Acceptance 7: Hidden when disabled & direct navigation guarded
            engine.openSettingsFrom(AppState.MENU)
            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_GENERAL)
            assertFalse(screen.getVisibleRows().any { it.definition.id == "tessellate:open_debug_options" })
            engine.setAppState(AppState.DEBUG_OPTIONS)
            assertEquals(AppState.SETTINGS, engine.currentAppState)

            // Acceptance 8: Toggle Enable Debug Options ON -> Debug Options button appears & navigates
            val enableRow = screen.getVisibleRows().first { it.definition.id == "tessellate:enable_debug_options" }
            tapButton(engine, enableRow.actionButton!!.rect.centerX(), enableRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis())
            assertTrue(engine.currentSettings.enableDebugOptions)

            val openDebugRow = screen.getVisibleRows().firstOrNull { it.definition.id == "tessellate:open_debug_options" }
            assertNotNull("Enabling Debug Options must reveal the Debug Options button", openDebugRow)
            tapButton(engine, openDebugRow!!.actionButton!!.rect.centerX(), openDebugRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 100)
            assertEquals(AppState.DEBUG_OPTIONS, engine.currentAppState)

            // Acceptance 9: Toggle Show Debug HUD OFF/ON
            val debugHudRow = screen.getVisibleRows().first { it.definition.id == "tessellate:debug_hud" }
            val wasDebugHud = engine.uiRenderer.showDebugHud
            tapButton(engine, debugHudRow.actionButton!!.rect.centerX(), debugHudRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 200)
            assertEquals(!wasDebugHud, engine.uiRenderer.showDebugHud)

            // Acceptance 10: Toggle Show Hitboxes ON/OFF
            val hitboxRow = screen.getVisibleRows().first { it.definition.id == "tessellate:debug_hitboxes" }
            assertFalse(engine.worldScene.worldRenderer.showHitboxes)
            tapButton(engine, hitboxRow.actionButton!!.rect.centerX(), hitboxRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 300)
            assertTrue(engine.worldScene.worldRenderer.showHitboxes)

            // Back returns from DEBUG_OPTIONS -> SETTINGS
            tapButton(engine, screen.backButton.rect.centerX(), screen.backButton.rect.centerY(), SystemClock.uptimeMillis() + 400)
            assertEquals(AppState.SETTINGS, engine.currentAppState)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `acceptance 11-12 Flight and Instamine stay synchronized bidirectionally between UI and slash commands`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(WorldSession("CheatWorld", GameMode.SURVIVAL, WorldType.FLAT, 7L))
            engine.settingsRegistry.setValue("tessellate:enable_debug_options", true)
            engine.setAppState(AppState.DEBUG_OPTIONS)

            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_PLAYER_CHEATS)

            // Acceptance 11: Toggle Flight via UI -> /fly toggles it OFF -> UI matches immediately
            val flyRow = screen.getVisibleRows().first { it.definition.id == "tessellate:debug_flight" }
            assertEquals("OFF", flyRow.actionButton!!.label)
            assertFalse(engine.worldScene.player.isFlying)

            tapButton(engine, flyRow.actionButton!!.rect.centerX(), flyRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis())
            assertTrue(engine.worldScene.player.isFlying)
            assertEquals(true, engine.settingsRegistry.getValue("tessellate:debug_flight"))
            assertEquals("ON", screen.getVisibleRows().first { it.definition.id == "tessellate:debug_flight" }.actionButton!!.label)

            // Verify flight physics disables gravity and allows ascending
            val yBeforeFly = engine.worldScene.player.posY
            engine.worldScene.player.update(0.05f, engine.worldScene.world, 0f, 0f, crouchInput = false, jumpInput = true)
            assertTrue("Flying with jumpInput held must ascend", engine.worldScene.player.posY > yBeforeFly)

            // Execute /fly via CommandConsole -> UI row reflects OFF immediately
            val flyCmdRes = engine.executeCommand("/fly")
            assertTrue(flyCmdRes.success)
            assertFalse(engine.worldScene.player.isFlying)
            assertEquals(false, engine.settingsRegistry.getValue("tessellate:debug_flight"))
            assertEquals("OFF", screen.getVisibleRows().first { it.definition.id == "tessellate:debug_flight" }.actionButton!!.label)

            // Acceptance 12: Instamine bidirectional sync with /instamine
            val instaRow = screen.getVisibleRows().first { it.definition.id == "tessellate:debug_instamine" }
            assertFalse(engine.worldScene.player.isInstaMine)
            tapButton(engine, instaRow.actionButton!!.rect.centerX(), instaRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 100)
            assertTrue(engine.worldScene.player.isInstaMine)
            assertEquals("ON", screen.getVisibleRows().first { it.definition.id == "tessellate:debug_instamine" }.actionButton!!.label)

            val instaCmdRes = engine.executeCommand("/instamine")
            assertTrue(instaCmdRes.success)
            assertFalse(engine.worldScene.player.isInstaMine)
            assertEquals("OFF", screen.getVisibleRows().first { it.definition.id == "tessellate:debug_instamine" }.actionButton!!.label)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `acceptance 13-15 Give 64 Dirt respects capacity, Vaporize Chunk confirms safely, and Flush GPU queues safely`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(WorldSession("DebugActionsWorld", GameMode.SURVIVAL, WorldType.FLAT, 123L))
            val player = engine.worldScene.player
            engine.settingsRegistry.setValue("tessellate:enable_debug_options", true)
            engine.setAppState(AppState.DEBUG_OPTIONS)

            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_DEBUG_ACTIONS)

            // Acceptance 13: Give 64 Dirt -> adds 64 dirt
            val giveRow = screen.getVisibleRows().first { it.definition.id == "tessellate:debug_give_dirt" }
            tapButton(engine, giveRow.actionButton!!.rect.centerX(), giveRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis())
            assertEquals("tessellate:dirt", player.inventory.getSlot(0)?.itemId?.lowercase())
            assertEquals(64, player.inventory.getSlot(0)?.count)

            // Fill remaining slots leaving only 4 capacity in slot 8 -> Give 64 Dirt only adds 4
            for (s in 0..7) {
                player.inventory.setSlot(s, ItemStack("tessellate:stone", 64))
            }
            player.inventory.setSlot(8, ItemStack("tessellate:dirt", 60))
            tapButton(engine, giveRow.actionButton!!.rect.centerX(), giveRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 50)
            assertEquals(64, player.inventory.getSlot(8)?.count)

            // Acceptance 14: Vaporize Chunk must require confirmation step before removing chunk
            val targetChunkX = 1
            val targetChunkY = 0
            val targetChunkZ = 1
            assertTrue(engine.worldScene.world.hasChunk(targetChunkX, targetChunkY, targetChunkZ))
            player.posX = 24.5
            player.posY = 8.0
            player.posZ = 24.5
            player.pitch = -85f
            engine.camera.setFirstPersonView(player.posX, player.eyeY, player.posZ, player.pitch, player.yaw)
            engine.worldScene.updateCameraRaycast(engine.camera)

            val vapRow = screen.getVisibleRows().first { it.definition.id == "tessellate:debug_vaporize_chunk" }
            tapButton(engine, vapRow.actionButton!!.rect.centerX(), vapRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 100)
            assertNotNull("Vaporize Chunk must prompt confirmation before deleting chunk", screen.pendingConfirmationSetting)
            assertTrue("Chunk must NOT be removed before confirmation", engine.worldScene.world.hasChunk(targetChunkX, targetChunkY, targetChunkZ))

            // Cancel preserves chunk
            screen.cancelPendingAction()
            assertNull(screen.pendingConfirmationSetting)
            assertTrue(engine.worldScene.world.hasChunk(targetChunkX, targetChunkY, targetChunkZ))

            // Re-trigger and Confirm -> removes targeted chunk safely
            tapButton(engine, vapRow.actionButton!!.rect.centerX(), vapRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 150)
            val res = screen.confirmPendingAction()
            assertNotNull(res)
            assertTrue(res!!.success)
            assertFalse("Confirmed Vaporize Chunk must remove chunk", engine.worldScene.world.hasChunk(targetChunkX, targetChunkY, targetChunkZ))

            // Acceptance 15: Flush GPU? queues safe deferred cleanup without destroying resources mid-draw
            val renderer = engine.worldScene.worldRenderer
            val beforeFlushCount = renderer.gpuFlushCount.get()
            val flushRow = screen.getVisibleRows().first { it.definition.id == "tessellate:debug_flush_gpu" }
            tapButton(engine, flushRow.actionButton!!.rect.centerX(), flushRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 200)
            assertTrue("Flush GPU? must queue a deferred flush rather than destroying mid-draw", renderer.isGpuFlushPending())
            assertTrue(renderer.processPendingGpuFlushIfSafe(engine.texturePipeline, isGlThread = false))
            assertFalse(renderer.isGpuFlushPending())
            assertEquals(beforeFlushCount + 1, renderer.gpuFlushCount.get())
        } finally {
            engine.worldScene.destroySession()
        }
    }

    // =========================================================================
    // ACCEPTANCE TESTS 16-21: LUA MOD SETTINGS API, VALIDATION, PERMISSIONS & ISOLATION
    // =========================================================================

    @Test
    fun `acceptance 16-18 Lua mods register categories, custom references, and callback settings rendered in UI`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            val lua = """
                Weather = { intensity = 50, mode = "rain", enabled = true, strikes = 0 }

                Settings.registerCategory("example:weather", { label = "Weather Mod", order = 50 })

                Settings.register("example:weather_intensity", {
                    type = "slider",
                    label = "Weather Intensity",
                    category = "example:weather",
                    min = 0,
                    max = 100,
                    step = 5,
                    default = 50,
                    get = function()
                        return Weather.intensity
                    end,
                    set = function(value)
                        Weather.intensity = value
                    end
                })

                Settings.registerReference("example:weather_mode_ref", {
                    type = "string",
                    get = function() return Weather.mode end,
                    set = function(v) Weather.mode = v end
                })

                Settings.register("example:weather_mode", {
                    type = "selector",
                    label = "Weather Mode",
                    category = "example:weather",
                    reference = "example:weather_mode_ref",
                    default = "rain",
                    options = {
                        { value = "clear", label = "Clear" },
                        { value = "rain", label = "Rain" },
                        { value = "storm", label = "Storm" }
                    }
                })

                Settings.registerReference("example:lightning_action", {
                    type = "action",
                    action = function()
                        Weather.strikes = Weather.strikes + 1
                        return "Strikes: " .. tostring(Weather.strikes)
                    end
                })

                Settings.register("example:trigger_lightning", {
                    type = "button",
                    label = "Trigger Lightning",
                    category = "example:weather",
                    reference = "example:lightning_action"
                })
            """.trimIndent()

            val result = engine.luaModRuntime.loadMod("example_weather", lua, displayName = "Weather Mod")
            assertTrue("Mod load must succeed without errors: ${result.errors}", result.success)
            assertEquals(3, result.registeredSettings.size)
            assertTrue(engine.settingsRegistry.references.isModReference("example:weather_mode_ref"))
            assertTrue(engine.settingsRegistry.references.isVanillaReference("Camera3D.fov"))

            // Interact via SettingsRegistry & UI SettingsScreen
            engine.openSettingsFrom(AppState.MENU)
            val screen = engine.uiRenderer.settingsScreen
            val weatherCat = screen.getCategoryTabs().firstOrNull { it.id == "example:weather" }
            assertNotNull("Custom mod category tab must be rendered in UI", weatherCat)
            screen.selectCategory("example:weather")

            // Adjust slider through registry -> Lua Weather.intensity updates & persists!
            assertTrue(engine.settingsRegistry.setValue("example:weather_intensity", 85).isValid)
            assertEquals(85, engine.luaModRuntime.evalInMod("example_weather", "return Weather.intensity").toint())

            // Cycle selector through UI button -> Lua Weather.mode updates to "storm"
            val modeRow = screen.getVisibleRows().first { it.definition.id == "example:weather_mode" }
            tapButton(engine, modeRow.actionButton!!.rect.centerX(), modeRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis())
            assertEquals("storm", engine.luaModRuntime.evalInMod("example_weather", "return Weather.mode").tojstring())

            // Click Lua action button in UI
            val btnRow = screen.getVisibleRows().first { it.definition.id == "example:trigger_lightning" }
            tapButton(engine, btnRow.actionButton!!.rect.centerX(), btnRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis() + 50)
            assertEquals(1, engine.luaModRuntime.evalInMod("example_weather", "return Weather.strikes").toint())
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `acceptance 19 invalid references, incompatible control types, and bounds mismatches produce clear errors`() {
        val engine = TessellateEngine(context)
        try {
            val reg = engine.settingsRegistry

            // Unknown reference
            val missingRef = reg.registerSetting(
                SettingDefinition(
                    id = "bad:missing",
                    label = "Broken",
                    controlType = SettingControlType.TOGGLE,
                    referenceId = "NonExistent.prop",
                    categoryId = VanillaSettingsRegistrar.CAT_GENERAL
                )
            )
            assertFalse(missingRef.isValid)
            assertTrue(missingRef.errorMessage!!.contains("unregistered target"))

            // Control-type incompatibility (SLIDER bound to BOOLEAN reference Camera3D.viewBobbing)
            val typeMismatch = reg.registerSetting(
                SettingDefinition(
                    id = "bad:mismatch",
                    label = "Mismatch",
                    controlType = SettingControlType.SLIDER,
                    referenceId = "Camera3D.viewBobbing",
                    categoryId = VanillaSettingsRegistrar.CAT_VIDEO,
                    min = 0.0,
                    max = 10.0
                )
            )
            assertFalse(typeMismatch.isValid)
            assertTrue(typeMismatch.errorMessage!!.contains("incompatible"))

            // Invalid Lua registration inside mod returns actionable error
            val badLua = engine.luaModRuntime.loadMod(
                "broken_mod",
                """
                Settings.register("broken:slider_no_bounds", {
                    type = "slider",
                    label = "Bad Slider",
                    get = function() return 1 end,
                    set = function(v) end
                })
                """.trimIndent()
            )
            assertFalse(badLua.success)
            assertTrue(badLua.errors.any { it.contains("min < max") })
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `acceptance 20-21 mod permissions inspect code + permissions yml, block unauthorized calls, and isolate mods`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            // Code inspector & permissions.yml parser detect declared + undeclared protected APIs & sandbox escapes
            val parsedYml = PermissionsYmlParser.parse(
                """
                mod_id: cheat_helper
                name: Cheat Helper
                permissions:
                  - player.cheats
                """.trimIndent()
            )
            assertEquals("Cheat Helper", parsedYml.displayName)
            assertEquals(setOf(ModPermissions.PLAYER_CHEATS), parsedYml.permissions)

            val modSource = """
                Helper = { flyAttempts = 0, cmdError = "", overwriteError = "" }
                function Helper.tryFly()
                    Player.setFlying(true)
                end
                function Helper.tryUndeclaredCommand()
                    local ok, err = pcall(function() Commands.execute("/clear") end)
                    if not ok then Helper.cmdError = tostring(err) end
                end
                function Helper.tryOverwriteVanilla()
                    local ok, err = pcall(function()
                        Settings.registerCategory("video", { label = "Hacked" })
                    end)
                    if not ok then Helper.overwriteError = tostring(err) end
                end
                Helper.tryOverwriteVanilla()
            """.trimIndent()

            val inspection = ModCodeInspector.inspect(modSource)
            assertTrue(inspection.detectedPermissions.contains(ModPermissions.PLAYER_CHEATS))
            assertTrue(inspection.detectedPermissions.contains(ModPermissions.COMMANDS_EXECUTE))

            val loadRes = engine.luaModRuntime.loadMod(
                modId = "cheat_helper",
                luaSource = modSource,
                permissionsYml = """
                    name: Cheat Helper
                    permissions:
                      - player.cheats
                """.trimIndent(),
                autoApproveDeclared = false
            )
            val record = loadRes.permissionRecord
            assertEquals(ModApprovalStatus.PENDING_USER_APPROVAL, record.status)
            assertTrue(record.missingDeclaredPermissions.contains(ModPermissions.COMMANDS_EXECUTE))
            assertFalse(engine.luaModRuntime.evalInMod("cheat_helper", "return Helper.overwriteError").tojstring().isBlank())

            // Before user approval: calling Player.setFlying throws SecurityException
            assertFalse(engine.worldScene.player.isFlying)
            val deniedCall = runCatching { engine.luaModRuntime.evalInMod("cheat_helper", "Helper.tryFly()") }
            assertTrue("Protected API must fail before user approval", deniedCall.isFailure)
            assertFalse(engine.worldScene.player.isFlying)

            // Verify user prompt appears in SettingsScreen & user approving via UI grants declared permissions
            engine.openSettingsFrom(AppState.MENU)
            val pending = engine.luaModRuntime.permissionManager.getPendingApprovalRecords()
            assertEquals(1, pending.size)
            assertTrue(engine.uiRenderer.settingsScreen.handleDynamicActionKey("perm_approve"))

            // Now declared permission player.cheats succeeds, while undeclared commands.execute STILL fails!
            engine.luaModRuntime.evalInMod("cheat_helper", "Helper.tryFly()")
            assertTrue("Approved declared permission must allow Player.setFlying", engine.worldScene.player.isFlying)
            engine.luaModRuntime.evalInMod("cheat_helper", "Helper.tryUndeclaredCommand()")
            val cmdErr = engine.luaModRuntime.evalInMod("cheat_helper", "return Helper.cmdError").tojstring()
            assertTrue("Undeclared permission must stay denied even after approving declared ones", cmdErr.contains("Permission denied"))

            // Acceptance 21: Load second isolated mod -> no permission leakage & no cross-mod setting overwrites
            val modB = engine.luaModRuntime.loadMod(
                modId = "mod_b",
                luaSource = """
                    ModB = { flyErr = "", overwriteSettingErr = "" }
                    local ok1, e1 = pcall(function() Player.setFlying(false) end)
                    if not ok1 then ModB.flyErr = tostring(e1) end
                    local ok2, e2 = pcall(function() Settings.set("tessellate:fov", 77) end)
                    if not ok2 then ModB.overwriteSettingErr = tostring(e2) end

                    Settings.register("shared:opt", {
                        type = "toggle",
                        label = "Mod B Opt",
                        default = true,
                        get = function() return true end,
                        set = function(v) end
                    })
                """.trimIndent(),
                autoApproveDeclared = true
            )
            assertTrue(modB.success)
            assertTrue(engine.luaModRuntime.evalInMod("mod_b", "return ModB.flyErr").tojstring().contains("Permission denied"))
            assertTrue(engine.luaModRuntime.evalInMod("mod_b", "return ModB.overwriteSettingErr").tojstring().contains("Permission denied"))

            val modC = engine.luaModRuntime.loadMod(
                modId = "mod_c",
                luaSource = """
                    Settings.register("shared:opt", {
                        type = "toggle",
                        label = "Mod C Hijack",
                        default = false,
                        get = function() return false end,
                        set = function(v) end
                    })
                """.trimIndent()
            )
            assertFalse("Mod C must not overwrite setting 'shared:opt' owned by Mod B", modC.success)
            assertEquals("Mod B Opt", engine.settingsRegistry.getSetting("shared:opt")?.label)

            // Sandbox check: luajava and dofile are stripped
            assertTrue(engine.luaModRuntime.evalInMod("mod_b", "return luajava == nil and dofile == nil and loadfile == nil").toboolean())
        } finally {
            engine.worldScene.destroySession()
        }
    }

    // =========================================================================
    // ACCEPTANCE TESTS 22-23: SYSTEM ACTIONS (OPEN APP FOLDER & CLEAR CACHE)
    // =========================================================================

    @Test
    fun `acceptance 22-23 Open App Folder targets external files Intent gracefully and Clear Cache preserves saves and settings`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            // Acceptance 22: Open App Folder builds Intent targeting Android/data/com.nitrous.tessellate/files/
            var capturedIntent: Intent? = null
            engine.appFolderIntentLauncher = { intent ->
                capturedIntent = intent
                true
            }
            val openOk = engine.settingsRegistry.invokeAction("tessellate:open_app_folder")
            assertTrue(openOk.success)
            assertNotNull(capturedIntent)
            assertEquals(Intent.ACTION_VIEW, capturedIntent!!.action)
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, capturedIntent!!.type)
            assertTrue(capturedIntent!!.dataString!!.contains(context.packageName))
            assertTrue(capturedIntent!!.dataString!!.contains("files"))

            // Unsupported file-manager graceful fallback (never throws or crashes)
            engine.appFolderIntentLauncher = null
            val openFallback = engine.settingsRegistry.invokeAction("tessellate:open_app_folder")
            assertFalse(openFallback.success)
            assertTrue(openFallback.message.contains("file manager", ignoreCase = true) || openFallback.message.contains("Path"))

            // Acceptance 23: Clear Cache removes temp cache files & requests GPU flush while preserving saves, settings, mods
            val tempInternal = File(context.cacheDir, "temp_mesh.bin").apply { writeText("temp1") }
            val tempExternal = File(WorldSaveManager.getBaseFilesDir(context), "cache/procedural.tmp").apply {
                parentFile?.mkdirs()
                writeText("temp2")
            }
            val saveFile = File(WorldSaveManager.getSavesRootDir(context), "KeepWorld/level.json").apply {
                parentFile?.mkdirs()
                writeText("{\"worldName\":\"KeepWorld\"}")
            }
            val modFile = File(WorldSaveManager.getBaseFilesDir(context), "mods/keepmod/init.lua").apply {
                parentFile?.mkdirs()
                writeText("-- mod")
            }
            engine.settingsRegistry.setValue("tessellate:fov", 90.0)
            awaitLastSave(engine)
            assertTrue(engine.settingsManager.settingsFile.exists())

            engine.openSettingsFrom(AppState.MENU)
            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_GENERAL)
            val clearRow = screen.getVisibleRows().first { it.definition.id == "tessellate:clear_cache" }
            tapButton(engine, clearRow.actionButton!!.rect.centerX(), clearRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis())

            assertFalse("Temp internal cache file must be deleted", tempInternal.exists())
            assertFalse("Temp external cache file must be deleted", tempExternal.exists())
            assertTrue(engine.worldScene.worldRenderer.isGpuFlushPending())

            assertTrue("World save MUST be preserved by Clear Cache", saveFile.exists())
            assertTrue("settings.json MUST be preserved by Clear Cache", engine.settingsManager.settingsFile.exists())
            assertTrue("Lua mods MUST be preserved by Clear Cache", modFile.exists())
            assertEquals(90f, engine.currentSettings.fov, 1e-4f)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `debug NanoTime Profiler toggles live profiling, persists across restarts, and resets cleanly`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            assertFalse("NanoTime Profiler must default to OFF", engine.currentSettings.enableNanoTimeProfiler)
            assertFalse(engine.worldScene.profiler.enabled)

            engine.settingsRegistry.setValue("tessellate:enable_debug_options", true)
            engine.setAppState(AppState.DEBUG_OPTIONS)
            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_DEBUG_RENDERING)

            val nanoRow = screen.getVisibleRows().firstOrNull { it.definition.id == "tessellate:debug_nanotime" }
            assertNotNull("NanoTime Profiler toggle must appear in Debug Options -> Debug Rendering", nanoRow)
            assertEquals("OFF", nanoRow!!.actionButton!!.label)

            tapButton(engine, nanoRow.actionButton!!.rect.centerX(), nanoRow.actionButton!!.rect.centerY(), SystemClock.uptimeMillis())
            awaitLastSave(engine)

            assertTrue(engine.currentSettings.enableNanoTimeProfiler)
            assertTrue(engine.worldScene.profiler.enabled)
            assertEquals("ON", screen.getVisibleRows().first { it.definition.id == "tessellate:debug_nanotime" }.actionButton!!.label)

            engine.worldScene.profiler.recordUpdateStage(1.50f, 0.50f, 0.25f, 2, 1)
            engine.worldScene.profiler.recordRenderStage(0.75f, 1.25f, 12, 4)
            val hudLine = engine.worldScene.profiler.formatCompactHudSummary()
            assertTrue(hudLine.startsWith("nanoTime:"))
            assertTrue(hudLine.contains("(2/1)"))
        } finally {
            engine.worldScene.destroySession()
        }

        val restarted = TessellateEngine(context)
        awaitJob(restarted.initialSettingsLoadJob)
        try {
            assertTrue("NanoTime Profiler must persist across restart", restarted.currentSettings.enableNanoTimeProfiler)
            assertTrue(restarted.worldScene.profiler.enabled)

            restarted.settingsRegistry.resetToDefaults()
            awaitLastSave(restarted)
            assertFalse(restarted.currentSettings.enableNanoTimeProfiler)
            assertFalse(restarted.worldScene.profiler.enabled)
        } finally {
            restarted.worldScene.destroySession()
        }
    }

    @Test
    fun `chunk radius adjusts camera farPlane safely, cancels stale streaming on shrink, and skips duplicate slider writes`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(WorldSession("FarWorld", GameMode.CREATIVE, WorldType.FLAT, 99L))
            engine.worldScene.world.streamChunksAround(
                engine.worldScene.player.posX,
                engine.worldScene.player.posZ,
                engine.texturePipeline,
                forceCheck = true
            )?.let { awaitJob(it, timeoutMs = 15_000L) }

            assertTrue(engine.settingsRegistry.setValue("tessellate:render_distance", 16).isValid)
            assertEquals(16, engine.worldScene.world.renderDistance)
            assertTrue(
                "Max render distance 16 (256 blocks) must extend camera farPlane beyond 250f",
                engine.camera.farPlane >= 400f
            )

            // Shrinking back down when inner chunks are already present must return null (canceling older job)
            assertTrue(engine.settingsRegistry.setValue("tessellate:render_distance", 2).isValid)
            assertEquals(2, engine.worldScene.world.renderDistance)
            val jobAfterShrink = engine.worldScene.world.streamChunksAround(
                engine.worldScene.player.posX,
                engine.worldScene.player.posZ,
                engine.texturePipeline,
                forceCheck = true
            )
            assertNull("No missing columns after shrinking inside loaded area", jobAfterShrink)

            // No-op write at quantized boundary keeps lastSaveJob unchanged
            awaitLastSave(engine)
            val jobBeforeNoOp = engine.settingsManager.lastSaveJob
            engine.updateAndPersistSettings { it.copy(renderDistance = 2) }
            assertEquals("Duplicate quantized setting update must not trigger extra file write", jobBeforeNoOp, engine.settingsManager.lastSaveJob)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `resizing controls scales D-pad, Jump, hotbar cleanly without overlap and syncs live Jump input`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(960, 540)
        try {
            engine.startWorldSession(WorldSession("ControlsWorld", GameMode.CREATIVE, WorldType.FLAT, 15L))

            assertTrue(engine.settingsRegistry.setValue("tessellate:ui_scale", 0.75).isValid)
            val dpadSmall = engine.touchController.dpadRect.width()
            val jumpSmall = engine.uiRenderer.jumpButtonRect.width()
            val hotbarSmall = engine.uiRenderer.hotbarBoundsRect.width()

            assertTrue(engine.settingsRegistry.setValue("tessellate:ui_scale", 1.25).isValid)
            val dpadLarge = engine.touchController.dpadRect.width()
            val jumpLarge = engine.uiRenderer.jumpButtonRect.width()
            val hotbarLarge = engine.uiRenderer.hotbarBoundsRect.width()

            assertTrue("D-pad width must grow with uiScale", dpadLarge > dpadSmall)
            assertTrue("Jump button width must grow with uiScale", jumpLarge > jumpSmall)
            assertTrue("Hotbar width must scale up on larger uiScale", hotbarLarge > hotbarSmall)
            assertTrue(
                "Hotbar must never overlap D-pad even on compact 960x540 screen at 1.25x scale",
                engine.uiRenderer.hotbarBoundsRect.left >= engine.touchController.dpadRect.right + 12f
            )
            assertTrue(
                "Hotbar must never overlap Jump button even on compact 960x540 screen at 1.25x scale",
                engine.uiRenderer.hotbarBoundsRect.right <= engine.uiRenderer.jumpButtonRect.left - 12f
            )

            // Holding Jump button in PLAYING state must drive touchController.isJumpHeld
            val jx = engine.uiRenderer.jumpButtonRect.centerX()
            val jy = engine.uiRenderer.jumpButtonRect.centerY()
            val t0 = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, jx, jy, 0)
            assertTrue(engine.onTouchEvent(down))
            down.recycle()
            assertTrue("Holding scaled Jump button must activate touchController.isJumpHeld", engine.touchController.isJumpHeld)

            val up = MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_UP, jx, jy, 0)
            assertTrue(engine.onTouchEvent(up))
            up.recycle()
            assertFalse("Releasing Jump button must clear touchController.isJumpHeld", engine.touchController.isJumpHeld)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `concurrent settings edits and GL render loop inside an active world never throw ConcurrentModificationException or NPE`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(WorldSession("RaceWorld", GameMode.CREATIVE, WorldType.FLAT, 42L))
            engine.setPaused(true)
            engine.openSettingsFrom(AppState.PLAYING)

            val screen = engine.uiRenderer.settingsScreen
            val fontRenderer = BitmapFontRenderer(context)
            val dummyShader = Shader("", "")
            val ortho = Matrix4.create()
            Matrix4.ortho(ortho, 0f, 1920f, 1080f, 0f, -1f, 1f)

            val stop = AtomicBoolean(false)
            val errorRef = AtomicReference<Throwable?>(null)
            val startedLatch = CountDownLatch(1)

            val glRenderThread = Thread({
                startedLatch.countDown()
                try {
                    while (!stop.get()) {
                        screen.render(
                            shader = dummyShader,
                            orthoMatrix = ortho,
                            quadVaoId = 0,
                            fontRenderer = fontRenderer,
                            drawButtonFn = { btn, _ -> btn.rect.width() },
                            drawSliderTrackFn = { rect, frac -> rect.width() * frac }
                        )
                        for (row in screen.getVisibleRows()) {
                            row.rowRect.centerX()
                        }
                        for (tab in screen.getCategoryTabs()) {
                            tab.id
                        }
                        engine.worldScene.worldRenderer.markAllChunksDirty()
                    }
                } catch (t: Throwable) {
                    errorRef.compareAndSet(null, t)
                }
            }, "TestGLThread")

            glRenderThread.start()
            assertTrue(startedLatch.await(2, TimeUnit.SECONDS))

            val categories = listOf(
                VanillaSettingsRegistrar.CAT_VIDEO,
                VanillaSettingsRegistrar.CAT_CONTROLS,
                VanillaSettingsRegistrar.CAT_GENERAL
            )
            for (i in 0 until 250) {
                if (errorRef.get() != null) break
                screen.selectCategory(categories[i % categories.size])
                engine.settingsRegistry.setValue("tessellate:fov", 35.0 + (i % 15) * 5.0, persist = true)
                engine.settingsRegistry.setValue("tessellate:ui_scale", if (i % 2 == 0) 0.8 else 1.2, persist = true)
                engine.settingsRegistry.toggleValue("tessellate:view_bobbing")
                engine.settingsRegistry.toggleValue("tessellate:enable_debug_options")
                engine.settingsRegistry.setValue("tessellate:render_distance", 2 + (i % 5), persist = true)
                screen.triggerButtonSetting(
                    engine.settingsRegistry.getSetting("tessellate:clear_cache")!!
                )
                if (i % 50 == 49) {
                    engine.resetAllSettingsToDefaults()
                }
            }

            stop.set(true)
            glRenderThread.join(5000L)
            assertFalse("GL render thread should exit cleanly", glRenderThread.isAlive)
            val failure = errorRef.get()
            if (failure != null) {
                throw AssertionError("Concurrent settings editing threw ${failure::class.java.simpleName}: ${failure.message}", failure)
            }
            awaitLastSave(engine)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `raising render distance to max while paused in settings caps prebuiltMeshes to prevent OOM`() {
        val engine = TessellateEngine(context)
        awaitJob(engine.initialSettingsLoadJob)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(WorldSession("LargeRadiusWorld", GameMode.SURVIVAL, WorldType.DEFAULT, 777L))
            engine.setPaused(true)
            engine.openSettingsFrom(AppState.PLAYING)

            assertTrue(engine.settingsRegistry.setValue("tessellate:render_distance", 16).isValid)
            val job = engine.worldScene.world.streamChunksAround(
                engine.worldScene.player.posX,
                engine.worldScene.player.posZ,
                engine.texturePipeline,
                forceCheck = true
            )
            if (job != null) {
                awaitJob(job, timeoutMs = 20_000L)
            }

            assertTrue("World chunks must still stream for renderDistance = 16", engine.worldScene.world.getAllChunks().isNotEmpty())
            assertTrue(
                "prebuiltMeshes must stay capped at <= ${World.MAX_PREBUILT_MESHES} while paused in Settings (was ${engine.worldScene.world.getPrebuiltMeshCount()})",
                engine.worldScene.world.getPrebuiltMeshCount() <= World.MAX_PREBUILT_MESHES
            )
        } finally {
            engine.worldScene.destroySession()
        }
    }

    private fun awaitLastSave(engine: TessellateEngine) {
        engine.settingsManager.lastSaveJob?.let { awaitJob(it) }
    }

    private fun awaitJob(job: Job, timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("Expected coroutine Job to finish within ${timeoutMs}ms", job.isCompleted)
    }

    private fun tapButton(engine: TessellateEngine, x: Float, y: Float, t0: Long) {
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0)
        assertTrue(engine.onTouchEvent(down))
        down.recycle()
        val up = MotionEvent.obtain(t0, t0 + 30, MotionEvent.ACTION_UP, x, y, 0)
        assertTrue(engine.onTouchEvent(up))
        up.recycle()
    }
}
