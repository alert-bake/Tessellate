package com.nitrous.tessellate.settings

import com.squareup.moshi.JsonClass

/**
 * Persistent game configuration settings serialized to JSON.
 */
@JsonClass(generateAdapter = true)
data class GameSettings(
    var renderDistance: Int = 4,
    var sensitivity: Float = 0.22f,
    var fov: Float = 60.0f,
    var soundVolume: Float = 1.0f
)
