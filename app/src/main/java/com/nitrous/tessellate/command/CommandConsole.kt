package com.nitrous.tessellate.command

import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.scene.WorldScene

/**
 * Entry point for parsing and executing slash commands via [CommandRegistry].
 */
object CommandConsole {

    fun execute(
        rawInput: String,
        worldScene: WorldScene,
        camera: Camera3D? = null
    ): CommandResult {
        return CommandRegistry.dispatch(rawInput, worldScene, camera)
    }
}
