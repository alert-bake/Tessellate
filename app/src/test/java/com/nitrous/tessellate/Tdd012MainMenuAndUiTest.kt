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
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.ui.BitmapFontRenderer
import com.nitrous.tessellate.ui.UiButtonState
import com.nitrous.tessellate.ui.UiLayoutLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Verification test suite for TDD-012: UI Overhaul, Main Menu, & App State.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd012MainMenuAndUiTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val settingsFile = SettingsManager.resolveSettingsFile(context)
        if (settingsFile.exists()) {
            settingsFile.delete()
        }
    }

    // =========================================================================
    // ACCEPTANCE TEST 1: Boot to Main Menu, Title & Stacked Buttons Layout
    // =========================================================================

    @Test
    fun `app boots to Main Menu state with TESSELATE title and centered vertical button stack`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val engine = activity.surfaceView!!.engine

        assertEquals(AppState.MENU, engine.currentAppState)
        assertEquals(View.GONE, activity.consoleToggleButton.visibility)

        engine.onSurfaceChanged(1920, 1080)
        val ui = engine.uiRenderer

        // Title at top center with integer scale 3x or 4x
        assertTrue(ui.menuTitleScale == 3 || ui.menuTitleScale == 4)
        assertEquals(960f, ui.menuTitleCenterX, 1f)
        assertTrue(ui.menuTitleCenterY < ui.singleplayerButton.rect.top)

        // Centered vertical stack order: Singleplayer -> Multiplayer -> Settings -> Quit
        val buttons = ui.mainMenuButtons
        assertEquals(listOf("Singleplayer", "Multiplayer", "Settings", "Quit"), buttons.map { it.label })
        for (i in 0 until buttons.size - 1) {
            assertTrue(buttons[i].rect.bottom < buttons[i + 1].rect.top)
            assertEquals(960f, buttons[i].rect.centerX(), 1f)
        }
        assertTrue(ui.menuButtonFontScale >= 2)
    }

    // =========================================================================
    // ACCEPTANCE TEST 2: Tap Multiplayer -> Disabled, Nothing Happens, No Crash
    // =========================================================================

    @Test
    fun `tapping disabled Multiplayer button keeps blackish state and does nothing without crashing`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        val mp = engine.uiRenderer.multiplayerButton

        assertFalse(mp.enabled)
        assertEquals(UiButtonState.DISABLED, mp.visualState)

        val t0 = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, mp.rect.centerX(), mp.rect.centerY(), 0)
        assertTrue(engine.onTouchEvent(down))
        down.recycle()

        // Must remain DISABLED (never turns green/hovered)
        assertEquals(UiButtonState.DISABLED, mp.visualState)

        val up = MotionEvent.obtain(t0, t0 + 30, MotionEvent.ACTION_UP, mp.rect.centerX(), mp.rect.centerY(), 0)
        assertTrue(engine.onTouchEvent(up))
        up.recycle()

        assertEquals(AppState.MENU, engine.currentAppState)
        assertEquals(UiButtonState.DISABLED, mp.visualState)
    }

    // =========================================================================
    // ACCEPTANCE TEST 3: Hold Singleplayer -> Turns Green (HOVERED) -> Release -> WORLD_SELECT
    // =========================================================================

    @Test
    fun `holding Singleplayer turns button hovered green and releasing transitions to WORLD_SELECT`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        val sp = engine.uiRenderer.singleplayerButton

        assertEquals(UiButtonState.NORMAL, sp.visualState)
        assertEquals(AppState.MENU, engine.currentAppState)

        val t0 = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, sp.rect.centerX(), sp.rect.centerY(), 0)
        assertTrue(engine.onTouchEvent(down))
        down.recycle()

        // While holding finger down, button transitions to HOVERED (green) and game is still MENU
        assertEquals(UiButtonState.HOVERED, sp.visualState)
        assertEquals(AppState.MENU, engine.currentAppState)

        // Release inside button -> opens World Select menu (AppState.WORLD_SELECT per TDD-014)
        val up = MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_UP, sp.rect.centerX(), sp.rect.centerY(), 0)
        assertTrue(engine.onTouchEvent(up))
        up.recycle()

        assertEquals(UiButtonState.NORMAL, sp.visualState)
        assertEquals(AppState.WORLD_SELECT, engine.currentAppState)
    }

    // =========================================================================
    // ACCEPTANCE TEST 4: Obsolete Debug Box & Orbit Mode Gone; World Pauses in Menu
    // =========================================================================

    @Test
    fun `obsolete debug box, orbit mode, and teleport reset buttons are removed and world physics pause in MENU`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)

        // World entities must NOT tick while in MENU or SETTINGS
        val drop = ItemDropEntity(Vec3(10.5, 20.0, 10.5))
        engine.worldScene.world.spawnEntity(drop)
        val startY = drop.position.y

        // Simulate frames while in MENU
        engine.onDrawFrame()
        assertEquals(startY, drop.position.y, 1e-4)

        // Tap top-left (where red debug box used to be) and top-right old Orbit/Reset areas in PLAYING
        engine.setAppState(AppState.PLAYING)
        val initialEntities = engine.worldScene.world.entities.size
        val t0 = SystemClock.uptimeMillis()
        val tapTopLeft = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 35f, 35f, 0)
        engine.onTouchEvent(tapTopLeft)
        tapTopLeft.recycle()
        val upTopLeft = MotionEvent.obtain(t0, t0 + 20, MotionEvent.ACTION_UP, 35f, 35f, 0)
        engine.onTouchEvent(upTopLeft)
        upTopLeft.recycle()

        // No debug entity spawned from top-left tap
        assertEquals(initialEntities, engine.worldScene.world.entities.size)

        // Verify TexturedUiRenderer no longer exposes obsolete debug/orbit/reset fields
        val fields = engine.uiRenderer::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("btnSpawnEntityRect"))
        assertFalse(fields.contains("btnSceneToggleRect"))
        assertFalse(fields.contains("btnResetRect"))
    }

    // =========================================================================
    // ACCEPTANCE TEST 5: Open Settings -> Change FOV -> Go Back -> Settings Persist
    // =========================================================================

    @Test
    fun `opening Settings, changing FOV, and going Back persists FOV across engine restart`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        val initialFov = engine.currentSettings.fov

        // 1. Tap Settings button on Main Menu
        val settingsBtn = engine.uiRenderer.settingsButton
        val t0 = SystemClock.uptimeMillis()
        tapButton(engine, settingsBtn.rect.centerX(), settingsBtn.rect.centerY(), t0)
        assertEquals(AppState.SETTINGS, engine.currentAppState)

        // 2. Tap "+" button twice to increase FOV (+10 degrees)
        val plusBtn = engine.uiRenderer.settingsScreen.fovPlusButton
        tapButton(engine, plusBtn.rect.centerX(), plusBtn.rect.centerY(), t0 + 100)
        tapButton(engine, plusBtn.rect.centerX(), plusBtn.rect.centerY(), t0 + 200)

        val expectedFov = (initialFov + 10f).coerceIn(30f, 110f)
        assertEquals(expectedFov, engine.currentSettings.fov, 1e-4f)
        assertEquals(expectedFov, engine.camera.fovDegrees, 1e-4f)

        // 3. Tap "Back" button to return to Main Menu
        val backBtn = engine.uiRenderer.settingsScreen.backButton
        tapButton(engine, backBtn.rect.centerX(), backBtn.rect.centerY(), t0 + 300)
        assertEquals(AppState.MENU, engine.currentAppState)

        // Wait cleanly for async SettingsManager disk write to complete
        val deadline = System.currentTimeMillis() + 3000L
        while (engine.settingsManager.lastSaveJob?.isCompleted == false && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        Thread.sleep(20)

        // 4. Simulate restart: fresh TessellateEngine loads persisted FOV
        val restarted = TessellateEngine(context)
        val loadDeadline = System.currentTimeMillis() + 3000L
        while (!restarted.initialSettingsLoadJob.isCompleted && System.currentTimeMillis() < loadDeadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertEquals(expectedFov, restarted.currentSettings.fov, 1e-4f)
        assertEquals(expectedFov, restarted.camera.fovDegrees, 1e-4f)
    }

    // =========================================================================
    // ADDITIONAL COVERAGE: Quit Handler, Widget UVs, & BitmapFontRenderer
    // =========================================================================

    @Test
    fun `Quit button triggers quitHandler and UI_gui_widgets button UVs load cleanly`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)

        var quitCalled = false
        engine.quitHandler = { quitCalled = true }

        val quitBtn = engine.uiRenderer.quitButton
        tapButton(engine, quitBtn.rect.centerX(), quitBtn.rect.centerY(), SystemClock.uptimeMillis())
        assertTrue(quitCalled)

        val layout = UiLayoutLoader.load(context)
        assertEquals(0f, layout.buttons.disabled.u1, 1e-5f)
        assertEquals(46f / 256f, layout.buttons.disabled.v1, 1e-5f)
        assertEquals(200f / 256f, layout.buttons.disabled.u2, 1e-5f)
        assertEquals(66f / 256f, layout.buttons.disabled.v2, 1e-5f)

        assertEquals(66f / 256f, layout.buttons.normal.v1, 1e-5f)
        assertEquals(86f / 256f, layout.buttons.normal.v2, 1e-5f)

        assertEquals(86f / 256f, layout.buttons.hovered.v1, 1e-5f)
        assertEquals(106f / 256f, layout.buttons.hovered.v2, 1e-5f)
    }

    @Test
    fun `BitmapFontRenderer builds full ASCII atlas and enforces integer scaling`() {
        val font = BitmapFontRenderer()
        val bmp = font.createFontAtlasBitmap()
        assertEquals(BitmapFontRenderer.ATLAS_WIDTH, bmp.width)
        assertEquals(BitmapFontRenderer.ATLAS_HEIGHT, bmp.height)
        bmp.recycle()

        // Integer scale clamping (0 or negative clamps to 1x)
        assertEquals(font.measureWidth("TESSELATE", 1), font.measureWidth("TESSELATE", 0), 1e-4f)
        assertEquals(7f * 3f, font.measureHeight(3), 1e-4f)
        assertEquals((9 * 6 - 1) * 4f, font.measureWidth("TESSELATE", 4), 1e-4f)
    }

    @Test
    fun `data-driven font assets load from textures folder and support resource-pack overrides`() {
        val fontDir = java.io.File(context.filesDir, "textures")
        val overrideConfig = java.io.File(fontDir, "UI_font.json")
        val overridePage = java.io.File(fontDir, "UI_font_extra.png")
        try {
            overrideConfig.delete()
            overridePage.delete()

            // Verify bundled textures/UI_font_ascii.png and textures/UI_font.json exist & load
            val bundledBitmap = context.assets.open("textures/UI_font_ascii.png").use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
            assertNotNull(bundledBitmap)
            assertEquals(128, bundledBitmap!!.width)
            assertEquals(64, bundledBitmap.height)
            bundledBitmap.recycle()

            val dataDrivenFont = BitmapFontRenderer(context)
            assertEquals(95, dataDrivenFont.getLoadedGlyphCount())
            assertEquals(1, dataDrivenFont.getLoadedPageCount())
            assertEquals((9 * 6 - 1) * 4f, dataDrivenFont.measureWidth("TESSELATE", 4), 1e-4f)

            // Verify resource-pack override: custom second page + extra Unicode glyph + proportional width
            fontDir.mkdirs()
            val extraBmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            extraBmp.eraseColor(android.graphics.Color.TRANSPARENT)
            // Draw a 2px-wide vertical bar in cell (0,0) for '★' and 6px-wide bar in cell (1,0) for 'é'
            for (y in 0 until 7) {
                extraBmp.setPixel(0, y, android.graphics.Color.WHITE)
                extraBmp.setPixel(1, y, android.graphics.Color.WHITE)
                for (x in 0 until 6) {
                    extraBmp.setPixel(8 + x, y, android.graphics.Color.WHITE)
                }
            }
            overridePage.outputStream().use { out ->
                extraBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            extraBmp.recycle()

            overrideConfig.writeText(
                """
                {
                  "lineHeight": 7,
                  "spaceWidth": 4,
                  "charGap": 1,
                  "monospace": false,
                  "pages": [
                    {
                      "texture": "UI_font_ascii.png",
                      "cols": 16,
                      "rows": 6,
                      "glyphWidth": 5,
                      "glyphHeight": 7,
                      "cellLogicalSize": 8,
                      "chars": [
                        " !\"#${'$'}%&'()*+,-./",
                        "0123456789:;<=>?",
                        "@ABCDEFGHIJKLMNO",
                        "PQRSTUVWXYZ[\\]^_",
                        "`abcdefghijklmno",
                        "pqrstuvwxyz{|}~"
                      ]
                    },
                    {
                      "texture": "UI_font_extra.png",
                      "cols": 2,
                      "rows": 2,
                      "glyphWidth": 8,
                      "glyphHeight": 7,
                      "cellLogicalSize": 8,
                      "chars": ["★é"]
                    }
                  ]
                }
                """.trimIndent()
            )

            dataDrivenFont.reloadFont()
            assertEquals(2, dataDrivenFont.getLoadedPageCount())
            assertEquals(97, dataDrivenFont.getLoadedGlyphCount())

            val starGlyph = dataDrivenFont.getGlyphMetrics('★'.code)
            val accentGlyph = dataDrivenFont.getGlyphMetrics('é'.code)
            val iGlyph = dataDrivenFont.getGlyphMetrics('i'.code)
            val mGlyph = dataDrivenFont.getGlyphMetrics('M'.code)
            assertNotNull(starGlyph)
            assertNotNull(accentGlyph)
            assertEquals(1, starGlyph!!.pageIndex)
            assertEquals(2, starGlyph.widthUnits)
            assertEquals(6, accentGlyph!!.widthUnits)
            val exclGlyph = dataDrivenFont.getGlyphMetrics('!'.code)
            assertNotNull(exclGlyph)
            // Verify 6x16 rows inside 128x64 power-of-two texture align to 8px cell height (row 4 -> y=32/64=0.5)
            assertEquals(32f / 64f, iGlyph!!.v1, 1e-4f)
            assertEquals(39f / 64f, iGlyph.v2, 1e-4f)
            // Proportional measurement makes narrow glyphs ('!'=3, 'i'=4) narrower than 'M'(5)
            assertEquals(3, exclGlyph!!.widthUnits)
            assertEquals(4, iGlyph.widthUnits)
            assertEquals(5, mGlyph!!.widthUnits)
            assertTrue(iGlyph.widthUnits < mGlyph.widthUnits)
        } finally {
            overrideConfig.delete()
            overridePage.delete()
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
