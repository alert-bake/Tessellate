package com.nitrous.tessellate

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES30
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.ChunkMesh
import com.nitrous.tessellate.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification test suite for TDD-011.5:
 * - Texture Atlas 2-pixel extruded edge padding
 * - Elimination of magenta background bleed in the atlas
 * - ChunkMesh UV mapping strictly to inner 16x16 texture area (excluding padding)
 * - Trilinear mipmapping (GL_LINEAR_MIPMAP_LINEAR) & GL_CLAMP_TO_EDGE atlas configuration
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Tdd011_5AtlasMipmapTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `atlas contains zero magenta pixels and extrudes 2px edge padding around every block texture`() {
        val pipeline = TexturePipeline(context)
        for ((id, _) in TexturePipeline.ATLAS_TILE_GRID) {
            assertTrue(
                "Block texture $id must load",
                pipeline.loadTexture(id, TextureConstants.getAssetPath(id))
            )
        }

        val atlas = pipeline.createAtlasBitmap()
        assertEquals(TexturePipeline.ATLAS_SIZE, atlas.width)
        assertEquals(TexturePipeline.ATLAS_SIZE, atlas.height)

        // 1. Verify zero opaque magenta pixels exist anywhere in the atlas
        for (y in 0 until atlas.height) {
            for (x in 0 until atlas.width) {
                val pixel = atlas.getPixel(x, y)
                val isMagenta = Color.alpha(pixel) > 0 &&
                    Color.red(pixel) == 255 &&
                    Color.green(pixel) == 0 &&
                    Color.blue(pixel) == 255
                assertFalse("Atlas must not contain magenta bleed pixel at ($x, $y)", isMagenta)
            }
        }

        // 2. Verify 2-pixel extruded edge padding for glass and stone
        val glassSrc = decodeAssetBitmap(TextureConstants.BLOCK_GLASS)
        val glassGrid = TexturePipeline.ATLAS_TILE_GRID.first { it.first == TextureConstants.BLOCK_GLASS }.second
        assertExtrudedPadding(atlas, glassSrc, glassGrid.first, glassGrid.second)

        val stoneSrc = decodeAssetBitmap(TextureConstants.BLOCK_STONE)
        val stoneGrid = TexturePipeline.ATLAS_TILE_GRID.first { it.first == TextureConstants.BLOCK_STONE }.second
        assertExtrudedPadding(atlas, stoneSrc, stoneGrid.first, stoneGrid.second)

        glassSrc.recycle()
        stoneSrc.recycle()
        atlas.recycle()
    }

    @Test
    fun `chunk mesh maps UVs strictly to inner 16x16 texture area excluding padding`() {
        val pipeline = TexturePipeline(context)
        for ((id, _) in TexturePipeline.ATLAS_TILE_GRID) {
            pipeline.loadTexture(id, TextureConstants.getAssetPath(id))
        }
        val atlas = pipeline.createAtlasBitmap()
        atlas.recycle()

        val glassUv = pipeline.getAtlasUv(TextureConstants.BLOCK_GLASS)
        val stoneUv = pipeline.getAtlasUv(TextureConstants.BLOCK_STONE)

        val expectedSpan = TexturePipeline.ATLAS_TILE_SIZE.toFloat() / TexturePipeline.ATLAS_SIZE
        assertEquals(expectedSpan, glassUv.uMax - glassUv.uMin, 1e-6f)
        assertEquals(expectedSpan, glassUv.vMax - glassUv.vMin, 1e-6f)
        assertEquals(expectedSpan, stoneUv.uMax - stoneUv.uMin, 1e-6f)
        assertEquals(expectedSpan, stoneUv.vMax - stoneUv.vMin, 1e-6f)

        // Inner offset puts UVs away from the 2px extruded border
        val padUv = TexturePipeline.ATLAS_PADDING.toFloat() / TexturePipeline.ATLAS_SIZE
        assertTrue(glassUv.uMin >= padUv)
        assertTrue(glassUv.vMin >= padUv)

        // Generate chunk mesh with glass in the sky and stone below
        val world = World(chunksX = 1, chunksY = 2, chunksZ = 1)
        for (x in 0 until world.sizeX) {
            for (y in 0 until world.sizeY) {
                for (z in 0 until world.sizeZ) {
                    world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }
        world.setBlock(8, 20, 8, BlockType.GLASS)
        world.setBlock(8, 4, 8, BlockType.STONE)

        val glassMesh = ChunkMesh(world.getChunk(0, 1, 0)!!)
        glassMesh.generateMeshData(world, pipeline)
        val glassUvs = glassMesh.getGeneratedUvs(transparentPass = true)
        assertEquals(6 * 4, glassUvs.size)
        for ((u, v) in glassUvs) {
            assertTrue("Glass u=$u must be inside inner rect", u in glassUv.uMin..glassUv.uMax)
            assertTrue("Glass v=$v must be inside inner rect", v in glassUv.vMin..glassUv.vMax)
        }

        val stoneMesh = ChunkMesh(world.getChunk(0, 0, 0)!!)
        stoneMesh.generateMeshData(world, pipeline)
        val stoneUvs = stoneMesh.getGeneratedUvs(transparentPass = false)
        assertEquals(6 * 4, stoneUvs.size)
        for ((u, v) in stoneUvs) {
            assertTrue("Stone u=$u must be inside inner rect", u in stoneUv.uMin..stoneUv.uMax)
            assertTrue("Stone v=$v must be inside inner rect", v in stoneUv.vMin..stoneUv.vMax)
        }
    }

    @Test
    fun `atlas mipmap and wrap constants match GL_LINEAR_MIPMAP_LINEAR and GL_CLAMP_TO_EDGE`() {
        assertEquals(GLES30.GL_LINEAR_MIPMAP_LINEAR, TexturePipeline.ATLAS_MIN_FILTER)
        assertEquals(GLES30.GL_CLAMP_TO_EDGE, TexturePipeline.ATLAS_WRAP_MODE)
        assertEquals(2, TexturePipeline.ATLAS_PADDING)
        assertTrue(
            "Chunk fragment shader must use highp float to avoid greedy UV seam precision loss",
            com.nitrous.tessellate.world.WorldRenderer.CHUNK_FRAGMENT_SHADER_SOURCE.contains("precision highp float;")
        )
    }

    @Test
    fun `tdd025 full atlas cell guard band extrudes edge pixels without alpha-0 black seams on grass stone dirt and glass`() {
        val pipeline = TexturePipeline(context)
        for ((id, _) in TexturePipeline.ATLAS_TILE_GRID) {
            assertTrue(pipeline.loadTexture(id, TextureConstants.getAssetPath(id)))
        }

        val atlas = pipeline.createAtlasBitmap()

        // Verify solid & edge-opaque blocks (including proto_grass & proto_stone whose PNG alpha < 255)
        // extrude across the ENTIRE 32x32 cell (8px guard band) with zero alpha=0 pixels
        val edgeOpaqueIds = listOf(
            TextureConstants.PROTO_GRASS,
            TextureConstants.PROTO_GRASS_SIDE,
            TextureConstants.PROTO_DIRT,
            TextureConstants.PROTO_STONE,
            TextureConstants.BLOCK_STONE,
            TextureConstants.BLOCK_COBBLESTONE,
            TextureConstants.BLOCK_SAND,
            TextureConstants.BLOCK_GLASS
        )

        for (id in edgeOpaqueIds) {
            val (col, row) = TexturePipeline.ATLAS_TILE_GRID.first { it.first == id }.second
            val src = decodeAssetBitmap(id)
            assertExtrudedPadding(atlas, src, col, row, fullCellGuardBand = true)

            val cellX = col * TexturePipeline.ATLAS_CELL_STRIDE
            val cellY = row * TexturePipeline.ATLAS_CELL_STRIDE
            val innerX = cellX + TexturePipeline.ATLAS_INNER_OFFSET
            val innerY = cellY + TexturePipeline.ATLAS_INNER_OFFSET

            // Simulate mipmap level 3 (8x8 box average per mip texel) on padding bands:
            // Top/bottom/left/right guard texels must retain full opaque-level alpha (no alpha=0 bleed!)
            for (y in cellY until cellY + TexturePipeline.ATLAS_CELL_STRIDE) {
                for (x in cellX until cellX + TexturePipeline.ATLAS_CELL_STRIDE) {
                    val inInner = x in innerX until (innerX + 16) && y in innerY until (innerY + 16)
                    if (!inInner) {
                        val alpha = Color.alpha(atlas.getPixel(x, y))
                        assertTrue(
                            "Tile '$id' guard pixel ($x, $y) must remain opaque (alpha=$alpha)",
                            alpha >= 250
                        )
                    }
                }
            }
            src.recycle()
        }

        // Verify genuinely transparent border texture (torch) naturally extrudes alpha=0 edge pixels
        val torchCoord = TexturePipeline.ATLAS_TILE_GRID.first { it.first == TextureConstants.BLOCK_TORCH }.second
        val torchSrc = decodeAssetBitmap(TextureConstants.BLOCK_TORCH)
        assertExtrudedPadding(atlas, torchSrc, torchCoord.first, torchCoord.second, fullCellGuardBand = true)
        torchSrc.recycle()

        // Verify procedural leaves tile extrudes non-zero leaf alpha across entire guard band (never wiped to 0)
        val leavesCoord = TexturePipeline.PROCEDURAL_ATLAS_TILES.first { it.first == TextureConstants.BLOCK_LEAVES }.second
        val leavesCellX = leavesCoord.first * TexturePipeline.ATLAS_CELL_STRIDE
        val leavesCellY = leavesCoord.second * TexturePipeline.ATLAS_CELL_STRIDE
        val leavesInnerX = leavesCellX + TexturePipeline.ATLAS_INNER_OFFSET
        val leavesInnerY = leavesCellY + TexturePipeline.ATLAS_INNER_OFFSET
        for (y in leavesCellY until leavesCellY + TexturePipeline.ATLAS_CELL_STRIDE) {
            for (x in leavesCellX until leavesCellX + TexturePipeline.ATLAS_CELL_STRIDE) {
                val sx = (x - leavesInnerX).coerceIn(0, 15)
                val sy = (y - leavesInnerY).coerceIn(0, 15)
                assertEquals(
                    "Leaves pixel at ($x, $y) must match clamped inner edge ($sx, $sy)",
                    atlas.getPixel(leavesInnerX + sx, leavesInnerY + sy),
                    atlas.getPixel(x, y)
                )
            }
        }

        atlas.recycle()
    }

    private fun decodeAssetBitmap(id: String) =
        context.assets.open(TextureConstants.getAssetPath(id)).use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inScaled = false }
            )!!
        }

    private fun assertExtrudedPadding(
        atlas: android.graphics.Bitmap,
        src: android.graphics.Bitmap,
        col: Int,
        row: Int,
        fullCellGuardBand: Boolean = false
    ) {
        val cellX = col * TexturePipeline.ATLAS_CELL_STRIDE
        val cellY = row * TexturePipeline.ATLAS_CELL_STRIDE
        val innerX = cellX + TexturePipeline.ATLAS_INNER_OFFSET
        val innerY = cellY + TexturePipeline.ATLAS_INNER_OFFSET
        val pad = if (fullCellGuardBand) TexturePipeline.ATLAS_INNER_OFFSET else TexturePipeline.ATLAS_PADDING

        // Inner pixels match source verbatim
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                assertEquals(src.getPixel(x, y), atlas.getPixel(innerX + x, innerY + y))
            }
        }

        // Top & bottom padding extrude row 0 and row 15
        for (p in 1..pad) {
            for (x in 0 until 16) {
                assertEquals(src.getPixel(x, 0), atlas.getPixel(innerX + x, innerY - p))
                assertEquals(src.getPixel(x, 15), atlas.getPixel(innerX + x, innerY + 15 + p))
            }
        }

        // Left & right padding extrude col 0 and col 15
        for (p in 1..pad) {
            for (y in 0 until 16) {
                assertEquals(src.getPixel(0, y), atlas.getPixel(innerX - p, innerY + y))
                assertEquals(src.getPixel(15, y), atlas.getPixel(innerX + 15 + p, innerY + y))
            }
        }

        // Corner padding regions extrude corner pixels
        for (py in 1..pad) {
            for (px in 1..pad) {
                assertEquals(src.getPixel(0, 0), atlas.getPixel(innerX - px, innerY - py))
                assertEquals(src.getPixel(15, 0), atlas.getPixel(innerX + 15 + px, innerY - py))
                assertEquals(src.getPixel(0, 15), atlas.getPixel(innerX - px, innerY + 15 + py))
                assertEquals(src.getPixel(15, 15), atlas.getPixel(innerX + 15 + px, innerY + 15 + py))
            }
        }
    }
}
