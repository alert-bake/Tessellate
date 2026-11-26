package com.nitrous.tessellate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.save.OsmiumCodec
import com.nitrous.tessellate.save.OsmiumFormat
import com.nitrous.tessellate.save.OsmiumProtocol
import com.nitrous.tessellate.save.WorldSaveManager
import android.os.SystemClock
import android.view.MotionEvent
import co.nstant.`in`.cbor.CborDecoder
import com.nitrous.tessellate.command.CommandConsole
import com.nitrous.tessellate.command.CommandRegistry
import com.nitrous.tessellate.input.TouchInputController
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.item.ItemType
import com.nitrous.tessellate.item.RecipeRegistry
import com.nitrous.tessellate.registry.CborRegistryCache
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.world.BlockRegistry
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.ChunkMesh
import com.nitrous.tessellate.world.ChunkPos
import com.nitrous.tessellate.world.Direction
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.RaycastHit
import com.nitrous.tessellate.world.TypedValue
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldRenderer
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import com.nitrous.tessellate.worldgen.WorldGenRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verification test suite for TDD-024: Osmium Protocol - Stages 1, 2, 3, 4, & 5
 * (Format Foundation & RLE, Dirty Tracking & Epochs, Unified Save Manager,
 * Deduplication & LZ4 Compaction, and Sparse Block Data & The Greener Item).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd024OsmiumProtocolTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        WorldSaveManager.getSavesRootDir(context).deleteRecursively()
        File(context.filesDir, "content").deleteRecursively()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
    }

    // =========================================================================
    // STAGE 1: Format Foundation, RLE, & Quarantine
    // =========================================================================

    @Test
    fun `test legacy 4096-byte chunk loading`() {
        val rawBytes = ByteArray(Chunk.VOLUME) { (it % 10).toByte() }
        val decodedWithFmt = OsmiumCodec.decodeWithFormat(rawBytes)
        assertNotNull(decodedWithFmt)
        assertEquals(OsmiumFormat.LEGACY_RAW, decodedWithFmt!!.format)
        assertArrayEquals(rawBytes, decodedWithFmt.blocks)
    }

    @Test
    fun `test V1 header parsing`() {
        val rawBytes = ByteArray(Chunk.VOLUME) { (it % 15).toByte() }
        val v1Bytes = ByteArray(2 + Chunk.VOLUME)
        v1Bytes[0] = OsmiumCodec.MAGIC_BYTE_0
        v1Bytes[1] = OsmiumCodec.VERSION_V1
        System.arraycopy(rawBytes, 0, v1Bytes, 2, Chunk.VOLUME)

        val decodedWithFmt = OsmiumCodec.decodeWithFormat(v1Bytes)
        assertNotNull(decodedWithFmt)
        assertEquals(OsmiumFormat.V1_RAW, decodedWithFmt!!.format)
        assertArrayEquals(rawBytes, decodedWithFmt.blocks)
    }

    @Test
    fun `test 100 percent stone chunk encoding and decoding with exact size check`() {
        val stoneChunkBytes = ByteArray(Chunk.VOLUME) { BlockType.STONE.id }
        val encodedWithFmt = OsmiumCodec.encodeWithFormat(stoneChunkBytes)

        assertEquals(OsmiumFormat.V2_OSMIUM, encodedWithFmt.format)
        assertEquals(34, encodedWithFmt.bytes.size)
        assertEquals(OsmiumCodec.MAGIC_BYTE_0, encodedWithFmt.bytes[0])
        assertEquals(OsmiumCodec.VERSION_V2, encodedWithFmt.bytes[1])

        val decoded = OsmiumCodec.decode(encodedWithFmt.bytes)
        assertNotNull(decoded)
        assertArrayEquals(stoneChunkBytes, decoded)
    }

    @Test
    fun `test complex cave chunk encoding and decoding`() {
        val caveChunkBytes = ByteArray(Chunk.VOLUME) { i ->
            when ((i / 8) % 3) {
                0 -> BlockType.AIR.id
                1 -> BlockType.STONE.id
                else -> BlockType.DIRT.id
            }
        }
        val encoded = OsmiumCodec.encode(caveChunkBytes)
        assertEquals(OsmiumCodec.MAGIC_BYTE_0, encoded[0])
        assertEquals(OsmiumCodec.VERSION_V3, encoded[1])

        val decoded = OsmiumCodec.decode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(caveChunkBytes, decoded)
    }

    @Test
    fun `test compression fallback to V1 for uncompressible chunks`() {
        // High-entropy random bytes expand under both RLE and LZ4, triggering V1_RAW fallback
        val randomChunkBytes = ByteArray(Chunk.VOLUME)
        Random(12345L).nextBytes(randomChunkBytes)

        val encodedWithFmt = OsmiumCodec.encodeWithFormat(randomChunkBytes)
        assertEquals(OsmiumFormat.V1_RAW, encodedWithFmt.format)
        assertEquals(OsmiumCodec.MAGIC_BYTE_0, encodedWithFmt.bytes[0])
        assertEquals(OsmiumCodec.VERSION_V1, encodedWithFmt.bytes[1])

        val decoded = OsmiumCodec.decode(encodedWithFmt.bytes)
        assertNotNull(decoded)
        assertArrayEquals(randomChunkBytes, decoded)
    }

    @Test
    fun `test 255 and 1 run splitting for long horizontal runs`() {
        val blocks = ByteArray(Chunk.VOLUME) { BlockType.AIR.id }
        for (i in 0 until 256) {
            blocks[i] = BlockType.STONE.id
        }

        val encoded = OsmiumCodec.encode(blocks)
        val decoded = OsmiumCodec.decode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(blocks, decoded)
    }

    @Test
    fun `test quarantine behavior on corrupt file`() {
        val savesRoot = WorldSaveManager.getSavesRootDir(context)
        val worldDir = File(savesRoot, "TestWorld")
        val chunksDir = File(worldDir, "chunks")
        chunksDir.mkdirs()

        val corruptFile = File(chunksDir, "c_0_0_0.bin")
        corruptFile.writeBytes(byteArrayOf(0x00, 0x02, 0x01, 0x02))

        val decoded = OsmiumProtocol.decodeChunkFile(corruptFile)
        assertNull("Corrupt file must return null", decoded)
        assertFalse("Original corrupt file must no longer exist at original path", corruptFile.exists())

        val corruptRenamed = File(chunksDir, "c_0_0_0.bin.corrupt")
        assertTrue("Corrupt file must be renamed to .corrupt", corruptRenamed.exists())
    }

    // =========================================================================
    // STAGE 2: Dirty Tracking & Epochs
    // =========================================================================

    @Test
    fun `test chunk modification sets needsSave and increments saveEpoch`() {
        val chunk = Chunk(0, 0, 0)
        assertFalse(chunk.needsSave)
        assertEquals(0, chunk.saveEpoch)

        chunk.setBlock(0, 0, 0, BlockType.STONE)
        assertTrue(chunk.needsSave)
        assertEquals(1, chunk.saveEpoch)

        chunk.setBlock(0, 0, 0, BlockType.AIR)
        assertTrue(chunk.needsSave)
        assertEquals(2, chunk.saveEpoch)
    }

    @Test
    fun `test successful save clears needsSave if epoch matches`() {
        val manager = WorldSaveManager(File(WorldSaveManager.getSavesRootDir(context), "EpochWorld"))
        val chunk = Chunk(0, 0, 0)
        chunk.setBlock(1, 1, 1, BlockType.STONE)
        assertTrue(chunk.needsSave)
        val initialEpoch = chunk.saveEpoch

        val job = manager.saveChunkAndEntitiesAsync(chunk, emptyList())
        runBlocking { job.join() }

        assertFalse("needsSave must be cleared when epoch matches after save", chunk.needsSave)
        assertEquals(initialEpoch, chunk.saveEpoch)
    }

    @Test
    fun `test modifying chunk during save preserves needsSave`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        try {
            val manager = WorldSaveManager(
                File(WorldSaveManager.getSavesRootDir(context), "MidSaveWorld"),
                ioDispatcher = dispatcher
            )
            // Hold the single IO worker thread until after we mutate the chunk
            executor.submit { gate.await(5, TimeUnit.SECONDS) }

            val chunk = Chunk(0, 0, 0)
            chunk.setBlock(2, 2, 2, BlockType.STONE)
            assertTrue(chunk.needsSave)

            val job = manager.saveChunkAndEntitiesAsync(chunk, emptyList())

            // Mutate chunk before the queued save runs (incrementing saveEpoch)
            chunk.setBlock(2, 2, 3, BlockType.DIRT)
            gate.countDown()

            runBlocking { job.join() }

            assertTrue("needsSave must remain true if chunk was modified mid-save", chunk.needsSave)
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    // =========================================================================
    // STAGE 3: Unified Save Manager, Queue Coalescing, Pause, Unload, & Flush
    // =========================================================================

    @Test
    fun `test duplicate queued chunk save overwrites previous request in IO queue`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val gate = CountDownLatch(1)
        try {
            val worldDir = File(WorldSaveManager.getSavesRootDir(context), "CoalesceWorld")
            val manager = WorldSaveManager(worldDir, ioDispatcher = dispatcher)

            // Block the single IO thread so both queued saves collide in the queue
            executor.submit { gate.await(5, TimeUnit.SECONDS) }

            val chunk = Chunk(0, 0, 0)
            chunk.setBlock(3, 3, 3, BlockType.STONE)
            val job1 = manager.saveChunkAndEntitiesAsync(chunk, emptyList())

            chunk.setBlock(3, 3, 3, BlockType.DIRT)
            val job2 = manager.saveChunkAndEntitiesAsync(chunk, emptyList())

            assertEquals(1, manager.coalescedChunkSaves.get())
            gate.countDown()

            runBlocking {
                job1.join()
                job2.join()
            }
            manager.flushBlocking()

            assertEquals("Coalesced queue must perform only 1 disk write for both requests", 1, manager.totalChunkDiskWrites.get())
            assertFalse(chunk.needsSave)

            val loadedBytes = manager.loadChunkBytes(ChunkPos(0, 0, 0))
            assertNotNull(loadedBytes)
            val idx = (3 shl 8) or (3 shl 4) or 3
            assertEquals("Disk must contain the overwriting DIRT block", BlockType.DIRT.id, loadedBytes!![idx])
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `test pausing the game saves all currently loaded chunks`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession("PauseChunksWorld", GameMode.CREATIVE, WorldType.FLAT, 321L)
            )
            val scene = engine.worldScene
            val manager = scene.saveManager!!

            // Wait for initial async chunk streaming around spawn to settle
            scene.world.streamChunksAround(scene.player.posX, scene.player.posZ)?.let { awaitJob(it) }

            // Pause the game -> triggers async snapshot of level.json + all loaded chunks
            engine.setPaused(true)
            manager.lastSaveJob?.let { awaitJob(it) }

            val loadedNonAirChunks = scene.world.getAllChunks().filter { !WorldSaveManager.isAllAir(it.blocks) }
            assertTrue("Expected loaded non-air chunks in flat world", loadedNonAirChunks.isNotEmpty())

            for (chunk in loadedNonAirChunks) {
                val pos = ChunkPos(chunk.chunkX, chunk.chunkY, chunk.chunkZ)
                val file = manager.chunkFileFor(pos)
                assertTrue("Chunk $pos must be saved to disk on pause", file.exists())
                assertFalse("Chunk $pos needsSave should be cleared after pause save", chunk.needsSave)
            }
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `test walking away unloads and saves chunks via unified manager`() {
        val worldDir = WorldSaveManager.resolveNewWorldFolder(context, "WalkAwayWorld")
        val manager = WorldSaveManager(worldDir)
        val world = World(
            session = WorldSession("WalkAwayWorld", GameMode.SURVIVAL, WorldType.FLAT, 777L),
            renderDistance = 1,
            saveStorage = manager
        )
        try {
            // Place a distinct marker block at (4, 20, 4) in chunk (0, 1, 0)
            assertTrue(world.setBlock(4, 20, 4, BlockType.COBBLESTONE))
            assertTrue(world.hasChunk(0, 1, 0))

            // Walk far away to (160, 160) -> chunk (0, 1, 0) leaves renderDistance=1
            val streamJob = world.streamChunksAround(playerX = 160.0, playerZ = 160.0, forceCheck = true)
            streamJob?.let { awaitJob(it) }
            manager.flushBlocking()

            assertFalse("Out-of-range chunk (0, 1, 0) must be unloaded from memory", world.hasChunk(0, 1, 0))
            val chunkFile = manager.chunkFileFor(ChunkPos(0, 1, 0))
            assertTrue("Unloaded chunk (0, 1, 0) must be saved to disk", chunkFile.exists())

            val decoded = manager.loadChunkBytes(ChunkPos(0, 1, 0))
            assertNotNull(decoded)
            val idx = (4 shl 8) or (4 shl 4) or 4 // lx=4, ly=4 (20-16), lz=4
            assertEquals(BlockType.COBBLESTONE.id, decoded!![idx])
        } finally {
            world.destroy()
        }
    }

    @Test
    fun `test blocking save-and-quit waits for all coroutines to finish before returning`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession("BlockingQuitWorld", GameMode.CREATIVE, WorldType.FLAT, 999L)
            )
            val scene = engine.worldScene
            val manager = scene.saveManager!!

            // Place a block in sky chunk (1, 4, 1) -> world (20, 68, 20)
            assertTrue(scene.world.setBlock(20, 68, 20, BlockType.GLASS))
            scene.player.posX = 41.25

            // Save and quit synchronously without any manual awaitJob() afterwards
            engine.saveAndQuitToWorldSelect()

            // Immediately verify both level.json and c_1_4_1.bin are completely flushed to disk
            assertTrue("level.json must exist immediately after saveAndQuitToWorldSelect", manager.levelFile.exists())
            assertEquals(41.25, manager.loadLevelData()!!.playerX, 1e-4)

            val chunkFile = manager.chunkFileFor(ChunkPos(1, 4, 1))
            assertTrue("Edited chunk .bin must exist immediately after blocking save-and-quit", chunkFile.exists())
            val decoded = manager.loadChunkBytes(ChunkPos(1, 4, 1))
            assertNotNull(decoded)
            val idx = (4 shl 8) or (4 shl 4) or 4 // lx=4, ly=4 (68-64), lz=4
            assertEquals(BlockType.GLASS.id, decoded!![idx])
        } finally {
            engine.worldScene.destroySession()
        }
    }

    // =========================================================================
    // STAGE 4: Deduplication (V4) & LZ4 Compaction (V3)
    // =========================================================================

    @Test
    fun `test V3 LZ4 compresses large RLE payloads and decompresses identically`() {
        // Checkerboard across slices produces a large, repetitive RLE payload (>= 64 bytes)
        val patternedBlocks = ByteArray(Chunk.VOLUME) { i ->
            when ((i / 4) % 4) {
                0 -> BlockType.STONE.id
                1 -> BlockType.DIRT.id
                2 -> BlockType.GRASS.id
                else -> BlockType.COBBLESTONE.id
            }
        }

        val encodedWithFmt = OsmiumCodec.encodeWithFormat(patternedBlocks)
        assertEquals(OsmiumFormat.V3_LZ4, encodedWithFmt.format)
        assertEquals(OsmiumCodec.MAGIC_BYTE_0, encodedWithFmt.bytes[0])
        assertEquals(OsmiumCodec.VERSION_V3, encodedWithFmt.bytes[1])

        val uncompressedRleLen =
            ((encodedWithFmt.bytes[2].toInt() and 0xFF) shl 8) or (encodedWithFmt.bytes[3].toInt() and 0xFF)
        assertTrue("Expected RLE payload >= 64 bytes to trigger LZ4", uncompressedRleLen >= OsmiumCodec.LZ4_THRESHOLD_BYTES)
        assertTrue(
            "Expected V3 LZ4 output (${encodedWithFmt.bytes.size}) to be smaller than raw RLE ($uncompressedRleLen)",
            encodedWithFmt.bytes.size < uncompressedRleLen
        )

        val decodedWithFmt = OsmiumCodec.decodeWithFormat(encodedWithFmt.bytes)
        assertNotNull(decodedWithFmt)
        assertEquals(OsmiumFormat.V3_LZ4, decodedWithFmt!!.format)
        assertArrayEquals(patternedBlocks, decodedWithFmt.blocks)
    }

    @Test
    fun `test V2 skips LZ4 when RLE payload is under 64 bytes`() {
        // 15 uniform stone slices (30 bytes) + 1 slice with 2 runs (5 bytes) = 35 bytes RLE (< 64 bytes)
        val simpleBlocks = ByteArray(Chunk.VOLUME) { BlockType.STONE.id }
        for (i in 0 until 128) {
            simpleBlocks[i] = BlockType.DIRT.id
        }

        val encodedWithFmt = OsmiumCodec.encodeWithFormat(simpleBlocks)
        assertEquals(OsmiumFormat.V2_OSMIUM, encodedWithFmt.format)
        assertEquals(OsmiumCodec.MAGIC_BYTE_0, encodedWithFmt.bytes[0])
        assertEquals(OsmiumCodec.VERSION_V2, encodedWithFmt.bytes[1])
        assertTrue(encodedWithFmt.bytes.size < 2 + OsmiumCodec.LZ4_THRESHOLD_BYTES)

        val decoded = OsmiumCodec.decode(encodedWithFmt.bytes)
        assertNotNull(decoded)
        assertArrayEquals(simpleBlocks, decoded)
    }

    @Test
    fun `test macro-chunk deduplication creates single master and multiple 34-byte V4 pointer files`() {
        val worldDir = File(WorldSaveManager.getSavesRootDir(context), "DedupWorld")
        val manager = WorldSaveManager(worldDir)

        val chunkA = Chunk(0, 0, 0)
        val chunkB = Chunk(1, 0, 0)
        val chunkC = Chunk(2, 0, 0)

        // Populate all three chunks with identical non-air terrain
        for (y in 0 until 8) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val block = if (y < 7) BlockType.STONE else BlockType.GRASS
                    chunkA.setBlock(x, y, z, block)
                    chunkB.setBlock(x, y, z, block)
                    chunkC.setBlock(x, y, z, block)
                }
            }
        }

        val expectedHashBytes = OsmiumCodec.sha256Bytes(chunkA.blocks)
        val expectedHashHex = OsmiumCodec.sha256Hex(expectedHashBytes)

        val j1 = manager.saveChunkAndEntitiesAsync(chunkA, emptyList())
        val j2 = manager.saveChunkAndEntitiesAsync(chunkB, emptyList())
        val j3 = manager.saveChunkAndEntitiesAsync(chunkC, emptyList())
        runBlocking {
            j1.join()
            j2.join()
            j3.join()
        }
        manager.flushBlocking()

        val masterFiles = manager.mastersDir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }.orEmpty()
        assertEquals("Identical chunks must share exactly one master file", 1, masterFiles.size)
        assertEquals("$expectedHashHex.bin", masterFiles.first().name)

        val positions = listOf(ChunkPos(0, 0, 0), ChunkPos(1, 0, 0), ChunkPos(2, 0, 0))
        for (pos in positions) {
            val pointerFile = manager.chunkFileFor(pos)
            assertTrue("Pointer file for $pos must exist", pointerFile.exists())
            assertEquals("V4 pointer file must be exact 34 bytes", 34L, pointerFile.length())

            val pointerBytes = pointerFile.readBytes()
            assertEquals(OsmiumCodec.MAGIC_BYTE_0, pointerBytes[0])
            assertEquals(OsmiumCodec.VERSION_V4, pointerBytes[1])
            assertArrayEquals(expectedHashBytes, OsmiumCodec.decodePointerHash(pointerBytes))

            val decoded = manager.loadChunkBytes(pos)
            assertNotNull(decoded)
            assertArrayEquals(chunkA.blocks, decoded)
        }
    }

    @Test
    fun `test missing or corrupted master quarantines V4 pointer file and falls back to worldgen`() {
        val worldDir = File(WorldSaveManager.getSavesRootDir(context), "PointerQuarantineWorld")
        val manager = WorldSaveManager(worldDir)

        // 1. Missing master test
        val posMissing = ChunkPos(0, 0, 0)
        val chunkMissing = Chunk(posMissing.x, posMissing.y, posMissing.z).apply {
            setBlock(4, 4, 4, BlockType.COBBLESTONE)
        }
        runBlocking { manager.saveChunkAndEntitiesAsync(chunkMissing, emptyList()).join() }

        val missingHashHex = OsmiumCodec.sha256Hex(OsmiumCodec.sha256Bytes(chunkMissing.blocks))
        val missingMasterFile = manager.masterFileFor(missingHashHex)
        assertTrue(missingMasterFile.exists())
        assertTrue(missingMasterFile.delete())

        val pointerMissingFile = manager.chunkFileFor(posMissing)
        assertTrue(pointerMissingFile.exists())

        val loadedMissing = manager.loadChunkBytes(posMissing)
        assertNull("Pointer with missing master must return null", loadedMissing)
        assertFalse("Broken pointer must be removed from original path", pointerMissingFile.exists())
        assertTrue(
            "Broken pointer must be quarantined to .corrupt",
            File(manager.chunksDir, "c_0_0_0.bin.corrupt").exists()
        )

        // 2. Corrupted / hash-mismatched master test (valid V2 stone chunk != COBBLESTONE hash)
        val posBadHash = ChunkPos(1, 0, 0)
        val chunkBadHash = Chunk(posBadHash.x, posBadHash.y, posBadHash.z).apply {
            setBlock(5, 5, 5, BlockType.GLASS)
        }
        runBlocking { manager.saveChunkAndEntitiesAsync(chunkBadHash, emptyList()).join() }

        val badHashHex = OsmiumCodec.sha256Hex(OsmiumCodec.sha256Bytes(chunkBadHash.blocks))
        val badMasterFile = manager.masterFileFor(badHashHex)
        // Overwrite master with valid V2 data of DIFFERENT blocks (all STONE) -> hash mismatch!
        badMasterFile.writeBytes(OsmiumCodec.encode(ByteArray(Chunk.VOLUME) { BlockType.STONE.id }))

        val pointerBadFile = manager.chunkFileFor(posBadHash)
        val loadedBad = manager.loadChunkBytes(posBadHash)
        assertNull("Pointer with mismatched master hash must return null", loadedBad)
        assertFalse(pointerBadFile.exists())
        assertTrue(File(manager.chunksDir, "c_1_0_0.bin.corrupt").exists())

        // 3. World fallback check: loading chunk (0, 0, 0) after quarantine falls back to worldgen cleanly
        val world = World(
            session = WorldSession("PointerQuarantineWorld", GameMode.CREATIVE, WorldType.FLAT, 123L),
            renderDistance = 1,
            saveStorage = manager
        )
        try {
            val streamJob = world.streamChunksAround(8.0, 8.0, forceCheck = true)
            streamJob?.let { awaitJob(it) }
            assertTrue("World must safely regenerate chunk after pointer quarantine", world.hasChunk(0, 0, 0))
            assertEquals(BlockType.GRASS, world.getBlock(4, WorldGenRegistry.FLAT_GRASS_Y, 4))
            assertEquals(BlockType.STONE, world.getBlock(4, 3, 4))
        } finally {
            world.destroy()
        }
    }

    @Test
    fun `test safe cleanup deletes orphaned masters, keeps referenced masters, and aborts on interruption`() {
        val worldDir = File(WorldSaveManager.getSavesRootDir(context), "CleanupWorld")
        val manager = WorldSaveManager(worldDir)

        // Save one active chunk whose master is referenced
        val activeChunk = Chunk(0, 0, 0).apply {
            setBlock(1, 1, 1, BlockType.STONE)
        }
        runBlocking { manager.saveChunkAndEntitiesAsync(activeChunk, emptyList()).join() }
        val referencedHash = OsmiumCodec.sha256Hex(OsmiumCodec.sha256Bytes(activeChunk.blocks))
        val referencedMaster = manager.masterFileFor(referencedHash)
        assertTrue(referencedMaster.exists())

        // Create an orphaned master file (e.g. from an edited/mined-to-air chunk)
        val orphanBlocks = ByteArray(Chunk.VOLUME) { BlockType.DIRT.id }
        val orphanHash = OsmiumCodec.sha256Hex(OsmiumCodec.sha256Bytes(orphanBlocks))
        val orphanMaster = manager.masterFileFor(orphanHash)
        orphanMaster.writeBytes(OsmiumCodec.encode(orphanBlocks))
        assertTrue(orphanMaster.exists())

        // 1. Interrupted cleanup MUST abort safely without deleting the orphaned master
        val interruptCleanupJob = manager.cleanupOrphanedMastersAsync {
            Thread.currentThread().interrupt()
        }
        runBlocking { interruptCleanupJob.join() }
        manager.flushBlocking()
        assertTrue("Interrupted cleanup must NOT delete masters", referencedMaster.exists())
        assertTrue("Interrupted cleanup must abort and leave orphan master intact", orphanMaster.exists())

        // 2. Cancelled cleanup MUST abort safely without deleting the orphaned master
        val hookEntered = CountDownLatch(1)
        val releaseHook = CountDownLatch(1)
        lateinit var cancelCleanupJob: Job
        cancelCleanupJob = manager.cleanupOrphanedMastersAsync {
            hookEntered.countDown()
            releaseHook.await(5, TimeUnit.SECONDS)
        }
        assertTrue(hookEntered.await(5, TimeUnit.SECONDS))
        cancelCleanupJob.cancel()
        releaseHook.countDown()
        manager.flushBlocking()
        assertTrue("Cancelled cleanup must NOT delete masters", orphanMaster.exists())

        // 3. Uninterrupted cleanup deletes orphaned master while keeping referenced master
        val cleanJob = manager.cleanupOrphanedMastersAsync()
        runBlocking { cleanJob.join() }
        manager.flushBlocking()
        assertTrue("Referenced master must remain after cleanup", referencedMaster.exists())
        assertFalse("Orphaned master must be deleted after safe cleanup", orphanMaster.exists())
    }

    @Test
    fun `test loading saved world asynchronously cleans up orphaned masters`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession("WorldLoadCleanup", GameMode.CREATIVE, WorldType.FLAT, 456L)
            )
            val worldDir = engine.worldScene.saveManager!!.worldDir
            engine.saveAndQuitToWorldSelect()

            // Plant an unreferenced orphaned master file in chunks/masters/
            val storage = WorldSaveManager(worldDir)
            val orphanBlocks = ByteArray(Chunk.VOLUME) { BlockType.SAND.id }
            val orphanHash = OsmiumCodec.sha256Hex(OsmiumCodec.sha256Bytes(orphanBlocks))
            val orphanMaster = storage.masterFileFor(orphanHash)
            orphanMaster.writeBytes(OsmiumCodec.encode(orphanBlocks))
            assertTrue(orphanMaster.exists())

            val savedData = WorldSaveManager.listSavedWorlds(context).first { it.worldName == "WorldLoadCleanup" }
            engine.loadSavedWorld(savedData)
            engine.worldScene.saveManager!!.flushBlocking()

            assertFalse(
                "Orphaned master should be cleaned up automatically on world load",
                orphanMaster.exists()
            )
            val activeMasters = engine.worldScene.saveManager!!.mastersDir.listFiles()?.toList().orEmpty()
            assertTrue("Referenced masters must stay intact after world load cleanup", activeMasters.isNotEmpty())
        } finally {
            engine.worldScene.destroySession()
        }
    }

    // =========================================================================
    // STAGE 5: Sparse Block Data, .data Sidecar, & Developer:greener Item
    // =========================================================================

    @Test
    fun `test Developer greener item registers cleanly as DEBUG item`() {
        val greener = ItemRegistry.get("Developer:greener")
        assertNotNull("Developer:greener must load from assets/content/items/greener.json", greener)
        assertEquals("Developer:greener", greener!!.identifier.toCanonicalString())
        assertEquals(ItemType.DEBUG, greener.itemType)
        assertEquals(1, greener.maxStackSize)
        assertFalse(greener.isPlaceable)
    }

    @Test
    fun `test tapping stone block with Greener inserts devpaint without breaking block`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession("GreenerTapWorld", GameMode.CREATIVE, WorldType.FLAT, 1234L)
            )
            val scene = engine.worldScene
            val chunk = scene.world.getChunk(1, 0, 1)!!
            val targetPos = BlockPos(20, WorldGenRegistry.FLAT_GRASS_Y, 20)
            scene.world.setBlock(targetPos, BlockType.STONE)
            scene.world.setBlock(BlockPos(20, WorldGenRegistry.FLAT_GRASS_Y + 1, 20), BlockType.AIR)
            scene.world.setBlock(BlockPos(20, WorldGenRegistry.FLAT_GRASS_Y + 2, 20), BlockType.AIR)

            // Clear default creative hotbar, equip Developer:greener via /give, and select its hotbar slot
            assertTrue(CommandConsole.execute("/clear", scene).success)
            val giveResult = CommandConsole.execute("/give Developer:greener 1", scene)
            assertTrue("/give Developer:greener 1 must succeed", giveResult.success)
            val greenerSlot = (0 until 9).first { slot ->
                scene.player.inventory.getSlot(slot)?.itemId.equals("Developer:greener", ignoreCase = true)
            }
            scene.selectHotbarSlot(greenerSlot)
            assertTrue(scene.isHoldingGreener())

            // Aim camera directly at the stone block to populate currentTargetHit via raycast
            engine.camera.setFirstPersonView(
                20.5,
                WorldGenRegistry.FLAT_GRASS_Y + 2.5,
                20.5,
                playerPitch = -89f,
                playerYaw = 0f
            )
            scene.updateCameraRaycast(engine.camera)
            assertEquals(targetPos, scene.currentTargetHit?.blockPos)
            chunk.needsSave = false
            chunk.needsMeshRebuild = false
            val epochBefore = chunk.saveEpoch

            // Simulate a touchscreen quick tap via TouchInputController (must NOT insta-mine break!)
            val touch = TouchInputController().apply { onSurfaceChanged(1920, 1080) }
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 960f, 540f, 0)
            val upEvent = MotionEvent.obtain(downTime, downTime + 40L, MotionEvent.ACTION_UP, 960f, 540f, 0)
            try {
                touch.handleTouchEvent(downEvent, scene.player, isWorldMode = true, worldScene = scene)
                touch.handleTouchEvent(upEvent, scene.player, isWorldMode = true, worldScene = scene)
            } finally {
                downEvent.recycle()
                upEvent.recycle()
            }

            // Block must NOT be broken, and blockDataMap must contain devpaint=true
            assertEquals("Tapping with Greener must NOT break the stone block", BlockType.STONE, scene.world.getBlock(targetPos))
            val localIdx = chunk.getBlockIndex(4, WorldGenRegistry.FLAT_GRASS_Y, 4)
            assertEquals(TypedValue.BooleanValue(true), chunk.blockDataMap[localIdx]?.get("devpaint"))
            assertTrue("Painting block must set chunk.needsSave = true", chunk.needsSave)
            assertTrue("Painting block must set chunk.needsMeshRebuild = true", chunk.needsMeshRebuild)
            assertEquals("Painting block must increment saveEpoch", epochBefore + 1, chunk.saveEpoch)
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `test painted block saves c_X_Y_Z data sidecar, preserves SHA256 deduplication, and reloads green`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            // 1. Verify SHA-256 deduplication ignores blockDataMap completely
            val dedupDir = File(WorldSaveManager.getSavesRootDir(context), "SidecarDedupWorld")
            val dedupManager = WorldSaveManager(dedupDir)
            val cleanChunk = Chunk(0, 0, 0).apply { setBlock(2, 2, 2, BlockType.STONE) }
            val paintedChunk = Chunk(1, 0, 0).apply {
                setBlock(2, 2, 2, BlockType.STONE)
                setBlockData(2, 2, 2, "devpaint", TypedValue.BooleanValue(true))
            }
            runBlocking {
                dedupManager.saveChunkAndEntitiesAsync(cleanChunk, emptyList()).join()
                dedupManager.saveChunkAndEntitiesAsync(paintedChunk, emptyList()).join()
            }
            dedupManager.flushBlocking()

            val masters = dedupManager.mastersDir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }.orEmpty()
            assertEquals("Painted & unpainted chunks with identical blocks must share 1 SHA-256 master", 1, masters.size)
            assertFalse("Unpainted chunk must NOT create a .data sidecar", dedupManager.chunkDataFileFor(ChunkPos(0, 0, 0)).exists())
            assertTrue("Painted chunk MUST create c_1_0_0.data sidecar", dedupManager.chunkDataFileFor(ChunkPos(1, 0, 0)).exists())

            // 2. End-to-end save and reload via TessellateEngine
            engine.startWorldSession(
                WorldSession("GreenerSaveWorld", GameMode.SURVIVAL, WorldType.FLAT, 808L)
            )
            val scene = engine.worldScene
            val worldDir = scene.saveManager!!.worldDir
            val targetPos = BlockPos(20, WorldGenRegistry.FLAT_GRASS_Y, 20)
            scene.world.setBlock(targetPos, BlockType.STONE)
            scene.world.setBlock(BlockPos(20, WorldGenRegistry.FLAT_GRASS_Y + 1, 20), BlockType.AIR)
            scene.world.setBlock(BlockPos(20, WorldGenRegistry.FLAT_GRASS_Y + 2, 20), BlockType.AIR)

            scene.player.inventory.setSlot(0, ItemStack("Developer:greener", 1))
            scene.selectHotbarSlot(0)
            engine.camera.setFirstPersonView(
                20.5,
                WorldGenRegistry.FLAT_GRASS_Y + 2.5,
                20.5,
                playerPitch = -89f,
                playerYaw = 0f
            )
            scene.updateCameraRaycast(engine.camera)
            assertEquals(targetPos, scene.currentTargetHit?.blockPos)
            assertTrue(scene.tapTargetedBlock())

            engine.saveAndQuitToWorldSelect()

            val sidecarFile = File(worldDir, "chunks/c_1_0_1.data")
            assertTrue("chunks/c_1_0_1.data must exist after saving painted block", sidecarFile.exists())
            val sidecarText = sidecarFile.readText()
            assertTrue("Sidecar must contain devpaint key", sidecarText.contains("devpaint"))
            assertTrue("Sidecar must contain boolean true value", sidecarText.contains("true"))

            // Reload world and confirm block is still stone and still painted green
            val savedData = WorldSaveManager.listSavedWorlds(context).first { it.worldName == "GreenerSaveWorld" }
            engine.loadSavedWorld(savedData)
            val reloadedScene = engine.worldScene
            assertEquals(BlockType.STONE, reloadedScene.world.getBlock(targetPos))
            val reloadedChunk = reloadedScene.world.getChunk(1, 0, 1)!!
            val idx = reloadedChunk.getBlockIndex(4, WorldGenRegistry.FLAT_GRASS_Y, 4)
            assertEquals(
                "Reloaded painted block must preserve devpaint=true",
                TypedValue.BooleanValue(true),
                reloadedChunk.blockDataMap[idx]?.get("devpaint")
            )

            // 3. Break the painted block and save -> map clears and c_1_0_1.data is deleted
            engine.camera.setFirstPersonView(
                20.5,
                WorldGenRegistry.FLAT_GRASS_Y + 2.5,
                20.5,
                playerPitch = -89f,
                playerYaw = 0f
            )
            reloadedScene.updateCameraRaycast(engine.camera)
            assertEquals(targetPos, reloadedScene.currentTargetHit?.blockPos)
            assertTrue(reloadedScene.breakTargetedBlock())
            assertFalse("Breaking painted block must remove it from blockDataMap", reloadedChunk.blockDataMap.containsKey(idx))
            assertTrue("blockDataMap should now be empty", reloadedChunk.blockDataMap.isEmpty())

            engine.saveAndQuitToWorldSelect()
            assertFalse("Empty blockDataMap must delete chunks/c_1_0_1.data", sidecarFile.exists())
        } finally {
            engine.worldScene.destroySession()
        }
    }

    @Test
    fun `test ChunkMesh applies 70 percent green vertex tint and shader multiplies texColor by vColor`() {
        val world = World(
            chunksX = 1,
            chunksY = 1,
            chunksZ = 1
        )
        world.generateTerrain()
        val chunk = world.getChunk(0, 0, 0)!!
        chunk.fill(BlockType.AIR)
        chunk.markCompletelyLit()

        // Place two adjacent stone blocks: (4, 4, 4) painted green, (5, 4, 4) unpainted white
        chunk.setBlock(4, 4, 4, BlockType.STONE)
        chunk.setBlock(5, 4, 4, BlockType.STONE)
        chunk.setBlockData(4, 4, 4, "devpaint", TypedValue.BooleanValue(true))

        val pipeline = TexturePipeline(context)
        pipeline.createAtlasBitmap().recycle()

        val mesh = ChunkMesh(chunk)
        mesh.generateMeshData(world, pipeline)

        val colors = mesh.getGeneratedVertexColors(transparentPass = false)
        assertTrue("Expected non-empty opaque vertices", colors.isNotEmpty())

        val hasGreenTint = colors.any { (r, g, b) ->
            kotlin.math.abs(r - 0.3f) < 0.01f &&
                kotlin.math.abs(g - 1.0f) < 0.01f &&
                kotlin.math.abs(b - 0.3f) < 0.01f
        }
        val hasWhiteTint = colors.any { (r, g, b) ->
            kotlin.math.abs(r - 1.0f) < 0.01f &&
                kotlin.math.abs(g - 1.0f) < 0.01f &&
                kotlin.math.abs(b - 1.0f) < 0.01f
        }
        assertTrue("Painted block vertices must emit 70% green tint (0.3, 1.0, 0.3)", hasGreenTint)
        assertTrue("Unpainted adjacent block vertices must stay white (1.0, 1.0, 1.0)", hasWhiteTint)

        val renderer = WorldRenderer(world)
        assertTrue(renderer.lastVertexShaderSource.contains("out vec3 vColor"))
        assertTrue(renderer.lastFragmentShaderSource.contains("in vec3 vColor"))
        assertTrue(renderer.lastFragmentShaderSource.contains("texColor.rgb * vColor"))

        world.destroy()
    }

    // =========================================================================
    // STAGE 6: CBOR Registry Caching & /cbopreload Command
    // =========================================================================

    @Test
    fun `test first boot generates data cbor and second boot skips JSON parsing`() {
        val contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        val cacheFile = File(contentDir, "data.cbor")
        val tmpFile = File(contentDir, "data.cbor.tmp")
        assertFalse(cacheFile.exists())

        // 1. First boot -> parses JSON and writes atomic data.cbor
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        assertFalse("First boot without cache must parse JSON", RegistryBootstrap.lastBootUsedCborCache)
        val firstParsedCount = RegistryBootstrap.lastJsonFilesParsedCount
        assertTrue("First boot must parse JSON definitions", firstParsedCount > 0)
        assertTrue("First boot must generate data.cbor", cacheFile.exists() && cacheFile.length() > 0L)
        assertFalse("Atomic write must not leave data.cbor.tmp behind", tmpFile.exists())

        // Verify data.cbor is valid binary RFC 8949 CBOR
        val decodedItems = CborDecoder(cacheFile.inputStream().buffered()).decode()
        assertEquals(1, decodedItems.size)
        val snapshot = CborRegistryCache.readCache(cacheFile)
        assertNotNull(snapshot)
        assertEquals(RegistryBootstrap.lastSourceHash, snapshot!!.sourceHash)

        val expectedBlocks = BlockRegistry.size
        val expectedItems = ItemRegistry.size
        val expectedRecipes = RecipeRegistry.size
        val expectedCommands = CommandRegistry.size
        val expectedPasses = WorldGenRegistry.size

        // 2. Second boot -> hits data.cbor with ZERO JSON parsing
        val hitsBefore = RegistryBootstrap.cborCacheHits
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        assertTrue("Second boot must load directly from data.cbor", RegistryBootstrap.lastBootUsedCborCache)
        assertEquals("Second boot must parse 0 JSON files", 0, RegistryBootstrap.lastJsonFilesParsedCount)
        assertEquals(hitsBefore + 1, RegistryBootstrap.cborCacheHits)

        assertEquals(expectedBlocks, BlockRegistry.size)
        assertEquals(expectedItems, ItemRegistry.size)
        assertEquals(expectedRecipes, RecipeRegistry.size)
        assertEquals(expectedCommands, CommandRegistry.size)
        assertEquals(expectedPasses, WorldGenRegistry.size)
        assertEquals(BlockType.STONE, BlockRegistry.get("tessellate:stone"))
        assertEquals(ItemRegistry.GREENER, ItemRegistry.get("Developer:greener"))
        assertNotNull(CommandRegistry.get("cbopreload"))
    }

    @Test
    fun `test editing a JSON file invalidates CBOR cache and forces rebuild`() {
        val contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        val blocksDir = File(contentDir, "blocks").apply { mkdirs() }
        val itemsDir = File(contentDir, "items").apply { mkdirs() }
        val recipesDir = File(contentDir, "recipes").apply { mkdirs() }
        val blockFile = File(blocksDir, "cbor_cube.json")
        val itemFile = File(itemsDir, "cbor_cube.json")
        val recipeFile = File(recipesDir, "cbor_cube_recipe.json")

        blockFile.writeText(
            """
            {
              "id": "cbormod:cbor_cube",
              "hardness": 1.5,
              "is_solid": true,
              "textures": { "all": "block_stone" }
            }
            """.trimIndent()
        )
        itemFile.writeText(
            """
            {
              "id": "cbormod:cbor_cube",
              "display_name": "CBOR Cube",
              "max_stack_size": 64,
              "item_type": "BLOCK",
              "block_id": "cbormod:cbor_cube"
            }
            """.trimIndent()
        )
        recipeFile.writeText(
            """
            {
              "id": "cbormod:cbor_cube_craft",
              "ingredients": [ { "item": "tessellate:dirt", "count": 1 } ],
              "result": { "item": "cbormod:cbor_cube", "count": 2 }
            }
            """.trimIndent()
        )

        // Boot 1 builds cache with mod JSONs; Boot 2 hits CBOR cache cleanly
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        assertFalse(RegistryBootstrap.lastBootUsedCborCache)
        val firstHash = RegistryBootstrap.lastSourceHash

        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        assertTrue(RegistryBootstrap.lastBootUsedCborCache)
        assertEquals(1.5f, BlockRegistry.get("cbormod:cbor_cube")!!.hardness, 0.001f)
        assertEquals(2, RecipeRegistry.get("cbormod:cbor_cube_craft")!!.resultCount)

        // Edit block JSON keeping the EXACT same byte length (1.5 -> 8.2)
        blockFile.writeText(
            """
            {
              "id": "cbormod:cbor_cube",
              "hardness": 8.2,
              "is_solid": true,
              "textures": { "all": "block_stone" }
            }
            """.trimIndent()
        )

        // Boot 3 detects hash change, invalidates CBOR cache, and rebuilds from JSON
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = false)
        assertFalse("Modified JSON must invalidate CBOR cache", RegistryBootstrap.lastBootUsedCborCache)
        assertTrue(RegistryBootstrap.lastJsonFilesParsedCount > 0)
        assertTrue(firstHash != RegistryBootstrap.lastSourceHash)
        assertEquals(8.2f, BlockRegistry.get("cbormod:cbor_cube")!!.hardness, 0.001f)

        // Boot 4 hits newly updated CBOR cache with updated hardness & recipe preserved
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        assertTrue(RegistryBootstrap.lastBootUsedCborCache)
        assertEquals(0, RegistryBootstrap.lastJsonFilesParsedCount)
        assertEquals(8.2f, BlockRegistry.get("cbormod:cbor_cube")!!.hardness, 0.001f)
        assertEquals(2, RecipeRegistry.get("cbormod:cbor_cube_craft")!!.resultCount)
    }

    @Test
    fun `test corrupted data cbor falls back to JSON parsing and repairs cache atomically`() {
        val contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        val cacheFile = File(contentDir, "data.cbor")

        // Generate valid cache first
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        assertTrue(cacheFile.exists())

        // Corrupt data.cbor with invalid CBOR / garbage bytes
        cacheFile.writeBytes(byteArrayOf(0xFF.toByte(), 0x1A, 0x00, 0x54, 0x45, 0x53, 0x53))

        // Reboot must NOT crash: must fall back to JSON and overwrite with valid CBOR atomically
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        assertFalse("Corrupted CBOR must fall back to JSON parsing", RegistryBootstrap.lastBootUsedCborCache)
        assertTrue(RegistryBootstrap.lastJsonFilesParsedCount > 0)
        assertEquals(BlockType.STONE, BlockRegistry.get("tessellate:stone"))
        assertFalse(File(contentDir, "data.cbor.tmp").exists())
        assertNotNull("Repaired data.cbor must decode cleanly", CborRegistryCache.readCache(cacheFile))

        // Subsequent boot now hits the repaired CBOR cache cleanly
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        assertTrue(RegistryBootstrap.lastBootUsedCborCache)
        assertEquals(0, RegistryBootstrap.lastJsonFilesParsedCount)
    }

    @Test
    fun `test cbopreload command clears cache, reparses JSON, rebuilds CBOR, and reports to chat`() {
        val contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        val cacheFile = File(contentDir, "data.cbor")

        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession("CborCommandWorld", GameMode.CREATIVE, WorldType.FLAT, 999L)
            )
            assertTrue(cacheFile.exists())

            // Run /cbopreload -> forces cache deletion, JSON parse, and new CBOR generation
            val result = CommandConsole.execute("/cbopreload", engine.worldScene)
            assertTrue("/cbopreload must succeed: ${result.message}", result.success)
            assertTrue("Chat response must mention CBOR rebuild", result.message.contains("CBOR", ignoreCase = true))
            assertFalse("Reload must bypass cache read and parse JSON", RegistryBootstrap.lastBootUsedCborCache)
            assertTrue(RegistryBootstrap.lastJsonFilesParsedCount > 0)
            assertTrue("New data.cbor must exist after /cbopreload", cacheFile.exists())
            assertFalse(File(contentDir, "data.cbor.tmp").exists())

            // Next boot hits newly generated CBOR cache
            AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
            assertTrue(RegistryBootstrap.lastBootUsedCborCache)

            // Inject invalid JSON and run /cbopreload -> must report failure cleanly without crashing
            val badBlockDir = File(contentDir, "blocks").apply { mkdirs() }
            val badFile = File(badBlockDir, "broken.json").apply {
                writeText("{ this is not valid json !!!")
            }
            val failResult = CommandConsole.execute("/cbopreload", engine.worldScene)
            assertFalse("Invalid JSON during /cbopreload must return failure to chat", failResult.success)
            assertTrue(
                "Failure message must explain reload failure",
                failResult.message.contains("fail", ignoreCase = true)
            )
            badFile.delete()
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
}
