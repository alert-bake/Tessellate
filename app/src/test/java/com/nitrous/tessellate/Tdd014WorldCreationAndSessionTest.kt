package com.nitrous.tessellate

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.ui.CreateWorldField
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import com.nitrous.tessellate.worldgen.SurfacePass
import com.nitrous.tessellate.worldgen.WorldGenRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Verification test suite for TDD-014: World Creation Menu & Session Routing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd014WorldCreationAndSessionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RegistryBootstrap.initialize(context = context, forceReinitialize = true)
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
    }

    @Test
    fun `acceptance test 1-4 full menu flow creates Lasagna World with seed 12345 then quits and creates Flat world`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val engine = activity.surfaceView!!.engine
        engine.onSurfaceChanged(1920, 1080)
        ShadowLooper.idleMainLooper()

        try {
            // 1. Open App -> Main Menu -> Singleplayer -> World Select -> Create New World
            assertEquals(AppState.MENU, engine.currentAppState)
            assertEquals(View.GONE, activity.createWorldNameEditText.visibility)
            assertEquals(View.GONE, activity.createWorldSeedEditText.visibility)

            var t0 = SystemClock.uptimeMillis()
            val spBtn = engine.uiRenderer.singleplayerButton
            tapButton(engine, spBtn.rect.centerX(), spBtn.rect.centerY(), t0)
            ShadowLooper.idleMainLooper()
            assertEquals(AppState.WORLD_SELECT, engine.currentAppState)

            // Verify World Select starts empty when no worlds saved yet, with Create New World and Back
            val wsScreen = engine.uiRenderer.worldSelectScreen
            assertTrue(wsScreen.existingWorlds.isEmpty())
            assertEquals("Create New World", wsScreen.createNewWorldButton.label)
            assertEquals("Delete World", wsScreen.deleteWorldButton.label)
            assertEquals("Back", wsScreen.backButton.label)

            t0 += 100
            tapButton(engine, wsScreen.createNewWorldButton.rect.centerX(), wsScreen.createNewWorldButton.rect.centerY(), t0)
            ShadowLooper.idleMainLooper()
            assertEquals(AppState.CREATE_WORLD, engine.currentAppState)

            // Verify Create World defaults and native EditText overlay visibility
            val cwScreen = engine.uiRenderer.createWorldScreen
            assertEquals("New World", cwScreen.worldName)
            assertEquals(GameMode.SURVIVAL, cwScreen.gamemode)
            assertEquals(WorldType.DEFAULT, cwScreen.worldType)
            assertEquals("", cwScreen.seedText)
            assertEquals(View.VISIBLE, activity.createWorldNameEditText.visibility)
            assertEquals(View.VISIBLE, activity.createWorldSeedEditText.visibility)
            assertEquals("New World", activity.createWorldNameEditText.text.toString())

            // 2. Set Name: "Lasagna World", Mode: Survival, Type: Default, Seed: "12345"
            activity.createWorldNameEditText.setText("Lasagna World")
            activity.createWorldSeedEditText.setText("12345")
            assertEquals("Lasagna World", cwScreen.worldName)
            assertEquals("12345", cwScreen.seedText)

            t0 += 100
            tapButton(engine, cwScreen.createButton.rect.centerX(), cwScreen.createButton.rect.centerY(), t0)
            ShadowLooper.idleMainLooper()

            // 3. Verify PLAYING, WorldSession passed to World, and terrain uses seed 12345
            assertEquals(AppState.PLAYING, engine.currentAppState)
            assertFalse(engine.isPaused)
            assertEquals(View.GONE, activity.createWorldNameEditText.visibility)
            assertEquals(View.GONE, activity.createWorldSeedEditText.visibility)
            assertEquals(View.VISIBLE, activity.consoleToggleButton.visibility)

            val firstWorld = engine.worldScene.world
            val firstSession = engine.worldScene.currentSession
            assertNotNull(firstSession)
            assertEquals("Lasagna World", firstSession!!.name)
            assertEquals(GameMode.SURVIVAL, firstSession.gamemode)
            assertEquals(WorldType.DEFAULT, firstSession.worldType)
            assertEquals(12345L, firstSession.seed)
            assertEquals(12345L, firstWorld.seed)
            assertEquals(firstSession, firstWorld.session)

            val expectedSurface = SurfacePass.computeSurfaceY(12345L, 24, 24)
            assertTrue("Default surface Y=$expectedSurface must be in 40..80", expectedSurface in 40..80)
            assertFalse(firstWorld.getBlock(24, expectedSurface, 24) == BlockType.AIR)

            val otherSeedSurface = SurfacePass.computeSurfaceY(999999L, 24, 24)
            // Verify world generation genuinely varies by seed
            val anyDiff = (0..40).any { x ->
                SurfacePass.computeSurfaceY(12345L, x, 24) != SurfacePass.computeSurfaceY(999999L, x, 24)
            }
            assertTrue("Different seeds must produce different terrain heightmaps", anyDiff)

            // 4. Open Pause Menu -> Save and Quit -> World Select -> Create Flat World
            engine.setPaused(true)
            ShadowLooper.idleMainLooper()
            assertTrue(engine.isPaused)
            assertEquals(View.GONE, activity.consoleToggleButton.visibility)

            val pauseScreen = engine.uiRenderer.pauseScreen
            t0 += 100
            tapButton(engine, pauseScreen.saveAndQuitButton.rect.centerX(), pauseScreen.saveAndQuitButton.rect.centerY(), t0)
            ShadowLooper.idleMainLooper()

            assertEquals(AppState.WORLD_SELECT, engine.currentAppState)
            assertFalse(engine.isPaused)
            assertTrue("Previous World must be destroyed on Save and Quit", firstWorld.isDestroyed)
            assertNull(engine.worldScene.currentSession)
            assertEquals(0, firstWorld.getLoadedChunkCount())

            // Create a new world with Type: Flat
            t0 += 100
            tapButton(engine, wsScreen.createNewWorldButton.rect.centerX(), wsScreen.createNewWorldButton.rect.centerY(), t0)
            ShadowLooper.idleMainLooper()
            assertEquals(AppState.CREATE_WORLD, engine.currentAppState)

            // Toggle World Type from Default -> Flat
            t0 += 100
            tapButton(engine, cwScreen.worldTypeButton.rect.centerX(), cwScreen.worldTypeButton.rect.centerY(), t0)
            assertEquals(WorldType.FLAT, cwScreen.worldType)
            assertEquals("Flat", cwScreen.worldTypeButton.label)

            t0 += 100
            tapButton(engine, cwScreen.createButton.rect.centerX(), cwScreen.createButton.rect.centerY(), t0)
            ShadowLooper.idleMainLooper()

            assertEquals(AppState.PLAYING, engine.currentAppState)
            val flatWorld = engine.worldScene.world
            assertEquals(WorldType.FLAT, flatWorld.session.worldType)

            // Verify every column across spawn area has exact flat height & grass/dirt/stone layers
            val sampleCoords = listOf(0 to 0, 15 to 15, 24 to 24, 35 to 40)
            for ((wx, wz) in sampleCoords) {
                assertEquals(
                    "Flat world surface at ($wx, $wz) must equal FLAT_GRASS_Y",
                    WorldGenRegistry.FLAT_GRASS_Y,
                    flatWorld.getSurfaceHeight(wx, wz)
                )
                assertEquals(BlockType.GRASS, flatWorld.getBlock(wx, WorldGenRegistry.FLAT_GRASS_Y, wz))
                assertEquals(BlockType.DIRT, flatWorld.getBlock(wx, WorldGenRegistry.FLAT_GRASS_Y - 1, wz))
                assertEquals(BlockType.STONE, flatWorld.getBlock(wx, 0, wz))
                assertEquals(BlockType.AIR, flatWorld.getBlock(wx, WorldGenRegistry.FLAT_GRASS_Y + 1, wz))
            }
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `gamemode and world type toggles cycle through all values and Creative enables instamine`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.openCreateWorldScreen()
            val cw = engine.uiRenderer.createWorldScreen

            assertEquals(GameMode.SURVIVAL, cw.gamemode)
            assertEquals(WorldType.DEFAULT, cw.worldType)

            val t0 = SystemClock.uptimeMillis()
            tapButton(engine, cw.gamemodeButton.rect.centerX(), cw.gamemodeButton.rect.centerY(), t0)
            assertEquals(GameMode.CREATIVE, cw.gamemode)
            assertEquals("Creative", cw.gamemodeButton.label)

            tapButton(engine, cw.gamemodeButton.rect.centerX(), cw.gamemodeButton.rect.centerY(), t0 + 100)
            assertEquals(GameMode.SURVIVAL, cw.gamemode)

            // Toggle to Creative again
            tapButton(engine, cw.gamemodeButton.rect.centerX(), cw.gamemodeButton.rect.centerY(), t0 + 200)
            assertEquals(GameMode.CREATIVE, cw.gamemode)

            // Cycle WorldType: Default -> Flat -> Debug -> Default
            tapButton(engine, cw.worldTypeButton.rect.centerX(), cw.worldTypeButton.rect.centerY(), t0 + 300)
            assertEquals(WorldType.FLAT, cw.worldType)
            tapButton(engine, cw.worldTypeButton.rect.centerX(), cw.worldTypeButton.rect.centerY(), t0 + 400)
            assertEquals(WorldType.DEBUG, cw.worldType)
            assertEquals("Debug", cw.worldTypeButton.label)

            // Create Creative + Debug world
            tapButton(engine, cw.createButton.rect.centerX(), cw.createButton.rect.centerY(), t0 + 500)
            assertEquals(AppState.PLAYING, engine.currentAppState)
            assertTrue(engine.worldScene.player.isInstaMine)
            assertNotNull(engine.worldScene.player.inventory.getSlot(0))
            assertEquals(WorldType.DEBUG, engine.worldScene.world.session.worldType)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `seed parsing handles blank random, integer strings, and alphanumeric hashes`() {
        assertEquals(12345L, WorldSession.parseSeed(" 12345 "))
        assertEquals(-42L, WorldSession.parseSeed("-42"))
        assertEquals("lasagna".hashCode().toLong(), WorldSession.parseSeed("lasagna"))
        assertEquals(777L, WorldSession.parseSeed("   ") { 777L })
        assertNotEquals(
            WorldSession.parseSeed("seed_a"),
            WorldSession.parseSeed("seed_b")
        )
    }

    @Test
    fun `navigation cancel, back, pause resume, and settings round-trip work cleanly`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            val t0 = SystemClock.uptimeMillis()

            // MENU -> WORLD_SELECT -> CREATE_WORLD -> Cancel -> WORLD_SELECT -> Back -> MENU
            tapButton(engine, engine.uiRenderer.singleplayerButton.rect.centerX(), engine.uiRenderer.singleplayerButton.rect.centerY(), t0)
            assertEquals(AppState.WORLD_SELECT, engine.currentAppState)

            val ws = engine.uiRenderer.worldSelectScreen
            tapButton(engine, ws.createNewWorldButton.rect.centerX(), ws.createNewWorldButton.rect.centerY(), t0 + 100)
            assertEquals(AppState.CREATE_WORLD, engine.currentAppState)

            val cw = engine.uiRenderer.createWorldScreen
            tapButton(engine, cw.cancelButton.rect.centerX(), cw.cancelButton.rect.centerY(), t0 + 200)
            assertEquals(AppState.WORLD_SELECT, engine.currentAppState)

            tapButton(engine, ws.backButton.rect.centerX(), ws.backButton.rect.centerY(), t0 + 300)
            assertEquals(AppState.MENU, engine.currentAppState)

            // Create a world then quit to World Select and relaunch it via Play button
            tapButton(engine, engine.uiRenderer.singleplayerButton.rect.centerX(), engine.uiRenderer.singleplayerButton.rect.centerY(), t0 + 400)
            tapButton(engine, ws.createNewWorldButton.rect.centerX(), ws.createNewWorldButton.rect.centerY(), t0 + 450)
            cw.worldName = "Test World"
            tapButton(engine, cw.createButton.rect.centerX(), cw.createButton.rect.centerY(), t0 + 500)
            assertEquals(AppState.PLAYING, engine.currentAppState)
            assertEquals("Test World", engine.worldScene.currentSession?.name)

            engine.saveAndQuitToWorldSelect()
            assertEquals(AppState.WORLD_SELECT, engine.currentAppState)
            assertTrue(ws.existingWorlds.any { it.worldName == "Test World" })
            tapButton(engine, ws.playSelectedButton.rect.centerX(), ws.playSelectedButton.rect.centerY(), t0 + 550)
            assertEquals(AppState.PLAYING, engine.currentAppState)
            assertEquals("Test World", engine.worldScene.currentSession?.name)

            // Pause freezes entities
            val drop = ItemDropEntity(Vec3(24.5f, 90.0f, 24.5f))
            engine.worldScene.world.spawnEntity(drop)
            val startY = drop.position.y
            engine.setPaused(true)
            engine.onDrawFrame()
            assertEquals(startY, drop.position.y, 1e-4f)

            // Pause -> Settings -> Back returns to PLAYING (paused)
            val ps = engine.uiRenderer.pauseScreen
            tapButton(engine, ps.settingsButton.rect.centerX(), ps.settingsButton.rect.centerY(), t0 + 600)
            assertEquals(AppState.SETTINGS, engine.currentAppState)

            val ss = engine.uiRenderer.settingsScreen
            tapButton(engine, ss.backButton.rect.centerX(), ss.backButton.rect.centerY(), t0 + 700)
            assertEquals(AppState.PLAYING, engine.currentAppState)
            assertTrue(engine.isPaused)

            // Resume unpauses
            tapButton(engine, ps.resumeButton.rect.centerX(), ps.resumeButton.rect.centerY(), t0 + 800)
            assertEquals(AppState.PLAYING, engine.currentAppState)
            assertFalse(engine.isPaused)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `create world and console text fields use app font styling and sync focus and cursor`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val engine = activity.surfaceView!!.engine
        engine.onSurfaceChanged(1920, 1080)
        ShadowLooper.idleMainLooper()

        try {
            val silkscreen = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.silkscreen)
            assertNotNull("Bundled Silkscreen pixel font resource must load", silkscreen)

            engine.openCreateWorldScreen()
            ShadowLooper.idleMainLooper()

            val cw = engine.uiRenderer.createWorldScreen
            // Native EditTexts stay visually transparent so BitmapFontRenderer renders app-font glyphs
            assertEquals(android.graphics.Color.TRANSPARENT, activity.createWorldNameEditText.currentTextColor)
            assertEquals(android.graphics.Color.TRANSPARENT, activity.createWorldSeedEditText.currentTextColor)
            assertFalse(activity.createWorldNameEditText.isCursorVisible)
            assertFalse(activity.createWorldSeedEditText.isCursorVisible)
            assertEquals(silkscreen, activity.consoleInputEditText.typeface)

            activity.createWorldNameEditText.requestFocus()
            activity.createWorldNameEditText.setText("Pixel World")
            activity.createWorldNameEditText.setSelection(5)
            assertEquals(CreateWorldField.WORLD_NAME, cw.focusedField)
            assertEquals("Pixel World", cw.worldName)
            assertEquals(5, cw.cursorIndex)

            activity.createWorldSeedEditText.requestFocus()
            activity.createWorldSeedEditText.setText("4242")
            activity.createWorldSeedEditText.setSelection(4)
            assertEquals(CreateWorldField.SEED, cw.focusedField)
            assertEquals("4242", cw.seedText)
            assertEquals(4, cw.cursorIndex)

            engine.setAppState(AppState.WORLD_SELECT)
            ShadowLooper.idleMainLooper()
            assertEquals(CreateWorldField.NONE, cw.focusedField)
        } finally {
            engine.worldScene.destroySession()
        }
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
