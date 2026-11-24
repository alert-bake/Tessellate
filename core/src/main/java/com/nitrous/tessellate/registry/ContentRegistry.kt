package com.nitrous.tessellate.registry

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Metadata representing a registered namespace and its ownership.
 */
data class NamespaceMetadata(
    val canonicalName: String,
    val owner: String,
    val isBuiltIn: Boolean
)

/**
 * Interface defining a frozen-after-startup content registry keyed by namespaced [Identifier].
 */
interface IContentRegistry<T> {
    /** The identifier of this registry itself (e.g. "Tessellate:blocks") */
    val registryId: Identifier

    /** Whether this registry is frozen against further registrations. */
    val isFrozen: Boolean

    /**
     * Freezes the registry, preventing any future registrations or namespace additions.
     */
    fun freeze()

    /**
     * Clears registered entries and resets freeze/sequence state for bootstrap initialization.
     */
    fun clearForBootstrap()

    /**
     * Allocates the next available sequential numeric ID without advancing past collisions.
     */
    fun nextNumericId(): Int

    /**
     * Registers a value with an identifier and auto-generated numeric ID.
     *
     * @throws RegistryFrozenException if the registry has already been frozen.
     * @throws DuplicateIdentifierException if the identifier (case-insensitive) is already registered.
     */
    fun register(identifier: Identifier, value: T): T

    /**
     * Registers a value with an identifier and explicit numeric ID (e.g., for byte ID compatibility).
     *
     * @throws RegistryFrozenException if the registry has already been frozen.
     * @throws DuplicateIdentifierException if the identifier is already registered.
     * @throws DuplicateNumericIdException if the numeric ID is already assigned.
     */
    fun register(identifier: Identifier, numericId: Int, value: T): T

    /**
     * Retrieves an entry by identifier (case-insensitive lookup).
     */
    fun get(identifier: Identifier): T?

    /**
     * Retrieves an entry by identifier string (case-insensitive lookup).
     */
    fun get(rawIdentifier: String): T?

    /**
     * Retrieves an entry by internal numeric ID.
     */
    fun getByNumericId(numericId: Int): T?

    /**
     * Resolves the canonical [Identifier] for a given identifier (case-insensitive).
     * For example, given "tessellate:stone", returns "Tessellate:stone".
     */
    fun getCanonicalIdentifier(identifier: Identifier): Identifier?

    /**
     * Resolves the canonical [Identifier] for a given string representation.
     */
    fun getCanonicalIdentifier(rawIdentifier: String): Identifier?

    /**
     * Returns the numeric ID associated with the identifier.
     */
    fun getNumericId(identifier: Identifier): Int?

    /**
     * Returns the [Identifier] associated with a numeric ID.
     */
    fun getIdentifierByNumericId(numericId: Int): Identifier?

    /**
     * Checks if an identifier is registered (case-insensitive).
     */
    fun contains(identifier: Identifier): Boolean

    /**
     * Checks if an identifier string is registered (case-insensitive).
     */
    fun contains(rawIdentifier: String): Boolean

    /**
     * Returns all registered identifiers with their canonical spelling preserved.
     */
    fun getRegisteredIdentifiers(): List<Identifier>

    /**
     * Returns a snapshot of all registered entries mapped by their canonical [Identifier].
     */
    fun getRegisteredEntries(): Map<Identifier, T>

    /**
     * Registers a new namespace with owner metadata.
     *
     * @throws RegistryFrozenException if the registry has already been frozen.
     */
    fun registerNamespace(namespace: String, owner: String = "Mod")

    /**
     * Checks if a namespace is registered (case-insensitive).
     */
    fun isNamespaceRegistered(namespace: String): Boolean

    /**
     * Returns all registered namespaces in their canonical spelling.
     */
    fun getRegisteredNamespaces(): Set<String>

    /** Number of registered entries */
    val size: Int
}

/**
 * Thread-safe implementation of [IContentRegistry] supporting dynamic runtime registration
 * during startup before freezing prior to world generation.
 */
class ContentRegistry<T>(
    override val registryId: Identifier,
    private val autoNamespaceRegistration: Boolean = true
) : IContentRegistry<T> {

    private val entries = ConcurrentHashMap<Identifier, T>()
    private val canonicalIdentifiers = ConcurrentHashMap<Identifier, Identifier>()
    private val identifierToNumericId = ConcurrentHashMap<Identifier, Int>()
    private val numericIdToIdentifier = ConcurrentHashMap<Int, Identifier>()
    private val namespaces = ConcurrentHashMap<String, NamespaceMetadata>()

    private val numericIdSequence = AtomicInteger(0)

    @Volatile
    private var frozen: Boolean = false

    override val isFrozen: Boolean
        get() = frozen

    init {
        registerBuiltInNamespaces()
    }

    private fun registerBuiltInNamespaces() {
        registerNamespaceInternal(Namespaces.DEBUG, "Vanilla Engine", isBuiltIn = true)
        registerNamespaceInternal(Namespaces.TESSELLATE, "Vanilla Engine", isBuiltIn = true)
        registerNamespaceInternal(Namespaces.PROTOTESSELLATE, "Vanilla Engine", isBuiltIn = true)
        registerNamespaceInternal(Namespaces.DEVELOPER, "Vanilla Engine", isBuiltIn = true)
    }

    private fun registerNamespaceInternal(namespace: String, owner: String, isBuiltIn: Boolean) {
        val normalized = namespace.lowercase(Locale.ROOT)
        namespaces[normalized] = NamespaceMetadata(
            canonicalName = namespace,
            owner = owner,
            isBuiltIn = isBuiltIn
        )
    }

    @Synchronized
    override fun freeze() {
        frozen = true
    }

    @Synchronized
    override fun clearForBootstrap() {
        entries.clear()
        canonicalIdentifiers.clear()
        identifierToNumericId.clear()
        numericIdToIdentifier.clear()
        namespaces.clear()
        numericIdSequence.set(0)
        frozen = false
        registerBuiltInNamespaces()
    }

    @Synchronized
    override fun nextNumericId(): Int {
        var nextId = numericIdSequence.get()
        while (numericIdToIdentifier.containsKey(nextId)) {
            nextId++
        }
        return nextId
    }

    @Synchronized
    override fun registerNamespace(namespace: String, owner: String) {
        if (frozen) {
            throw RegistryFrozenException(
                "Registry '${registryId.toCanonicalString()}' is frozen; cannot register namespace '$namespace'."
            )
        }
        if (!Identifier.isValidNamespace(namespace)) {
            throw MalformedIdentifierException("Cannot register invalid namespace '$namespace'")
        }
        val normalized = namespace.lowercase(Locale.ROOT)
        val existing = namespaces[normalized]
        if (existing != null && existing.canonicalName != namespace) {
            throw DuplicateIdentifierException(
                "Namespace '$namespace' conflicts with existing namespace '${existing.canonicalName}' differing only by capitalization."
            )
        }
        namespaces[normalized] = NamespaceMetadata(
            canonicalName = existing?.canonicalName ?: namespace,
            owner = owner,
            isBuiltIn = existing?.isBuiltIn ?: false
        )
    }

    override fun isNamespaceRegistered(namespace: String): Boolean {
        return namespaces.containsKey(namespace.lowercase(Locale.ROOT))
    }

    override fun getRegisteredNamespaces(): Set<String> {
        return namespaces.values.map { it.canonicalName }.toSet()
    }

    @Synchronized
    override fun register(identifier: Identifier, value: T): T {
        if (frozen) {
            throw RegistryFrozenException(
                "Registry '${registryId.toCanonicalString()}' is frozen; cannot register '${identifier.toCanonicalString()}'."
            )
        }
        var nextId = numericIdSequence.getAndIncrement()
        while (numericIdToIdentifier.containsKey(nextId)) {
            nextId = numericIdSequence.getAndIncrement()
        }
        return register(identifier, nextId, value)
    }

    @Synchronized
    override fun register(identifier: Identifier, numericId: Int, value: T): T {
        if (frozen) {
            throw RegistryFrozenException(
                "Registry '${registryId.toCanonicalString()}' is frozen; cannot register '${identifier.toCanonicalString()}'."
            )
        }

        // Namespace ownership / registration check
        val nsNormalized = identifier.normalizedNamespace
        if (!namespaces.containsKey(nsNormalized)) {
            if (autoNamespaceRegistration) {
                registerNamespace(identifier.namespace, "Auto-Registered")
            } else {
                throw NamespaceNotRegisteredException(
                    "Namespace '${identifier.namespace}' is not registered in registry '${registryId.toCanonicalString()}'"
                )
            }
        }

        // Duplicate identifier check (case-insensitive)
        val existingCanonical = canonicalIdentifiers[identifier]
        if (existingCanonical != null) {
            throw DuplicateIdentifierException(
                "Cannot register identifier '${identifier.toCanonicalString()}'. " +
                        "It conflicts with already registered canonical identifier '${existingCanonical.toCanonicalString()}'. " +
                        "Identifiers that differ only by capitalization must not coexist."
            )
        }

        // Duplicate numeric ID check
        val existingForNumeric = numericIdToIdentifier[numericId]
        if (existingForNumeric != null) {
            throw DuplicateNumericIdException(
                "Cannot assign numeric ID $numericId to '${identifier.toCanonicalString()}'. " +
                        "It is already assigned to '${existingForNumeric.toCanonicalString()}'."
            )
        }

        // Register with canonical preservation
        entries[identifier] = value
        canonicalIdentifiers[identifier] = identifier
        identifierToNumericId[identifier] = numericId
        numericIdToIdentifier[numericId] = identifier

        // Keep sequence ahead of manual IDs
        if (numericId >= numericIdSequence.get()) {
            numericIdSequence.set(numericId + 1)
        }

        return value
    }

    override fun get(identifier: Identifier): T? {
        return entries[identifier]
    }

    override fun get(rawIdentifier: String): T? {
        val parsed = Identifier.parseOrNull(rawIdentifier) ?: return null
        return get(parsed)
    }

    override fun getByNumericId(numericId: Int): T? {
        val id = numericIdToIdentifier[numericId] ?: return null
        return entries[id]
    }

    override fun getCanonicalIdentifier(identifier: Identifier): Identifier? {
        return canonicalIdentifiers[identifier]
    }

    override fun getCanonicalIdentifier(rawIdentifier: String): Identifier? {
        val parsed = Identifier.parseOrNull(rawIdentifier) ?: return null
        return canonicalIdentifiers[parsed]
    }

    override fun getNumericId(identifier: Identifier): Int? {
        return identifierToNumericId[identifier]
    }

    override fun getIdentifierByNumericId(numericId: Int): Identifier? {
        return numericIdToIdentifier[numericId]
    }

    override fun contains(identifier: Identifier): Boolean {
        return entries.containsKey(identifier)
    }

    override fun contains(rawIdentifier: String): Boolean {
        val parsed = Identifier.parseOrNull(rawIdentifier) ?: return false
        return contains(parsed)
    }

    override fun getRegisteredIdentifiers(): List<Identifier> {
        return canonicalIdentifiers.values.toList().sorted()
    }

    override fun getRegisteredEntries(): Map<Identifier, T> {
        val result = LinkedHashMap<Identifier, T>()
        for (canonical in getRegisteredIdentifiers()) {
            val value = entries[canonical]
            if (value != null) {
                result[canonical] = value
            }
        }
        return result
    }

    override val size: Int
        get() = entries.size
}
