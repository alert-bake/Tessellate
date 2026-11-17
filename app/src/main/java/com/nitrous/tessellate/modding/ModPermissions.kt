package com.nitrous.tessellate.modding

import java.util.concurrent.ConcurrentHashMap

/**
 * Standard protected permission scopes enforced for Lua mods.
 */
object ModPermissions {
    const val SETTINGS_VANILLA_WRITE = "settings.vanilla.write"
    const val COMMANDS_EXECUTE = "commands.execute"
    const val PLAYER_CHEATS = "player.cheats"
    const val WORLD_MODIFY = "world.modify"
    const val RENDERER_CONTROL = "renderer.control"
    const val SYSTEM_IO = "system.io"

    val ALL_PROTECTED = setOf(
        SETTINGS_VANILLA_WRITE,
        COMMANDS_EXECUTE,
        PLAYER_CHEATS,
        WORLD_MODIFY,
        RENDERER_CONTROL,
        SYSTEM_IO
    )
}

/**
 * Parsed metadata and permission list from a mod's `permissions.yml`.
 */
data class ParsedPermissionsYml(
    val modId: String? = null,
    val displayName: String? = null,
    val permissions: Set<String> = emptySet(),
    val descriptions: Map<String, String> = emptyMap()
)

/**
 * Lightweight parser for mod `permissions.yml` manifests.
 */
object PermissionsYmlParser {

    fun parse(yamlText: String?): ParsedPermissionsYml {
        if (yamlText.isNullOrBlank()) return ParsedPermissionsYml()

        var modId: String? = null
        var displayName: String? = null
        val perms = LinkedHashSet<String>()
        val descriptions = LinkedHashMap<String, String>()

        var inPermissionsSection = false
        var inDescriptionsSection = false

        val lines = yamlText.lines()
        for (rawLine in lines) {
            val noComment = stripComment(rawLine).trimEnd()
            if (noComment.isBlank()) continue

            val trimmed = noComment.trim()
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }

            if (indent == 0) {
                if (trimmed.startsWith("mod_id:") || trimmed.startsWith("id:")) {
                    inPermissionsSection = false
                    inDescriptionsSection = false
                    modId = trimmed.substringAfter(':').trim().unquote()
                    continue
                }
                if (trimmed.startsWith("name:") || trimmed.startsWith("display_name:")) {
                    inPermissionsSection = false
                    inDescriptionsSection = false
                    displayName = trimmed.substringAfter(':').trim().unquote()
                    continue
                }
                if (trimmed.startsWith("permissions:")) {
                    inPermissionsSection = true
                    inDescriptionsSection = false
                    val inline = trimmed.substringAfter(':').trim()
                    if (inline.startsWith("[") && inline.endsWith("]")) {
                        inline.substring(1, inline.length - 1)
                            .split(',')
                            .map { it.trim().unquote() }
                            .filter { it.isNotEmpty() }
                            .forEach { perms.add(it) }
                        inPermissionsSection = false
                    }
                    continue
                }
                if (trimmed.startsWith("descriptions:") || trimmed.startsWith("reasons:")) {
                    inPermissionsSection = false
                    inDescriptionsSection = true
                    continue
                }
                inPermissionsSection = false
                inDescriptionsSection = false
                continue
            }

            if (inPermissionsSection) {
                if (trimmed.startsWith("-")) {
                    val value = trimmed.removePrefix("-").trim().unquote()
                    if (value.isNotEmpty()) {
                        perms.add(value)
                    }
                } else if (trimmed.contains(':')) {
                    val key = trimmed.substringBefore(':').trim().unquote()
                    val value = trimmed.substringAfter(':').trim().lowercase()
                    if (key.isNotEmpty() && (value == "true" || value == "required" || value == "1")) {
                        perms.add(key)
                    }
                }
            } else if (inDescriptionsSection && trimmed.contains(':')) {
                val key = trimmed.substringBefore(':').trim().unquote()
                val value = trimmed.substringAfter(':').trim().unquote()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    descriptions[key] = value
                }
            }
        }

        return ParsedPermissionsYml(
            modId = modId,
            displayName = displayName,
            permissions = perms,
            descriptions = descriptions
        )
    }

    private fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '\'' && !inDouble) inSingle = !inSingle
            else if (c == '"' && !inSingle) inDouble = !inDouble
            else if (c == '#' && !inSingle && !inDouble) return line.substring(0, i)
        }
        return line
    }

    private fun String.unquote(): String {
        if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
            return substring(1, length - 1)
        }
        return this
    }
}

/**
 * Static code analysis report produced when inspecting a mod's Lua source before execution.
 */
data class CodeInspectionResult(
    val detectedPermissions: Set<String>,
    val disallowedSandboxTokens: Set<String>,
    val warnings: List<String>
)

/**
 * Inspects mod Lua source code for protected engine API usage and forbidden sandbox escapes.
 */
object ModCodeInspector {

    private val apiPatterns: List<Pair<Regex, String>> = listOf(
        Regex("""\bCommands\s*\.\s*execute\b""") to ModPermissions.COMMANDS_EXECUTE,
        Regex("""\bPlayer\s*\.\s*(setFlying|setInstaMine|giveItem)\b""") to ModPermissions.PLAYER_CHEATS,
        Regex("""\bWorld\s*\.\s*(setBlock|vaporizeChunk)\b""") to ModPermissions.WORLD_MODIFY,
        Regex("""\bRenderer\s*\.\s*(flushGPU|setHitboxes)\b""") to ModPermissions.RENDERER_CONTROL,
        Regex("""\bSystem\s*\.\s*(openAppFolder|clearCache|getFilesDir)\b""") to ModPermissions.SYSTEM_IO,
        Regex("""\bSettings\s*\.\s*set\s*\(\s*["']tessellate:""") to ModPermissions.SETTINGS_VANILLA_WRITE
    )

    private val disallowedTokens: List<Pair<Regex, String>> = listOf(
        Regex("""\bluajava\b""") to "luajava",
        Regex("""\bio\s*\.\s*(open|popen|lines)\b""") to "io.*",
        Regex("""\bos\s*\.\s*(execute|remove|rename)\b""") to "os.execute",
        Regex("""\b(dofile|loadfile)\b""") to "dofile/loadfile"
    )

    fun inspect(luaSource: String): CodeInspectionResult {
        val detected = LinkedHashSet<String>()
        val forbidden = LinkedHashSet<String>()
        val warnings = mutableListOf<String>()

        for ((regex, perm) in apiPatterns) {
            if (regex.containsMatchIn(luaSource)) {
                detected.add(perm)
            }
        }
        for ((regex, token) in disallowedTokens) {
            if (regex.containsMatchIn(luaSource)) {
                forbidden.add(token)
                warnings.add("Forbidden Lua API '$token' detected and blocked by sandbox.")
            }
        }

        return CodeInspectionResult(
            detectedPermissions = detected,
            disallowedSandboxTokens = forbidden,
            warnings = warnings
        )
    }
}

enum class ModApprovalStatus {
    NO_PROTECTED_APIS,
    PENDING_USER_APPROVAL,
    APPROVED,
    DENIED
}

/**
 * Scoped permission record for a single mod, combining `permissions.yml` declarations,
 * static code inspection findings, and user grant state.
 */
data class ModPermissionRecord(
    val modId: String,
    val displayName: String,
    val declaredPermissions: Set<String>,
    val detectedPermissions: Set<String>,
    val missingDeclaredPermissions: Set<String>,
    val grantedPermissions: Set<String>,
    val deniedPermissions: Set<String>,
    val disallowedTokens: Set<String>,
    val status: ModApprovalStatus
) {
    val allRequiredPermissions: Set<String>
        get() = declaredPermissions + detectedPermissions

    val userPromptSummary: String
        get() = buildString {
            append("Mod '$displayName' ($modId)")
            if (allRequiredPermissions.isEmpty()) {
                append(": no protected permissions required.")
            } else {
                append(" requests: ${allRequiredPermissions.sorted().joinToString(", ")}")
            }
            if (missingDeclaredPermissions.isNotEmpty()) {
                append(" [UNDECLARED in permissions.yml: ${missingDeclaredPermissions.sorted().joinToString(", ")}]")
            }
        }
}

/**
 * Per-mod scoped permission manager enforcing least-privilege runtime checks.
 * Permissions granted to one mod NEVER bleed into any other mod.
 */
class ModPermissionManager {

    private val lock = Any()
    private val records = ConcurrentHashMap<String, ModPermissionRecord>()
    private val runtimeDenials = mutableListOf<String>()

    fun inspectAndRegisterMod(
        modId: String,
        displayName: String = modId,
        luaSource: String,
        permissionsYmlText: String? = null,
        autoApproveDeclared: Boolean = false
    ): ModPermissionRecord {
        val parsed = PermissionsYmlParser.parse(permissionsYmlText)
        val inspection = ModCodeInspector.inspect(luaSource)
        val resolvedName = parsed.displayName?.takeIf { it.isNotBlank() } ?: displayName

        val declared = parsed.permissions
        val detected = inspection.detectedPermissions
        val missingDeclared = detected - declared
        val allRequired = declared + detected

        val granted = if (autoApproveDeclared) declared.toSet() else emptySet()
        val status = when {
            allRequired.isEmpty() -> ModApprovalStatus.NO_PROTECTED_APIS
            autoApproveDeclared && missingDeclared.isEmpty() -> ModApprovalStatus.APPROVED
            else -> ModApprovalStatus.PENDING_USER_APPROVAL
        }

        val record = ModPermissionRecord(
            modId = modId,
            displayName = resolvedName,
            declaredPermissions = declared,
            detectedPermissions = detected,
            missingDeclaredPermissions = missingDeclared,
            grantedPermissions = granted,
            deniedPermissions = emptySet(),
            disallowedTokens = inspection.disallowedSandboxTokens,
            status = status
        )
        records[modId] = record
        return record
    }

    fun approveModPermissions(modId: String, allowUndeclaredDetected: Boolean = false): ModPermissionRecord? {
        synchronized(lock) {
            val current = records[modId] ?: return null
            val toGrant = if (allowUndeclaredDetected) {
                current.declaredPermissions + current.detectedPermissions
            } else {
                current.declaredPermissions
            }
            val updated = current.copy(
                grantedPermissions = toGrant,
                deniedPermissions = emptySet(),
                status = ModApprovalStatus.APPROVED
            )
            records[modId] = updated
            return updated
        }
    }

    fun denyModPermissions(modId: String): ModPermissionRecord? {
        synchronized(lock) {
            val current = records[modId] ?: return null
            val updated = current.copy(
                grantedPermissions = emptySet(),
                deniedPermissions = current.allRequiredPermissions,
                status = ModApprovalStatus.DENIED
            )
            records[modId] = updated
            return updated
        }
    }

    fun hasPermission(modId: String, permission: String): Boolean {
        val rec = records[modId] ?: return false
        return rec.grantedPermissions.contains(permission)
    }

    fun requirePermission(modId: String, permission: String) {
        if (!hasPermission(modId, permission)) {
            val msg = "Permission denied for mod '$modId': '$permission' is not declared/approved."
            synchronized(runtimeDenials) {
                runtimeDenials.add(msg)
            }
            throw SecurityException(msg)
        }
    }

    fun getRecord(modId: String): ModPermissionRecord? = records[modId]

    fun getAllRecords(): List<ModPermissionRecord> =
        records.values.sortedBy { it.modId }

    fun getPendingApprovalRecords(): List<ModPermissionRecord> =
        records.values.filter { it.status == ModApprovalStatus.PENDING_USER_APPROVAL }.sortedBy { it.modId }

    fun getRuntimeDenials(): List<String> = synchronized(runtimeDenials) { runtimeDenials.toList() }

    fun clear() {
        records.clear()
        synchronized(runtimeDenials) {
            runtimeDenials.clear()
        }
    }
}
