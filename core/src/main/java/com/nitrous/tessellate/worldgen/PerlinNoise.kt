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
        val ix = floor(x).toInt()
        val iz = floor(z).toInt()
        val fx = x - ix
        val fz = z - iz

        val u = fade(fx)
        val v = fade(fz)

        val n00 = grad2D(hash2(seed, ix, iz), fx, fz)
        val n10 = grad2D(hash2(seed, ix + 1, iz), fx - 1.0, fz)
        val n01 = grad2D(hash2(seed, ix, iz + 1), fx, fz - 1.0)
        val n11 = grad2D(hash2(seed, ix + 1, iz + 1), fx - 1.0, fz - 1.0)

        val nx0 = lerp(n00, n10, u)
        val nx1 = lerp(n01, n11, u)
        return lerp(nx0, nx1, v)
    }

    fun noise3D(seed: Long, x: Double, y: Double, z: Double): Double {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val iz = floor(z).toInt()

        val fx = x - ix
        val fy = y - iy
        val fz = z - iz

        val u = fade(fx)
        val v = fade(fy)
        val w = fade(fz)

        val n000 = grad3D(hash3(seed, ix, iy, iz), fx, fy, fz)
        val n100 = grad3D(hash3(seed, ix + 1, iy, iz), fx - 1.0, fy, fz)
        val n010 = grad3D(hash3(seed, ix, iy + 1, iz), fx, fy - 1.0, fz)
        val n110 = grad3D(hash3(seed, ix + 1, iy + 1, iz), fx - 1.0, fy - 1.0, fz)
        val n001 = grad3D(hash3(seed, ix, iy, iz + 1), fx, fy, fz - 1.0)
        val n101 = grad3D(hash3(seed, ix + 1, iy, iz + 1), fx - 1.0, fy, fz - 1.0)
        val n011 = grad3D(hash3(seed, ix, iy + 1, iz + 1), fx, fy - 1.0, fz - 1.0)
        val n111 = grad3D(hash3(seed, ix + 1, iy + 1, iz + 1), fx - 1.0, fy - 1.0, fz - 1.0)

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
    fun hashFloat(seed: Long, x: Int, y: Int, z: Int): Float {
        val h = hash3(seed, x, y, z) and 0x00FFFFFF
        return h / 16777216.0f
    }

    /**
     * Deterministic integer in [0, bound) for spatial hashes.
     */
    fun hashInt(seed: Long, x: Int, y: Int, z: Int, bound: Int): Int {
        if (bound <= 1) return 0
        val h = hash3(seed, x, y, z) and 0x7FFFFFFF
        return h % bound
    }

    private fun fade(t: Double): Double = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)

    private fun lerp(a: Double, b: Double, t: Double): Double = a + t * (b - a)

    private fun hash2(seed: Long, x: Int, z: Int): Int {
        return hash3(seed, x, 0, z)
    }

    private fun hash3(seed: Long, x: Int, y: Int, z: Int): Int {
        var h = seed
        h = h xor (x.toLong() * 0x9E3779B97F4A7C15UL.toLong())
        h = h xor (y.toLong() * 0xBF58476D1CE4E5B9UL.toLong())
        h = h xor (z.toLong() * 0x94D049BB133111EBUL.toLong())
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
