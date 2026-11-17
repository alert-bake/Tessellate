package com.nitrous.tessellate.math

import kotlin.math.sqrt

/**
 * Minimal 3D floating-point vector for entity positions, velocities, and offsets.
 */
data class Vec3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(other: Vec3): Vec3 = set(other.x, other.y, other.z)

    fun copyVector(): Vec3 = Vec3(x, y, z)

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Float): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

    fun addLocal(dx: Float, dy: Float, dz: Float): Vec3 {
        x += dx
        y += dy
        z += dz
        return this
    }

    fun addLocal(other: Vec3): Vec3 = addLocal(other.x, other.y, other.z)

    fun offset(dx: Float, dy: Float, dz: Float): Vec3 = Vec3(x + dx, y + dy, z + dz)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 1e-6f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, 0f)
    }
}
