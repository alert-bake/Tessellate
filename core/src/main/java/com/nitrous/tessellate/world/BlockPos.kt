package com.nitrous.tessellate.world

import kotlin.math.sqrt

/**
 * Immutable 3D block coordinate representation.
 */
data class BlockPos(val x: Int, val y: Int, val z: Int) {

    fun offset(direction: Direction, distance: Int = 1): BlockPos {
        return BlockPos(
            x + direction.dx * distance,
            y + direction.dy * distance,
            z + direction.dz * distance
        )
    }

    fun add(dx: Int, dy: Int, dz: Int): BlockPos {
        return BlockPos(x + dx, y + dy, z + dz)
    }

    fun distanceTo(other: BlockPos): Float {
        val dx = (x - other.x).toFloat()
        val dy = (y - other.y).toFloat()
        val dz = (z - other.z).toFloat()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        val ORIGIN = BlockPos(0, 0, 0)
    }
}
