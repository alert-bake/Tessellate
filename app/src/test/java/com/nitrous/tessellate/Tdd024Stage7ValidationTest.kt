package com.nitrous.tessellate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.command.CommandConsole
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.registry.CborRegistryCache
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.save.ChunkEntityFileData
import com.nitrous.tessellate.save.ChunkFilenameCodec
import com.nitrous.tessellate.save.OsmiumCodec
import com.nitrous.tessellate.save.OsmiumFormat
import com.nitrous.tessellate.save.OsmiumProtocol
import com.nitrous.tessellate.save.SavedEntityData
import com.nitrous.tessellate.save.WorldSaveData
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockRegistry
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.ChunkMesh
import com.nitrous.tessellate.world.ChunkPos
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.TypedValue
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import com.nitrous.tessellate.worldgen.WorldGenRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * TDD-024 Stage 7: Comprehensive Regression Testing, Edge-Case Validation,
 * Crash Recovery, 64-bit Coordinate Audit, Base36 Migration, Concurrency Stress,
 * and Measured Performance Benchmarks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd024Stage7ValidationTest {

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
    // SECTION 2: Storage Format, Corruption Handling, & LZ4 Size Reduction Rule
    // =========================================================================

    @Test
    fun `sec2 all formats roundtrip byte for byte across uniform mixed checkerboard repeating and random`() {
        val uniform = ByteArray(Chunk.VOLUME) { BlockType.STONE.id }
        val repeating = ByteArray(Chunk.VOLUME) { i -> ((i / 17) % 5).toByte() }
        val checkerboard = ByteArray(Chunk.VOLUME) { i -> if ((i + (i shr 4) + (i shr 8)) and 1 == 0) 1 else 2 }
        val mixed = ByteArray(Chunk.VOLUME) { i ->
            val y = i ushr 8
            when {
                y < 4 -> BlockType.STONE.id
                y < 7 -> BlockType.DIRT.id
                y == 7 -> BlockType.GRASS.id
                else -> BlockType.AIR.id
            }
        }
        val randomBytes = ByteArray(Chunk.VOLUME).also { Random(20261127L).nextBytes(it) }

        val datasets = listOf(
            "uniform" to uniform,
            "repeating" to repeating,
            "checkerboard" to checkerboard,
            "mixed" to mixed,
            "random" to randomBytes
        )

        for ((label, raw) in datasets) {
            // Legacy raw 4096B
            val legacyDecoded = OsmiumCodec.decodeWithFormat(raw)
            assertNotNull("Legacy decode failed for $label", legacyDecoded)
            assertEquals(OsmiumFormat.LEGACY_RAW, legacyDecoded!!.format)
            assertArrayEquals("Legacy mismatch for $label", raw, legacyDecoded.blocks)

            // V1 raw with [0x54, 0x01] header
            val v1 = ByteArray(2 + Chunk.VOLUME)
            v1[0] = OsmiumCodec.MAGIC_BYTE_0
            v1[1] = OsmiumCodec.VERSION_V1
            System.arraycopy(raw, 0, v1, 2, Chunk.VOLUME)
            val v1Decoded = OsmiumCodec.decodeWithFormat(v1)
            assertNotNull("V1 decode failed for $label", v1Decoded)
            assertEquals(OsmiumFormat.V1_RAW, v1Decoded!!.format)
            assertArrayEquals("V1 mismatch for $label", raw, v1Decoded.blocks)

            // Osmium auto-encode (V2 / V3 / V1)
            val encoded = OsmiumCodec.encodeWithFormat(raw)
            val decoded = OsmiumCodec.decodeWithFormat(encoded.bytes)
            assertNotNull("Osmium decode failed for $label", decoded)
            assertEquals(encoded.format, decoded!!.format)
            assertArrayEquals("Osmium roundtrip mismatch for $label", raw, decoded.blocks)
        }
    }

    @Test
    fun `sec2 rejects malformed headers truncated payloads invalid counts overruns and unsupported versions`() {
        // Build a valid V2 RLE stream without header (32 bytes = 16 uniform slices of stone)
        val validV2 = OsmiumCodec.encode(ByteArray(Chunk.VOLUME) { BlockType.STONE.id })
        assertEquals(34, validV2.size)

        val validV3 = OsmiumCodec.encode(ByteArray(Chunk.VOLUME) { i -> ((i / 4) % 4 + 1).toByte() })
        assertEquals(OsmiumCodec.VERSION_V3, validV3[1])

        // 1. Empty / 1-byte / wrong magic / wrong lengths
        assertNull(OsmiumCodec.decode(byteArrayOf()))
        assertNull(OsmiumCodec.decode(byteArrayOf(0x54)))
        assertNull(OsmiumCodec.decode(byteArrayOf(0x53, 0x02, 0x01, 0x01)))
        assertNull(OsmiumCodec.decode(ByteArray(4095)))
        assertNull(OsmiumCodec.decode(ByteArray(4097)))
        // V1 wrong length (4097 total, header + 4095)
        assertNull(OsmiumCodec.decode(ByteArray(4097).apply {
            this[0] = OsmiumCodec.MAGIC_BYTE_0
            this[1] = OsmiumCodec.VERSION_V1
        }))

        // 2. Unsupported version 0x05
        assertNull(OsmiumCodec.decode(byteArrayOf(0x54, 0x05, 0x01, 0x01)))
        assertNull(OsmiumCodec.detectFormat(byteArrayOf(0x54, 0x05, 0x01, 0x01)))

        // 3. Truncated V2 (missing final byte) & trailing garbage byte on V2
        assertNull(OsmiumCodec.decode(validV2.copyOf(validV2.size - 1)))
        assertNull(OsmiumCodec.decode(validV2 + byteArrayOf(0x00)))

        // 4. V2 invalid slice mode 0x02
        val badMode = validV2.copyOf()
        badMode[2] = 0x02
        assertNull(OsmiumCodec.decode(badMode))

        // 5. V2 zero run count [0x00, blockId, 0x00]
        val zeroCount = byteArrayOf(0x54, 0x02, 0x00, 0x01, 0x00)
        assertNull(OsmiumCodec.decode(zeroCount))

        // 6. V2 slice overrun ([0x00, blockId, 200, blockId, 100] = 300 > 256)
        val sliceOverrun = byteArrayOf(
            0x54, 0x02,
            0x00, 0x01, 200.toByte(), 0x02, 100.toByte()
        )
        assertNull(OsmiumCodec.decode(sliceOverrun))

        // 7. V3 truncated compressed payload & corrupted compressed payload
        assertNull(OsmiumCodec.decode(validV3.copyOf(4)))
        assertNull(OsmiumCodec.decode(validV3.copyOf(validV3.size - 1)))
        val corruptedV3 = validV3.copyOf()
        corruptedV3[corruptedV3.lastIndex] = (corruptedV3[corruptedV3.lastIndex].toInt() xor 0xFF).toByte()
        // Flip declared RLE length to bogus oversized value (9000 > MAX_RLE_PAYLOAD_BYTES)
        val oversizedLenV3 = validV3.copyOf()
        oversizedLenV3[2] = (9000 ushr 8).toByte()
        oversizedLenV3[3] = (9000 and 0xFF).toByte()
        assertNull(OsmiumCodec.decode(oversizedLenV3))
        // Corrupt header compressed length inside V3 payload (flip byte 4/5 LZ4 token)
        val corruptTokenV3 = validV3.copyOf()
        corruptTokenV3[4] = 0xFF.toByte()
        corruptTokenV3[5] = 0xFF.toByte()
        assertNull(OsmiumCodec.decode(corruptTokenV3))

        // 8. Truncated V4 pointer (33 bytes instead of 34)
        assertNull(OsmiumCodec.detectFormat(ByteArray(33).apply {
            this[0] = OsmiumCodec.MAGIC_BYTE_0
            this[1] = OsmiumCodec.VERSION_V4
        }))
        assertNull(OsmiumCodec.decodePointerHash(ByteArray(33).apply {
            this[0] = OsmiumCodec.MAGIC_BYTE_0
            this[1] = OsmiumCodec.VERSION_V4
        }))
    }

    @Test
    fun `sec2 LZ4 is only selected when it strictly reduces payload size vs V2`() {
        // Construct a chunk whose RLE payload is >= 64 bytes with zero 4-byte repeats anywhere,
        // so LZ4 adds framing overhead and does NOT shrink below V2 (2 + rlePayload.size).
        val blocks = ByteArray(Chunk.VOLUME)
        for (i in 0 until 100) {
            blocks[i] = (0x10 + i).toByte()
        }
        for (i in 100 until 256) {
            blocks[i] = 0x74.toByte()
        }
        for (y in 1 until 16) {
            val sliceId = (0x75 + y).toByte()
            val start = y shl 8
            for (i in 0 until 256) {
                blocks[start + i] = sliceId
            }
        }

        val encoded = OsmiumCodec.encodeWithFormat(blocks)
        // Must pick V2_OSMIUM (because 2 + RLE <= 4 + LZ4(RLE)), NOT blindly V3_LZ4!
        assertEquals(
            "Incompressible RLE >= 64 bytes must NOT pick V3_LZ4 when LZ4 does not strictly reduce size",
            OsmiumFormat.V2_OSMIUM,
            encoded.format
        )
        assertArrayEquals(blocks, OsmiumCodec.decode(encoded.bytes))
    }

    // =========================================================================
    // SECTION 3: Save Manager, Epochs, Coalescing, Error Preservation & Flush
    // =========================================================================

    @Test
    fun `sec3 failed chunk write preserves needsSave keeps previous valid disk file intact and retries cleanly`() {
        val worldDir = File(WorldSaveManager.getSavesRootDir(context), "FailedWriteWorld")
        val manager = WorldSaveManager(worldDir)
        val pos = ChunkPos(0, 0, 0)

        // 1. Write initial valid chunk (STONE)
        val chunk = Chunk(0, 0, 0).apply { setBlock(1, 1, 1, BlockType.STONE) }
        runBlocking { manager.saveChunkAndEntitiesAsync(chunk, emptyList()).join() }
        assertFalse(chunk.needsSave)
        val chunkFile = manager.chunkFileFor(pos)
        assertTrue(chunkFile.exists())
        val initialBytes = manager.loadChunkBytes(pos)!!
        assertEquals(BlockType.STONE.id, initialBytes[chunk.getBlockIndex(1, 1, 1)])

        // 2. Edit chunk to COBBLESTONE, then inject write failure before atomic rename of target .bin
        chunk.setBlock(1, 1, 1, BlockType.COBBLESTONE)
        assertTrue(chunk.needsSave)
        manager.testAtomicWriteStageHook = { targetFile, _ ->
            if (targetFile == chunkFile) {
                throw IOException("Simulated disk full before rename")
            }
        }
        runBlocking { manager.saveChunkAndEntitiesAsync(chunk, emptyList()).join() }

        // After failed write: needsSave MUST still be true, no .tmp left behind, previous disk file intact!
        assertTrue("needsSave must stay true when write fails", chunk.needsSave)
        assertFalse(File(chunkFile.parentFile, "${chunkFile.name}.tmp").exists())
        val onDiskAfterFailure = manager.loadChunkBytes(pos)!!
        assertEquals(
            "Previous valid chunk on disk must survive failed atomic write",
            BlockType.STONE.id,
            onDiskAfterFailure[chunk.getBlockIndex(1, 1, 1)]
        )

        // 3. Clear fault hook and retry -> succeeds and clears needsSave
        manager.testAtomicWriteStageHook = null
        runBlocking { manager.saveChunkAndEntitiesAsync(chunk, emptyList()).join() }
        assertFalse(chunk.needsSave)
        val onDiskAfterRetry = manager.loadChunkBytes(pos)!!
        assertEquals(BlockType.COBBLESTONE.id, onDiskAfterRetry[chunk.getBlockIndex(1, 1, 1)])
    }

    // =========================================================================
    // SECTION 4: Sparse Block Data & CBOR Cache Failure Safety
    // =========================================================================

    @Test
    fun `sec4 failed cbopreload preserves active registries and existing data cbor`() {
        val contentDir = File(context.filesDir, "content").apply { deleteRecursively() }
        val cacheFile = File(contentDir, "data.cbor")

        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession("CborFailureSafeWorld", GameMode.CREATIVE, WorldType.FLAT, 5150L)
            )
            assertTrue("Initial data.cbor must exist", cacheFile.exists())
            val originalCacheBytes = cacheFile.readBytes()
            val originalBlockCount = BlockRegistry.size
            val originalItemCount = ItemRegistry.size

            // Case A: Inject invalid syntax JSON in mod folder and invoke /cbopreload
            val modBlocksDir = File(contentDir, "blocks").apply { mkdirs() }
            val badJson = File(modBlocksDir, "syntax_error.json").apply {
                writeText("{ broken json")
            }
            val failSyntax = CommandConsole.execute("/cbopreload", engine.worldScene)
            assertFalse(failSyntax.success)
            assertEquals("Active BlockRegistry must stay intact after failed reload", originalBlockCount, BlockRegistry.size)
            assertEquals("Active ItemRegistry must stay intact after failed reload", originalItemCount, ItemRegistry.size)
            assertTrue("Existing valid data.cbor must NOT be deleted on failed reload", cacheFile.exists())
            assertArrayEquals(originalCacheBytes, cacheFile.readBytes())
            badJson.delete()

            // Case B: Inject valid JSON syntax with broken item->block link and invoke /cbopreload
            val modItemsDir = File(contentDir, "items").apply { mkdirs() }
            val brokenLink = File(modItemsDir, "broken_link.json").apply {
                writeText(
                    """
                    {
                      "id": "brokenmod:ghost_item",
                      "display_name": "Ghost",
                      "item_type": "BLOCK",
                      "block_id": "brokenmod:nonexistent_block"
                    }
                    """.trimIndent()
                )
            }
            val failLink = CommandConsole.execute("/cbopreload", engine.worldScene)
            assertFalse("Broken block_id link must fail validation", failLink.success)
            assertEquals(originalBlockCount, BlockRegistry.size)
            assertEquals(originalItemCount, ItemRegistry.size)
            assertEquals(BlockType.STONE, BlockRegistry.get("tessellate:stone"))
            assertTrue(cacheFile.exists())
            assertArrayEquals(originalCacheBytes, cacheFile.readBytes())
            brokenLink.delete()
        } finally {
            engine.worldScene.destroySession()
        }
    }

    // =========================================================================
    // SECTION 5: Crash Recovery & Atomic Writes (.tmp Ignore & Non-Corruption)
    // =========================================================================

    @Test
    fun `sec5 leftover tmp files never interfere with chunk data entities level or cleanup`() {
        val worldDir = File(WorldSaveManager.getSavesRootDir(context), "LeftoverTmpWorld")
        val manager = WorldSaveManager(worldDir)

        val pos = ChunkPos(0, 0, 0)
        val chunk = Chunk(0, 0, 0).apply {
            setBlock(2, 2, 2, BlockType.STONE)
            setBlockData(2, 2, 2, "devpaint", TypedValue.BooleanValue(true))
        }
        val entityData = SavedEntityData(
            typeId = "tessellate:item_drop",
            x = 2.5,
            y = 4.0,
            z = 2.5,
            extraData = mapOf("itemId" to "tessellate:stone", "count" to 3)
        )
        assertTrue(
            manager.saveLevelDataSync(
                WorldSaveData(
                    worldName = "LeftoverTmpWorld",
                    seed = 42L,
                    gamemode = GameMode.CREATIVE,
                    worldType = WorldType.FLAT,
                    playerX = 0.0,
                    playerY = 10.0,
                    playerZ = 0.0,
                    playerYaw = 0f,
                    playerPitch = 0f,
                    folderName = worldDir.name
                )
            )
        )
        runBlocking { manager.saveChunkAndEntitiesAsync(chunk, emptyList()).join() }

        // Write valid entities JSON via manager helper, plus stray .tmp files everywhere simulating crash
        manager.entitiesDir.mkdirs()
        manager.entityFileFor(pos).writeText(
            WorldSaveManager.serializeChunkEntities(ChunkEntityFileData(0, 0, 0, listOf(entityData)))
        )

        val hashHex = OsmiumCodec.sha256Hex(OsmiumCodec.sha256Bytes(chunk.blocks))
        File(manager.chunksDir, "c_0_0_0.bin.tmp").writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        File(manager.mastersDir, "$hashHex.bin.tmp").writeBytes(byteArrayOf(0xFF.toByte()))
        File(manager.chunksDir, "c_0_0_0.data.tmp").writeText("{garbage")
        File(manager.entitiesDir, "c_0_0_0.json.tmp").writeText("{garbage")
        File(worldDir, "level.json.tmp").writeText("{garbage")

        // Cleanup, migration, and loads MUST ignore all .tmp files and read real committed files intact
        val report = manager.migrateLegacyChunkFilenames()
        assertEquals(0, report.collisionsSkipped)
        runBlocking { manager.cleanupOrphanedMastersAsync().join() }

        assertTrue("Committed master must remain intact", manager.masterFileFor(hashHex).exists())
        val loadedBlocks = manager.loadChunkBytes(pos)!!
        assertEquals(BlockType.STONE.id, loadedBlocks[chunk.getBlockIndex(2, 2, 2)])
        assertEquals(TypedValue.BooleanValue(true), manager.loadBlockData(pos)[chunk.getBlockIndex(2, 2, 2)]?.get("devpaint"))
        assertEquals(1, manager.loadEntities(pos).size)
        assertEquals("LeftoverTmpWorld", manager.loadLevelData()?.worldName)
    }

    // =========================================================================
    // SECTION 6: 64-Bit Coordinate System Audit & Precision Bounds
    // =========================================================================

    @Test
    fun `sec6 64bit coordinates work past Int MAX_VALUE and to Long MIN MAX without overflow`() {
        // Test BlockPos & ChunkPos far past 32-bit Int bounds
        val farBlockPos = BlockPos(3_000_000_000L, 4L, -3_000_000_000L)
        assertEquals(3_000_000_000L, farBlockPos.x)
        assertEquals(-3_000_000_000L, farBlockPos.z)

        val farChunkX = farBlockPos.x shr 4 // 187_500_000L (exceeds 28-bit fastChunks limit +-134M chunks)
        val farChunkZ = farBlockPos.z shr 4 // -187_500_000L

        val worldDir = File(WorldSaveManager.getSavesRootDir(context), "Coord64World")
        val manager = WorldSaveManager(worldDir)
        val world = World(
            session = WorldSession("Coord64World", GameMode.CREATIVE, WorldType.FLAT, 123L),
            renderDistance = 1,
            saveStorage = manager
        )
        try {
            // 1. World block placement/lookup at 3_000_000_000L (chunk 187_500_000L -> HashMap fallback)
            assertTrue(world.setBlock(farBlockPos, BlockType.COBBLESTONE))
            assertEquals(BlockType.COBBLESTONE, world.getBlock(farBlockPos))
            val farChunk = world.getChunk(farChunkX, 0L, farChunkZ)
            assertNotNull("Chunk at 187.5M must load via HashMap fallback without packing collision", farChunk)
            assertEquals(BlockType.COBBLESTONE, farChunk!!.getBlock(0, 4, 0))

            // Ensure saving/loading that chunk persists with Base36 filenames cleanly
            runBlocking { manager.saveChunkAndEntitiesAsync(farChunk, emptyList()).join() }
            val loadedFarBytes = manager.loadChunkBytes(ChunkPos(farChunkX, 0L, farChunkZ))
            assertNotNull(loadedFarBytes)
            assertEquals(BlockType.COBBLESTONE.id, loadedFarBytes!![farChunk.getBlockIndex(0, 4, 0)])

            // 2. Extreme Long.MIN_VALUE / Long.MAX_VALUE BlockPos & ChunkPos + ChunkMesh.worldBounds overflow check
            // Within Double precision range (100 trillion blocks >> Int.MAX_VALUE), maxX - minX == 16.0 exactly
            val hundredTrillionChunk = Chunk(100_000_000_000_000L / Chunk.SIZE, 0L, -100_000_000_000_000L / Chunk.SIZE)
            val trillionMesh = ChunkMesh(hundredTrillionChunk)
            assertEquals(16.0, trillionMesh.worldBounds.maxX - trillionMesh.worldBounds.minX, 0.0)
            // Near Long.MAX_VALUE / Chunk.SIZE (past 2^53 Double precision), signs must NEVER wrap negative via Long overflow
            val maxChunk = Chunk(Long.MAX_VALUE / Chunk.SIZE, 0L, Long.MIN_VALUE / Chunk.SIZE)
            val mesh = ChunkMesh(maxChunk)
            assertTrue("worldBounds.minX must remain positive near Long.MAX_VALUE", mesh.worldBounds.minX > 0.0)
            assertTrue("worldBounds.maxX >= minX without Long sign wrap", mesh.worldBounds.maxX >= mesh.worldBounds.minX)
            assertTrue("worldBounds.minZ must remain negative near Long.MIN_VALUE", mesh.worldBounds.minZ < 0.0)

            // 3. ItemDropEntity physics & Camera3D camera-relative Float view matrix at 3_000_000_000.0
            val drop = ItemDropEntity(
                position = Vec3(3_000_000_000.5, 5.5, -3_000_000_000.5),
                itemId = "tessellate:cobblestone",
                count = 1
            )
            drop.tick(world, 0.05f)
            assertTrue(drop.position.y.isFinite())

            val camera = Camera3D()
            camera.setFirstPersonView(3_000_000_000.5, 6.62, -3_000_000_000.5, -15f, 45f)
            // Camera-relative viewMatrix keeps translation near zero (~0f) regardless of 3B double offset!
            assertTrue(abs(camera.viewMatrix[12]) < 1e-4f)
            assertTrue(abs(camera.viewMatrix[13]) < 1e-4f)
            assertTrue(abs(camera.viewMatrix[14]) < 1e-4f)
        } finally {
            world.destroy()
        }
    }

    @Test
    fun `sec6 fpptest pushes past Int MAX_VALUE cleanly and Double ULP boundary is explicit`() {
        val engine = TessellateEngine(context)
        engine.onSurfaceChanged(1920, 1080)
        try {
            engine.startWorldSession(
                WorldSession("FpTestExtremeWorld", GameMode.CREATIVE, WorldType.FLAT, 777L)
            )
            engine.worldScene.player.posX = 100_000.0
            // 100_000 * 32_676 = 3_267_600_000.0 (> Int.MAX_VALUE = 2_147_483_647)
            val res = engine.executeCommand("/fpptest X")
            assertTrue("/fpptest X past Int.MAX_VALUE must succeed: ${res.message}", res.success)
            assertTrue(engine.worldScene.player.posX > Int.MAX_VALUE.toDouble())
            assertTrue(engine.worldScene.player.posX.isFinite())
        } finally {
            engine.worldScene.destroySession()
        }

        // Document & verify IEEE-754 Double 53-bit significand precision boundary:
        // BlockPos/ChunkPos use Long (exact up to 2^63-1), while entity/player Double coordinates
        // lose integer-unit precision at 2^53 (9_007_199_254_740_992.0).
        val twoPow53 = 9_007_199_254_740_992L
        assertEquals(twoPow53.toDouble(), (twoPow53 + 1L).toDouble(), 0.0)
        assertNotEquals(BlockPos(twoPow53, 0L, 0L), BlockPos(twoPow53 + 1L, 0L, 0L))
    }

    // =========================================================================
    // SECTION 7: Base36 Filename Codec & Filesystem Portability
    // =========================================================================

    @Test
    fun `sec7 base36 codec matches spec handles Long MIN MAX rejects malformed and non canonical`() {
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", ChunkFilenameCodec.ALPHABET)
        assertEquals("c_LFLS_3_2.bin", ChunkFilenameCodec.encodeChunkFilename(ChunkPos(1_000_000L, 3L, 2L)))
        assertEquals("c_LFLS_3_2.data", ChunkFilenameCodec.encodeBlockDataFilename(ChunkPos(1_000_000L, 3L, 2L)))
        assertEquals("c_LFLS_3_2.json", ChunkFilenameCodec.encodeEntityFilename(ChunkPos(1_000_000L, 3L, 2L)))

        val extremes = listOf(
            ChunkPos(0L, 0L, 0L),
            ChunkPos(-1L, -36L, -1_000_000L),
            ChunkPos(Long.MAX_VALUE, 0L, Long.MIN_VALUE),
            ChunkPos(Long.MIN_VALUE, -1L, Long.MAX_VALUE)
        )
        for (pos in extremes) {
            val bin = ChunkFilenameCodec.encodeChunkFilename(pos)
            assertTrue("Filename length (${bin.length}) must stay well below 255", bin.length <= 50)
            assertEquals(pos, ChunkFilenameCodec.decodeChunkFilename(bin))
            // Case-insensitive lowercase variant (e.g. macOS/Windows FS normalization) decodes cleanly
            assertEquals(pos, ChunkFilenameCodec.decodeChunkFilename(bin.lowercase()))
        }

        // Exact signed 64-bit extremes in Base36
        assertEquals("1Y2P0IJ32E8E7", ChunkFilenameCodec.encodeBase36(Long.MAX_VALUE))
        assertEquals("-1Y2P0IJ32E8E8", ChunkFilenameCodec.encodeBase36(Long.MIN_VALUE))

        // Non-canonical leading zeros, -0, + sign, bad characters, overflow, bad syntax MUST return null
        val invalidComponents = listOf(
            "", "-", "+", "00", "01", "-0", "-05", "+10", "1_2", "1$2",
            "1Y2P0IJ32E8E8",   // Long.MAX_VALUE + 1 overflow
            "-1Y2P0IJ32E8E9",  // Long.MIN_VALUE - 1 underflow
            "ZZZZZZZZZZZZZZ"   // Massive overflow
        )
        for (bad in invalidComponents) {
            assertNull("Component '$bad' should be rejected", ChunkFilenameCodec.decodeBase36(bad))
        }

        val invalidFilenames = listOf(
            "0_0_0.bin", "c_0_0.bin", "c_0_0_0_0.bin", "c_00_0_0.bin", "c_-0_0_0.bin", "c_0_0_0.txt", "c__0_0.bin"
        )
        for (badFile in invalidFilenames) {
            assertNull("Filename '$badFile' should be rejected", ChunkFilenameCodec.decodeFilename(badFile))
        }
    }

    // =========================================================================
    // SECTION 8: Decimal -> Base36 Migration, Cycles, Collisions, Crash Recovery
    // =========================================================================

    @Test
    fun `sec8 migration handles cyclic renames negative coords collisions idempotency and crash recovery`() {
        // 1. Positive, negative, sidecar, entity, V4 master integrity, and CYCLIC chain:
        // Decimal "10" -> Base36 "A" (c_10_0_0 -> c_A_0_0)
        // Decimal "36" -> Base36 "10" (c_36_0_0 -> c_10_0_0)
        // Naive in-place renaming in wrong order would overwrite c_10_0_0!
        val cycleDir = File(WorldSaveManager.getSavesRootDir(context), "MigrationCycleWorld")
        val manager = WorldSaveManager(cycleDir)
        manager.chunksDir.mkdirs()
        manager.mastersDir.mkdirs()
        manager.entitiesDir.mkdirs()

        val blocks10 = ByteArray(Chunk.VOLUME) { BlockType.STONE.id }.apply { this[0] = BlockType.COBBLESTONE.id }
        val blocks36 = ByteArray(Chunk.VOLUME) { BlockType.DIRT.id }.apply { this[0] = BlockType.GRASS.id }
        val blocksNeg = ByteArray(Chunk.VOLUME) { BlockType.SAND.id }

        // Save V4 master + legacy decimal filenames
        fun writeV4AtLegacy(legacyBinName: String, blocks: ByteArray) {
            val hash = OsmiumCodec.sha256Bytes(blocks)
            val hex = OsmiumCodec.sha256Hex(hash)
            manager.masterFileFor(hex).writeBytes(OsmiumCodec.encode(blocks))
            File(manager.chunksDir, legacyBinName).writeBytes(OsmiumCodec.encodePointer(hash))
        }

        writeV4AtLegacy("c_10_0_0.bin", blocks10)
        writeV4AtLegacy("c_36_0_0.bin", blocks36)
        writeV4AtLegacy("c_-100_0_-200.bin", blocksNeg)

        File(manager.chunksDir, "c_10_0_0.data").writeText(
            WorldSaveManager.serializeChunkBlockData(mapOf(0 to mapOf("devpaint" to TypedValue.BooleanValue(true))))
        )
        File(manager.entitiesDir, "c_36_0_0.json").writeText(
            WorldSaveManager.serializeChunkEntities(
                ChunkEntityFileData(
                    36, 0, 0,
                    listOf(
                        SavedEntityData(
                            typeId = "tessellate:item_drop",
                            x = 576.5,
                            y = 4.0,
                            z = 0.5,
                            extraData = mapOf("itemId" to "tessellate:dirt", "count" to 2)
                        )
                    )
                )
            )
        )

        val report1 = manager.migrateLegacyChunkFilenames()
        assertEquals(5, report1.migratedCount)
        assertEquals(0, report1.collisionsSkipped)

        assertTrue(File(manager.chunksDir, "c_A_0_0.bin").exists())
        assertTrue(File(manager.chunksDir, "c_10_0_0.bin").exists())
        assertTrue(File(manager.chunksDir, "c_-2S_0_-5K.bin").exists())
        assertFalse(File(manager.chunksDir, "c_36_0_0.bin").exists())
        assertTrue(File(manager.chunksDir, "c_A_0_0.data").exists())
        assertTrue(File(manager.entitiesDir, "c_10_0_0.json").exists())

        // Verify payloads at migrated coordinates suffered zero corruption from cyclic rename
        assertArrayEquals(blocks10, manager.loadChunkBytes(ChunkPos(10, 0, 0)))
        assertArrayEquals(blocks36, manager.loadChunkBytes(ChunkPos(36, 0, 0)))
        assertArrayEquals(blocksNeg, manager.loadChunkBytes(ChunkPos(-100, 0, -200)))
        assertEquals(TypedValue.BooleanValue(true), manager.loadBlockData(ChunkPos(10, 0, 0))[0]?.get("devpaint"))
        assertEquals(1, manager.loadEntities(ChunkPos(36, 0, 0)).size)

        // Idempotency: second call does zero work and marks alreadyMigrated = true
        val report2 = manager.migrateLegacyChunkFilenames()
        assertTrue(report2.alreadyMigrated)
        assertEquals(0, report2.migratedCount)

        // 2. Collision protection: existing Base36 c_A_0_0.bin + legacy c_10_0_0.bin (with neither vacating c_A_0_0)
        val collisionDir = File(WorldSaveManager.getSavesRootDir(context), "MigrationCollisionWorld")
        val colManager = WorldSaveManager(collisionDir)
        colManager.chunksDir.mkdirs()
        val existingBase36Payload = OsmiumCodec.encode(ByteArray(Chunk.VOLUME) { BlockType.GRASS.id })
        val legacyPayload = OsmiumCodec.encode(ByteArray(Chunk.VOLUME) { BlockType.STONE.id })
        File(colManager.chunksDir, "c_A_0_0.bin").writeBytes(existingBase36Payload)
        File(colManager.chunksDir, "c_10_0_0.bin").writeBytes(legacyPayload)

        val colReport = colManager.migrateLegacyChunkFilenames()
        assertEquals(1, colReport.collisionsSkipped)
        assertEquals(0, colReport.migratedCount)
        assertArrayEquals(existingBase36Payload, File(colManager.chunksDir, "c_A_0_0.bin").readBytes())
        assertArrayEquals(legacyPayload, File(colManager.chunksDir, "c_10_0_0.bin").readBytes())

        // 3. Crash recovery across EVERY phase hook
        val stagesToCrash = listOf(
            "PHASE1_AFTER_STAGING",
            "PHASE2_AFTER_JOURNAL",
            "PHASE2_AFTER_ORIG",
            "PHASE2_MID_TARGET_RENAME"
        )
        for (stage in stagesToCrash) {
            val crashDir = File(WorldSaveManager.getSavesRootDir(context), "CrashRecovery_$stage")
            val crashManager = WorldSaveManager(crashDir)
            crashManager.chunksDir.mkdirs()
            val expected42 = ByteArray(Chunk.VOLUME) { BlockType.COBBLESTONE.id }
            val expected10 = ByteArray(Chunk.VOLUME) { BlockType.DIRT.id }
            val expected36 = ByteArray(Chunk.VOLUME) { BlockType.STONE.id }
            File(crashManager.chunksDir, "c_42_0_0.bin").writeBytes(OsmiumCodec.encode(expected42))
            File(crashManager.chunksDir, "c_10_0_0.bin").writeBytes(OsmiumCodec.encode(expected10))
            File(crashManager.chunksDir, "c_36_0_0.bin").writeBytes(OsmiumCodec.encode(expected36))

            crashManager.testMigrationStageHook = { currentStage ->
                if (currentStage == stage) {
                    throw RuntimeException("Simulated crash at $stage")
                }
            }
            var crashed = false
            try {
                crashManager.migrateLegacyChunkFilenames()
            } catch (_: RuntimeException) {
                crashed = true
            }
            assertTrue("Expected simulated crash at $stage", crashed)

            // Recover on restart (fresh manager instance, no hook)
            val recoveredManager = WorldSaveManager(crashDir)
            val recoveryReport = recoveredManager.migrateLegacyChunkFilenames()
            assertEquals(3, recoveryReport.migratedCount)
            if (stage != "PHASE1_AFTER_STAGING") {
                assertTrue("Phase 2 crash at $stage must recover via journal", recoveryReport.recoveredFromJournal)
            }
            assertArrayEquals(expected42, recoveredManager.loadChunkBytes(ChunkPos(42, 0, 0)))
            assertArrayEquals(expected10, recoveredManager.loadChunkBytes(ChunkPos(10, 0, 0)))
            assertArrayEquals(expected36, recoveredManager.loadChunkBytes(ChunkPos(36, 0, 0)))
        }
    }

    // =========================================================================
    // SECTION 9: Concurrency Stress (Mutation + Save + Unload/Reload + Cleanup)
    // =========================================================================

    @Test
    fun `sec9 concurrent chunk mutation save cleanup and reload completes without deadlock or corruption`() {
        val worldDir = File(WorldSaveManager.getSavesRootDir(context), "ConcurrencyStressWorld")
        val manager = WorldSaveManager(worldDir)
        val chunkCount = 12
        val chunks = (0 until chunkCount).map { idx ->
            Chunk(idx.toLong() + 100L, 0L, -idx.toLong() - 10L).apply {
                setBlock(0, 0, 0, BlockType.STONE)
            }
        }

        val workers = Executors.newFixedThreadPool(4)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(4)

        try {
            // Thread 1 & 2: Mutate chunks & queue saves concurrently
            for (workerIdx in 0 until 2) {
                workers.submit {
                    try {
                        startGate.await(5, TimeUnit.SECONDS)
                        for (round in 0 until 20) {
                            for ((idx, chunk) in chunks.withIndex()) {
                                val lx = (round + workerIdx) and 15
                                chunk.setBlock(lx, 1, idx and 15, if (round % 2 == 0) BlockType.COBBLESTONE else BlockType.DIRT)
                                if (round % 5 == 0) {
                                    chunk.setBlockData(lx, 1, idx and 15, "devpaint", TypedValue.BooleanValue(true))
                                }
                                manager.saveChunkAndEntitiesAsync(chunk, emptyList())
                            }
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        doneGate.countDown()
                    }
                }
            }

            // Thread 3: Concurrent loads & reads
            workers.submit {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (round in 0 until 25) {
                        val c = chunks[round % chunkCount]
                        val pos = ChunkPos(c.chunkX, c.chunkY, c.chunkZ)
                        val loaded = manager.loadChunkBytes(pos)
                        if (loaded != null) {
                            assertEquals(Chunk.VOLUME, loaded.size)
                        }
                        manager.loadBlockData(pos)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneGate.countDown()
                }
            }

            // Thread 4: Periodic orphaned master cleanup while saves are actively running
            workers.submit {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (i in 0 until 6) {
                        manager.cleanupOrphanedMastersAsync()
                        Thread.sleep(5)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneGate.countDown()
                }
            }

            startGate.countDown()
            assertTrue("Concurrency test timed out (possible deadlock)", doneGate.await(30, TimeUnit.SECONDS))
            manager.flushBlocking()
            assertTrue("Unexpected worker exceptions: $errors", errors.isEmpty())

            // Re-save all chunks cleanly once mutations cease and verify exact disk parity
            for (chunk in chunks) {
                runBlocking { manager.saveChunkAndEntitiesAsync(chunk, emptyList()).join() }
            }
            for (chunk in chunks) {
                val pos = ChunkPos(chunk.chunkX, chunk.chunkY, chunk.chunkZ)
                val diskBlocks = manager.loadChunkBytes(pos)
                assertNotNull("Chunk $pos missing after concurrent saves", diskBlocks)
                assertArrayEquals("Disk parity mismatch for $pos", chunk.blocks, diskBlocks)
            }
        } finally {
            workers.shutdownNow()
        }
    }

    // =========================================================================
    // SECTION 10: Measured Performance & Storage Baselines
    // =========================================================================

    @Test
    fun `sec10 measure real compression ratios encode decode throughput and cbor vs json boot time`() {
        val uniformStone = ByteArray(Chunk.VOLUME) { BlockType.STONE.id }
        val flatTerrain = ByteArray(Chunk.VOLUME) { i ->
            val y = i ushr 8
            when {
                y == 0 -> BlockType.STONE.id
                y < 4 -> BlockType.DIRT.id
                y == 4 -> BlockType.GRASS.id
                else -> BlockType.AIR.id
            }
        }
        val caveMix = ByteArray(Chunk.VOLUME) { i ->
            when ((i / 8) % 3) {
                0 -> BlockType.AIR.id
                1 -> BlockType.STONE.id
                else -> BlockType.DIRT.id
            }
        }
        val checkerboard = ByteArray(Chunk.VOLUME) { i ->
            if ((i + (i shr 4) + (i shr 8)) and 1 == 0) BlockType.STONE.id else BlockType.DIRT.id
        }
        val randomBytes = ByteArray(Chunk.VOLUME).also { Random(999L).nextBytes(it) }

        val samples = listOf(
            "UniformStone" to uniformStone,
            "FlatTerrain" to flatTerrain,
            "CaveMix" to caveMix,
            "Checkerboard" to checkerboard,
            "HighEntropyRandom" to randomBytes
        )

        println("[STAGE7_BENCHMARK] === Compression Size Baselines (Raw = 4096 bytes) ===")
        for ((name, raw) in samples) {
            val res = OsmiumCodec.encodeWithFormat(raw)
            val ratio = (res.bytes.size.toDouble() / raw.size.toDouble()) * 100.0
            println("[STAGE7_BENCHMARK] $name -> format=${res.format}, bytes=${res.bytes.size}, ratio=${"%.2f".format(ratio)}%")
            assertArrayEquals(raw, OsmiumCodec.decode(res.bytes))
        }

        // Encode/decode throughput across 500 mixed chunks
        val iterations = 500
        val encodedCave = OsmiumCodec.encode(caveMix)
        // Warmup
        repeat(50) { OsmiumCodec.encode(caveMix); OsmiumCodec.decode(encodedCave) }

        val encStartNs = System.nanoTime()
        repeat(iterations) {
            OsmiumCodec.encode(caveMix)
            OsmiumCodec.sha256Bytes(caveMix)
        }
        val encElapsedMs = (System.nanoTime() - encStartNs) / 1_000_000.0

        val decStartNs = System.nanoTime()
        repeat(iterations) {
            OsmiumCodec.decode(encodedCave)
        }
        val decElapsedMs = (System.nanoTime() - decStartNs) / 1_000_000.0

        println("[STAGE7_BENCHMARK] Encode+SHA256 ($iterations CaveMix chunks): ${"%.2f".format(encElapsedMs)} ms (${"%.3f".format(encElapsedMs / iterations)} ms/chunk)")
        println("[STAGE7_BENCHMARK] Decode ($iterations V3_LZ4 CaveMix chunks): ${"%.2f".format(decElapsedMs)} ms (${"%.3f".format(decElapsedMs / iterations)} ms/chunk)")

        // Disk save throughput for 64 deduplicated terrain chunks
        val benchWorldDir = File(WorldSaveManager.getSavesRootDir(context), "BenchWorld")
        val benchManager = WorldSaveManager(benchWorldDir)
        val benchChunks = (0 until 64).map { i ->
            Chunk(i.toLong(), 0L, 0L).apply {
                System.arraycopy(flatTerrain, 0, blocks, 0, Chunk.VOLUME)
                setBlock(i and 15, 4, (i ushr 4) and 15, BlockType.COBBLESTONE)
            }
        }
        val saveStartNs = System.nanoTime()
        runBlocking {
            val jobs = benchChunks.map { benchManager.saveChunkAndEntitiesAsync(it, emptyList()) }
            jobs.forEach { it.join() }
        }
        val saveElapsedMs = (System.nanoTime() - saveStartNs) / 1_000_000.0
        println("[STAGE7_BENCHMARK] Async Disk Save (64 unique chunks -> V4 pointers + masters): ${"%.2f".format(saveElapsedMs)} ms")

        // JSON vs CBOR boot latency comparison
        val contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        val coldStartNs = System.nanoTime()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        val coldBootMs = (System.nanoTime() - coldStartNs) / 1_000_000.0
        assertFalse(RegistryBootstrap.lastBootUsedCborCache)
        val coldJsonFiles = RegistryBootstrap.lastJsonFilesParsedCount
        assertTrue("Cold boot must parse JSON files", coldJsonFiles > 0)

        val warmStartNs = System.nanoTime()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
        val warmBootMs = (System.nanoTime() - warmStartNs) / 1_000_000.0
        assertTrue(RegistryBootstrap.lastBootUsedCborCache)

        println("[STAGE7_BENCHMARK] Registry Cold JSON Boot: ${"%.2f".format(coldBootMs)} ms ($coldJsonFiles JSON files)")
        println("[STAGE7_BENCHMARK] Registry Warm CBOR Boot: ${"%.2f".format(warmBootMs)} ms (${RegistryBootstrap.lastJsonFilesParsedCount} JSON files)")
    }
}
