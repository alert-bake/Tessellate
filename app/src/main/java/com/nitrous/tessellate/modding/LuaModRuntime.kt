package com.nitrous.tessellate.modding

import android.util.Log
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.settings.ReferenceActionResult
import com.nitrous.tessellate.settings.ReferenceOrigin
import com.nitrous.tessellate.settings.ReferenceValueType
import com.nitrous.tessellate.settings.SelectorOption
import com.nitrous.tessellate.settings.SettingCategory
import com.nitrous.tessellate.settings.SettingControlType
import com.nitrous.tessellate.settings.SettingDefinition
import com.nitrous.tessellate.settings.SettingScreenTarget
import com.nitrous.tessellate.settings.SettingsRegistry
import com.nitrous.tessellate.world.BlockType
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ModLoadResult(
    val modId: String,
    val success: Boolean,
    val permissionRecord: ModPermissionRecord,
    val registeredSettings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

/**
 * Sandboxed LuaJ runtime integrating mod settings, categories, custom references,
 * and permission-guarded engine APIs into [SettingsRegistry].
 */
class LuaModRuntime(
    private val engine: TessellateEngine,
    val settingsRegistry: SettingsRegistry = engine.settingsRegistry,
    val permissionManager: ModPermissionManager = ModPermissionManager()
) {
    private val modGlobals = ConcurrentHashMap<String, Globals>()
    private val modErrors = ConcurrentHashMap<String, MutableList<String>>()

    /**
     * Scans `Android/data/com.nitrous.tessellate/files/mods/` for mod subdirectories
     * containing `permissions.yml` and `.lua` scripts and loads them cleanly.
     */
    fun loadModsFromDisk(): List<ModLoadResult> {
        val modsDir = File(WorldSaveManager.getBaseFilesDir(engine.appContext), "mods")
        if (!modsDir.exists() || !modsDir.isDirectory) return emptyList()

        val modDirs = modsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        val results = mutableListOf<ModLoadResult>()
        for (dir in modDirs) {
            val luaFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".lua", ignoreCase = true) }
                ?.sortedWith(compareBy<File> { it.name != "init.lua" }.thenBy { it.name })
                .orEmpty()
            if (luaFiles.isEmpty()) continue

            val combinedSource = luaFiles.joinToString("\n") { it.readText(Charsets.UTF_8) }
            val permsFile = File(dir, "permissions.yml")
            val permsText = if (permsFile.isFile) permsFile.readText(Charsets.UTF_8) else null
            results.add(
                loadMod(
                    modId = dir.name,
                    luaSource = combinedSource,
                    permissionsYml = permsText,
                    displayName = dir.name,
                    autoApproveDeclared = false
                )
            )
        }
        return results
    }

    /**
     * Inspects [luaSource] and [permissionsYml], enforces the Lua sandbox, executes the script
     * in a dedicated per-mod [Globals] environment, and tracks any registered settings/errors.
     */
    fun loadMod(
        modId: String,
        luaSource: String,
        permissionsYml: String? = null,
        displayName: String = modId,
        autoApproveDeclared: Boolean = false
    ): ModLoadResult {
        val cleanModId = modId.trim()
        require(cleanModId.isNotEmpty()) { "modId must not be blank" }

        // Clear previous registrations if reloading this specific mod (other mods stay untouched)
        settingsRegistry.unregisterMod(cleanModId)
        val errors = mutableListOf<String>()
        modErrors[cleanModId] = errors

        val permRecord = permissionManager.inspectAndRegisterMod(
            modId = cleanModId,
            displayName = displayName,
            luaSource = luaSource,
            permissionsYmlText = permissionsYml,
            autoApproveDeclared = autoApproveDeclared
        )

        if (permRecord.disallowedTokens.isNotEmpty()) {
            errors.add("Blocked forbidden sandbox tokens: ${permRecord.disallowedTokens.joinToString(", ")}")
        }

        val registeredSettings = mutableListOf<String>()
        val globals = createSandboxedGlobals(cleanModId, registeredSettings, errors)
        modGlobals[cleanModId] = globals

        var executionSucceeded = true
        try {
            val chunk = globals.load(luaSource, "$cleanModId.lua")
            chunk.call()
        } catch (t: Throwable) {
            executionSucceeded = false
            val msg = t.message ?: t::class.java.simpleName
            errors.add("Lua error in mod '$cleanModId': $msg")
            Log.w(TAG, "Mod '$cleanModId' execution failed: $msg", t)
        }

        return ModLoadResult(
            modId = cleanModId,
            success = executionSucceeded && errors.isEmpty(),
            permissionRecord = permRecord,
            registeredSettings = registeredSettings.toList(),
            errors = errors.toList()
        )
    }

    /**
     * Executes an inline Lua snippet inside an already loaded mod's sandboxed [Globals].
     */
    fun evalInMod(modId: String, snippet: String): LuaValue {
        val globals = modGlobals[modId]
            ?: throw IllegalArgumentException("Mod '$modId' is not loaded.")
        return globals.load(snippet, "$modId:eval").call()
    }

    private fun createSandboxedGlobals(
        modId: String,
        registeredSettings: MutableList<String>,
        errors: MutableList<String>
    ): Globals {
        val globals = Globals()
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(CoroutineLib())
        globals.load(JseMathLib())
        LoadState.install(globals)
        LuaC.install(globals)

        // Strip file-loading globals from the sandbox
        globals.set("dofile", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("luajava", LuaValue.NIL)

        globals.set("Settings", buildSettingsApiTable(modId, registeredSettings, errors))
        globals.set("Commands", buildCommandsApiTable(modId))
        globals.set("Player", buildPlayerApiTable(modId))
        globals.set("World", buildWorldApiTable(modId))
        globals.set("Renderer", buildRendererApiTable(modId))
        globals.set("System", buildSystemApiTable(modId))
        return globals
    }

    private fun buildSettingsApiTable(
        modId: String,
        registeredSettings: MutableList<String>,
        errors: MutableList<String>
    ): LuaTable {
        val table = LuaTable()

        // Settings.registerCategory(id, labelOrTable)
        table.set("registerCategory", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val catId = arg1.checkjstring().trim()
                val label: String
                val screen: SettingScreenTarget
                val order: Int

                if (arg2.istable()) {
                    val t = arg2.checktable()
                    label = t.get("label").optjstring(catId)
                    val screenRaw = t.get("screen").optjstring("settings").lowercase()
                    screen = if (screenRaw == "debug" || screenRaw == "debug_options") {
                        SettingScreenTarget.DEBUG_OPTIONS
                    } else {
                        SettingScreenTarget.SETTINGS
                    }
                    order = t.get("order").optint(200)
                } else {
                    label = arg2.optjstring(catId)
                    screen = SettingScreenTarget.SETTINGS
                    order = 200
                }

                val res = settingsRegistry.registerCategory(
                    SettingCategory(
                        id = catId,
                        label = label,
                        ownerModId = modId,
                        screen = screen,
                        order = order
                    ),
                    callerModId = modId
                )
                if (!res.isValid) {
                    val err = res.errorMessage ?: "Failed registering category '$catId'"
                    errors.add(err)
                    throw LuaError(err)
                }
                return LuaValue.TRUE
            }
        })

        // Settings.registerReference(refId, defTable)
        table.set("registerReference", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val refId = arg1.checkjstring().trim()
                val cfg = arg2.checktable()
                val typeRaw = cfg.get("type").optjstring(cfg.get("valueType").optjstring("number")).lowercase()

                val res = when (typeRaw) {
                    "action", "button", "command" -> {
                        val fn = resolveActionFunction(cfg)
                            ?: throw LuaError("Action reference '$refId' requires an 'action' or 'run' function.")
                        settingsRegistry.references.registerAction(
                            id = refId,
                            ownerModId = modId
                        ) {
                            try {
                                val out = fn.call()
                                if (out.isstring()) {
                                    ReferenceActionResult(true, out.tojstring())
                                } else {
                                    ReferenceActionResult(true, "Executed '$refId'")
                                }
                            } catch (t: Throwable) {
                                ReferenceActionResult(false, t.message ?: "Lua action failed")
                            }
                        }
                    }
                    "boolean", "bool", "toggle" -> registerLuaPropertyRef(refId, modId, ReferenceValueType.BOOLEAN, cfg)
                    "string", "selector" -> registerLuaPropertyRef(refId, modId, ReferenceValueType.STRING, cfg)
                    "number", "float", "int", "slider" -> registerLuaPropertyRef(refId, modId, ReferenceValueType.NUMBER, cfg)
                    else -> throw LuaError("Unsupported reference type '$typeRaw' for '$refId'")
                }

                if (!res.isValid) {
                    val err = res.errorMessage ?: "Invalid reference registration '$refId'"
                    errors.add(err)
                    throw LuaError(err)
                }
                return LuaValue.TRUE
            }
        })

        // Settings.register(settingId, configTable)
        table.set("register", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val settingId = arg1.checkjstring().trim()
                val cfg = arg2.checktable()

                val rawType = cfg.get("type").optjstring(cfg.get("controlType").optjstring("")).trim()
                val controlType = SettingControlType.fromWireName(rawType)
                    ?: run {
                        val err = "Unsupported setting control type '$rawType' for '$settingId'."
                        errors.add(err)
                        throw LuaError(err)
                    }

                val label = cfg.get("label").optjstring(settingId)
                val description = cfg.get("description").let { if (it.isnil()) null else it.tojstring() }

                val categoryId = cfg.get("category").let { catVal ->
                    if (!catVal.isnil() && catVal.tojstring().isNotBlank()) {
                        catVal.tojstring().trim()
                    } else {
                        val defaultModCat = "mod:$modId"
                        if (settingsRegistry.getCategory(defaultModCat) == null) {
                            settingsRegistry.registerCategory(
                                SettingCategory(
                                    id = defaultModCat,
                                    label = modId.replaceFirstChar { it.uppercase() },
                                    ownerModId = modId,
                                    screen = SettingScreenTarget.SETTINGS,
                                    order = 200
                                ),
                                callerModId = modId
                            )
                        }
                        defaultModCat
                    }
                }

                val screenRaw = cfg.get("screen").optjstring("settings").lowercase()
                val screen = if (screenRaw == "debug" || screenRaw == "debug_options") {
                    SettingScreenTarget.DEBUG_OPTIONS
                } else {
                    settingsRegistry.getCategory(categoryId)?.screen ?: SettingScreenTarget.SETTINGS
                }

                val hasInlineGetter = cfg.get("get").isfunction()
                val hasInlineSetter = cfg.get("set").isfunction()
                val inlineAction = resolveActionFunction(cfg)
                val explicitRef = cfg.get("reference").let { if (it.isnil()) null else it.tojstring().trim() }

                val resolvedRefId: String = when {
                    !explicitRef.isNullOrEmpty() -> {
                        val existingRef = settingsRegistry.references.get(explicitRef)
                        if (existingRef != null && existingRef.origin == ReferenceOrigin.VANILLA) {
                            permissionManager.requirePermission(modId, ModPermissions.SETTINGS_VANILLA_WRITE)
                        }
                        explicitRef
                    }
                    controlType == SettingControlType.BUTTON && inlineAction != null -> {
                        val syntheticRef = "$modId:action.${settingId.replace(':', '_')}"
                        val regRes = settingsRegistry.references.registerAction(
                            id = syntheticRef,
                            ownerModId = modId
                        ) {
                            try {
                                val out = inlineAction.call()
                                ReferenceActionResult(true, if (out.isstring()) out.tojstring() else "Executed $label")
                            } catch (t: Throwable) {
                                ReferenceActionResult(false, t.message ?: "Lua action error")
                            }
                        }
                        if (!regRes.isValid) {
                            errors.add(regRes.errorMessage ?: "Failed creating action ref")
                            throw LuaError(regRes.errorMessage ?: "Failed creating action ref")
                        }
                        syntheticRef
                    }
                    hasInlineGetter || hasInlineSetter -> {
                        val syntheticRef = "$modId:prop.${settingId.replace(':', '_')}"
                        val propType = when (controlType) {
                            SettingControlType.TOGGLE -> ReferenceValueType.BOOLEAN
                            SettingControlType.SLIDER -> ReferenceValueType.NUMBER
                            SettingControlType.SELECTOR -> ReferenceValueType.STRING
                            SettingControlType.BUTTON -> {
                                val err = "Button setting '$settingId' cannot use property getter/setter; provide 'action' or action 'reference'."
                                errors.add(err)
                                throw LuaError(err)
                            }
                        }
                        val regRes = registerLuaPropertyRef(syntheticRef, modId, propType, cfg)
                        if (!regRes.isValid) {
                            errors.add(regRes.errorMessage ?: "Failed creating property ref")
                            throw LuaError(regRes.errorMessage ?: "Failed creating property ref")
                        }
                        syntheticRef
                    }
                    else -> {
                        val err = "Setting '$settingId' must provide either a registered 'reference' or Lua callback functions."
                        errors.add(err)
                        throw LuaError(err)
                    }
                }

                val defaultVal: Any? = when (controlType) {
                    SettingControlType.TOGGLE -> cfg.get("default").let { if (it.isnil()) false else it.toboolean() }
                    SettingControlType.SLIDER -> cfg.get("default").let { if (it.isnil()) cfg.get("min").optdouble(0.0) else it.todouble() }
                    SettingControlType.SELECTOR -> cfg.get("default").let { if (it.isnil()) null else it.tojstring() }
                    SettingControlType.BUTTON -> null
                }

                val minVal = cfg.get("min").let { if (it.isnil()) null else it.todouble() }
                val maxVal = cfg.get("max").let { if (it.isnil()) null else it.todouble() }
                val stepVal = cfg.get("step").let { if (it.isnil()) null else it.todouble() }

                val optionsList = mutableListOf<SelectorOption>()
                val optTable = cfg.get("options")
                if (optTable.istable()) {
                    val tbl = optTable.checktable()
                    val len = tbl.length()
                    for (i in 1..len) {
                        val entry = tbl.get(i)
                        if (entry.istable()) {
                            val et = entry.checktable()
                            val v = et.get("value").tojstring()
                            val l = et.get("label").optjstring(v)
                            optionsList.add(SelectorOption(v, l))
                        } else if (!entry.isnil()) {
                            val v = entry.tojstring()
                            optionsList.add(SelectorOption(v, v))
                        }
                    }
                }

                val requiresConfirmation = cfg.get("requiresConfirmation").optboolean(false)
                val confirmationMessage = cfg.get("confirmationMessage").let { if (it.isnil()) null else it.tojstring() }
                val persist = cfg.get("persist").optboolean(controlType != SettingControlType.BUTTON)

                val definition = SettingDefinition(
                    id = settingId,
                    label = label,
                    description = description,
                    controlType = controlType,
                    referenceId = resolvedRefId,
                    defaultValue = defaultVal,
                    categoryId = categoryId,
                    min = minVal,
                    max = maxVal,
                    step = stepVal,
                    options = optionsList,
                    requiresConfirmation = requiresConfirmation,
                    confirmationMessage = confirmationMessage,
                    persist = persist,
                    ownerModId = modId,
                    screen = screen,
                    order = cfg.get("order").optint(200)
                )

                val regResult = settingsRegistry.registerSetting(definition, callerModId = modId)
                if (!regResult.isValid) {
                    val err = regResult.errorMessage ?: "Failed registering setting '$settingId'"
                    errors.add(err)
                    throw LuaError(err)
                }

                // Restore previously persisted mod setting value if present in settings.json
                val savedModRaw = engine.currentSettings.modValues[settingId]
                if (savedModRaw != null && controlType != SettingControlType.BUTTON) {
                    settingsRegistry.setValue(settingId, savedModRaw, persist = false)
                }

                registeredSettings.add(settingId)
                return LuaValue.TRUE
            }
        })

        // Settings.get(settingId)
        table.set("get", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val settingId = arg.checkjstring().trim()
                val v = settingsRegistry.getValue(settingId)
                return toLuaValue(v)
            }
        })

        // Settings.set(settingId, value)
        table.set("set", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val settingId = arg1.checkjstring().trim()
                val def = settingsRegistry.getSetting(settingId)
                    ?: throw LuaError("Unknown setting '$settingId'")
                if (!def.isModSetting) {
                    permissionManager.requirePermission(modId, ModPermissions.SETTINGS_VANILLA_WRITE)
                } else if (def.ownerModId != modId) {
                    throw LuaError("Mod '$modId' cannot modify setting '$settingId' owned by '${def.ownerModId}'.")
                }
                val kotlinVal = toKotlinValue(arg2)
                val res = settingsRegistry.setValue(settingId, kotlinVal, persist = true)
                if (!res.isValid) {
                    throw LuaError(res.errorMessage ?: "Failed setting '$settingId'")
                }
                return LuaValue.TRUE
            }
        })

        return table
    }

    private fun registerLuaPropertyRef(
        refId: String,
        modId: String,
        valueType: ReferenceValueType,
        cfg: LuaTable
    ) = run {
        val getFn = cfg.get("get").let { if (it.isfunction()) it.checkfunction() else null }
        val setFn = cfg.get("set").let { if (it.isfunction()) it.checkfunction() else null }
        if (getFn == null && setFn == null) {
            throw LuaError("Property reference '$refId' requires 'get' and/or 'set' Lua callbacks.")
        }
        var localFallback: Any? = toKotlinValue(cfg.get("default"))

        settingsRegistry.references.registerProperty(
            id = refId,
            valueType = valueType,
            ownerModId = modId,
            getter = {
                if (getFn != null) {
                    toKotlinValue(getFn.call())
                } else {
                    localFallback
                }
            },
            setter = { newVal ->
                localFallback = newVal
                if (setFn != null) {
                    setFn.call(toLuaValue(newVal))
                }
            }
        )
    }

    private fun buildCommandsApiTable(modId: String): LuaTable {
        val table = LuaTable()
        table.set("execute", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.COMMANDS_EXECUTE)
                val cmd = arg.checkjstring()
                val res = engine.executeCommand(cmd)
                val out = LuaTable()
                out.set("success", LuaValue.valueOf(res.success))
                out.set("message", LuaValue.valueOf(res.message))
                return out
            }
        })
        return table
    }

    private fun buildPlayerApiTable(modId: String): LuaTable {
        val table = LuaTable()
        table.set("isFlying", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(engine.worldScene.player.isFlying)
        })
        table.set("isInstaMine", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(engine.worldScene.player.isInstaMine)
        })
        table.set("setFlying", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.PLAYER_CHEATS)
                engine.worldScene.player.isFlying = arg.checkboolean()
                return LuaValue.TRUE
            }
        })
        table.set("setInstaMine", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.PLAYER_CHEATS)
                engine.worldScene.player.isInstaMine = arg.checkboolean()
                return LuaValue.TRUE
            }
        })
        table.set("giveItem", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.PLAYER_CHEATS)
                val itemId = arg1.checkjstring()
                val count = arg2.optint(1).coerceAtLeast(1)
                val leftover = engine.worldScene.player.inventory.addItem(itemId, count)
                engine.worldScene.syncSelectedItemFromInventory()
                return LuaValue.valueOf(count - leftover)
            }
        })
        return table
    }

    private fun buildWorldApiTable(modId: String): LuaTable {
        val table = LuaTable()
        table.set("getRenderDistance", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(engine.worldScene.world.renderDistance)
        })
        table.set("vaporizeChunk", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.WORLD_MODIFY)
                val removed = engine.worldScene.vaporizeTargetedChunk(engine.camera)
                return LuaValue.valueOf(removed != null)
            }
        })
        table.set("setBlock", object : org.luaj.vm2.lib.VarArgFunction() {
            override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs {
                permissionManager.requirePermission(modId, ModPermissions.WORLD_MODIFY)
                val x = args.checkint(1)
                val y = args.checkint(2)
                val z = args.checkint(3)
                val blockId = args.checkjstring(4)
                val bt = BlockType.fromIdentifier(blockId) ?: return LuaValue.FALSE
                return LuaValue.valueOf(engine.worldScene.world.setBlock(x, y, z, bt))
            }
        })
        return table
    }

    private fun buildRendererApiTable(modId: String): LuaTable {
        val table = LuaTable()
        table.set("flushGPU", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.RENDERER_CONTROL)
                engine.worldScene.worldRenderer.requestSafeGpuFlush()
                return LuaValue.TRUE
            }
        })
        table.set("setHitboxes", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.RENDERER_CONTROL)
                engine.worldScene.worldRenderer.showHitboxes = arg.checkboolean()
                return LuaValue.TRUE
            }
        })
        return table
    }

    private fun buildSystemApiTable(modId: String): LuaTable {
        val table = LuaTable()
        table.set("clearCache", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.SYSTEM_IO)
                val res = engine.clearCache()
                return LuaValue.valueOf(res.deletedFiles)
            }
        })
        table.set("openAppFolder", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                permissionManager.requirePermission(modId, ModPermissions.SYSTEM_IO)
                return LuaValue.valueOf(engine.openAppFolder().success)
            }
        })
        return table
    }

    private fun resolveActionFunction(cfg: LuaTable): LuaFunction? {
        for (key in listOf("action", "onClick", "onPress", "run", "call")) {
            val v = cfg.get(key)
            if (v.isfunction()) return v.checkfunction()
        }
        return null
    }

    private fun toLuaValue(v: Any?): LuaValue {
        return when (v) {
            null -> LuaValue.NIL
            is Boolean -> LuaValue.valueOf(v)
            is Int -> LuaValue.valueOf(v)
            is Long -> LuaValue.valueOf(v.toDouble())
            is Float -> LuaValue.valueOf(v.toDouble())
            is Double -> LuaValue.valueOf(v)
            else -> LuaValue.valueOf(v.toString())
        }
    }

    private fun toKotlinValue(v: LuaValue): Any? {
        return when {
            v.isnil() -> null
            v.isboolean() -> v.toboolean()
            v.isint() -> v.toint()
            v.isnumber() -> v.todouble()
            else -> v.tojstring()
        }
    }

    fun getModErrors(modId: String): List<String> = modErrors[modId]?.toList().orEmpty()

    companion object {
        private const val TAG = "LuaModRuntime"
    }
}
