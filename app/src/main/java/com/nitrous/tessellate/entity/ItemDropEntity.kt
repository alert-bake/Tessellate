package com.nitrous.tessellate.entity

import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.world.AABB
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.World
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Dynamic item-drop entity spawned when blocks break or via `/spawn`.
 *
 * Hitbox: 0.25 x 0.25 x 0.25 world units.
 * [position] represents the bottom-center point of the entity (consistent with Player),
 * so [boundingBox] spans [x - 0.125 .. x + 0.125], [y .. y + 0.25], [z - 0.125 .. z + 0.125].
 */
open class ItemDropEntity(
    position: Vec3,
    val itemId: String = ItemRegistry.DIRT.identifier.toCanonicalString()
) : Entity(
    position = position,
    boundingBox = createBoundingBox(position)
) {

    override val typeId: String
        get() = TYPE_ID

    init {
        updateBoundingBox()
    }

    override fun writeExtraData(): Map<String, Any?> {
        return mapOf("itemId" to itemId)
    }

    /**
     * Advances physics and resolves vertical collisions with solid world blocks.
     * Uses bounded substeps and swept vertical bounds to prevent tunneling through
     * 1-block-thick floors during large [deltaTime] (seconds) timesteps.
     */
    override fun tick(world: World, deltaTime: Float) {
        if (isRemoved || deltaTime <= 0f) return
        if (position.y < VOID_DESPAWN_Y || !position.y.isFinite()) {
            isRemoved = true
            return
        }

        val projectedEndVy = (velocity.y - GRAVITY * deltaTime).coerceAtLeast(TERMINAL_VELOCITY_Y)
        val maxSpeedY = maxOf(abs(velocity.y), abs(projectedEndVy))
        val displacementSteps = ceil((maxSpeedY * deltaTime) / MAX_SUBSTEP_DISPLACEMENT).toInt()
        val timeSteps = ceil(deltaTime / MAX_SUBSTEP_DT).toInt()
        val steps = maxOf(1, displacementSteps, timeSteps).coerceAtMost(MAX_SUBSTEPS)
        val stepDt = deltaTime / steps

        for (step in 0 until steps) {
            if (isRemoved) break

            val prevMinY = boundingBox.minY
            val prevMaxY = boundingBox.maxY

            super.tick(world, stepDt)
            if (velocity.y < TERMINAL_VELOCITY_Y) {
                velocity.y = TERMINAL_VELOCITY_Y
            }

            resolveVerticalCollisions(world, prevMinY, prevMaxY)
        }
    }

    private fun resolveVerticalCollisions(world: World, prevMinY: Float, prevMaxY: Float) {
        val eps = 1e-4f
        val minBx = floor(boundingBox.minX + eps).toInt()
        val maxBx = floor(boundingBox.maxX - eps).toInt()
        val minBz = floor(boundingBox.minZ + eps).toInt()
        val maxBz = floor(boundingBox.maxZ - eps).toInt()

        val sweptMinY = minOf(prevMinY, boundingBox.minY)
        val sweptMaxY = maxOf(prevMaxY, boundingBox.maxY)
        val minBy = floor(sweptMinY).toInt().coerceIn(0, world.sizeY - 1)
        val maxBy = floor(sweptMaxY - eps).toInt().coerceIn(0, world.sizeY - 1)
        if (maxBy < minBy) return

        if (velocity.y <= 0f) {
            var hitFloorY: Float? = null

            for (bx in minBx..maxBx) {
                for (bz in minBz..maxBz) {
                    for (by in maxBy downTo minBy) {
                        val block = world.getBlock(bx, by, bz)
                        if (!isBlockSolid(block)) continue

                        val blockTopY = by + 1.0f
                        val crossedFromAbove = prevMinY >= blockTopY - eps && boundingBox.minY <= blockTopY
                        val overlapsBlock = boundingBox.minY < blockTopY && boundingBox.maxY > by.toFloat()

                        if (crossedFromAbove || overlapsBlock) {
                            if (hitFloorY == null || blockTopY > hitFloorY) {
                                hitFloorY = blockTopY
                            }
                        }
                    }
                }
            }

            if (hitFloorY != null) {
                position.y = hitFloorY
                velocity.y = 0f
                updateBoundingBox()
            }
        } else {
            var hitCeilingY: Float? = null

            for (bx in minBx..maxBx) {
                for (bz in minBz..maxBz) {
                    for (by in minBy..maxBy) {
                        val block = world.getBlock(bx, by, bz)
                        if (!isBlockSolid(block)) continue

                        val blockBottomY = by.toFloat()
                        val crossedFromBelow = prevMaxY <= blockBottomY + eps && boundingBox.maxY >= blockBottomY
                        val overlapsBlock = boundingBox.minY < (by + 1.0f) && boundingBox.maxY > blockBottomY

                        if (crossedFromBelow || overlapsBlock) {
                            if (hitCeilingY == null || blockBottomY < hitCeilingY) {
                                hitCeilingY = blockBottomY
                            }
                        }
                    }
                }
            }

            if (hitCeilingY != null) {
                position.y = hitCeilingY - HEIGHT
                velocity.y = 0f
                updateBoundingBox()
            }
        }
    }

    /**
     * Uses existing engine block solidity rules rather than treating every non-air block as solid.
     */
    protected open fun isBlockSolid(block: BlockType): Boolean = block.isSolid

    override fun updateBoundingBox() {
        boundingBox.set(
            position.x - HALF_WIDTH,
            position.y,
            position.z - HALF_DEPTH,
            position.x + HALF_WIDTH,
            position.y + HEIGHT,
            position.z + HALF_DEPTH
        )
    }

    companion object {
        const val TYPE_ID: String = "tessellate:item_drop"
        const val WIDTH: Float = 0.25f
        const val HEIGHT: Float = 0.25f
        const val DEPTH: Float = 0.25f
        const val HALF_WIDTH: Float = WIDTH * 0.5f
        const val HALF_DEPTH: Float = DEPTH * 0.5f

        private const val MAX_SUBSTEP_DT: Float = 0.01f
        private const val MAX_SUBSTEP_DISPLACEMENT: Float = 0.125f
        private const val MAX_SUBSTEPS: Int = 512
        private const val TERMINAL_VELOCITY_Y: Float = -35.0f
        private const val VOID_DESPAWN_Y: Float = -64.0f

        private fun createBoundingBox(position: Vec3): AABB {
            return AABB(
                position.x - HALF_WIDTH,
                position.y,
                position.z - HALF_DEPTH,
                position.x + HALF_WIDTH,
                position.y + HEIGHT,
                position.z + HALF_DEPTH
            )
        }
    }
}
