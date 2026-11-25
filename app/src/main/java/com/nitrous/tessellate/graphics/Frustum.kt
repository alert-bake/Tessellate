package com.nitrous.tessellate.graphics

import com.nitrous.tessellate.math.AABB
import kotlin.math.sqrt

/**
 * View-frustum extractor and AABB intersection tester using the Gribb-Hartmann method.
 * Stores 6 normalized inward-facing planes (a, b, c, d) in a flat FloatArray(24)
 * to avoid per-frame heap allocations in the render loop.
 */
class Frustum {

    // 6 planes * 4 floats (a, b, c, d): Left, Right, Bottom, Top, Near, Far
    private val planes = FloatArray(24)

    /**
     * Extracts and normalizes the 6 frustum planes from a column-major 4x4 [viewProjectionMatrix].
     */
    fun extractPlanes(vp: FloatArray) {
        if (vp.size < 16) return

        // Left: Row 3 + Row 0
        setNormalizedPlane(0, vp[3] + vp[0], vp[7] + vp[4], vp[11] + vp[8], vp[15] + vp[12])
        // Right: Row 3 - Row 0
        setNormalizedPlane(4, vp[3] - vp[0], vp[7] - vp[4], vp[11] - vp[8], vp[15] - vp[12])
        // Bottom: Row 3 + Row 1
        setNormalizedPlane(8, vp[3] + vp[1], vp[7] + vp[5], vp[11] + vp[9], vp[15] + vp[13])
        // Top: Row 3 - Row 1
        setNormalizedPlane(12, vp[3] - vp[1], vp[7] - vp[5], vp[11] - vp[9], vp[15] - vp[13])
        // Near: Row 3 + Row 2
        setNormalizedPlane(16, vp[3] + vp[2], vp[7] + vp[6], vp[11] + vp[10], vp[15] + vp[14])
        // Far: Row 3 - Row 2
        setNormalizedPlane(20, vp[3] - vp[2], vp[7] - vp[6], vp[11] - vp[10], vp[15] - vp[14])
    }

    private fun setNormalizedPlane(offset: Int, a: Float, b: Float, c: Float, d: Float) {
        val len = sqrt(a * a + b * b + c * c)
        if (len > 1e-6f) {
            val inv = 1f / len
            planes[offset] = a * inv
            planes[offset + 1] = b * inv
            planes[offset + 2] = c * inv
            planes[offset + 3] = d * inv
        } else {
            planes[offset] = a
            planes[offset + 1] = b
            planes[offset + 2] = c
            planes[offset + 3] = d
        }
    }

    /**
     * Returns true if [box] intersects or lies completely inside all 6 frustum planes
     * (when frustum planes are in the same coordinate space as [box]).
     */
    fun intersectsAabb(box: AABB): Boolean {
        return intersectsAabb(
            box.minX.toFloat(), box.minY.toFloat(), box.minZ.toFloat(),
            box.maxX.toFloat(), box.maxY.toFloat(), box.maxZ.toFloat()
        )
    }

    /**
     * Returns true if world-space [box] shifted by `(-cameraX, -cameraY, -cameraZ)` intersects
     * the camera-relative frustum planes (extracted from a viewProjectionMatrix whose eye is `(0, 0, 0)`).
     */
    fun intersectsRelativeAabb(
        box: AABB,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double
    ): Boolean {
        return intersectsAabb(
            (box.minX - cameraX).toFloat(),
            (box.minY - cameraY).toFloat(),
            (box.minZ - cameraZ).toFloat(),
            (box.maxX - cameraX).toFloat(),
            (box.maxY - cameraY).toFloat(),
            (box.maxZ - cameraZ).toFloat()
        )
    }

    /**
     * Positive-vertex (p-vertex) AABB vs 6-plane frustum test.
     */
    fun intersectsAabb(
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float
    ): Boolean {
        var offset = 0
        while (offset < 24) {
            val a = planes[offset]
            val b = planes[offset + 1]
            val c = planes[offset + 2]
            val d = planes[offset + 3]

            val px = if (a >= 0f) maxX else minX
            val py = if (b >= 0f) maxY else minY
            val pz = if (c >= 0f) maxZ else minZ

            if (a * px + b * py + c * pz + d < 0f) {
                return false
            }
            offset += 4
        }
        return true
    }
}
