package com.nitrous.tessellate.command

import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.registry.DuplicateIdentifierException
import com.nitrous.tessellate.registry.RegistryBootstrap
import com.nitrous.tessellate.registry.RegistryFrozenException
import com.nitrous.tessellate.scene.WorldScene
import java.util.Locale

/**
 * Registry for built-in and mod commands loaded from `assets/content/commands/`.
 */
object CommandRegistry {

    private val lock = Any()
    private val commands = LinkedHashMap<String, Command>()
    private val WHITESPACE_REGEX = Regex("\\s+")

    @Volatile
    private var frozen: Boolean = false

    val isFrozen: Boolean
        get() = frozen

    internal fun clearForBootstrap() {
        synchronized(lock) {
            commands.clear()
            frozen = false
        }
    }

    internal fun freeze() {
        synchronized(lock) {
            frozen = true
        }
    }

    fun register(command: Command): Command {
        val key = normalizeName(command.name)
        require(key.isNotEmpty()) { "Command name cannot be blank" }
        synchronized(lock) {
            if (frozen) {
                throw RegistryFrozenException(
                    "CommandRegistry is frozen; cannot register '/$key' after startup."
                )
            }
            if (commands.containsKey(key)) {
                throw DuplicateIdentifierException("Command '/$key' is already registered.")
            }
            commands[key] = command
            return command
        }
    }

    fun get(name: String): Command? {
        RegistryBootstrap.ensureInitialized()
        val key = normalizeName(name)
        return synchronized(lock) { commands[key] }
    }

    fun contains(name: String): Boolean = get(name) != null

    fun getAllCommands(): List<Command> {
        RegistryBootstrap.ensureInitialized()
        return synchronized(lock) { commands.values.toList() }
    }

    val size: Int
        get() {
            RegistryBootstrap.ensureInitialized()
            return synchronized(lock) { commands.size }
        }

    fun dispatch(
        rawInput: String,
        worldScene: WorldScene,
        camera: Camera3D? = null
    ): CommandResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return CommandResult(false, "Enter a command (e.g. /give tessellate:cobblestone 5)")
        }
        if (!trimmed.startsWith("/")) {
            return CommandResult(false, "Commands must start with '/'")
        }

        val tokens = trimmed.split(WHITESPACE_REGEX)
        val rawCommand = tokens[0].lowercase(Locale.ROOT)
        val key = normalizeName(rawCommand)
        val args = if (tokens.size > 1) tokens.subList(1, tokens.size) else emptyList()

        val command = get(key)
            ?: return CommandResult(false, "Unknown command: $rawCommand")

        val context = CommandContext(
            worldScene = worldScene,
            camera = camera,
            args = args,
            rawCommand = rawCommand
        )
        return synchronized(worldScene) {
            command.execute(context)
        }
    }

    private fun normalizeName(raw: String): String {
        return raw.trim().removePrefix("/").lowercase(Locale.ROOT)
    }
}
