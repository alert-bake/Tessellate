package com.nitrous.tessellate.world

import kotlin.math.sqrt

/**
 * Immutable 3D world block coordinate representation using 64-bit [Long] axes
 * to eliminate 32-bit integer overflow across extreme distances.
 */
data class BlockPos(val x: Long, val y: Long, val z: Long) {

    constructor(x: Int, y: Int, z: Int) : this(x.toLong(), y.toLong(), z.toLong())

    fun offset(direction: Direction, distance: Int = 1): BlockPos {
        val d = distance.toLong()
        return BlockPos(
            x + direction.dx.toLong() * d,
            y + direction.dy.toLong() * d,
            z + direction.dz.toLong() * d
        )
    }

    fun offset(direction: Direction, distance: Long): BlockPos {
        return BlockPos(
            x + direction.dx.toLong() * distance,
            y + direction.dy.toLong() * distance,
            z + direction.dz.toLong() * distance
        )
    }

    fun add(dx: Long, dy: Long, dz: Long): BlockPos {
        return BlockPos(x + dx, y + dy, z + dz)
    }

    fun add(dx: Int, dy: Int, dz: Int): BlockPos {
        return BlockPos(x + dx.toLong(), y + dy.toLong(), z + dz.toLong())
    }

    fun distanceTo(other: BlockPos): Float {
        val dx = (x - other.x).toDouble()
        val dy = (y - other.y).toDouble()
        val dz = (z - other.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    companion object {
        val ORIGIN = BlockPos(0L, 0L, 0L)
    }
}
