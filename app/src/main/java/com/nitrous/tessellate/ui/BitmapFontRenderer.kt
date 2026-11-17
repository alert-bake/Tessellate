package com.nitrous.tessellate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.nitrous.tessellate.graphics.Matrix4
import com.nitrous.tessellate.graphics.Shader
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlin.math.round
import kotlin.math.roundToInt

@JsonClass(generateAdapter = true)
data class BitmapFontPageConfig(
    val texture: String = "UI_font_ascii.png",
    val cols: Int = 16,
    val rows: Int = 6,
    val glyphWidth: Int = 5,
    val glyphHeight: Int = 7,
    val cellLogicalSize: Int = 8,
    val cellHeightLogical: Int = 8,
    val chars: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BitmapFontConfig(
    val lineHeight: Int = 7,
    val spaceWidth: Int = 5,
    val charGap: Int = 1,
    val monospace: Boolean = true,
    val pages: List<BitmapFontPageConfig> = emptyList()
)

data class FontGlyphMetrics(
    val pageIndex: Int,
    val u1: Float,
    val v1: Float,
    val u2: Float,
    val v2: Float,
    val widthUnits: Int,
    val heightUnits: Int
)

/**
 * Data-driven, resource-pack-ready bitmap font renderer for OpenGL ES 3.0.
 *
 * Loads `textures/UI_font.json` and PNG font sheets (checking `filesDir/textures/` overrides
 * first for resource packs before falling back to bundled `assets/textures/`), and keeps the
 * procedural 5x7 ASCII bitmap as a resilient fallback.
 */
class BitmapFontRenderer(
    private val context: Context? = null
) {

    var fontTextureHandle: Int = 0
        private set

    private val pageTextureHandles = mutableListOf<Int>()
    private val loadedPageBitmaps = mutableListOf<Bitmap>()
    private val glyphMap = mutableMapOf<Int, FontGlyphMetrics>()

    private var activeConfig: BitmapFontConfig = defaultFontConfig()
    private var isFontDataLoaded: Boolean = false

    private val modelMatrix = Matrix4.create()
    private val mvpMatrix = Matrix4.create()

    init {
        loadFontMetadataAndBitmaps(keepBitmaps = false)
    }

    fun initGl() {
        release()
        loadFontMetadataAndBitmaps(keepBitmaps = true)

        if (loadedPageBitmaps.isEmpty()) {
            val fallbackBmp = createFontAtlasBitmap()
            loadedPageBitmaps.add(fallbackBmp)
        }

        for (bmp in loadedPageBitmaps) {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            val handle = tex[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            pageTextureHandles.add(handle)
            bmp.recycle()
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        loadedPageBitmaps.clear()
        fontTextureHandle = pageTextureHandles.firstOrNull() ?: 0
    }

    fun reloadFont() {
        if (pageTextureHandles.isNotEmpty()) {
            initGl()
        } else {
            loadFontMetadataAndBitmaps(keepBitmaps = false)
        }
    }

    fun getLoadedGlyphCount(): Int = glyphMap.size

    fun getLoadedPageCount(): Int = activeConfig.pages.size.coerceAtLeast(1)

    fun getGlyphMetrics(codePoint: Int): FontGlyphMetrics? = glyphMap[codePoint]

    private fun loadFontMetadataAndBitmaps(keepBitmaps: Boolean) {
        recyclePendingBitmaps()
        glyphMap.clear()

        val config = loadFontConfig(context)
        activeConfig = config
        val pages = config.pages.ifEmpty { defaultFontConfig().pages }

        for ((pageIndex, page) in pages.withIndex()) {
            val pageBitmap = loadPageBitmap(context, page.texture) ?: if (pageIndex == 0) {
                createFontAtlasBitmap()
            } else {
                continue
            }

            indexPageGlyphs(pageIndex, page, pageBitmap, config)

            if (keepBitmaps) {
                loadedPageBitmaps.add(pageBitmap)
            } else {
                pageBitmap.recycle()
            }
        }

        if (glyphMap.isEmpty()) {
            val fallbackBmp = createFontAtlasBitmap()
            val fallbackConfig = defaultFontConfig()
            activeConfig = fallbackConfig
            indexPageGlyphs(0, fallbackConfig.pages.first(), fallbackBmp, fallbackConfig)
            if (keepBitmaps) {
                loadedPageBitmaps.add(fallbackBmp)
            } else {
                fallbackBmp.recycle()
            }
        }

        isFontDataLoaded = true
    }

    private fun indexPageGlyphs(
        pageIndex: Int,
        page: BitmapFontPageConfig,
        bmp: Bitmap,
        config: BitmapFontConfig
    ) {
        val cols = page.cols.coerceAtLeast(1)
        val rows = page.rows.coerceAtLeast(1)
        val bmpW = bmp.width.coerceAtLeast(1)
        val bmpH = bmp.height.coerceAtLeast(1)
        val logicalCell = page.cellLogicalSize.coerceAtLeast(1)
        val logicalCellH = page.cellHeightLogical.coerceAtLeast(1)
        val cellPxW = (bmpW / cols).coerceAtLeast(1)
        val expectedCellPxH = ((cellPxW.toFloat() * logicalCellH.toFloat()) / logicalCell.toFloat())
            .roundToInt()
            .coerceAtLeast(1)
        val cellPxH = if (rows * expectedCellPxH <= bmpH) {
            expectedCellPxH
        } else {
            (bmpH / rows).coerceAtLeast(1)
        }
        val targetGlyphW = page.glyphWidth.coerceIn(1, logicalCell)
        val targetGlyphH = page.glyphHeight.coerceIn(1, logicalCellH)

        val defaultUFrac = (targetGlyphW.toFloat() / logicalCell.toFloat()).coerceIn(0.05f, 1f)
        val defaultVFrac = (targetGlyphH.toFloat() / logicalCellH.toFloat()).coerceIn(0.05f, 1f)

        val charRows = page.chars.ifEmpty { DEFAULT_ASCII_CHAR_ROWS }
        for (rowIdx in charRows.indices) {
            if (rowIdx >= rows) break
            val rowStr = charRows[rowIdx]
            var strOffset = 0
            var colIdx = 0
            while (strOffset < rowStr.length && colIdx < cols) {
                val cp = rowStr.codePointAt(strOffset)
                strOffset += Character.charCount(cp)

                if (cp != 0) {
                    val cellLeftPx = colIdx * cellPxW
                    val cellTopPx = rowIdx * cellPxH
                    val u1 = cellLeftPx.toFloat() / bmpW.toFloat()
                    val v1 = cellTopPx.toFloat() / bmpH.toFloat()

                    val (widthUnits, u2) = if (config.monospace) {
                        val spanPx = cellPxW * defaultUFrac
                        targetGlyphW to ((cellLeftPx + spanPx) / bmpW.toFloat())
                    } else if (cp == ' '.code) {
                        val spaceW = config.spaceWidth.coerceAtLeast(1)
                        val spanPx = cellPxW * (spaceW.toFloat() / logicalCell.toFloat()).coerceIn(0.05f, 1f)
                        spaceW to ((cellLeftPx + spanPx) / bmpW.toFloat())
                    } else {
                        val measuredPxW = measureCellOpaqueWidthPx(bmp, cellLeftPx, cellTopPx, cellPxW, cellPxH)
                        if (measuredPxW <= 0) {
                            val fallbackW = config.spaceWidth.coerceAtLeast(1)
                            fallbackW to (u1 + (cellPxW.toFloat() * defaultUFrac / bmpW.toFloat()))
                        } else {
                            val scaledUnits = ((measuredPxW.toFloat() * logicalCell.toFloat()) / cellPxW.toFloat())
                                .roundToInt()
                                .coerceIn(1, logicalCell)
                            scaledUnits to ((cellLeftPx + measuredPxW).toFloat() / bmpW.toFloat())
                        }
                    }

                    val v2 = (cellTopPx + cellPxH * defaultVFrac) / bmpH.toFloat()
                    glyphMap[cp] = FontGlyphMetrics(
                        pageIndex = pageIndex,
                        u1 = u1,
                        v1 = v1,
                        u2 = u2,
                        v2 = v2,
                        widthUnits = widthUnits,
                        heightUnits = targetGlyphH
                    )
                }
                colIdx++
            }
        }
    }

    private fun measureCellOpaqueWidthPx(
        bmp: Bitmap,
        cellLeft: Int,
        cellTop: Int,
        cellW: Int,
        cellH: Int
    ): Int {
        val maxX = (cellLeft + cellW).coerceAtMost(bmp.width)
        val maxY = (cellTop + cellH).coerceAtMost(bmp.height)
        for (x in (maxX - 1) downTo cellLeft) {
            for (y in cellTop until maxY) {
                if (Color.alpha(bmp.getPixel(x, y)) > 16) {
                    return (x - cellLeft) + 1
                }
            }
        }
        return 0
    }

    /**
     * Builds the 128x64 ARGB_8888 bitmap font atlas for ASCII [32..126].
     * Used as the generator for `UI_font_ascii.png` and fallback if assets are unavailable.
     */
    fun createFontAtlasBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(ATLAS_WIDTH, ATLAS_HEIGHT, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)

        for (code in FIRST_CHAR..LAST_CHAR) {
            val index = code - FIRST_CHAR
            val col = index % ATLAS_COLS
            val row = index / ATLAS_COLS
            val cellX = col * CELL_SIZE
            val cellY = row * CELL_SIZE
            val glyph = GLYPHS[code.toChar()] ?: GLYPHS['?']!!

            for (gy in 0 until GLYPH_HEIGHT) {
                val bits = glyph[gy]
                for (gx in 0 until GLYPH_WIDTH) {
                    if (((bits shr (GLYPH_WIDTH - 1 - gx)) and 1) == 1) {
                        bmp.setPixel(cellX + gx, cellY + gy, Color.WHITE)
                    }
                }
            }
        }
        return bmp
    }

    /**
     * Measures text width in pixels using strictly integer scaling.
     */
    fun measureWidth(text: String, scale: Int): Float {
        if (text.isEmpty()) return 0f
        if (!isFontDataLoaded) loadFontMetadataAndBitmaps(keepBitmaps = false)

        val safeScale = scale.coerceAtLeast(1)
        val gap = activeConfig.charGap.coerceAtLeast(0)
        val fallbackMetric = glyphMap['?'.code]
        val defaultW = fallbackMetric?.widthUnits ?: GLYPH_WIDTH

        var totalUnits = 0
        var idx = 0
        var count = 0
        while (idx < text.length) {
            val cp = text.codePointAt(idx)
            idx += Character.charCount(cp)
            val widthUnits = if (cp == ' '.code) {
                glyphMap[cp]?.widthUnits ?: activeConfig.spaceWidth
            } else {
                glyphMap[cp]?.widthUnits ?: defaultW
            }
            if (count > 0) totalUnits += gap
            totalUnits += widthUnits
            count++
        }
        return (totalUnits * safeScale).toFloat()
    }

    /**
     * Measures text height in pixels using strictly integer scaling.
     */
    fun measureHeight(scale: Int): Float {
        val safeScale = scale.coerceAtLeast(1)
        val lineH = activeConfig.lineHeight.coerceAtLeast(1)
        return (lineH * safeScale).toFloat()
    }

    /**
     * Draws [text] left-aligned at ([x], [y]) using integer [scale].
     * Coordinates are snapped to whole pixels to prevent texel shimmering.
     */
    fun drawText(
        shader: Shader,
        orthoMatrix: FloatArray,
        quadVaoId: Int,
        text: String,
        x: Float,
        y: Float,
        scale: Int,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f,
        shadow: Boolean = true
    ) {
        if (pageTextureHandles.isEmpty() || quadVaoId == 0 || text.isEmpty()) return

        val safeScale = scale.coerceAtLeast(1)
        val gapPx = (activeConfig.charGap.coerceAtLeast(0) * safeScale).toFloat()
        val startX = round(x)
        val startY = round(y)
        val fallbackGlyph = glyphMap['?'.code]

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        shader.setUniform1i("uTexture", 0)
        shader.setUniform1i("uUseTexture", 1)
        shader.setUniform1i("uWhiteOverlay", 0)
        GLES30.glBindVertexArray(quadVaoId)

        var boundPage = -1
        val startPass = if (shadow) 0 else 1
        for (pass in startPass..1) {
            val isShadow = (pass == 0)
            val ox = if (isShadow) safeScale.toFloat() else 0f
            val oy = if (isShadow) safeScale.toFloat() else 0f
            val cr = if (isShadow) r * 0.18f else r
            val cg = if (isShadow) g * 0.18f else g
            val cb = if (isShadow) b * 0.18f else b
            val ca = if (isShadow) a * 0.9f else a

            shader.setUniform4f("uColor", cr, cg, cb, ca)

            var cursorX = startX
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)

                if (cp == ' '.code) {
                    val spaceUnits = glyphMap[cp]?.widthUnits ?: activeConfig.spaceWidth
                    cursorX += spaceUnits * safeScale + gapPx
                    continue
                }

                val glyph = glyphMap[cp] ?: fallbackGlyph
                if (glyph == null) {
                    cursorX += activeConfig.spaceWidth * safeScale + gapPx
                    continue
                }

                val handle = pageTextureHandles.getOrNull(glyph.pageIndex) ?: fontTextureHandle
                if (handle == 0) {
                    cursorX += glyph.widthUnits * safeScale + gapPx
                    continue
                }
                if (glyph.pageIndex != boundPage) {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                    boundPage = glyph.pageIndex
                }

                val glyphW = (glyph.widthUnits * safeScale).toFloat()
                val glyphH = (glyph.heightUnits * safeScale).toFloat()
                val drawX = cursorX + ox
                val drawY = startY + oy

                Matrix4.setIdentity(modelMatrix)
                Matrix4.translate(modelMatrix, drawX, drawY, 0f)
                Matrix4.scale(modelMatrix, glyphW, glyphH, 1f)
                Matrix4.multiply(mvpMatrix, orthoMatrix, modelMatrix)

                shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)
                shader.setUniform4f("uUvRect", glyph.u1, glyph.v1, glyph.u2, glyph.v2)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

                cursorX += glyphW + gapPx
            }
        }
    }

    /**
     * Draws [text] horizontally and vertically centered around ([centerX], [centerY]).
     */
    fun drawTextCentered(
        shader: Shader,
        orthoMatrix: FloatArray,
        quadVaoId: Int,
        text: String,
        centerX: Float,
        centerY: Float,
        scale: Int,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f,
        shadow: Boolean = true
    ) {
        val w = measureWidth(text, scale)
        val h = measureHeight(scale)
        drawText(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = text,
            x = round(centerX - w * 0.5f),
            y = round(centerY - h * 0.5f),
            scale = scale,
            r = r,
            g = g,
            b = b,
            a = a,
            shadow = shadow
        )
    }

    /**
     * Draws [text] right-aligned and bottom-aligned at ([rightX], [bottomY]).
     */
    fun drawTextRightAligned(
        shader: Shader,
        orthoMatrix: FloatArray,
        quadVaoId: Int,
        text: String,
        rightX: Float,
        bottomY: Float,
        scale: Int,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        a: Float = 1f,
        shadow: Boolean = true
    ) {
        val w = measureWidth(text, scale)
        val h = measureHeight(scale)
        drawText(
            shader = shader,
            orthoMatrix = orthoMatrix,
            quadVaoId = quadVaoId,
            text = text,
            x = round(rightX - w),
            y = round(bottomY - h),
            scale = scale,
            r = r,
            g = g,
            b = b,
            a = a,
            shadow = shadow
        )
    }

    private fun recyclePendingBitmaps() {
        for (bmp in loadedPageBitmaps) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        loadedPageBitmaps.clear()
    }

    fun release() {
        recyclePendingBitmaps()
        if (pageTextureHandles.isNotEmpty()) {
            val arr = pageTextureHandles.toIntArray()
            GLES30.glDeleteTextures(arr.size, arr, 0)
            pageTextureHandles.clear()
        }
        fontTextureHandle = 0
    }

    companion object {
        private const val TAG = "Tessellate.BitmapFont"
        const val FONT_CONFIG_RELATIVE_PATH = "textures/UI_font.json"
        const val GLYPH_WIDTH = 5
        const val GLYPH_HEIGHT = 7
        const val CHAR_ADVANCE = 6
        const val CELL_SIZE = 8
        const val ATLAS_COLS = 16
        const val ATLAS_ROWS = 6
        const val ATLAS_WIDTH = ATLAS_COLS * CELL_SIZE   // 128
        const val ATLAS_HEIGHT = ATLAS_ROWS * CELL_SIZE  // 64 (power of two)
        const val FIRST_CHAR = 32
        const val LAST_CHAR = 126

        private val DEFAULT_ASCII_CHAR_ROWS = listOf(
            " !\"#$%&'()*+,-./",
            "0123456789:;<=>?",
            "@ABCDEFGHIJKLMNO",
            "PQRSTUVWXYZ[\\]^_",
            "`abcdefghijklmno",
            "pqrstuvwxyz{|}~"
        )

        private val moshi: Moshi by lazy {
            Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
        }
        private val configAdapter by lazy { moshi.adapter(BitmapFontConfig::class.java) }

        fun defaultFontConfig(): BitmapFontConfig = BitmapFontConfig(
            lineHeight = GLYPH_HEIGHT,
            spaceWidth = GLYPH_WIDTH,
            charGap = 1,
            monospace = true,
            pages = listOf(
                BitmapFontPageConfig(
                    texture = "UI_font_ascii.png",
                    cols = ATLAS_COLS,
                    rows = ATLAS_ROWS,
                    glyphWidth = GLYPH_WIDTH,
                    glyphHeight = GLYPH_HEIGHT,
                    cellLogicalSize = CELL_SIZE,
                    chars = DEFAULT_ASCII_CHAR_ROWS
                )
            )
        )

        fun loadFontConfig(context: Context?): BitmapFontConfig {
            if (context == null) return defaultFontConfig()

            val overrideFile = File(context.filesDir, FONT_CONFIG_RELATIVE_PATH)
            if (overrideFile.exists() && overrideFile.isFile) {
                try {
                    val parsed = configAdapter.fromJson(overrideFile.readText())
                    if (parsed != null && parsed.pages.isNotEmpty()) return parsed
                } catch (e: Exception) {
                    Log.w(TAG, "Failed parsing override $FONT_CONFIG_RELATIVE_PATH; falling back.", e)
                }
            }

            return try {
                val json = context.assets.open(FONT_CONFIG_RELATIVE_PATH).bufferedReader().use { it.readText() }
                val parsed = configAdapter.fromJson(json)
                if (parsed != null && parsed.pages.isNotEmpty()) parsed else defaultFontConfig()
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading $FONT_CONFIG_RELATIVE_PATH; using default font config.", e)
                defaultFontConfig()
            }
        }

        private fun normalizeTexturePath(textureName: String): String {
            val trimmed = textureName.trim().removePrefix("/")
            return if (trimmed.startsWith("textures/")) trimmed else "textures/$trimmed"
        }

        private fun loadPageBitmap(context: Context?, textureName: String): Bitmap? {
            if (context == null) return null
            val relPath = normalizeTexturePath(textureName)
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val overrideFile = File(context.filesDir, relPath)
            if (overrideFile.exists() && overrideFile.isFile) {
                try {
                    overrideFile.inputStream().use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)?.let { return it }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed decoding override font texture $relPath; falling back to assets.", e)
                }
            }

            return try {
                context.assets.open(relPath).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed decoding bundled font texture $relPath.", e)
                null
            }
        }

        // Fallback 5x7 pixel font bitmaps for printable ASCII characters
        private val GLYPHS: Map<Char, IntArray> = mapOf(
            ' ' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000),
            '!' to intArrayOf(0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100),
            '"' to intArrayOf(0b01010, 0b01010, 0b01010, 0b00000, 0b00000, 0b00000, 0b00000),
            '#' to intArrayOf(0b01010, 0b01010, 0b11111, 0b01010, 0b11111, 0b01010, 0b01010),
            '$' to intArrayOf(0b00100, 0b01111, 0b10100, 0b01110, 0b00101, 0b11110, 0b00100),
            '%' to intArrayOf(0b11001, 0b11010, 0b00010, 0b00100, 0b01000, 0b01011, 0b10011),
            '&' to intArrayOf(0b01100, 0b10010, 0b10100, 0b01000, 0b10101, 0b10010, 0b01101),
            '\'' to intArrayOf(0b00100, 0b00100, 0b01000, 0b00000, 0b00000, 0b00000, 0b00000),
            '(' to intArrayOf(0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010),
            ')' to intArrayOf(0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000),
            '*' to intArrayOf(0b00100, 0b10101, 0b01110, 0b11111, 0b01110, 0b10101, 0b00100),
            '+' to intArrayOf(0b00000, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0b00000),
            ',' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00100, 0b00100, 0b01000),
            '-' to intArrayOf(0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000),
            '.' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100),
            '/' to intArrayOf(0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000),

            '0' to intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110),
            '1' to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
            '2' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b01000, 0b10000, 0b11111),
            '3' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b00001, 0b10001, 0b01110),
            '4' to intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010),
            '5' to intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110),
            '6' to intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
            '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000),
            '8' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110),
            '9' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100),

            ':' to intArrayOf(0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000),
            ';' to intArrayOf(0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b10000),
            '<' to intArrayOf(0b00010, 0b00100, 0b01000, 0b10000, 0b01000, 0b00100, 0b00010),
            '=' to intArrayOf(0b00000, 0b00000, 0b11111, 0b00000, 0b11111, 0b00000, 0b00000),
            '>' to intArrayOf(0b01000, 0b00100, 0b00010, 0b00001, 0b00010, 0b00100, 0b01000),
            '?' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b00100, 0b00000, 0b00100),
            '@' to intArrayOf(0b01110, 0b10001, 0b10111, 0b10101, 0b10110, 0b10000, 0b01110),

            'A' to intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
            'B' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110),
            'C' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110),
            'D' to intArrayOf(0b11100, 0b10010, 0b10001, 0b10001, 0b10001, 0b10010, 0b11100),
            'E' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111),
            'F' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000),
            'G' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111),
            'H' to intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
            'I' to intArrayOf(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
            'J' to intArrayOf(0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100),
            'K' to intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
            'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
            'M' to intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001),
            'N' to intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001),
            'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
            'P' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000),
            'Q' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101),
            'R' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001),
            'S' to intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110),
            'T' to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100),
            'U' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
            'V' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
            'W' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001),
            'X' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001),
            'Y' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100),
            'Z' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111),

            '[' to intArrayOf(0b01110, 0b01000, 0b01000, 0b01000, 0b01000, 0b01000, 0b01110),
            '\\' to intArrayOf(0b10000, 0b01000, 0b01000, 0b00100, 0b00010, 0b00010, 0b00001),
            ']' to intArrayOf(0b01110, 0b00010, 0b00010, 0b00010, 0b00010, 0b00010, 0b01110),
            '^' to intArrayOf(0b00100, 0b01010, 0b10001, 0b00000, 0b00000, 0b00000, 0b00000),
            '_' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b11111),
            '`' to intArrayOf(0b01000, 0b00100, 0b00010, 0b00000, 0b00000, 0b00000, 0b00000),

            'a' to intArrayOf(0b00000, 0b00000, 0b01110, 0b00001, 0b01111, 0b10001, 0b01111),
            'b' to intArrayOf(0b10000, 0b10000, 0b10110, 0b11001, 0b10001, 0b10001, 0b11110),
            'c' to intArrayOf(0b00000, 0b00000, 0b01110, 0b10000, 0b10000, 0b10001, 0b01110),
            'd' to intArrayOf(0b00001, 0b00001, 0b01101, 0b10011, 0b10001, 0b10001, 0b01111),
            'e' to intArrayOf(0b00000, 0b00000, 0b01110, 0b10001, 0b11111, 0b10000, 0b01110),
            'f' to intArrayOf(0b00110, 0b01001, 0b01000, 0b11100, 0b01000, 0b01000, 0b01000),
            'g' to intArrayOf(0b00000, 0b01111, 0b10001, 0b10001, 0b01111, 0b00001, 0b01110),
            'h' to intArrayOf(0b10000, 0b10000, 0b10110, 0b11001, 0b10001, 0b10001, 0b10001),
            'i' to intArrayOf(0b00100, 0b00000, 0b01100, 0b00100, 0b00100, 0b00100, 0b01110),
            'j' to intArrayOf(0b00010, 0b00000, 0b00110, 0b00010, 0b00010, 0b10010, 0b01100),
            'k' to intArrayOf(0b10000, 0b10000, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010),
            'l' to intArrayOf(0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
            'm' to intArrayOf(0b00000, 0b00000, 0b11010, 0b10101, 0b10101, 0b10001, 0b10001),
            'n' to intArrayOf(0b00000, 0b00000, 0b10110, 0b11001, 0b10001, 0b10001, 0b10001),
            'o' to intArrayOf(0b00000, 0b00000, 0b01110, 0b10001, 0b10001, 0b10001, 0b01110),
            'p' to intArrayOf(0b00000, 0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000),
            'q' to intArrayOf(0b00000, 0b01101, 0b10011, 0b10001, 0b01111, 0b00001, 0b00001),
            'r' to intArrayOf(0b00000, 0b00000, 0b10110, 0b11001, 0b10000, 0b10000, 0b10000),
            's' to intArrayOf(0b00000, 0b00000, 0b01110, 0b10000, 0b01110, 0b00001, 0b11110),
            't' to intArrayOf(0b01000, 0b01000, 0b11100, 0b01000, 0b01000, 0b01001, 0b00110),
            'u' to intArrayOf(0b00000, 0b00000, 0b10001, 0b10001, 0b10001, 0b10011, 0b01101),
            'v' to intArrayOf(0b00000, 0b00000, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
            'w' to intArrayOf(0b00000, 0b00000, 0b10001, 0b10001, 0b10101, 0b10101, 0b01010),
            'x' to intArrayOf(0b00000, 0b00000, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001),
            'y' to intArrayOf(0b00000, 0b10001, 0b10001, 0b01111, 0b00001, 0b10001, 0b01110),
            'z' to intArrayOf(0b00000, 0b00000, 0b11111, 0b00010, 0b00100, 0b01000, 0b11111),

            '{' to intArrayOf(0b00110, 0b01000, 0b01000, 0b11000, 0b01000, 0b01000, 0b00110),
            '|' to intArrayOf(0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100),
            '}' to intArrayOf(0b01100, 0b00010, 0b00010, 0b00011, 0b00010, 0b00010, 0b01100),
            '~' to intArrayOf(0b00000, 0b00000, 0b01000, 0b10101, 0b00010, 0b00000, 0b00000)
        )
    }
}
