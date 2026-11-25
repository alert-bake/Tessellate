package com.nitrous.tessellate

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.save.EntityPersistenceRegistry
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.settings.GameSettings
import com.nitrous.tessellate.settings.SettingsManager
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.ChunkPos
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Verification test suite for TDD-015: Full Save System, Entity Persistence, & Power User Access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd015SaveSystemAndPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
        SettingsManager.resolveSettingsFile(context).delete()
    }

    @Test
    fun `acceptance test 1-3 directory layout, block placement, item drop, and inventory persist across quit and reload`() {
        val baseFilesDir = WorldSaveManager.getBaseFilesDir(context)
        assertEquals(context.getExternalFilesDir(null) ?: context.filesDir, baseFilesDir)

        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)

        try {
            // Save settings -> stored under external files/settings.json
            val customSettings = GameSettings(renderDistance = 5, sensitivity = 0.30f, fov = 82f, soundVolume = 0.6f)
            awaitJob(engine.settingsManager.saveSettings(customSettings))
            assertEquals(File(baseFilesDir, "settings.json").absolutePath, engine.settingsManager.settingsFile.absolutePath)
            assertTrue(File(baseFilesDir, "settings.json").exists())

            // Create a Flat Survival world named "Bakery Save"
            val session = WorldSession(
                name = "Bakery Save",
                gamemode = GameMode.SURVIVAL,
                worldType = WorldType.FLAT,
                seed = 98765L
            )
            engine.startWorldSession(session)

            val scene = engine.worldScene
            // Place a glass block above the flat surface at (24, 5, 24)
            assertTrue(scene.world.setBlock(24, 5, 24, BlockType.GLASS))

            // Drop a cobblestone item entity in chunk (1, 0, 1) -> world (22.5, 5.0, 22.5)
            val droppedItem = ItemDropEntity(
                position = Vec3(22.5, 5.0, 22.5),
                itemId = "tessellate:cobblestone"
            ).apply {
                velocity.set(0.5, 1.25, -0.25)
            }
            scene.world.spawnEntity(droppedItem)

            // Modify player position, look orientation, and hotbar inventory
            scene.player.posX = 28.25
            scene.player.posY = 5.0
            scene.player.posZ = 27.75
            scene.player.yaw = 132.5f
            scene.player.pitch = -18.0f
            scene.player.inventory.setSlot(0, ItemStack("tessellate:stone", 17))
            scene.player.inventory.setSlot(4, ItemStack("tessellate:dirt", 42))
            scene.player.inventory.selectSlot(4)

            // Save and Quit -> WORLD_SELECT
            engine.saveAndQuitToWorldSelect()
            assertEquals(AppState.WORLD_SELECT, engine.currentAppState)

            // Verify power-user directory structure: files/saves/Bakery Save/{level.json, chunks/, entities/}
            val worldDir = File(baseFilesDir, "saves/Bakery Save")
            assertTrue(worldDir.exists() && worldDir.isDirectory)
            assertTrue(File(worldDir, "level.json").exists())
            assertTrue(File(worldDir, "chunks/c_1_0_1.bin").exists())
            assertTrue(File(worldDir, "entities/c_1_0_1.json").exists())
        } finally {
            engine.worldScene.destroySession()
        }

        // Simulate fresh app restart with brand-new TessellateEngine
        val restartedEngine = TessellateEngine(context)
        restartedEngine.onSurfaceChanged(1920, 1080)
        try {
            restartedEngine.setAppState(AppState.WORLD_SELECT)
            val wsScreen = restartedEngine.uiRenderer.worldSelectScreen
            assertEquals(1, wsScreen.existingWorlds.size)
            assertEquals("Bakery Save", wsScreen.existingWorlds[0].worldName)

            val t0 = SystemClock.uptimeMillis()
            tapButton(restartedEngine, wsScreen.playSelectedButton.rect.centerX(), wsScreen.playSelectedButton.rect.centerY(), t0)
            assertEquals(AppState.PLAYING, restartedEngine.currentAppState)

            val loadedScene = restartedEngine.worldScene
            // Verify placed block, dropped entity, inventory, and player transform restored
            assertEquals(BlockType.GLASS, loadedScene.world.getBlock(24, 5, 24))
            assertEquals(28.25, loadedScene.player.posX, 1e-4)
            assertEquals(5.0, loadedScene.player.posY, 1e-4)
            assertEquals(27.75, loadedScene.player.posZ, 1e-4)
            assertEquals(132.5f, loadedScene.player.yaw, 1e-4f)
            assertEquals(-18.0f, loadedScene.player.pitch, 1e-4f)
            assertEquals(4, loadedScene.player.inventory.selectedSlot)
            assertEquals("tessellate:stone", loadedScene.player.inventory.getSlot(0)?.itemId)
            assertEquals(17, loadedScene.player.inventory.getSlot(0)?.count)
            assertEquals("tessellate:dirt", loadedScene.player.inventory.getSlot(4)?.itemId)
            assertEquals(42, loadedScene.player.inventory.getSlot(4)?.count)

            val restoredDrops = loadedScene.world.entities.filterIsInstance<ItemDropEntity>()
            assertEquals(1, restoredDrops.size)
            val restoredDrop = restoredDrops.first()
            assertEquals("tessellate:cobblestone", restoredDrop.itemId)
            assertEquals(22.5, restoredDrop.position.x, 1e-4)
            assertEquals(5.0, restoredDrop.position.y, 1e-4)
            assertEquals(22.5, restoredDrop.position.z, 1e-4)
            assertEquals(0.5, restoredDrop.velocity.x, 1e-4)
        } finally {
            restartedEngine.worldScene.destroySession()
        }
    }

    @Test
    fun `chunk binary format saves raw 4096 bytes and omits 100 percent air chunks`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession(
                    name = "AirOptWorld",
                    gamemode = GameMode.CREATIVE,
                    worldType = WorldType.FLAT,
                    seed = 111L
                )
            )
            val worldDir = File(WorldSaveManager.getSavesRootDir(context), "AirOptWorld")

            // Place a single stone block in chunk (1, 5, 1) -> world (20, 82, 20)
            engine.worldScene.world.setBlock(20, 82, 20, BlockType.STONE)
            engine.saveAndQuitToWorldSelect()

            // Y=0 has stone/dirt/grass -> saved as exact raw 4096 bytes
            val floorChunkFile = File(worldDir, "chunks/c_1_0_1.bin")
            assertTrue(floorChunkFile.exists())
            assertEquals(Chunk.VOLUME.toLong(), floorChunkFile.length())

            // Y=5 had one stone block -> saved as exact raw 4096 bytes
            val editedSkyChunkFile = File(worldDir, "chunks/c_1_5_1.bin")
            assertTrue(editedSkyChunkFile.exists())
            assertEquals(Chunk.VOLUME.toLong(), editedSkyChunkFile.length())

            // Y=7 is 100% air -> must NOT be saved
            val emptySkyChunkFile = File(worldDir, "chunks/c_1_7_1.bin")
            assertFalse("100% air chunk must NOT be saved to disk", emptySkyChunkFile.exists())

            // Reload and clear the single block at (20, 82, 20) -> stale c_1_5_1.bin must be deleted
            val savedData = WorldSaveManager.listSavedWorlds(context).first { it.worldName == "AirOptWorld" }
            engine.loadSavedWorld(savedData)
            assertEquals(BlockType.STONE, engine.worldScene.world.getBlock(20, 82, 20))
            engine.worldScene.world.setBlock(20, 82, 20, BlockType.AIR)
            engine.saveAndQuitToWorldSelect()

            assertFalse("Chunk mined to 100% air must delete its .bin file", editedSkyChunkFile.exists())
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `async chunk unload and reload preserves modded GasVolume GasAmount entity data`() {
        val gasTypeId = "testmod:gas_volume"
        EntityPersistenceRegistry.register(gasTypeId) { saved ->
            GasVolumeEntity(Vec3(saved.x, saved.y, saved.z))
        }

        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            val worldDir = WorldSaveManager.resolveNewWorldFolder(context, "AsyncStreamWorld")
            val storage = com.nitrous.tessellate.save.WorldSaveStorage(worldDir)
            val world = World(
                session = WorldSession("AsyncStreamWorld", GameMode.SURVIVAL, WorldType.FLAT, 42L),
                renderDistance = 2,
                saveStorage = storage
            )

            // Spawn modded GasVolume entity at chunk (0, 1, 0) -> world (4.5, 20.0, 4.5)
            val gas = GasVolumeEntity(Vec3(4.5, 20.0, 4.5), gasAmount = 73.5f)
            world.spawnEntity(gas)
            // Place marker block in chunk (0, 1, 0) -> world (4, 20, 4)
            world.setBlock(4, 20, 4, BlockType.COBBLESTONE)

            // Walk player far away to (160, 160) -> chunk (10, 10), outside renderDistance=2 from (0, 0)
            val farJob = world.streamChunksAround(playerX = 160.0, playerZ = 160.0, forceCheck = true)
            farJob?.let { awaitJob(it) }
            storage.awaitPendingWritesBlocking()

            // Chunk (0, 1, 0) must be unloaded and removed from active world memory
            assertFalse(world.hasChunk(0, 1, 0))
            assertTrue("Modded entity in unloaded chunk must be removed from active list", world.entities.none { it === gas })
            assertTrue(File(worldDir, "chunks/c_0_1_0.bin").exists())
            assertTrue(File(worldDir, "entities/c_0_1_0.json").exists())

            // Walk back near (8, 8) -> chunk (0, 1, 0) reloads asynchronously with block & GasVolume entity!
            val returnJob = world.streamChunksAround(playerX = 8.0, playerZ = 8.0, forceCheck = true)
            returnJob?.let { awaitJob(it) }

            assertTrue(world.hasChunk(0, 1, 0))
            assertEquals(BlockType.COBBLESTONE, world.getBlock(4, 20, 4))
            val restoredGas = world.entities.filterIsInstance<GasVolumeEntity>().firstOrNull()
            assertNotNull("GasVolume entity must respawn when chunk reloads", restoredGas)
            assertEquals(73.5f, restoredGas!!.gasAmount, 1e-4f)
            assertEquals(4.5, restoredGas.position.x, 1e-4)
            world.destroy()
        } finally {
            EntityPersistenceRegistry.unregister(gasTypeId)
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `pause save and 5-minute autosave persist level metadata cleanly`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession("AutoSaveWorld", GameMode.SURVIVAL, WorldType.FLAT, 555L)
            )
            val scene = engine.worldScene
            val storage = scene.saveStorage!!

            // Move player & pause -> triggers async save
            scene.player.posX = 31.0
            engine.setPaused(true)
            storage.lastSaveJob?.let { awaitJob(it) }
            assertEquals(31.0, storage.loadLevelData()!!.playerX, 1e-4)

            // Unpause, move player again, and advance update by 300s (5 minutes) -> triggers Autosave
            engine.setPaused(false)
            scene.player.posZ = 39.5
            scene.update(300.0f)
            storage.lastSaveJob?.let { awaitJob(it) }
            assertEquals(39.5, storage.loadLevelData()!!.playerZ, 1e-4)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `acceptance test 4 delete world button removes world folder from disk and updates UI list`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(WorldSession("DoomedWorld", GameMode.SURVIVAL, WorldType.FLAT, 77L))
            val worldDir = File(WorldSaveManager.getSavesRootDir(context), "DoomedWorld")
            engine.saveAndQuitToWorldSelect()

            assertTrue(worldDir.exists())
            val ws = engine.uiRenderer.worldSelectScreen
            assertEquals(1, ws.existingWorlds.size)
            assertEquals("DoomedWorld", ws.selectedWorld?.worldName)
            assertTrue(ws.deleteWorldButton.enabled)

            val t0 = SystemClock.uptimeMillis()
            tapButton(engine, ws.deleteWorldButton.rect.centerX(), ws.deleteWorldButton.rect.centerY(), t0)

            assertFalse("Delete World must recursively delete save folder", worldDir.exists())
            assertTrue(ws.existingWorlds.isEmpty())
            assertFalse(ws.deleteWorldButton.enabled)
            assertFalse(ws.playSelectedButton.enabled)
        } finally {
            engine.worldScene.destroySession()
        }
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

    private class GasVolumeEntity(
        position: Vec3,
        var gasAmount: Float = 0f
    ) : Entity(
        position = position,
        boundingBox = AABB(position.x, position.y, position.z, position.x + 1.0, position.y + 1.0, position.z + 1.0)
    ) {
        override val typeId: String = "testmod:gas_volume"

        override fun writeExtraData(): Map<String, Any?> = mapOf("GasAmount" to gasAmount)

        override fun readExtraData(data: Map<String, Any?>) {
            val raw = data["GasAmount"]
            gasAmount = when (raw) {
                is Number -> raw.toFloat()
                is String -> raw.toFloatOrNull() ?: 0f
                else -> 0f
            }
        }

        override fun updateBoundingBox() {
            boundingBox.set(position.x, position.y, position.z, position.x + 1.0, position.y + 1.0, position.z + 1.0)
        }
    }
}
