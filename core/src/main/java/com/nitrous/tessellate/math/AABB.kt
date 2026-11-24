package com.nitrous.tessellate.math

/**
 * Axis-Aligned Bounding Box (AABB) for 3D voxel collision detection.
 */
data class AABB(
    var minX: Float,
    var minY: Float,
    var minZ: Float,
    var maxX: Float,
    var maxY: Float,
    var maxZ: Float
) {
    fun set(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) {
        this.minX = minX
        this.minY = minY
        this.minZ = minZ
        this.maxX = maxX
        this.maxY = maxY
        this.maxZ = maxZ
    }

    fun intersects(other: AABB): Boolean {
        return minX < other.maxX && maxX > other.minX &&
               minY < other.maxY && maxY > other.minY &&
               minZ < other.maxZ && maxZ > other.minZ
    }

    fun contains(x: Float, y: Float, z: Float): Boolean {
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }
}
