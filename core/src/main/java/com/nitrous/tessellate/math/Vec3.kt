package com.nitrous.tessellate.math

import kotlin.math.sqrt

/**
 * Minimal 3D 64-bit floating-point vector for entity positions, velocities, and offsets.
 */
data class Vec3(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0
) {
    fun set(x: Double, y: Double, z: Double): Vec3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(other: Vec3): Vec3 = set(other.x, other.y, other.z)

    fun copyVector(): Vec3 = Vec3(x, y, z)

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Double): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

    fun addLocal(dx: Double, dy: Double, dz: Double): Vec3 {
        x += dx
        y += dy
        z += dz
        return this
    }

    fun addLocal(other: Vec3): Vec3 = addLocal(other.x, other.y, other.z)

    fun offset(dx: Double, dy: Double, dz: Double): Vec3 = Vec3(x + dx, y + dy, z + dz)

    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 1e-12) Vec3(x / len, y / len, z / len) else Vec3(0.0, 0.0, 0.0)
    }
}
