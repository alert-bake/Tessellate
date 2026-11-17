package com.nitrous.tessellate.settings

import com.squareup.moshi.JsonClass

/**
 * Persistent game configuration settings serialized to `files/settings.json`.
 */
@JsonClass(generateAdapter = true)
data class GameSettings(
    var renderDistance: Int = 4,
    var sensitivity: Float = 0.22f,
    var fov: Float = 60.0f,
    var soundVolume: Float = 1.0f,
    var maxFps: Int = 60,
    var viewBobbing: Boolean = true,
    var uiScale: Float = 1.0f,
    var invertYAxis: Boolean = false,
    var autoJump: Boolean = false,
    var showCoordinates: Boolean = true,
    var enableDebugOptions: Boolean = false,
    var showDebugHud: Boolean = true,
    var showHitboxes: Boolean = false,
    var modValues: Map<String, String> = emptyMap()
) {
    companion object {
        val VALID_MAX_FPS = setOf(0, 30, 60, 120)
    }
}
