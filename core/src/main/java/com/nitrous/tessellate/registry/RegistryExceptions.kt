package com.nitrous.tessellate.registry

/**
 * Base exception for registry operations.
 */
open class RegistryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown when an identifier registration conflicts with an already registered identifier,
 * including case-insensitive collisions where identifiers differ only by capitalization.
 */
class DuplicateIdentifierException(message: String) : RegistryException(message)

/**
 * Thrown when attempting to assign a numeric ID that is already bound in the registry.
 */
class DuplicateNumericIdException(message: String) : RegistryException(message)

/**
 * Thrown when an operation targets an unregistered namespace or an unknown registry key.
 */
class NamespaceNotRegisteredException(message: String) : RegistryException(message)

/**
 * Thrown when attempting to register content or namespaces into a registry after it has been frozen.
 */
class RegistryFrozenException(message: String) : RegistryException(message)

/**
 * Thrown when data-driven content JSON definitions fail to parse or resolve relationships.
 */
class ContentLoadException(message: String, cause: Throwable? = null) : RegistryException(message, cause)
