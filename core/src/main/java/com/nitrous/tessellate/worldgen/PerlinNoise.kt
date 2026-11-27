package com.nitrous.tessellate.worldgen

import kotlin.math.floor

/**
 * Deterministic, thread-safe 2D and 3D gradient Perlin noise and spatial hash helpers
 * used across Atomic Lasagna passes and Structoid carving.
 */
object PerlinNoise {

    fun noise2DNormalized(seed: Long, x: Double, z: Double): Double {
        return ((noise2D(seed, x, z) + 1.0) * 0.5).coerceIn(0.0, 1.0)
    }

    fun fbm2DNormalized(
        seed: Long,
        x: Double,
        z: Double,
        octaves: Int = 3,
        lacunarity: Double = 2.0,
        gain: Double = 0.5
    ): Double {
        var total = 0.0
        var frequency = 1.0
        var amplitude = 1.0
        var maxAmplitude = 0.0
        for (i in 0 until octaves) {
            total += noise2DNormalized(seed + i * 97L, x * frequency, z * frequency) * amplitude
            maxAmplitude += amplitude
            amplitude *= gain
            frequency *= lacunarity
        }
        return if (maxAmplitude > 0.0) (total / maxAmplitude).coerceIn(0.0, 1.0) else 0.5
    }

    fun noise3DNormalized(seed: Long, x: Double, y: Double, z: Double): Double {
        return ((noise3D(seed, x, y, z) + 1.0) * 0.5).coerceIn(0.0, 1.0)
    }

    fun noise2D(seed: Long, x: Double, z: Double): Double {
        val ix = floor(x).toLong()
        val iz = floor(z).toLong()
        val fx = x - ix.toDouble()
        val fz = z - iz.toDouble()

        val u = fade(fx)
        val v = fade(fz)

        val n00 = grad2D(hash2(seed, ix, iz), fx, fz)
        val n10 = grad2D(hash2(seed, ix + 1L, iz), fx - 1.0, fz)
        val n01 = grad2D(hash2(seed, ix, iz + 1L), fx, fz - 1.0)
        val n11 = grad2D(hash2(seed, ix + 1L, iz + 1L), fx - 1.0, fz - 1.0)

        val nx0 = lerp(n00, n10, u)
        val nx1 = lerp(n01, n11, u)
        return lerp(nx0, nx1, v)
    }

    fun noise3D(seed: Long, x: Double, y: Double, z: Double): Double {
        val ix = floor(x).toLong()
        val iy = floor(y).toLong()
        val iz = floor(z).toLong()

        val fx = x - ix.toDouble()
        val fy = y - iy.toDouble()
        val fz = z - iz.toDouble()

        val u = fade(fx)
        val v = fade(fy)
        val w = fade(fz)

        val n000 = grad3D(hash3(seed, ix, iy, iz), fx, fy, fz)
        val n100 = grad3D(hash3(seed, ix + 1L, iy, iz), fx - 1.0, fy, fz)
        val n010 = grad3D(hash3(seed, ix, iy + 1L, iz), fx, fy - 1.0, fz)
        val n110 = grad3D(hash3(seed, ix + 1L, iy + 1L, iz), fx - 1.0, fy - 1.0, fz)
        val n001 = grad3D(hash3(seed, ix, iy, iz + 1L), fx, fy, fz - 1.0)
        val n101 = grad3D(hash3(seed, ix + 1L, iy, iz + 1L), fx - 1.0, fy, fz - 1.0)
        val n011 = grad3D(hash3(seed, ix, iy + 1L, iz + 1L), fx, fy - 1.0, fz - 1.0)
        val n111 = grad3D(hash3(seed, ix + 1L, iy + 1L, iz + 1L), fx - 1.0, fy - 1.0, fz - 1.0)

        val nx00 = lerp(n000, n100, u)
        val nx10 = lerp(n010, n110, u)
        val nx01 = lerp(n001, n101, u)
        val nx11 = lerp(n011, n111, u)

        val nxy0 = lerp(nx00, nx10, v)
        val nxy1 = lerp(nx01, nx11, v)

        return lerp(nxy0, nxy1, w)
    }

    /**
     * Deterministic float in [0, 1) for spatial hashes.
     */
    fun hashFloat(seed: Long, x: Long, y: Long, z: Long): Float {
        val h = hash3(seed, x, y, z) and 0x00FFFFFF
        return h / 16777216.0f
    }

    fun hashFloat(seed: Long, x: Int, y: Int, z: Int): Float =
        hashFloat(seed, x.toLong(), y.toLong(), z.toLong())

    /**
     * Deterministic integer in [0, bound) for spatial hashes.
     */
    fun hashInt(seed: Long, x: Long, y: Long, z: Long, bound: Int): Int {
        if (bound <= 1) return 0
        val h = hash3(seed, x, y, z) and 0x7FFFFFFF
        return h % bound
    }

    fun hashInt(seed: Long, x: Int, y: Int, z: Int, bound: Int): Int =
        hashInt(seed, x.toLong(), y.toLong(), z.toLong(), bound)

    private fun fade(t: Double): Double = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)

    private fun lerp(a: Double, b: Double, t: Double): Double = a + t * (b - a)

    private fun hash2(seed: Long, x: Long, z: Long): Int {
        return hash3(seed, x, 0L, z)
    }

    private fun hash3(seed: Long, x: Long, y: Long, z: Long): Int {
        var h = seed
        h = h xor (x * 0x9E3779B97F4A7C15UL.toLong())
        h = h xor (y * 0xBF58476D1CE4E5B9UL.toLong())
        h = h xor (z * 0x94D049BB133111EBUL.toLong())
        h = (h xor (h ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        h = (h xor (h ushr 27)) * 0x94D049BB133111EBUL.toLong()
        h = h xor (h ushr 31)
        return h.toInt()
    }

    private fun grad2D(hash: Int, x: Double, z: Double): Double {
        return when (hash and 7) {
            0 -> x + z
            1 -> -x + z
            2 -> x - z
            3 -> -x - z
            4 -> x * 1.4142135623730951
            5 -> -x * 1.4142135623730951
            6 -> z * 1.4142135623730951
            else -> -z * 1.4142135623730951
        }
    }

    private fun grad3D(hash: Int, x: Double, y: Double, z: Double): Double {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if ((h and 1) == 0) u else -u) + (if ((h and 2) == 0) v else -v)
    }
}
