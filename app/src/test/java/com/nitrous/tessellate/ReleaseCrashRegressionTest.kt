package com.nitrous.tessellate

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.SceneMode
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression test suite for release-build crashes in:
 * - Concurrent entity spawning & world entity ticks
 * - Scene mode transition, hotbar slot snapshot transition (9 -> 6), and camera orbit
 * - Void-falling ItemDropEntity termination without unbounded block-Y iteration
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReleaseCrashRegressionTest {

    @Test
    fun `concurrent spawn commands and world entity ticks never throw`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = TessellateEngine(context)
        engine.setAppState(AppState.PLAYING)
        engine.switchSceneMode(SceneMode.WORLD)
        engine.onSurfaceChanged(1920, 1080)

        val failure = AtomicReference<Throwable?>(null)
        val scratch = ArrayList<Entity>()

        val uiThread = Thread {
            try {
                repeat(120) {
                    engine.worldScene.spawnDebugItemDrop(engine.camera)
                    if (it % 10 == 0) {
                        engine.executeCommand("/spawn item_drop")
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        val glThread = Thread {
            try {
                repeat(120) {
                    engine.worldScene.update(0.016f)
                    engine.worldScene.world.copyEntitiesTo(scratch)
                    for (e in scratch) {
                        assertTrue(e.position.y.isFinite())
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        uiThread.start()
        glThread.start()
        uiThread.join()
        glThread.join()

        assertNull("Concurrent spawn-drop must not throw: ${failure.get()}", failure.get())
        assertTrue(engine.worldScene.world.entities.isNotEmpty())
    }

    @Test
    fun `concurrent orbit mode toggles and renderUi never mismatch hotbar slots or crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        engine.setAppState(AppState.PLAYING)
        engine.switchSceneMode(SceneMode.WORLD)
        assertEquals(9, engine.uiRenderer.getHotbarSlotRects().size)

        val failure = AtomicReference<Throwable?>(null)
        val vpCopy = FloatArray(16)

        val uiThread = Thread {
            try {
                repeat(150) { i ->
                    val next = if (i % 2 == 0) SceneMode.SHOWCASE else SceneMode.WORLD
                    engine.switchSceneMode(next)
                    if (next == SceneMode.SHOWCASE) {
                        engine.camera.orbit(2.5f, -3.5f)
                        engine.camera.zoom(-0.2f)
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        val glThread = Thread {
            try {
                repeat(150) {
                    val (mode, slots) = engine.snapshotModeAndHotbarSlots()
                    if (mode == SceneMode.SHOWCASE) {
                        assertEquals("Showcase slots must equal 6", 6, slots.size)
                    } else {
                        assertEquals("World slots must equal 9", 9, slots.size)
                    }
                    engine.camera.copyViewProjectionMatrix(vpCopy)
                    engine.uiRenderer.renderUi(engine, mode, AppState.PLAYING)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        uiThread.start()
        glThread.start()
        uiThread.join()
        glThread.join()

        assertNull("Concurrent orbit mode transition must not throw: ${failure.get()}", failure.get())

        // Verify final deterministic transition to Orbit (SHOWCASE) mode
        engine.switchSceneMode(SceneMode.SHOWCASE)
        assertEquals(SceneMode.SHOWCASE, engine.currentSceneMode)
        assertEquals(6, engine.uiRenderer.getHotbarSlotRects().size)
        assertFalse(engine.camera.isFirstPerson)
    }

    @Test
    fun `item drop falling into void despawns cleanly without unbounded collision loop`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = TessellateEngine(context)
        val world = engine.worldScene.world
        world.clearEntities()

        val drop = ItemDropEntity(Vec3(-5.0f, -65.0f, -5.0f))
        world.spawnEntity(drop)
        world.tickEntities(0.05f)

        assertTrue(drop.isRemoved)
        assertTrue(world.entities.isEmpty())
    }
}
