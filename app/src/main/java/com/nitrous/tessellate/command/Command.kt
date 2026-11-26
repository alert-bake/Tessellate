package com.nitrous.tessellate.command

import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.registry.AndroidRegistryBootstrap
import com.nitrous.tessellate.registry.CommandDefinitionJson
import com.nitrous.tessellate.scene.WorldScene
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Outcome returned by executing a developer or mod command.
 */
data class CommandResult(
    val success: Boolean,
    val message: String
)

/**
 * Execution context passed to registered [Command] instances.
 */
data class CommandContext(
    val worldScene: WorldScene,
    val camera: Camera3D? = null,
    val args: List<String> = emptyList(),
    val rawCommand: String = ""
)

/**
 * Contract for a data-driven or programmatic console command.
 */
interface Command {
    val name: String
    val description: String
    val usage: String

    fun execute(context: CommandContext): CommandResult
}

/**
 * Command backed by a JSON definition loaded from `assets/content/commands/`.
 * Maps declared command actions to Kotlin execution logic via a `when(command)` block.
 */
class JsonCommand(
    val definition: CommandDefinitionJson
) : Command {

    override val name: String = definition.name.trim().removePrefix("/").lowercase(Locale.ROOT)
    override val description: String = definition.description
    override val usage: String = definition.usage.ifBlank { "/$name" }

    private val actionKey: String = (definition.action ?: name)
        .trim()
        .removePrefix("/")
        .lowercase(Locale.ROOT)

    override fun execute(context: CommandContext): CommandResult {
        return when (actionKey) {
            "give" -> handleGive(context.args, context.worldScene)
            "clear" -> handleClear(context.worldScene)
            "spawn" -> handleSpawn(context.args, context.worldScene, context.camera)
            "instamine" -> handleInstaMine(context.worldScene)
            "fly" -> handleFly(context.worldScene)
            "fpptest" -> handleFppTest(context.args, context.worldScene, context.camera)
            "cbopreload" -> handleCboPreload(context.worldScene)
            else -> {
                val msg = definition.response?.takeIf { it.isNotBlank() }
                    ?: "Executed /$name"
                CommandResult(true, msg)
            }
        }
    }

    private fun handleCboPreload(worldScene: WorldScene): CommandResult {
        val result = AndroidRegistryBootstrap.reloadCborCache()
        if (result.success) {
            worldScene.syncSelectedItemFromInventory()
            worldScene.worldRenderer.markAllChunksDirty()
        }
        return CommandResult(
            success = result.success,
            message = result.message
        )
    }

    private fun handleGive(args: List<String>, worldScene: WorldScene): CommandResult {
        val itemId = args.getOrNull(0)
            ?: return CommandResult(false, "Usage: /give <item_id> [count]")

        val item = ItemRegistry.get(itemId)
            ?: return CommandResult(false, "Unknown item: $itemId")

        val count = if (args.size >= 2) {
            val parsed = args[1].toIntOrNull()
            if (parsed == null || parsed <= 0) {
                return CommandResult(false, "Invalid count: ${args[1]}")
            }
            parsed
        } else {
            1
        }

        val canonicalId = item.identifier.toCanonicalString()
        val leftover = worldScene.player.inventory.addItem(canonicalId, count)
        worldScene.syncSelectedItemFromInventory()

        val added = count - leftover
        return if (leftover == 0) {
            CommandResult(true, "Gave $added x $canonicalId")
        } else {
            CommandResult(added > 0, "Gave $added x $canonicalId (inventory full, $leftover dropped)")
        }
    }

    private fun handleClear(worldScene: WorldScene): CommandResult {
        worldScene.player.inventory.clear()
        worldScene.syncSelectedItemFromInventory()
        return CommandResult(true, "Cleared player inventory")
    }

    private fun handleInstaMine(worldScene: WorldScene): CommandResult {
        val player = worldScene.player
        player.isInstaMine = !player.isInstaMine
        return CommandResult(
            success = true,
            message = if (player.isInstaMine) "InstaMine enabled" else "InstaMine disabled"
        )
    }

    private fun handleFly(worldScene: WorldScene): CommandResult {
        val player = worldScene.player
        player.isFlying = !player.isFlying
        if (player.isFlying) {
            player.velY = 0.0
        }
        return CommandResult(
            success = true,
            message = if (player.isFlying) "Flight enabled" else "Flight disabled"
        )
    }

    private fun handleFppTest(
        args: List<String>,
        worldScene: WorldScene,
        camera: Camera3D?
    ): CommandResult {
        if (args.size !in 1..2) {
            return CommandResult(false, "Usage: /fpptest <axis1> [axis2] (valid axes: X, Y, Z)")
        }

        val selectedAxes = linkedSetOf<String>()
        for (rawArg in args) {
            val normalized = rawArg.trim().uppercase(Locale.ROOT)
            if (normalized != "X" && normalized != "Y" && normalized != "Z") {
                return CommandResult(false, "Invalid axis '$rawArg'. Valid axes: X, Y, Z")
            }
            if (!selectedAxes.add(normalized)) {
                return CommandResult(false, "Duplicate axis '$normalized'. Specify distinct axes from X, Y, Z")
            }
        }

        val player = worldScene.player
        val newX = if ("X" in selectedAxes) player.posX * FPP_TEST_MULTIPLIER else player.posX
        val newY = if ("Y" in selectedAxes) player.posY * FPP_TEST_MULTIPLIER else player.posY
        val newZ = if ("Z" in selectedAxes) player.posZ * FPP_TEST_MULTIPLIER else player.posZ

        if (!newX.isFinite() || !newY.isFinite() || !newZ.isFinite()) {
            return CommandResult(false, "Coordinate overflow while multiplying by $FPP_TEST_MULTIPLIER")
        }

        player.posX = newX
        player.posY = newY
        player.posZ = newZ
        player.velX = 0.0
        player.velY = 0.0
        player.velZ = 0.0
        player.updateBoundingBox()

        camera?.setFirstPersonView(player.posX, player.eyeY, player.posZ, player.pitch, player.yaw)
        worldScene.world.streamChunksAround(player.posX, player.posZ, forceCheck = true)

        return CommandResult(
            success = true,
            message = String.format(
                Locale.US,
                "FPP test (%s) teleported to (%.2f, %.2f, %.2f)",
                selectedAxes.joinToString(", "),
                player.posX,
                player.posY,
                player.posZ
            )
        )
    }

    private fun handleSpawn(
        args: List<String>,
        worldScene: WorldScene,
        camera: Camera3D?
    ): CommandResult {
        val entityId = args.getOrNull(0)
            ?: return CommandResult(false, "Usage: /spawn <entity_id>")

        val player = worldScene.player
        val forwardX: Double
        val forwardY: Double
        val forwardZ: Double
        if (camera != null && camera.isFirstPerson) {
            forwardX = camera.forwardX.toDouble()
            forwardY = camera.forwardY.toDouble()
            forwardZ = camera.forwardZ.toDouble()
        } else {
            val pitchRad = Math.toRadians(player.pitch.toDouble())
            val yawRad = Math.toRadians(player.yaw.toDouble())
            forwardX = sin(yawRad) * cos(pitchRad)
            forwardY = sin(pitchRad)
            forwardZ = -cos(yawRad) * cos(pitchRad)
        }

        val spawnPos = Vec3(
            x = player.posX + forwardX * 3.0,
            y = (player.eyeY + forwardY * 3.0).coerceAtLeast(1.0),
            z = player.posZ + forwardZ * 3.0
        )

        val normalizedEntity = entityId.lowercase(Locale.ROOT)
        val spawnedEntity = when (normalizedEntity) {
            "item_drop", "tessellate:item_drop", "item", "drop" -> {
                val argItemId = args.getOrNull(1)
                val resolvedItemId = if (argItemId != null) {
                    ItemRegistry.get(argItemId)?.identifier?.toCanonicalString()
                        ?: return CommandResult(false, "Unknown item: $argItemId")
                } else {
                    ItemRegistry.DIRT.identifier.toCanonicalString()
                }
                ItemDropEntity(position = spawnPos, itemId = resolvedItemId)
            }
            else -> {
                val registeredItem = ItemRegistry.get(entityId)
                if (registeredItem != null) {
                    ItemDropEntity(
                        position = spawnPos,
                        itemId = registeredItem.identifier.toCanonicalString()
                    )
                } else {
                    return CommandResult(false, "Unknown entity: $entityId")
                }
            }
        }

        worldScene.world.spawnEntity(spawnedEntity)
        return CommandResult(
            true,
            "Spawned $entityId at (%.1f, %.1f, %.1f)".format(spawnPos.x, spawnPos.y, spawnPos.z)
        )
    }

    companion object {
        const val FPP_TEST_MULTIPLIER: Double = 32676.0
    }
}
