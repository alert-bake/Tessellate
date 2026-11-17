package com.nitrous.tessellate.settings

/**
 * UI control types supported by the data-driven settings renderer.
 */
enum class SettingControlType(val wireName: String) {
    TOGGLE("toggle"),
    SLIDER("slider"),
    SELECTOR("selector"),
    BUTTON("button");

    companion object {
        fun fromWireName(raw: String?): SettingControlType? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

/**
 * Screen destination that owns a setting category/entry.
 */
enum class SettingScreenTarget {
    SETTINGS,
    DEBUG_OPTIONS
}

/**
 * Logical tab/group for organizing vanilla and mod settings.
 */
data class SettingCategory(
    val id: String,
    val label: String,
    val ownerModId: String? = null,
    val screen: SettingScreenTarget = SettingScreenTarget.SETTINGS,
    val order: Int = 100
)

/**
 * Choice item for [SettingControlType.SELECTOR] settings.
 */
data class SelectorOption(
    val value: String,
    val label: String
)

/**
 * Structured definition of a single data-driven setting control.
 */
data class SettingDefinition(
    val id: String,
    val label: String,
    val description: String? = null,
    val controlType: SettingControlType,
    val referenceId: String,
    val defaultValue: Any? = null,
    val categoryId: String,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<SelectorOption> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val confirmationMessage: String? = null,
    val persist: Boolean = (controlType != SettingControlType.BUTTON),
    val ownerModId: String? = null,
    val screen: SettingScreenTarget = SettingScreenTarget.SETTINGS,
    val order: Int = 100,
    val visibleWhen: (() -> Boolean)? = null,
    val valueFormatter: ((Any?) -> String)? = null
) {
    val isModSetting: Boolean
        get() = ownerModId != null
}

/**
 * Result of validating a setting definition or registered reference.
 */
data class SettingValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

/**
 * Outcome returned when invoking an action or command reference from a setting button or toggle.
 */
data class ReferenceActionResult(
    val success: Boolean,
    val message: String = ""
)
