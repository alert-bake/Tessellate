package com.nitrous.tessellate.registry

import java.util.Locale

/**
 * Built-in vanilla content namespaces for Tessellate.
 *
 * All three namespaces MUST be available in both debug and release builds.
 * Content MUST NOT be stripped merely because it belongs to "Debug" or "Developer".
 */
object Namespaces {
    /** Debug-related content (e.g. "Debug:debugbug") */
    const val DEBUG = "Debug"

    /** Standard vanilla game content (e.g. "Tessellate:stone") */
    const val TESSELLATE = "Tessellate"

    /** Migrated prototype placeholder content (e.g. "prototesselate:stone") */
    const val PROTOTESSELLATE = "prototesselate"

    /** Developer-specific content (e.g. "Developer:thing_that_goes_brrr") */
    const val DEVELOPER = "Developer"

    /** Set of canonical built-in namespaces */
    val BUILT_IN_NAMESPACES: Set<String> = setOf(DEBUG, TESSELLATE, DEVELOPER, PROTOTESSELLATE)

    private val NORMALIZED_TO_CANONICAL: Map<String, String> =
        BUILT_IN_NAMESPACES.associateBy { it.lowercase(Locale.ROOT) } + mapOf(
            "tesselate" to TESSELLATE,
            "prototessellate" to PROTOTESSELLATE
        )

    /**
     * Checks if the given namespace is one of the built-in namespaces (case-insensitive).
     */
    fun isBuiltIn(namespace: String): Boolean {
        return NORMALIZED_TO_CANONICAL.containsKey(namespace.lowercase(Locale.ROOT))
    }

    /**
     * Returns the canonical casing for a built-in namespace, or null if not a built-in.
     */
    fun getCanonicalBuiltIn(namespace: String): String? {
        return NORMALIZED_TO_CANONICAL[namespace.lowercase(Locale.ROOT)]
    }
}
