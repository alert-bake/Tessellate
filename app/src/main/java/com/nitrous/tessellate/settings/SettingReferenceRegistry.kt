package com.nitrous.tessellate.settings

import com.nitrous.tessellate.command.CommandResult
import java.util.concurrent.ConcurrentHashMap

enum class ReferenceOrigin {
    VANILLA,
    MOD
}

enum class ReferenceValueType {
    BOOLEAN,
    NUMBER,
    STRING,
    ACTION,
    COMMAND
}

/**
 * Explicitly registered setting target (never uses arbitrary runtime reflection).
 */
sealed class RegisteredReference {
    abstract val id: String
    abstract val origin: ReferenceOrigin
    abstract val ownerModId: String?
    abstract val valueType: ReferenceValueType

    class PropertyReference(
        override val id: String,
        override val origin: ReferenceOrigin,
        override val ownerModId: String?,
        override val valueType: ReferenceValueType,
        val getter: () -> Any?,
        val setter: (Any?) -> Unit
    ) : RegisteredReference() {
        init {
            require(
                valueType == ReferenceValueType.BOOLEAN ||
                    valueType == ReferenceValueType.NUMBER ||
                    valueType == ReferenceValueType.STRING
            ) {
                "PropertyReference '$id' cannot use valueType $valueType"
            }
        }
    }

    class ActionReference(
        override val id: String,
        override val origin: ReferenceOrigin,
        override val ownerModId: String?,
        val action: () -> ReferenceActionResult
    ) : RegisteredReference() {
        override val valueType: ReferenceValueType = ReferenceValueType.ACTION
    }

    class CommandReference(
        override val id: String,
        override val origin: ReferenceOrigin,
        override val ownerModId: String?,
        val commandLine: String,
        val stateType: ReferenceValueType? = null,
        val stateGetter: (() -> Any?)? = null,
        val stateSetter: ((Any?) -> Unit)? = null,
        val commandExecutor: (String) -> CommandResult
    ) : RegisteredReference() {
        override val valueType: ReferenceValueType
            get() = stateType ?: ReferenceValueType.COMMAND
    }
}

/**
 * Central validated registry mapping reference IDs (e.g. `Camera3D.fov`, `Renderer.flushGPU`)
 * to strongly typed property accessors, actions, or command handlers.
 */
class SettingReferenceRegistry {

    private val references = ConcurrentHashMap<String, RegisteredReference>()

    fun registerProperty(
        id: String,
        valueType: ReferenceValueType,
        ownerModId: String? = null,
        getter: () -> Any?,
        setter: (Any?) -> Unit
    ): SettingValidationResult {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            return SettingValidationResult(false, "Reference ID must not be blank.")
        }
        val origin = if (ownerModId == null) ReferenceOrigin.VANILLA else ReferenceOrigin.MOD
        val ownershipError = checkOwnership(cleanId, origin, ownerModId)
        if (ownershipError != null) {
            return SettingValidationResult(false, ownershipError)
        }
        if (valueType != ReferenceValueType.BOOLEAN &&
            valueType != ReferenceValueType.NUMBER &&
            valueType != ReferenceValueType.STRING
        ) {
            return SettingValidationResult(
                false,
                "Invalid property valueType '$valueType' for reference '$cleanId'."
            )
        }
        references[cleanId] = RegisteredReference.PropertyReference(
            id = cleanId,
            origin = origin,
            ownerModId = ownerModId,
            valueType = valueType,
            getter = getter,
            setter = setter
        )
        return SettingValidationResult(true)
    }

    fun registerAction(
        id: String,
        ownerModId: String? = null,
        action: () -> ReferenceActionResult
    ): SettingValidationResult {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            return SettingValidationResult(false, "Action reference ID must not be blank.")
        }
        val origin = if (ownerModId == null) ReferenceOrigin.VANILLA else ReferenceOrigin.MOD
        val ownershipError = checkOwnership(cleanId, origin, ownerModId)
        if (ownershipError != null) {
            return SettingValidationResult(false, ownershipError)
        }
        references[cleanId] = RegisteredReference.ActionReference(
            id = cleanId,
            origin = origin,
            ownerModId = ownerModId,
            action = action
        )
        return SettingValidationResult(true)
    }

    fun registerCommand(
        id: String,
        commandLine: String,
        ownerModId: String? = null,
        stateType: ReferenceValueType? = null,
        stateGetter: (() -> Any?)? = null,
        stateSetter: ((Any?) -> Unit)? = null,
        commandExecutor: (String) -> CommandResult
    ): SettingValidationResult {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            return SettingValidationResult(false, "Command reference ID must not be blank.")
        }
        val origin = if (ownerModId == null) ReferenceOrigin.VANILLA else ReferenceOrigin.MOD
        val ownershipError = checkOwnership(cleanId, origin, ownerModId)
        if (ownershipError != null) {
            return SettingValidationResult(false, ownershipError)
        }
        references[cleanId] = RegisteredReference.CommandReference(
            id = cleanId,
            origin = origin,
            ownerModId = ownerModId,
            commandLine = commandLine,
            stateType = stateType,
            stateGetter = stateGetter,
            stateSetter = stateSetter,
            commandExecutor = commandExecutor
        )
        return SettingValidationResult(true)
    }

    private fun checkOwnership(id: String, origin: ReferenceOrigin, ownerModId: String?): String? {
        val existing = references[id]
        if (existing != null) {
            if (existing.origin == ReferenceOrigin.VANILLA && origin == ReferenceOrigin.MOD) {
                return "Mod '$ownerModId' cannot overwrite vanilla reference '$id'."
            }
            if (existing.origin == ReferenceOrigin.MOD && existing.ownerModId != ownerModId) {
                return "Mod '${ownerModId ?: "unknown"}' cannot overwrite reference '$id' owned by '${existing.ownerModId}'."
            }
        }
        return null
    }

    fun get(id: String): RegisteredReference? = references[id.trim()]

    fun isVanillaReference(id: String): Boolean = get(id)?.origin == ReferenceOrigin.VANILLA

    fun isModReference(id: String): Boolean = get(id)?.origin == ReferenceOrigin.MOD

    fun unregisterModReferences(modId: String) {
        val iter = references.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.origin == ReferenceOrigin.MOD && entry.value.ownerModId == modId) {
                iter.remove()
            }
        }
    }

    fun validate(definition: SettingDefinition): SettingValidationResult {
        val refId = definition.referenceId.trim()
        if (refId.isEmpty()) {
            return SettingValidationResult(
                false,
                "Setting '${definition.id}' specifies an empty reference."
            )
        }
        val ref = references[refId]
            ?: return SettingValidationResult(
                false,
                "Setting '${definition.id}' references unregistered target '$refId'."
            )

        return when (definition.controlType) {
            SettingControlType.TOGGLE -> {
                if (ref.valueType != ReferenceValueType.BOOLEAN) {
                    SettingValidationResult(
                        false,
                        "Toggle setting '${definition.id}' is incompatible with reference '$refId' (${ref.valueType}); expected BOOLEAN."
                    )
                } else {
                    SettingValidationResult(true)
                }
            }
            SettingControlType.SLIDER -> {
                if (ref !is RegisteredReference.PropertyReference || ref.valueType != ReferenceValueType.NUMBER) {
                    SettingValidationResult(
                        false,
                        "Slider setting '${definition.id}' is incompatible with reference '$refId' (${ref.valueType}); expected NUMBER property."
                    )
                } else {
                    val min = definition.min
                    val max = definition.max
                    if (min == null || max == null || !min.isFinite() || !max.isFinite() || min >= max) {
                        SettingValidationResult(
                            false,
                            "Slider setting '${definition.id}' must define valid numeric bounds with min < max."
                        )
                    } else {
                        SettingValidationResult(true)
                    }
                }
            }
            SettingControlType.SELECTOR -> {
                if (ref !is RegisteredReference.PropertyReference ||
                    (ref.valueType != ReferenceValueType.STRING && ref.valueType != ReferenceValueType.NUMBER)
                ) {
                    SettingValidationResult(
                        false,
                        "Selector setting '${definition.id}' is incompatible with reference '$refId' (${ref.valueType}); expected STRING or NUMBER property."
                    )
                } else if (definition.options.isEmpty()) {
                    SettingValidationResult(
                        false,
                        "Selector setting '${definition.id}' must specify at least one option."
                    )
                } else {
                    SettingValidationResult(true)
                }
            }
            SettingControlType.BUTTON -> {
                if (ref !is RegisteredReference.ActionReference && ref !is RegisteredReference.CommandReference) {
                    SettingValidationResult(
                        false,
                        "Button setting '${definition.id}' is incompatible with property reference '$refId'; expected ACTION or COMMAND."
                    )
                } else {
                    SettingValidationResult(true)
                }
            }
        }
    }

    fun getAllReferences(): Map<String, RegisteredReference> = references.toMap()
}
