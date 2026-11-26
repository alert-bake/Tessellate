package com.nitrous.tessellate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.item.BlockItem
import com.nitrous.tessellate.item.Item
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemType
import com.nitrous.tessellate.registry.ContentLoadException
import com.nitrous.tessellate.registry.GameRegistries
import com.nitrous.tessellate.registry.Identifier
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.registry.RegistryFrozenException
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.world.BlockRegistry
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Direction
import com.nitrous.tessellate.world.World
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verification test suite for TDD-012.7:
 * - Data-driven JSON block & item schemas in `assets/content/`
 * - Dynamic BlockType data class & runtime ID assignment
 * - 5-step RegistryBootstrap pipeline (init -> load -> resolve -> freeze -> world gen)
 * - Mod JSON content injection prior to world initialization
 * - Flat tessellate:stone placeholder world generation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd012_7DataDrivenRegistryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
    }

    @After
    fun tearDown() {
        val modRoot = File(context.filesDir, "content")
        if (modRoot.exists()) {
            modRoot.deleteRecursively()
        }
        AndroidRegistryBootstrap.initialize(context = context, forceReinitialize = true)
    }

    @Test
    fun `block type is a data class and legacy REGISTRY accessor works dynamically`() {
        assertFalse(BlockType::class.java.isEnum)
        assertTrue(BlockType::class.isData)

        assertEquals(0.toByte(), BlockType.AIR.id)
        assertEquals(BlockType.STONE, BlockType.REGISTRY.get("tessellate:stone"))
        assertEquals(BlockType.STONE, BlockType.REGISTRY.getByNumericId(BlockType.STONE.id.toInt()))
        assertEquals(14, BlockRegistry.size)
        assertEquals(18, ItemRegistry.size)
    }

    @Test
    fun `json content definitions load all blocks and items with accurate textures and relationships`() {
        // Grass multi-face texture resolution
        val grass = BlockRegistry.get("tessellate:grass")!!
        assertEquals(0.6f, grass.hardness, 0.001f)
        assertTrue(grass.isSolid)
        assertTrue(grass.isSimple)
        assertEquals(TextureConstants.PROTO_GRASS, grass.getTextureForFace(Direction.UP))
        assertEquals(TextureConstants.PROTO_GRASS_SIDE, grass.getTextureForFace(Direction.NORTH))
        assertEquals(TextureConstants.PROTO_DIRT, grass.getTextureForFace(Direction.DOWN))

        // Glass non-opaque transparent flags
        val glass = BlockRegistry.get("tessellate:glass")!!
        assertFalse(glass.isOpaque)
        assertTrue(glass.isTransparent)
        assertTrue(glass.isSolid)
        assertFalse(glass.isSimple)

        // Relationships resolved: block <-> item
        val stone = BlockRegistry.STONE
        val stoneItem = ItemRegistry.getItemForBlock(stone)
        assertTrue(stoneItem is BlockItem)
        assertEquals("Stone", stoneItem?.displayName)
        assertEquals(stone, stoneItem?.associatedBlock)

        val pickaxe = ItemRegistry.get("tessellate:iron_pickaxe")!!
        assertEquals(ItemType.TOOL, pickaxe.itemType)
        assertEquals(1, pickaxe.maxStackSize)
        assertFalse(pickaxe.isPlaceable)
    }

    @Test
    fun `registries freeze after startup pipeline and reject post-freeze mutations`() {
        assertTrue(BlockRegistry.isFrozen)
        assertTrue(ItemRegistry.isFrozen)
        assertTrue(GameRegistries.BLOCKS.isFrozen)
        assertTrue(GameRegistries.ITEMS.isFrozen)

        assertThrows(RegistryFrozenException::class.java) {
            BlockRegistry.registerDynamic(
                identifier = Identifier.of("mod", "late_block"),
                isOpaque = true,
                topTexture = "block_stone",
                sideTexture = "block_stone",
                bottomTexture = "block_stone"
            )
        }

        assertThrows(RegistryFrozenException::class.java) {
            ItemRegistry.register(Item(Identifier.of("mod", "late_item"), "Late Item"))
        }

        assertThrows(RegistryFrozenException::class.java) {
            GameRegistries.BLOCKS.registerNamespace("late_namespace")
        }
    }

    @Test
    fun `mods can inject JSON blocks and items before freeze and use them in world`() {
        val modBlocksDir = File(context.filesDir, "content/blocks").apply { mkdirs() }
        val modItemsDir = File(context.filesDir, "content/items").apply { mkdirs() }

        File(modBlocksDir, "neon_brick.json").writeText(
            """
            {
              "id": "neonmod:neon_brick",
              "hardness": 2.5,
              "is_simple": true,
              "is_solid": true,
              "textures": { "all": "block_cobblestone" }
            }
            """.trimIndent()
        )
        File(modItemsDir, "neon_brick.json").writeText(
            """
            {
              "id": "neonmod:neon_brick",
              "display_name": "Neon Brick",
              "max_stack_size": 64,
              "item_type": "BLOCK",
              "block_id": "neonmod:neon_brick"
            }
            """.trimIndent()
        )

        val engine = TessellateEngine(context)
        assertTrue(BlockRegistry.isFrozen)
        assertTrue(ItemRegistry.isFrozen)

        val modBlock = BlockRegistry.get("neonmod:neon_brick")
        assertNotNull(modBlock)
        assertTrue("Mod block must receive dynamic numeric ID > 0", (modBlock!!.id.toInt() and 0xFF) >= 10)
        assertTrue(modBlock.isOpaque)
        assertTrue(modBlock.isSolid)
        assertTrue(modBlock.isSimple)

        val modItem = ItemRegistry.getItemForBlock(modBlock)
        assertNotNull(modItem)
        assertEquals("Neon Brick", modItem?.displayName)

        // Mod block can be placed into the initialized world and retrieved accurately
        val world = engine.worldScene.world
        assertTrue(world.setBlock(10, 7, 10, modBlock))
        assertEquals(modBlock, world.getBlock(10, 7, 10))
    }

    @Test
    fun `item referencing missing block fails during relationship resolution`() {
        val brokenItemJson = """
            {
              "id": "brokenmod:ghost_item",
              "display_name": "Ghost Block",
              "item_type": "BLOCK",
              "block_id": "brokenmod:non_existent_block"
            }
        """.trimIndent()

        assertThrows(ContentLoadException::class.java) {
            AndroidRegistryBootstrap.initialize(
                context = context,
                extraItemJsons = listOf(brokenItemJson),
                forceReinitialize = true
            )
        }
    }

    @Test
    fun `world generates a flat plane of tessellate stone across entire horizontal bounds`() {
        val world = World(chunksX = 3, chunksY = 2, chunksZ = 3)
        val expectedHeight = 6

        for (x in 0 until world.sizeX step 7) {
            for (z in 0 until world.sizeZ step 7) {
                assertEquals(expectedHeight, world.getSurfaceHeight(x, z))
                assertEquals(BlockType.STONE, world.getBlock(x, expectedHeight, z))
                assertEquals(BlockType.STONE, world.getBlock(x, 0, z))
                assertEquals(BlockType.AIR, world.getBlock(x, expectedHeight + 1, z))
            }
        }
    }
}
