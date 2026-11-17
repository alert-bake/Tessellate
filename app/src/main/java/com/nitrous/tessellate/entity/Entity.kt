package com.nitrous.tessellate.entity

import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.world.AABB
import com.nitrous.tessellate.world.World

/**
 * Base class for dynamic world entities decoupled from static voxel chunks.
 *
 * Physics contract:
 * - [deltaTime] is expressed in **seconds**.
 * - [GRAVITY] is expressed in **world-units (blocks) per second squared** (24.0f units/s^2,
 *   matching Player's continuous downward acceleration).
 * - Each subclass owns its bounding-box dimensions and positioning rules via [updateBoundingBox].
 */
abstract class Entity(
    var position: Vec3,
    val boundingBox: AABB
) {
    val velocity = Vec3(0f, 0f, 0f)
    var isRemoved = false

    /** Canonical entity type identifier used when persisting to chunk entity JSON files. */
    open val typeId: String
        get() = "tessellate:entity"

    /** Serializes subclass- or mod-specific fields (e.g., GasAmount for a GasVolume). */
    open fun writeExtraData(): Map<String, Any?> = emptyMap()

    /** Restores subclass- or mod-specific fields from deserialized JSON data. */
    open fun readExtraData(data: Map<String, Any?>) = Unit

    /**
     * Advances entity physics by [deltaTime] seconds.
     */
    open fun tick(world: World, deltaTime: Float) {
        if (isRemoved || deltaTime <= 0f) return

        // Gravity is expressed in world-units per second squared.
        velocity.y -= GRAVITY * deltaTime

        position.x += velocity.x * deltaTime
        position.y += velocity.y * deltaTime
        position.z += velocity.z * deltaTime

        updateBoundingBox()
    }

    fun refreshBoundingBox() {
        updateBoundingBox()
    }

    protected abstract fun updateBoundingBox()

    companion object {
        /** Downward gravitational acceleration in world-units (blocks) per second squared. */
        const val GRAVITY: Float = 24.0f
    }
}
