package com.nitrous.tessellate.settings

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Central repository of structured setting categories, validated [SettingDefinition]s,
 * and persistence synchronization for both Vanilla Settings/Debug Options and Lua Mods.
 */
class SettingsRegistry(
    val references: SettingReferenceRegistry = SettingReferenceRegistry()
) {
    private val lock = Any()
    private val categories = LinkedHashMap<String, SettingCategory>()
    private val settings = LinkedHashMap<String, SettingDefinition>()
    private val errors = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var onPersistSetting: ((SettingDefinition, Any?) -> Unit)? = null
    var onResetDefaults: (() -> Unit)? = null

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun notifyListeners() {
        for (listener in listeners) {
            listener.invoke()
        }
    }

    fun getRegistrationErrors(): List<String> = errors.toList()

    fun registerCategory(category: SettingCategory, callerModId: String? = null): SettingValidationResult {
        val cleanId = category.id.trim()
        if (cleanId.isEmpty()) {
            return recordError("Category ID cannot be blank.")
        }
        synchronized(lock) {
            val existing = categories[cleanId]
            if (existing != null) {
                if (existing.ownerModId == null && callerModId != null) {
                    return recordError("Mod '$callerModId' cannot overwrite vanilla category '$cleanId'.")
                }
                if (existing.ownerModId != null && existing.ownerModId != callerModId) {
                    return recordError("Mod '${callerModId ?: "vanilla"}' cannot overwrite category '$cleanId' owned by '${existing.ownerModId}'.")
                }
            }
            categories[cleanId] = category.copy(id = cleanId, ownerModId = callerModId)
        }
        notifyListeners()
        return SettingValidationResult(true)
    }

    fun registerSetting(
        definition: SettingDefinition,
        callerModId: String? = null
    ): SettingValidationResult {
        val cleanId = definition.id.trim()
        if (cleanId.isEmpty()) {
            return recordError("Setting ID cannot be blank.")
        }
        if (definition.label.isBlank()) {
            return recordError("Setting '$cleanId' must define a display label.")
        }

        val normalizedDef = definition.copy(
            id = cleanId,
            ownerModId = callerModId
        )

        synchronized(lock) {
            val existing = settings[cleanId]
            if (existing != null) {
                if (existing.ownerModId == null && callerModId != null) {
                    return recordError("Mod '$callerModId' cannot overwrite vanilla setting '$cleanId'.")
                }
                if (existing.ownerModId != null && existing.ownerModId != callerModId) {
                    return recordError("Mod '${callerModId ?: "vanilla"}' cannot overwrite setting '$cleanId' owned by '${existing.ownerModId}'.")
                }
            }

            if (!categories.containsKey(normalizedDef.categoryId)) {
                return recordError("Setting '$cleanId' references unknown category '${normalizedDef.categoryId}'.")
            }

            val refCheck = references.validate(normalizedDef)
            if (!refCheck.isValid) {
                return recordError(refCheck.errorMessage ?: "Invalid reference for '$cleanId'.")
            }

            val ref = references.get(normalizedDef.referenceId)
            if (callerModId != null && ref?.origin == ReferenceOrigin.VANILLA && ref.ownerModId != callerModId) {
                // Allowed only if explicitly marked compatible; mods should normally use their own refs
                // unless permission-checked at the Lua bridge.
            }

            settings[cleanId] = normalizedDef
        }
        notifyListeners()
        return SettingValidationResult(true)
    }

    fun unregisterMod(modId: String) {
        synchronized(lock) {
            val settingIter = settings.entries.iterator()
            while (settingIter.hasNext()) {
                if (settingIter.next().value.ownerModId == modId) {
                    settingIter.remove()
                }
            }
            val catIter = categories.entries.iterator()
            while (catIter.hasNext()) {
                if (catIter.next().value.ownerModId == modId) {
                    catIter.remove()
                }
            }
        }
        references.unregisterModReferences(modId)
        notifyListeners()
    }

    fun getCategory(id: String): SettingCategory? = synchronized(lock) { categories[id] }

    fun getCategories(
        screen: SettingScreenTarget,
        includeEmpty: Boolean = false
    ): List<SettingCategory> = synchronized(lock) {
        categories.values
            .filter { it.screen == screen }
            .filter { cat ->
                includeEmpty || settings.values.any { def ->
                    def.categoryId == cat.id && (def.visibleWhen?.invoke() != false)
                }
            }
            .sortedWith(compareBy<SettingCategory> { it.order }.thenBy { it.label.lowercase() })
    }

    fun getSetting(id: String): SettingDefinition? = synchronized(lock) { settings[id] }

    fun getSettingsForCategory(categoryId: String, visibleOnly: Boolean = true): List<SettingDefinition> =
        synchronized(lock) {
            settings.values
                .filter { it.categoryId == categoryId }
                .filter { !visibleOnly || (it.visibleWhen?.invoke() != false) }
                .sortedWith(compareBy<SettingDefinition> { it.order }.thenBy { it.label.lowercase() })
        }

    fun getAllSettings(): List<SettingDefinition> = synchronized(lock) { settings.values.toList() }

    fun getValue(settingId: String): Any? {
        val def = getSetting(settingId) ?: return null
        val ref = references.get(def.referenceId) ?: return def.defaultValue
        return try {
            when (ref) {
                is RegisteredReference.PropertyReference -> ref.getter() ?: def.defaultValue
                is RegisteredReference.CommandReference -> ref.stateGetter?.invoke() ?: def.defaultValue
                is RegisteredReference.ActionReference -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed reading setting '$settingId' via '${def.referenceId}': ${t.message}")
            def.defaultValue
        }
    }

    fun setValue(settingId: String, rawValue: Any?, persist: Boolean = true): SettingValidationResult {
        val def = getSetting(settingId)
            ?: return SettingValidationResult(false, "Unknown setting '$settingId'.")
        val ref = references.get(def.referenceId)
            ?: return SettingValidationResult(false, "Missing reference '${def.referenceId}' for setting '$settingId'.")

        val sanitizedValue: Any? = when (def.controlType) {
            SettingControlType.TOGGLE -> coerceBoolean(rawValue, def.defaultValue as? Boolean ?: false)
            SettingControlType.SLIDER -> {
                val numeric = coerceDouble(rawValue)
                    ?: return SettingValidationResult(false, "Slider setting '$settingId' requires a numeric value.")
                val min = def.min ?: Double.NEGATIVE_INFINITY
                val max = def.max ?: Double.POSITIVE_INFINITY
                val clamped = numeric.coerceIn(min, max)
                val step = def.step
                if (step != null && step > 0.0) {
                    val steps = ((clamped - min) / step).roundToInt()
                    (min + steps * step).coerceIn(min, max)
                } else {
                    clamped
                }
            }
            SettingControlType.SELECTOR -> {
                val str = rawValue?.toString()
                    ?: return SettingValidationResult(false, "Selector setting '$settingId' requires a non-null option.")
                val matched = def.options.firstOrNull { it.value == str }
                    ?: return SettingValidationResult(
                        false,
                        "Invalid selector option '$str' for '$settingId'. Valid: ${def.options.map { it.value }}"
                    )
                if (ref.valueType == ReferenceValueType.NUMBER) {
                    matched.value.toDoubleOrNull() ?: matched.value
                } else {
                    matched.value
                }
            }
            SettingControlType.BUTTON -> {
                return SettingValidationResult(false, "Button setting '$settingId' does not store a value; invoke it instead.")
            }
        }

        return try {
            when (ref) {
                is RegisteredReference.PropertyReference -> {
                    if (persist && !def.isModSetting && valuesEqual(ref.getter(), sanitizedValue)) {
                        return SettingValidationResult(true)
                    }
                    ref.setter(sanitizedValue)
                }
                is RegisteredReference.CommandReference -> {
                    if (def.controlType == SettingControlType.TOGGLE && ref.stateGetter != null) {
                        val currentBool = ref.stateGetter.invoke() as? Boolean ?: false
                        val desiredBool = sanitizedValue as Boolean
                        if (currentBool != desiredBool) {
                            ref.commandExecutor(ref.commandLine)
                        }
                    } else if (ref.stateSetter != null) {
                        ref.stateSetter.invoke(sanitizedValue)
                    } else {
                        ref.commandExecutor(ref.commandLine)
                    }
                }
                is RegisteredReference.ActionReference -> {
                    return SettingValidationResult(false, "Cannot assign value to action reference '${def.referenceId}'.")
                }
            }

            if (persist && def.persist) {
                onPersistSetting?.invoke(def, sanitizedValue)
            }
            notifyListeners()
            SettingValidationResult(true)
        } catch (t: Throwable) {
            SettingValidationResult(false, "Failed applying setting '$settingId': ${t.message}")
        }
    }

    fun toggleValue(settingId: String): SettingValidationResult {
        val current = getValue(settingId) as? Boolean ?: false
        return setValue(settingId, !current, persist = true)
    }

    fun cycleSelector(settingId: String, step: Int = 1): SettingValidationResult {
        val def = getSetting(settingId)
            ?: return SettingValidationResult(false, "Unknown setting '$settingId'.")
        if (def.options.isEmpty()) {
            return SettingValidationResult(false, "Selector '$settingId' has no options.")
        }
        val currentStr = formatSelectorCompareValue(getValue(settingId))
        val idx = def.options.indexOfFirst { it.value == currentStr }.let { if (it < 0) 0 else it }
        val nextIdx = Math.floorMod(idx + step, def.options.size)
        return setValue(settingId, def.options[nextIdx].value, persist = true)
    }

    fun invokeAction(settingId: String): ReferenceActionResult {
        val def = getSetting(settingId)
            ?: return ReferenceActionResult(false, "Unknown setting '$settingId'.")
        val ref = references.get(def.referenceId)
            ?: return ReferenceActionResult(false, "Missing reference '${def.referenceId}'.")

        val result = try {
            when (ref) {
                is RegisteredReference.ActionReference -> ref.action()
                is RegisteredReference.CommandReference -> {
                    val cmdRes = ref.commandExecutor(ref.commandLine)
                    ReferenceActionResult(cmdRes.success, cmdRes.message)
                }
                is RegisteredReference.PropertyReference -> {
                    ReferenceActionResult(false, "Reference '${def.referenceId}' is a property, not an action.")
                }
            }
        } catch (t: Throwable) {
            ReferenceActionResult(false, t.message ?: "Action invocation failed.")
        }
        notifyListeners()
        return result
    }

    fun resetToDefaults(includeModSettings: Boolean = false) {
        onResetDefaults?.invoke()
        val defs = synchronized(lock) { settings.values.toList() }
        for (def in defs) {
            if (!def.persist || def.controlType == SettingControlType.BUTTON) continue
            if (def.isModSetting && !includeModSettings) continue
            if (!def.isModSetting && onResetDefaults != null) continue
            val defaultVal = def.defaultValue ?: continue
            setValue(def.id, defaultVal, persist = false)
        }
        notifyListeners()
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) {
            return kotlin.math.abs(a.toDouble() - b.toDouble()) < 1e-5
        }
        return a == b
    }

    fun formatValueDisplay(settingId: String): String {
        val def = getSetting(settingId) ?: return ""
        val value = getValue(settingId)
        def.valueFormatter?.let { return it(value) }
        return when (def.controlType) {
            SettingControlType.TOGGLE -> if (value == true) "ON" else "OFF"
            SettingControlType.SLIDER -> {
                val num = coerceDouble(value) ?: return value?.toString().orEmpty()
                val step = def.step ?: 1.0
                if (step >= 1.0) num.roundToInt().toString() else "%.2f".format(num)
            }
            SettingControlType.SELECTOR -> {
                val raw = formatSelectorCompareValue(value)
                def.options.firstOrNull { it.value == raw }?.label ?: raw
            }
            SettingControlType.BUTTON -> def.label
        }
    }

    private fun recordError(msg: String): SettingValidationResult {
        errors.add(msg)
        Log.w(TAG, msg)
        return SettingValidationResult(false, msg)
    }

    private fun coerceBoolean(raw: Any?, fallback: Boolean): Boolean {
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> fallback
            }
            else -> fallback
        }
    }

    private fun coerceDouble(raw: Any?): Double? {
        return when (raw) {
            is Number -> raw.toDouble().takeIf { it.isFinite() }
            is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            else -> null
        }
    }

    private fun formatSelectorCompareValue(value: Any?): String {
        return when (value) {
            is Double -> if (value % 1.0 == 0.0) value.roundToInt().toString() else value.toString()
            is Float -> if (value % 1.0f == 0f) value.roundToInt().toString() else value.toString()
            else -> value?.toString().orEmpty()
        }
    }

    companion object {
        private const val TAG = "SettingsRegistry"
    }
}
