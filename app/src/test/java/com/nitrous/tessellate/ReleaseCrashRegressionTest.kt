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
import com.nitrous.tessellate.settings.GameSettings
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.settings.VanillaSettingsRegistrar
import com.nitrous.tessellate.world.AtlasTileResolver
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.ChunkGeometryBuilder
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.ParallelChunkMesher
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
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

        val drop = ItemDropEntity(Vec3(-5.0, -65.0, -5.0))
        world.spawnEntity(drop)
        world.tickEntities(0.05f)

        assertTrue(drop.isRemoved)
        assertTrue(world.entities.isEmpty())
    }

    @Test
    fun `concurrent world session start quit and draw frame loop never throws`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1280, 720)
        val failure = AtomicReference<Throwable?>(null)

        val uiThread = Thread {
            try {
                repeat(12) { idx ->
                    engine.startWorldSession(
                        WorldSession("Session_$idx", GameMode.SURVIVAL, WorldType.DEFAULT, 100L + idx)
                    )
                    Thread.sleep(4)
                    engine.applySettings(GameSettings(renderDistance = 2 + (idx % 3)))
                    Thread.sleep(4)
                    engine.saveAndQuitToWorldSelect()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        val glThread = Thread {
            try {
                repeat(120) {
                    engine.onDrawFrame()
                    engine.worldScene.worldRenderer.computeVisibleChunks(
                        engine.camera.viewProjectionMatrix,
                        engine.camera.eyeX,
                        engine.camera.eyeY,
                        engine.camera.eyeZ
                    )
                    Thread.sleep(2)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        uiThread.start()
        glThread.start()
        uiThread.join()
        glThread.join()
        engine.worldScene.destroySession()

        assertNull("Concurrent world session transitions + draw loop must not throw: ${failure.get()}", failure.get())
    }

    @Test
    fun `volatile parallel options default to false, appear in debug options UI, and persist safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsManager.resolveSettingsFile(context).delete()
        val engine = TessellateEngine(context)
        run {
            val deadline = System.currentTimeMillis() + 5000L
            while (!engine.initialSettingsLoadJob.isCompleted && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        engine.onSurfaceChanged(1920, 1080)
        try {
            assertFalse("parallelChunkMeshing must default to false", engine.currentSettings.parallelChunkMeshing)
            assertFalse("asyncMeshPrebuild must default to false", engine.currentSettings.asyncMeshPrebuild)
            assertFalse(engine.worldScene.worldRenderer.parallelMeshingEnabled)
            assertFalse(engine.worldScene.asyncMeshPrebuildEnabled)

            assertTrue(engine.settingsRegistry.setValue("tessellate:enable_debug_options", true).isValid)
            engine.setAppState(AppState.DEBUG_OPTIONS)
            val screen = engine.uiRenderer.settingsScreen
            screen.selectCategory(VanillaSettingsRegistrar.CAT_DEBUG_RENDERING)

            val visibleIds = screen.getVisibleRows().map { it.definition.id }
            assertTrue(visibleIds.contains("tessellate:debug_parallel_meshing"))
            assertTrue(visibleIds.contains("tessellate:debug_async_mesh_prebuild"))

            assertTrue(engine.settingsRegistry.setValue("tessellate:debug_parallel_meshing", true).isValid)
            assertTrue(engine.settingsRegistry.setValue("tessellate:debug_async_mesh_prebuild", true).isValid)
            assertTrue(engine.worldScene.worldRenderer.parallelMeshingEnabled)
            assertTrue(engine.worldScene.asyncMeshPrebuildEnabled)

            engine.settingsManager.lastSaveJob?.let { job ->
                val deadline = System.currentTimeMillis() + 5000L
                while (!job.isCompleted && System.currentTimeMillis() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(5)
                }
            }

            val reloaded = SettingsManager(context).loadSettingsBlocking()
            assertTrue(reloaded.parallelChunkMeshing)
            assertTrue(reloaded.asyncMeshPrebuild)
        } finally {
            engine.worldScene.destroySession()
            SettingsManager.resolveSettingsFile(context).delete()
        }
    }

    @Test
    fun `parallel chunk mesher isolates worker errors and preserves dirty state across concurrent edits`() {
        val world = World(chunksX = 1, chunksY = 1, chunksZ = 1)
        val chunk = world.getChunk(0, 0, 0)!!
        chunk.setBlock(3, 3, 3, BlockType.STONE)
        chunk.markDirty()

        // 1. Failing tile resolver must not crash background worker threads
        val mesher = ParallelChunkMesher()
        try {
            val throwingResolver = AtlasTileResolver { throw IllegalStateException("Simulated missing atlas tile") }
            assertTrue(mesher.submitDirtyChunk(chunk, world, throwingResolver, forceRebuild = true))
            Thread.sleep(150)
            assertNull("Failing build must not push corrupted mesh output", mesher.pollCompletedMesh())
        } finally {
            mesher.shutdown()
        }

        // 2. Concurrent edit during geometry build keeps needsMeshRebuild = true via meshDirtyEpoch
        val builder = ChunkGeometryBuilder()
        val mutatingResolver = AtlasTileResolver { _ ->
            chunk.setBlock(4, 4, 4, BlockType.DIRT)
            0
        }
        val output = builder.buildChunkGeometry(world, chunk, mutatingResolver)
        assertTrue("Chunk dirtied mid-build must remain marked dirty", chunk.needsMeshRebuild)
        assertTrue(chunk.meshDirtyEpoch > output.dirtyEpoch)
    }
}
