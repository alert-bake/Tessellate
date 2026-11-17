package com.nitrous.tessellate.command

import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.math.Vec3
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
            else -> {
                val msg = definition.response?.takeIf { it.isNotBlank() }
                    ?: "Executed /$name"
                CommandResult(true, msg)
            }
        }
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
            player.velY = 0f
        }
        return CommandResult(
            success = true,
            message = if (player.isFlying) "Flight enabled" else "Flight disabled"
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
        val forwardX: Float
        val forwardY: Float
        val forwardZ: Float
        if (camera != null && camera.isFirstPerson) {
            forwardX = camera.forwardX
            forwardY = camera.forwardY
            forwardZ = camera.forwardZ
        } else {
            val pitchRad = Math.toRadians(player.pitch.toDouble())
            val yawRad = Math.toRadians(player.yaw.toDouble())
            forwardX = (sin(yawRad) * cos(pitchRad)).toFloat()
            forwardY = sin(pitchRad).toFloat()
            forwardZ = (-cos(yawRad) * cos(pitchRad)).toFloat()
        }

        val spawnPos = Vec3(
            x = player.posX + forwardX * 3.0f,
            y = (player.eyeY + forwardY * 3.0f).coerceAtLeast(1.0f),
            z = player.posZ + forwardZ * 3.0f
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
}
