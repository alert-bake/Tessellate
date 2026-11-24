package com.nitrous.tessellate.entity

import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/**
 * Dynamic item-drop entity spawned when blocks break or via `/spawn`.
 *
 * Lives in `:core` (100% platform-independent):
 * - Hitbox: `0.25 x 0.25 x 0.25` world units (`position` is bottom-center).
 * - Continuous Y-axis rotation (`rotationYawDegrees`) and sine-wave vertical bobbing (`bobOffsetY`).
 * - Unbounded simulation stack [count] (absorbs nearby identical drops into a single entity) while
 *   visual layered rendering caps at [MAX_RENDER_STACK_LAYERS] (64) via [ItemDropRenderState].
 */
open class ItemDropEntity(
    position: Vec3,
    val itemId: String = ItemRegistry.DIRT.identifier.toCanonicalString(),
    count: Int = 1
) : Entity(
    position = position,
    boundingBox = createBoundingBox(position)
) {

    var count: Int = count.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /** Accumulated simulation age in seconds for spinning and sine-wave bobbing. */
    var ageSeconds: Float = 0f
        private set

    /** Current Y-axis visual rotation angle in degrees. */
    var rotationYawDegrees: Float = 0f
        private set

    /** Current Y-axis visual rotation angle in radians. */
    val rotationYawRadians: Float
        get() = (rotationYawDegrees * (PI / 180.0)).toFloat()

    /** Vertical floating + sine-wave bob offset applied when rendering this item. */
    var bobOffsetY: Float = FLOAT_BASE_OFFSET_Y
        private set

    /** Deterministic per-drop phase offset so neighboring item piles bob organically. */
    val bobPhaseOffset: Float = ((abs(position.x * 11.37f + position.z * 7.91f) % 1.0f) * (2.0 * PI)).toFloat()

    override val typeId: String
        get() = TYPE_ID

    init {
        refreshBoundingBox()
        updateAnimationState(0f)
    }

    /** Returns how many visual offset-spinning layers should be rendered (capped at 64). */
    fun visualRenderLayerCount(): Int = count.coerceIn(1, MAX_RENDER_STACK_LAYERS)

    /** Resolves the platform-independent texture key associated with [itemId], if any. */
    fun resolveTextureKey(): String {
        val block = ItemRegistry.get(itemId)?.associatedBlock
        return if (block != null && !block.isAir && block.topTexture.isNotEmpty()) {
            block.topTexture
        } else {
            ""
        }
    }

    override fun updateRenderState(reuse: EntityRenderState?): ItemDropRenderState {
        val state = (reuse as? ItemDropRenderState) ?: ItemDropRenderState()
        state.centerX = (boundingBox.minX + boundingBox.maxX) * 0.5f
        state.centerY = (boundingBox.minY + boundingBox.maxY) * 0.5f
        state.centerZ = (boundingBox.minZ + boundingBox.maxZ) * 0.5f
        state.scale = WIDTH
        state.yawRadians = rotationYawRadians
        state.bobOffsetY = bobOffsetY
        state.textureKey = resolveTextureKey()
        state.stackCount = count
        state.renderLayerCount = visualRenderLayerCount()
        return state
    }

    /** Absorbs [other]'s entire item count into this entity and marks [other] removed. */
    fun absorbStack(other: ItemDropEntity) {
        if (other === this || other.isRemoved) return
        val combined = this.count.toLong() + other.count.toLong()
        this.count = combined.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        other.isRemoved = true
    }

    override fun writeExtraData(): Map<String, Any?> {
        return mapOf(
            "itemId" to itemId,
            "count" to count
        )
    }

    override fun readExtraData(data: Map<String, Any?>) {
        val rawCount = data["count"]
        val parsed = when (rawCount) {
            is Number -> rawCount.toInt()
            is String -> rawCount.toIntOrNull() ?: 1
            else -> 1
        }
        count = parsed.coerceAtLeast(1)
    }

    /**
     * Advances physics, spinning, sine bobbing, and resolves vertical collisions with solid blocks.
     * Uses bounded substeps and swept vertical bounds without thrashing [boundingBox]
     * per substep; updates cached AABB once at the end of the tick.
     */
    override fun tick(world: World, deltaTime: Float) {
        if (isRemoved || deltaTime <= 0f) return
        if (position.y < VOID_DESPAWN_Y || !position.y.isFinite()) {
            isRemoved = true
            return
        }

        updateAnimationState(deltaTime)

        val projectedEndVy = (velocity.y - GRAVITY * deltaTime).coerceAtLeast(TERMINAL_VELOCITY_Y)
        val maxSpeedY = maxOf(abs(velocity.y), abs(projectedEndVy))
        val displacementSteps = ceil((maxSpeedY * deltaTime) / MAX_SUBSTEP_DISPLACEMENT).toInt()
        val timeSteps = ceil(deltaTime / MAX_SUBSTEP_DT).toInt()
        val steps = maxOf(1, displacementSteps, timeSteps).coerceAtMost(MAX_SUBSTEPS)
        val stepDt = deltaTime / steps

        val eps = 1e-4f
        val minBx = floor(position.x - HALF_WIDTH + eps).toInt()
        val maxBx = floor(position.x + HALF_WIDTH - eps).toInt()
        val minBz = floor(position.z - HALF_DEPTH + eps).toInt()
        val maxBz = floor(position.z + HALF_DEPTH - eps).toInt()

        for (step in 0 until steps) {
            if (isRemoved) break

            val prevMinY = position.y
            val prevMaxY = position.y + HEIGHT

            velocity.y -= GRAVITY * stepDt
            if (velocity.y < TERMINAL_VELOCITY_Y) {
                velocity.y = TERMINAL_VELOCITY_Y
            }
            position.x += velocity.x * stepDt
            position.y += velocity.y * stepDt
            position.z += velocity.z * stepDt

            resolveVerticalCollisions(world, prevMinY, prevMaxY, minBx, maxBx, minBz, maxBz)
        }

        tickCount++
        refreshBoundingBox()
    }

    private fun updateAnimationState(deltaTime: Float) {
        if (deltaTime > 0f) {
            ageSeconds += deltaTime
        }
        rotationYawDegrees = (ageSeconds * SPIN_DEGREES_PER_SEC) % 360f
        bobOffsetY = FLOAT_BASE_OFFSET_Y + sin(ageSeconds * BOB_RAD_PER_SEC + bobPhaseOffset) * BOB_AMPLITUDE
    }

    private fun resolveVerticalCollisions(
        world: World,
        prevMinY: Float,
        prevMaxY: Float,
        minBx: Int,
        maxBx: Int,
        minBz: Int,
        maxBz: Int
    ) {
        val eps = 1e-4f
        val curMinY = position.y
        val curMaxY = position.y + HEIGHT

        val sweptMinY = minOf(prevMinY, curMinY)
        val sweptMaxY = maxOf(prevMaxY, curMaxY)
        val minBy = floor(sweptMinY).toInt().coerceIn(0, world.sizeY - 1)
        val maxBy = floor(sweptMaxY - eps).toInt().coerceIn(0, world.sizeY - 1)
        if (maxBy < minBy) return

        if (velocity.y <= 0f) {
            var hitFloorY = Float.NEGATIVE_INFINITY

            for (bx in minBx..maxBx) {
                for (bz in minBz..maxBz) {
                    for (by in maxBy downTo minBy) {
                        val block = world.getBlock(bx, by, bz)
                        if (!isBlockSolid(block)) continue

                        val blockTopY = by + 1.0f
                        val crossedFromAbove = prevMinY >= blockTopY - eps && curMinY <= blockTopY
                        val overlapsBlock = curMinY < blockTopY && curMaxY > by.toFloat()

                        if (crossedFromAbove || overlapsBlock) {
                            if (blockTopY > hitFloorY) {
                                hitFloorY = blockTopY
                            }
                        }
                    }
                }
            }

            if (hitFloorY.isFinite()) {
                position.y = hitFloorY
                velocity.y = 0f
            }
        } else {
            var hitCeilingY = Float.POSITIVE_INFINITY

            for (bx in minBx..maxBx) {
                for (bz in minBz..maxBz) {
                    for (by in minBy..maxBy) {
                        val block = world.getBlock(bx, by, bz)
                        if (!isBlockSolid(block)) continue

                        val blockBottomY = by.toFloat()
                        val crossedFromBelow = prevMaxY <= blockBottomY + eps && curMaxY >= blockBottomY
                        val overlapsBlock = curMinY < (by + 1.0f) && curMaxY > blockBottomY

                        if (crossedFromBelow || overlapsBlock) {
                            if (blockBottomY < hitCeilingY) {
                                hitCeilingY = blockBottomY
                            }
                        }
                    }
                }
            }

            if (hitCeilingY.isFinite()) {
                position.y = hitCeilingY - HEIGHT
                velocity.y = 0f
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

        const val SPIN_DEGREES_PER_SEC: Float = 90.0f
        const val BOB_RAD_PER_SEC: Float = 3.2f
        const val BOB_AMPLITUDE: Float = 0.05f
        const val FLOAT_BASE_OFFSET_Y: Float = 0.10f
        const val MAX_RENDER_STACK_LAYERS: Int = 64

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
