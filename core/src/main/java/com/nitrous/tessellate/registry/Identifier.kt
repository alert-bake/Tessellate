package com.nitrous.tessellate.registry

import java.util.Locale

/**
 * Exception thrown when an identifier cannot be parsed or validated.
 */
class MalformedIdentifierException(message: String) : IllegalArgumentException(message)

/**
 * Namespaced content identifier (`namespace:path`).
 *
 * Structure: "namespace:path" (e.g. "Tessellate:stone", "Debug:debugbug", "Developer:thing_that_goes_brrr").
 *
 * Characteristics:
 * - Case-insensitive lookup and comparison using locale-independent [Locale.ROOT] normalization.
 * - Exact canonical registered spelling is preserved for display, serialization, and inspection.
 * - Supports underscores and hyphens in namespaces and paths.
 * - Preferred vanilla path convention is "snake_case", but modders are not forced into it.
 */
class Identifier private constructor(
    val namespace: String,
    val path: String
) : Comparable<Identifier> {

    /** Locale-independent normalized namespace for comparison */
    val normalizedNamespace: String = namespace.lowercase(Locale.ROOT)

    /** Locale-independent normalized path for comparison */
    val normalizedPath: String = path.lowercase(Locale.ROOT)

    /** Canonical serialized representation ("namespace:path") */
    val canonicalString: String = "$namespace:$path"

    /** Normalized serialized representation ("normalizedNamespace:normalizedPath") */
    val normalizedString: String = "$normalizedNamespace:$normalizedPath"

    /**
     * Whether this identifier belongs to the standard vanilla "Tessellate" namespace.
     */
    val isVanilla: Boolean
        get() = normalizedNamespace == Namespaces.TESSELLATE.lowercase(Locale.ROOT)

    /**
     * Whether this identifier belongs to the vanilla "Debug" namespace.
     */
    val isDebug: Boolean
        get() = normalizedNamespace == Namespaces.DEBUG.lowercase(Locale.ROOT)

    /**
     * Whether this identifier belongs to the vanilla "Developer" namespace.
     */
    val isDeveloper: Boolean
        get() = normalizedNamespace == Namespaces.DEVELOPER.lowercase(Locale.ROOT)

    /**
     * Whether this identifier belongs to the migrated "prototesselate" namespace.
     */
    val isProtoTessellate: Boolean
        get() = normalizedNamespace == Namespaces.PROTOTESSELLATE.lowercase(Locale.ROOT)

    /**
     * Checks if the path conforms to the preferred vanilla "snake_case" convention.
     * Allowed: lower-case letters, digits, and underscores.
     */
    val isPreferredVanillaNaming: Boolean
        get() = SNAKE_CASE_REGEX.matches(path)

    override fun toString(): String = canonicalString

    fun toCanonicalString(): String = canonicalString

    fun toNormalizedString(): String = normalizedString

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identifier) return false
        return normalizedNamespace == other.normalizedNamespace &&
                normalizedPath == other.normalizedPath
    }

    override fun hashCode(): Int {
        var result = normalizedNamespace.hashCode()
        result = 31 * result + normalizedPath.hashCode()
        return result
    }

    override fun compareTo(other: Identifier): Int {
        val nsComp = normalizedNamespace.compareTo(other.normalizedNamespace)
        if (nsComp != 0) return nsComp
        return normalizedPath.compareTo(other.normalizedPath)
    }

    companion object {
        private val NAMESPACE_REGEX = Regex("^[a-zA-Z0-9_-]+$")
        private val PATH_REGEX = Regex("^[a-zA-Z0-9_\\-/]+$")
        private val SNAKE_CASE_REGEX = Regex("^[a-z0-9_]+$")

        /**
         * Validates namespace string according to identifier grammar.
         */
        fun isValidNamespace(namespace: String): Boolean {
            return namespace.isNotEmpty() && NAMESPACE_REGEX.matches(namespace)
        }

        /**
         * Validates path string according to identifier grammar.
         */
        fun isValidPath(path: String): Boolean {
            return path.isNotEmpty() && PATH_REGEX.matches(path)
        }

        /**
         * Creates an [Identifier] from explicit namespace and path components.
         * Built-in namespaces are canonicalized to their official casing (e.g., "tessellate" -> "Tessellate").
         *
         * @throws MalformedIdentifierException if either component fails grammar validation.
         */
        fun of(namespace: String, path: String): Identifier {
            if (namespace.isEmpty()) {
                throw MalformedIdentifierException("Identifier namespace cannot be empty (path='$path')")
            }
            if (path.isEmpty()) {
                throw MalformedIdentifierException("Identifier path cannot be empty (namespace='$namespace')")
            }
            if (!NAMESPACE_REGEX.matches(namespace)) {
                throw MalformedIdentifierException(
                    "Invalid namespace '$namespace'. Namespaces must contain only alphanumeric characters, underscores, or hyphens."
                )
            }
            if (!PATH_REGEX.matches(path)) {
                throw MalformedIdentifierException(
                    "Invalid path '$path'. Paths must contain only alphanumeric characters, underscores, hyphens, or forward slashes."
                )
            }

            // Canonicalize built-in namespaces if matched case-insensitively
            val canonicalNamespace = Namespaces.getCanonicalBuiltIn(namespace) ?: namespace
            return Identifier(canonicalNamespace, path)
        }

        /**
         * Parses a string in "namespace:path" format.
         *
         * @throws MalformedIdentifierException if the string is malformed or invalid.
         */
        fun parse(raw: String): Identifier {
            if (raw.isEmpty()) {
                throw MalformedIdentifierException("Identifier string cannot be empty")
            }
            val colonIndex = raw.indexOf(':')
            if (colonIndex == -1) {
                throw MalformedIdentifierException(
                    "Malformed identifier '$raw': missing ':' namespace separator. Expected format: 'namespace:path'."
                )
            }
            if (raw.indexOf(':', colonIndex + 1) != -1) {
                throw MalformedIdentifierException(
                    "Malformed identifier '$raw': contains multiple ':' separators. Only one ':' is permitted."
                )
            }

            val namespace = raw.substring(0, colonIndex)
            val path = raw.substring(colonIndex + 1)
            return of(namespace, path)
        }

        /**
         * Attempts to parse an identifier, returning null on any malformed input instead of throwing.
         */
        fun parseOrNull(raw: String?): Identifier? {
            if (raw.isNullOrBlank()) return null
            return try {
                parse(raw)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Compatibility parser with legacy identifier fallback.
         * Migrates legacy un-namespaced or uppercase IDs (e.g. "AIR", "GRASS", "DIRT", "STONE", "DEBUGBUG")
         * to canonical namespaced identifiers.
         */
        fun parseWithLegacyFallback(
            raw: String,
            defaultNamespace: String = Namespaces.TESSELLATE
        ): Identifier {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw MalformedIdentifierException("Identifier cannot be empty")
            }

            // If it contains a colon, parse normally
            if (trimmed.contains(':')) {
                return parse(trimmed)
            }

            // Legacy block mappings
            return when (trimmed.uppercase(Locale.ROOT)) {
                "AIR" -> of(Namespaces.TESSELLATE, "air")
                "GRASS" -> of(Namespaces.TESSELLATE, "grass")
                "DIRT" -> of(Namespaces.TESSELLATE, "dirt")
                "STONE" -> of(Namespaces.TESSELLATE, "stone")
                "PROTO_STONE" -> of(Namespaces.PROTOTESSELLATE, "stone")
                "DEBUGBUG" -> of(Namespaces.DEBUG, "debugbug")
                else -> {
                    // For any legacy un-namespaced string, map to default namespace with snake_case path
                    of(defaultNamespace, trimmed.lowercase(Locale.ROOT))
                }
            }
        }
    }
}
