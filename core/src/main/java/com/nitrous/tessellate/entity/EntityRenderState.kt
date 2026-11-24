package com.nitrous.tessellate.entity

import kotlin.math.cos
import kotlin.math.sin

/**
 * Platform-independent render snapshot contract produced by simulation entities in `:core`
 * and consumed by rendering backends in `:app` (or other targets) without coupling `:core`
 * to OpenGL or Android APIs.
 */
interface EntityRenderState {
    val centerX: Float
    val centerY: Float
    val centerZ: Float
    val scale: Float
    val yawRadians: Float
    val bobOffsetY: Float
    val textureKey: String
    val renderLayerCount: Int
}

/**
 * Render state snapshot for [ItemDropEntity], including orbital cluster offset math for up to 64
 * visual stack layers.
 */
data class ItemDropRenderState(
    override var centerX: Float = 0f,
    override var centerY: Float = 0f,
    override var centerZ: Float = 0f,
    override var scale: Float = ItemDropEntity.WIDTH,
    override var yawRadians: Float = 0f,
    override var bobOffsetY: Float = ItemDropEntity.FLOAT_BASE_OFFSET_Y,
    override var textureKey: String = "",
    var stackCount: Int = 1,
    override var renderLayerCount: Int = 1
) : EntityRenderState {

    /**
     * Computes world position `(out[0], out[1], out[2])` and Y-axis rotation `out[3]` for [layerIndex]
     * in `0 until renderLayerCount` without allocating objects.
     */
    fun writeLayerTransform(layerIndex: Int, outPosRot: FloatArray, outOffset: Int = 0) {
        var ox = centerX
        var oy = centerY + bobOffsetY
        var oz = centerZ
        var yaw = yawRadians

        if (layerIndex > 0) {
            val progress = layerIndex.toFloat() / ItemDropEntity.MAX_RENDER_STACK_LAYERS.toFloat()
            val radius = 0.025f + 0.085f * progress
            val orbitAngle = layerIndex * GOLDEN_ANGLE_RAD + yawRadians * 0.25f
            ox += cos(orbitAngle) * radius
            oz += sin(orbitAngle) * radius
            oy += ((layerIndex % 5) - 2) * 0.016f + progress * 0.04f
            yaw = yawRadians + layerIndex * 0.11f
        }

        outPosRot[outOffset] = ox
        outPosRot[outOffset + 1] = oy
        outPosRot[outOffset + 2] = oz
        outPosRot[outOffset + 3] = yaw
    }

    companion object {
        const val GOLDEN_ANGLE_RAD: Float = 2.3999632f
    }
}
