package com.nitrous.tessellate.save

import com.nitrous.tessellate.world.ChunkPos

/**
 * Stateless codec translating [ChunkPos] <-> Base36 chunk/sidecar/entity filenames.
 *
 * Strictly decoupled from in-memory chunk addressing.
 *
 * Alphabet: `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ`
 * Format:
 * - Chunk binary: `c_<X>_<Y>_<Z>.bin` (e.g. `(1_000_000, 3, 2)` -> `c_LFLS_3_2.bin`)
 * - Block data sidecar: `c_<X>_<Y>_<Z>.data`
 * - Entity JSON: `c_<X>_<Y>_<Z>.json`
 */
object ChunkFilenameCodec {

    const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val RADIX = 36
    const val PREFIX = "c_"
    const val EXT_BIN = ".bin"
    const val EXT_DATA = ".data"
    const val EXT_JSON = ".json"

    private val DECIMAL_COMPONENT_REGEX = Regex("^-?(?:0|[1-9][0-9]*)$")

    data class DecodedFilename(
        val pos: ChunkPos,
        val extension: String,
        val canonicalName: String
    )

    /**
     * Encodes a signed 64-bit [Long] coordinate using strict uppercase Base36 [ALPHABET].
     * Correctly handles `0L`, negative values, [Long.MAX_VALUE], and [Long.MIN_VALUE] without overflow.
     */
    fun encodeBase36(value: Long): String {
        if (value == 0L) return "0"
        val isNegative = value < 0L
        // Work with negative numbers so Long.MIN_VALUE (-9223372036854775808) never overflows
        var current = if (isNegative) value else -value
        val buf = CharArray(14) // max 13 digits + '-' sign for Long.MIN_VALUE
        var pos = buf.size
        while (current < 0L) {
            val q = current / RADIX
            val rem = -(current - q * RADIX).toInt()
            buf[--pos] = ALPHABET[rem]
            current = q
        }
        if (isNegative) {
            buf[--pos] = '-'
        }
        return String(buf, pos, buf.size - pos)
    }

    /**
     * Decodes a Base36 coordinate component into a [Long], returning `null` for malformed inputs,
     * empty strings, non-canonical leading zeros (e.g. `"00"`, `"01"`, `"-0"`, `"-05"`),
     * invalid characters, or 64-bit signed integer overflow.
     *
     * Accepts lowercase ASCII `a..z` (normalizing to `A..Z`) so files stored on case-insensitive
     * filesystems (Windows/macOS) decode identically without ambiguity.
     */
    fun decodeBase36(raw: String): Long? {
        if (raw.isEmpty()) return null

        var idx = 0
        var isNegative = false
        val first = raw[0]
        if (first == '-') {
            isNegative = true
            idx = 1
        } else if (first == '+') {
            return null
        }

        if (idx >= raw.length) return null

        // Leading-zero rule: "0" is valid, "-0" or "0<anything>" is non-canonical & rejected
        val firstDigitChar = raw[idx]
        if (firstDigitChar == '0') {
            return if (!isNegative && raw.length == 1) 0L else null
        }

        val limit = if (isNegative) Long.MIN_VALUE else -Long.MAX_VALUE
        val multMin = limit / RADIX
        var acc = 0L

        while (idx < raw.length) {
            val digit = base36Digit(raw[idx++]) ?: return null
            if (acc < multMin) return null
            acc *= RADIX
            if (acc < limit + digit) return null
            acc -= digit
        }

        return if (isNegative) acc else -acc
    }

    private fun base36Digit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> (c - 'A') + 10
        in 'a'..'z' -> (c - 'a') + 10
        else -> null
    }

    fun encodeChunkFilename(pos: ChunkPos): String = encodeWithExtension(pos, EXT_BIN)

    fun encodeBlockDataFilename(pos: ChunkPos): String = encodeWithExtension(pos, EXT_DATA)

    fun encodeEntityFilename(pos: ChunkPos): String = encodeWithExtension(pos, EXT_JSON)

    fun encodeWithExtension(pos: ChunkPos, extension: String): String {
        return "$PREFIX${encodeBase36(pos.x)}_${encodeBase36(pos.y)}_${encodeBase36(pos.z)}$extension"
    }

    fun decodeChunkFilename(filename: String): ChunkPos? {
        val decoded = decodeFilename(filename, EXT_BIN) ?: return null
        return decoded.pos
    }

    fun decodeBlockDataFilename(filename: String): ChunkPos? {
        val decoded = decodeFilename(filename, EXT_DATA) ?: return null
        return decoded.pos
    }

    fun decodeEntityFilename(filename: String): ChunkPos? {
        val decoded = decodeFilename(filename, EXT_JSON) ?: return null
        return decoded.pos
    }

    /**
     * Parses `c_<X>_<Y>_<Z><ext>` where `<ext>` is one of `.bin`, `.data`, `.json` (or an
     * explicitly required [requiredExtension]). Rejects non-canonical or overflowing coordinates.
     */
    fun decodeFilename(filename: String, requiredExtension: String? = null): DecodedFilename? {
        if (!filename.startsWith(PREFIX)) return null

        val ext = when {
            requiredExtension != null -> {
                if (!filename.endsWith(requiredExtension)) return null
                requiredExtension
            }
            filename.endsWith(EXT_BIN) -> EXT_BIN
            filename.endsWith(EXT_DATA) -> EXT_DATA
            filename.endsWith(EXT_JSON) -> EXT_JSON
            else -> return null
        }

        val body = filename.substring(PREFIX.length, filename.length - ext.length)
        val firstSep = body.indexOf('_')
        if (firstSep <= 0) return null
        val secondSep = body.indexOf('_', firstSep + 1)
        if (secondSep <= firstSep + 1) return null
        if (body.indexOf('_', secondSep + 1) >= 0) return null

        val rawX = body.substring(0, firstSep)
        val rawY = body.substring(firstSep + 1, secondSep)
        val rawZ = body.substring(secondSep + 1)

        val x = decodeBase36(rawX) ?: return null
        val y = decodeBase36(rawY) ?: return null
        val z = decodeBase36(rawZ) ?: return null

        val pos = ChunkPos(x, y, z)
        return DecodedFilename(
            pos = pos,
            extension = ext,
            canonicalName = encodeWithExtension(pos, ext)
        )
    }

    /**
     * Parses a legacy decimal filename (`c_<decX>_<decY>_<decZ>.(bin|data|json)`).
     * Returns `null` if the name is not strictly decimal (e.g. contains Base36 letters `A..Z`,
     * leading zeros, or overflows [Long]).
     */
    fun decodeLegacyDecimalFilename(filename: String): DecodedFilename? {
        if (!filename.startsWith(PREFIX)) return null
        val ext = when {
            filename.endsWith(EXT_BIN) -> EXT_BIN
            filename.endsWith(EXT_DATA) -> EXT_DATA
            filename.endsWith(EXT_JSON) -> EXT_JSON
            else -> return null
        }

        val body = filename.substring(PREFIX.length, filename.length - ext.length)
        val firstSep = body.indexOf('_')
        if (firstSep <= 0) return null
        val secondSep = body.indexOf('_', firstSep + 1)
        if (secondSep <= firstSep + 1) return null
        if (body.indexOf('_', secondSep + 1) >= 0) return null

        val rawX = body.substring(0, firstSep)
        val rawY = body.substring(firstSep + 1, secondSep)
        val rawZ = body.substring(secondSep + 1)

        if (!DECIMAL_COMPONENT_REGEX.matches(rawX) ||
            !DECIMAL_COMPONENT_REGEX.matches(rawY) ||
            !DECIMAL_COMPONENT_REGEX.matches(rawZ)
        ) {
            return null
        }

        val x = rawX.toLongOrNull() ?: return null
        val y = rawY.toLongOrNull() ?: return null
        val z = rawZ.toLongOrNull() ?: return null

        val pos = ChunkPos(x, y, z)
        return DecodedFilename(
            pos = pos,
            extension = ext,
            canonicalName = encodeWithExtension(pos, ext)
        )
    }
}
