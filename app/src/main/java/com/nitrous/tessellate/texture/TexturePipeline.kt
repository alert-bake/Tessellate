package com.nitrous.tessellate.texture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.nitrous.tessellate.core.ITexturePipeline
import com.nitrous.tessellate.core.TextureDiagnostic
import java.io.IOException

enum class TextureFilterMode {
    NEAREST, // Pixel-perfect voxel retro filtering
    LINEAR   // Bilinear filtering
}

data class TextureUvRect(
    val uMin: Float,
    val vMin: Float,
    val uMax: Float,
    val vMax: Float
)

/**
 * Robust OpenGL ES 3.0 texture pipeline for Tessellate.
 * Manages prototype texture loading from assets, pixel-art filtering,
 * mipmap generation, texture atlas construction, and missing texture diagnostics.
 */
class TexturePipeline(private val context: Context) : ITexturePipeline {

    private val textureHandles = mutableMapOf<String, Int>()
    private val diagnostics = mutableMapOf<String, TextureDiagnostic>()
    private val loadedBitmaps = mutableMapOf<String, Bitmap>()
    private val atlasUvMap = mutableMapOf<String, TextureUvRect>()

    @Volatile
    private var filterModeDirty: Boolean = false

    @Volatile
    var filterMode: TextureFilterMode = TextureFilterMode.NEAREST
        set(value) {
            field = value
            filterModeDirty = true
        }

    private var proceduralDebugHandle: Int = 0
    var atlasTextureHandle: Int = 0
        private set

    private var isGlInitialized: Boolean = false

    /**
     * Initializes GL-dependent texture resources.
     * MUST be called from the OpenGL render thread inside onSurfaceCreated.
     */
    fun initGl() {
        isGlInitialized = true
        createProceduralFallback()
        reloadAll()
        buildAtlas()
    }

    private fun createProceduralFallback() {
        if (proceduralDebugHandle != 0) {
            val arr = intArrayOf(proceduralDebugHandle)
            GLES30.glDeleteTextures(1, arr, 0)
            proceduralDebugHandle = 0
        }

        val size = 16
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // Magenta and black diagnostic checkerboard
                val isMagenta = ((x / 4) + (y / 4)) % 2 == 0
                val color = if (isMagenta) Color.rgb(255, 0, 255) else Color.BLACK
                bitmap.setPixel(x, y, color)
            }
        }
        proceduralDebugHandle = uploadBitmapToGl(bitmap, TextureFilterMode.NEAREST)
        bitmap.recycle()
    }

    override fun loadTexture(id: String, assetPath: String): Boolean {
        try {
            context.assets.open(assetPath).use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                if (bitmap == null) {
                    val msg = "BitmapFactory returned null for asset: $assetPath"
                    Log.e(TAG, msg)
                    diagnostics[id] = TextureDiagnostic(id, isSuccess = false, isFallback = true, message = msg)
                    return false
                }

                val previousBitmap = loadedBitmaps.put(id, bitmap)
                if (previousBitmap != null && previousBitmap !== bitmap && !previousBitmap.isRecycled) {
                    previousBitmap.recycle()
                }

                if (isGlInitialized) {
                    val previousHandle = textureHandles[id]
                    if (previousHandle != null && previousHandle != 0 && previousHandle != proceduralDebugHandle) {
                        GLES30.glDeleteTextures(1, intArrayOf(previousHandle), 0)
                    }
                    val handle = if (isUiTextureId(id)) {
                        uploadUiBitmapToGl(bitmap)
                    } else {
                        uploadBitmapToGl(bitmap, filterMode)
                    }
                    textureHandles[id] = handle
                }

                diagnostics[id] = TextureDiagnostic(
                    textureId = id,
                    isSuccess = true,
                    isFallback = false,
                    message = "Loaded ${bitmap.width}x${bitmap.height} ARGB_8888 texture from $assetPath"
                )
                Log.i(TAG, "Successfully loaded prototype texture '$id' ($assetPath)")
                return true
            }
        } catch (e: IOException) {
            val msg = "Asset not found: $assetPath (${e.message})"
            Log.w(TAG, "Diagnosing missing texture '$id': $msg")
            diagnostics[id] = TextureDiagnostic(id, isSuccess = false, isFallback = true, message = msg)
            return false
        } catch (e: Exception) {
            val msg = "Unexpected error loading $id: ${e.message}"
            Log.e(TAG, msg, e)
            diagnostics[id] = TextureDiagnostic(id, isSuccess = false, isFallback = true, message = msg)
            return false
        }
    }

    private fun isUiTextureId(id: String): Boolean {
        return TextureConstants.ALL_UI_IDS.contains(id) || TextureConstants.ALL_CONTROLLER_IDS.contains(id)
    }

    /**
     * Stitches block and prototype textures into a unified 128x128 power-of-two texture atlas
     * (4x4 grid of 32x32 padded cells around each 16x16 inner texture) enabling single-draw-call
     * chunk rendering with safe trilinear mipmapping and zero neighbor/magenta bleed.
     */
    fun buildAtlas() {
        val atlasBitmap = createAtlasBitmap()
        if (!isGlInitialized) {
            atlasBitmap.recycle()
            return
        }

        if (atlasTextureHandle != 0) {
            val arr = intArrayOf(atlasTextureHandle)
            GLES30.glDeleteTextures(1, arr, 0)
            atlasTextureHandle = 0
        }

        atlasTextureHandle = uploadAtlasBitmapToGl(atlasBitmap, filterMode)
        atlasBitmap.recycle()
        Log.i(TAG, "Generated Padded Texture Atlas (${ATLAS_SIZE}x${ATLAS_SIZE}, handle: $atlasTextureHandle)")
    }

    /**
     * Generates the CPU-side 128x128 RGBA atlas bitmap with 2-pixel (and cell-safe) extruded edge
     * padding around each 16x16 tile and updates [atlasUvMap] to point strictly to the inner 16x16 region.
     */
    fun createAtlasBitmap(): Bitmap {
        atlasUvMap.clear()

        val atlasBitmap = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888)
        atlasBitmap.eraseColor(Color.TRANSPARENT)

        for ((id, gridCoord) in ATLAS_TILE_GRID) {
            val (col, row) = gridCoord
            val cellX = col * ATLAS_CELL_STRIDE
            val cellY = row * ATLAS_CELL_STRIDE
            val innerX = cellX + ATLAS_INNER_OFFSET
            val innerY = cellY + ATLAS_INNER_OFFSET

            val bmp = loadedBitmaps[id]
            if (bmp != null) {
                blitTileWithExtrudedPadding(
                    source = bmp,
                    atlas = atlasBitmap,
                    cellX = cellX,
                    cellY = cellY,
                    innerX = innerX,
                    innerY = innerY
                )
            }

            val uMin = innerX.toFloat() / ATLAS_SIZE
            val vMin = innerY.toFloat() / ATLAS_SIZE
            val uMax = (innerX + ATLAS_TILE_SIZE).toFloat() / ATLAS_SIZE
            val vMax = (innerY + ATLAS_TILE_SIZE).toFloat() / ATLAS_SIZE
            atlasUvMap[id] = TextureUvRect(uMin, vMin, uMax, vMax)
        }

        // Procedural fallback tiles for tree blocks when standalone PNGs aren't bundled
        for ((id, gridCoord) in PROCEDURAL_ATLAS_TILES) {
            val (col, row) = gridCoord
            val cellX = col * ATLAS_CELL_STRIDE
            val cellY = row * ATLAS_CELL_STRIDE
            val innerX = cellX + ATLAS_INNER_OFFSET
            val innerY = cellY + ATLAS_INNER_OFFSET

            val bmp = loadedBitmaps[id] ?: createProceduralBlockTile(id)
            blitTileWithExtrudedPadding(
                source = bmp,
                atlas = atlasBitmap,
                cellX = cellX,
                cellY = cellY,
                innerX = innerX,
                innerY = innerY
            )
            if (loadedBitmaps[id] == null) {
                bmp.recycle()
            }

            val uMin = innerX.toFloat() / ATLAS_SIZE
            val vMin = innerY.toFloat() / ATLAS_SIZE
            val uMax = (innerX + ATLAS_TILE_SIZE).toFloat() / ATLAS_SIZE
            val vMax = (innerY + ATLAS_TILE_SIZE).toFloat() / ATLAS_SIZE
            atlasUvMap[id] = TextureUvRect(uMin, vMin, uMax, vMax)
        }

        return atlasBitmap
    }

    private fun createProceduralBlockTile(id: String): Bitmap {
        val w = ATLAS_TILE_SIZE
        val h = ATLAS_TILE_SIZE
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val color = when (id) {
                    TextureConstants.BLOCK_OAK_LOG -> {
                        val stripe = ((x * 7 + y * 3) % 5 == 0) || (x % 4 == 1)
                        if (stripe) Color.rgb(92, 62, 34) else Color.rgb(122, 86, 48)
                    }
                    TextureConstants.BLOCK_LEAVES -> {
                        val hole = ((x + y * 3) % 7 == 0) && ((x * y) % 3 == 0)
                        if (hole) Color.argb(175, 42, 118, 36) else Color.argb(245, 58, 148, 44)
                    }
                    else -> Color.rgb(128, 128, 128)
                }
                bitmap.setPixel(x, y, color)
            }
        }
        return bitmap
    }

    /**
     * Copies a 16x16 source tile into ([innerX], [innerY]) and extrudes its edge pixels outward
     * by at least [ATLAS_PADDING] (2px) — and across the surrounding cell guard band — so mipmap
     * generation and linear filtering never bleed into neighboring tiles or background pixels.
     */
    private fun blitTileWithExtrudedPadding(
        source: Bitmap,
        atlas: Bitmap,
        cellX: Int,
        cellY: Int,
        innerX: Int,
        innerY: Int
    ) {
        val w = ATLAS_TILE_SIZE
        val h = ATLAS_TILE_SIZE
        val scaledSource = if (source.width != w || source.height != h) {
            Bitmap.createScaledBitmap(source, w, h, false)
        } else {
            source
        }

        val srcPixels = IntArray(w * h)
        scaledSource.getPixels(srcPixels, 0, w, 0, 0, w, h)
        if (scaledSource !== source) {
            scaledSource.recycle()
        }

        val hasTransparency = srcPixels.any { Color.alpha(it) < 255 }

        // Copy inner 16x16 pixels verbatim
        atlas.setPixels(srcPixels, 0, w, innerX, innerY, w, h)

        val minPadX = innerX - ATLAS_PADDING
        val maxPadX = innerX + w + ATLAS_PADDING - 1
        val minPadY = innerY - ATLAS_PADDING
        val maxPadY = innerY + h + ATLAS_PADDING - 1
        val cellEndX = cellX + ATLAS_CELL_STRIDE
        val cellEndY = cellY + ATLAS_CELL_STRIDE

        for (ay in cellY until cellEndY) {
            for (ax in cellX until cellEndX) {
                if (ax in innerX until (innerX + w) && ay in innerY until (innerY + h)) {
                    continue
                }
                val sx = (ax - innerX).coerceIn(0, w - 1)
                val sy = (ay - innerY).coerceIn(0, h - 1)
                val edgePixel = srcPixels[sy * w + sx]

                val in2PxPadding = ax in minPadX..maxPadX && ay in minPadY..maxPadY
                val outPixel = if (in2PxPadding || !hasTransparency) {
                    edgePixel
                } else {
                    // Preserve edge RGB with alpha=0 in outer transparent guard pixels to avoid dark fringes
                    edgePixel and 0x00FFFFFF
                }
                atlas.setPixel(ax, ay, outPixel)
            }
        }
    }

    fun getAtlasUv(textureId: String): TextureUvRect {
        return atlasUvMap[textureId] ?: atlasUvMap[TextureConstants.PROTO_DEBUG_BUG] ?: TextureUvRect(0f, 0f, 1f, 1f)
    }

    override fun getTextureHandle(id: String): Int {
        val handle = textureHandles[id]
        if (handle != null && handle != 0) {
            return handle
        }

        // Section 5 Requirement: Missing or invalid references diagnosed and fall back to proto_debugbug
        val fallbackHandle = textureHandles[TextureConstants.PROTO_DEBUG_BUG]
        val resolvedHandle = if (fallbackHandle != null && fallbackHandle != 0) {
            if (!diagnostics.containsKey(id)) {
                Log.w(TAG, "Diagnosing texture reference '$id': Not found. Redirected to '${TextureConstants.PROTO_DEBUG_BUG}'.")
                diagnostics[id] = TextureDiagnostic(
                    textureId = id,
                    isSuccess = false,
                    isFallback = true,
                    message = "Diagnosed: Missing '$id', routed to fallback ${TextureConstants.PROTO_DEBUG_BUG}"
                )
            }
            fallbackHandle
        } else {
            proceduralDebugHandle
        }

        textureHandles[id] = resolvedHandle
        return resolvedHandle
    }

    override fun getDiagnostic(id: String): TextureDiagnostic {
        return diagnostics[id] ?: TextureDiagnostic(
            textureId = id,
            isSuccess = false,
            isFallback = true,
            message = "Texture ID '$id' was not found"
        )
    }

    override fun getAllLoadedIds(): List<String> = textureHandles.keys.toList()

    override fun reloadAll() {
        val oldHandles = textureHandles.values.filter { it != 0 && it != proceduralDebugHandle }.toIntArray()
        if (oldHandles.isNotEmpty()) {
            GLES30.glDeleteTextures(oldHandles.size, oldHandles, 0)
        }
        textureHandles.clear()

        createProceduralFallback()

        // Re-upload cached bitmaps
        for ((id, bitmap) in loadedBitmaps) {
            val handle = if (isUiTextureId(id)) {
                uploadUiBitmapToGl(bitmap)
            } else {
                uploadBitmapToGl(bitmap, filterMode)
            }
            textureHandles[id] = handle
        }

        buildAtlas()
    }

    private fun uploadUiBitmapToGl(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val handle = textures[0]
        if (handle == 0) {
            Log.e(TAG, "Failed to allocate OpenGL UI texture handle")
            return 0
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return handle
    }

    private fun uploadBitmapToGl(bitmap: Bitmap, mode: TextureFilterMode): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val handle = textures[0]
        if (handle == 0) {
            Log.e(TAG, "Failed to allocate OpenGL texture handle")
            return 0
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)

        // Texture wrapping
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Texture filtering
        applyFilterParameters(mode)

        // Upload pixel data
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

        // Generate Mipmaps for smooth distant rendering
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return handle
    }

    private fun uploadAtlasBitmapToGl(bitmap: Bitmap, mode: TextureFilterMode): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val handle = textures[0]
        if (handle == 0) {
            Log.e(TAG, "Failed to allocate OpenGL atlas texture handle")
            return 0
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)

        // Clamp wrap mode prevents sampling across atlas borders
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, ATLAS_MAX_MIP_LEVEL)

        applyAtlasFilterParameters(mode)

        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return handle
    }

    private fun applyAtlasFilterParameters(mode: TextureFilterMode) {
        // Always use trilinear mipmapping on the padded block atlas to eliminate distant shimmer
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, ATLAS_MIN_FILTER)
        val magFilter = when (mode) {
            TextureFilterMode.NEAREST -> GLES30.GL_NEAREST
            TextureFilterMode.LINEAR -> GLES30.GL_LINEAR
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, magFilter)
    }

    private fun applyFilterParameters(mode: TextureFilterMode) {
        when (mode) {
            TextureFilterMode.NEAREST -> {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST_MIPMAP_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            }
            TextureFilterMode.LINEAR -> {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            }
        }
    }

    fun applyPendingFilterMode() {
        if (filterModeDirty) {
            filterModeDirty = false
            applyFilterModeToAll()
        }
    }

    private fun applyFilterModeToAll() {
        if (!isGlInitialized) return
        filterModeDirty = false
        for ((id, handle) in textureHandles) {
            if (handle != 0 && !isUiTextureId(id)) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)
                applyFilterParameters(filterMode)
            }
        }
        if (proceduralDebugHandle != 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, proceduralDebugHandle)
            applyFilterParameters(filterMode)
        }
        if (atlasTextureHandle != 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTextureHandle)
            applyAtlasFilterParameters(filterMode)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    override fun release() {
        val handles = textureHandles.values.toIntArray()
        if (handles.isNotEmpty()) {
            GLES30.glDeleteTextures(handles.size, handles, 0)
        }
        if (proceduralDebugHandle != 0) {
            val debugArr = intArrayOf(proceduralDebugHandle)
            GLES30.glDeleteTextures(1, debugArr, 0)
            proceduralDebugHandle = 0
        }
        if (atlasTextureHandle != 0) {
            val atlasArr = intArrayOf(atlasTextureHandle)
            GLES30.glDeleteTextures(1, atlasArr, 0)
            atlasTextureHandle = 0
        }
        textureHandles.clear()
        diagnostics.clear()
        atlasUvMap.clear()
        loadedBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        loadedBitmaps.clear()
        isGlInitialized = false
    }

    companion object {
        private const val TAG = "Tessellate.Texture"
        const val ATLAS_SIZE = 128
        const val ATLAS_TILE_SIZE = 16
        const val ATLAS_PADDING = 2
        const val ATLAS_CELL_STRIDE = 32
        const val ATLAS_INNER_OFFSET = 8
        const val ATLAS_MAX_MIP_LEVEL = 3
        const val ATLAS_MIN_FILTER = GLES30.GL_LINEAR_MIPMAP_LINEAR
        const val ATLAS_WRAP_MODE = GLES30.GL_CLAMP_TO_EDGE

        val ATLAS_TILE_GRID: List<Pair<String, Pair<Int, Int>>> = listOf(
            TextureConstants.PROTO_GRASS to Pair(0, 0),
            TextureConstants.PROTO_GRASS_SIDE to Pair(1, 0),
            TextureConstants.PROTO_DIRT to Pair(2, 0),
            TextureConstants.PROTO_STONE to Pair(3, 0),
            TextureConstants.PROTO_DEBUG_BUG to Pair(0, 1),
            TextureConstants.BLOCK_STONE to Pair(1, 1),
            TextureConstants.BLOCK_COBBLESTONE to Pair(2, 1),
            TextureConstants.BLOCK_SAND to Pair(3, 1),
            TextureConstants.BLOCK_GLASS to Pair(0, 2)
        )

        val PROCEDURAL_ATLAS_TILES: List<Pair<String, Pair<Int, Int>>> = listOf(
            TextureConstants.BLOCK_OAK_LOG to Pair(1, 2),
            TextureConstants.BLOCK_LEAVES to Pair(2, 2)
        )
    }
}
