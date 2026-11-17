package com.nitrous.tessellate

import com.nitrous.tessellate.registry.ContentRegistry
import com.nitrous.tessellate.registry.DuplicateIdentifierException
import com.nitrous.tessellate.registry.DuplicateNumericIdException
import com.nitrous.tessellate.registry.GameRegistries
import com.nitrous.tessellate.registry.Identifier
import com.nitrous.tessellate.registry.MalformedIdentifierException
import com.nitrous.tessellate.registry.Namespaces
import com.nitrous.tessellate.world.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Verification test suite for TDD-001 Side B:
 * Namespace & Identifier Standardization.
 */
class IdentifierAndRegistryTest {

    // --- 1. Android Application Identity ---

    @Test
    fun `android application identity matches canonical specification`() {
        assertEquals("com.nitrous.tessellate", BuildConfig.APPLICATION_ID)
    }

    // --- 2. Built-in Content Namespaces ---

    @Test
    fun `built-in namespaces exist and are canonicalized`() {
        assertEquals("Debug", Namespaces.DEBUG)
        assertEquals("Tessellate", Namespaces.TESSELLATE)
        assertEquals("Developer", Namespaces.DEVELOPER)

        assertTrue(Namespaces.isBuiltIn("debug"))
        assertTrue(Namespaces.isBuiltIn("DEBUG"))
        assertTrue(Namespaces.isBuiltIn("Tessellate"))
        assertTrue(Namespaces.isBuiltIn("tessellate"))
        assertTrue(Namespaces.isBuiltIn("developer"))
        assertTrue(Namespaces.isBuiltIn("DEVELOPER"))
        assertFalse(Namespaces.isBuiltIn("custom_mod"))

        assertEquals("Debug", Namespaces.getCanonicalBuiltIn("debug"))
        assertEquals("Tessellate", Namespaces.getCanonicalBuiltIn("TESSELLATE"))
        assertEquals("Developer", Namespaces.getCanonicalBuiltIn("Developer"))
    }

    // --- 3. Identifier Format & Grammar Validation ---

    @Test
    fun `valid identifiers parse and retain canonical spelling`() {
        val stone = Identifier.parse("Tessellate:stone")
        assertEquals("Tessellate", stone.namespace)
        assertEquals("stone", stone.path)
        assertEquals("Tessellate:stone", stone.toCanonicalString())
        assertEquals("Tessellate:stone", stone.toString())
        assertTrue(stone.isVanilla)
        assertTrue(stone.isPreferredVanillaNaming)

        val debugBug = Identifier.parse("Debug:debugbug")
        assertEquals("Debug", debugBug.namespace)
        assertEquals("debugbug", debugBug.path)
        assertEquals("Debug:debugbug", debugBug.toCanonicalString())
        assertTrue(debugBug.isDebug)
        assertTrue(debugBug.isPreferredVanillaNaming)

        val devThing = Identifier.parse("Developer:thing_that_goes_brrr")
        assertEquals("Developer", devThing.namespace)
        assertEquals("thing_that_goes_brrr", devThing.path)
        assertEquals("Developer:thing_that_goes_brrr", devThing.toCanonicalString())
        assertTrue(devThing.isDeveloper)
        assertTrue(devThing.isPreferredVanillaNaming)

        val oakLog = Identifier.parse("Tessellate:oak_log")
        assertEquals("Tessellate:oak_log", oakLog.toCanonicalString())

        val rubidium = Identifier.parse("Tessellate:rubidium_ingot")
        assertEquals("Tessellate:rubidium_ingot", rubidium.toCanonicalString())
    }

    @Test
    fun `hyphens underscores and modder conventions are supported`() {
        val hyphenated = Identifier.parse("mod-id:custom-laser_blaster")
        assertEquals("mod-id", hyphenated.namespace)
        assertEquals("custom-laser_blaster", hyphenated.path)
        assertEquals("mod-id:custom-laser_blaster", hyphenated.toCanonicalString())

        // Modders are NOT forced to use vanilla snake_case
        val modPascal = Identifier.parse("CoolMod:SuperSword")
        assertEquals("CoolMod:SuperSword", modPascal.toCanonicalString())
        assertFalse(modPascal.isPreferredVanillaNaming)
    }

    @Test
    fun `malformed identifiers are rejected with descriptive errors`() {
        // Missing colon
        assertThrows(MalformedIdentifierException::class.java) {
            Identifier.parse("stone")
        }

        // Empty namespace
        assertThrows(MalformedIdentifierException::class.java) {
            Identifier.parse(":stone")
        }

        // Empty path
        assertThrows(MalformedIdentifierException::class.java) {
            Identifier.parse("Tessellate:")
        }

        // Multiple colons
        assertThrows(MalformedIdentifierException::class.java) {
            Identifier.parse("Tessellate:stone:extra")
        }

        // Forbidden characters
        assertThrows(MalformedIdentifierException::class.java) {
            Identifier.parse("Tessellate:stone with spaces")
        }
        assertThrows(MalformedIdentifierException::class.java) {
            Identifier.parse("Tessellate@engine:stone")
        }
    }

    // --- 4. Case Handling & Canonical Preservation ---

    @Test
    fun `identifier comparison and equality is case-insensitive`() {
        val id1 = Identifier.of("Tessellate", "stone")
        val id2 = Identifier.of("tessellate", "STONE")
        val id3 = Identifier.parse("TESSELLATE:Stone")

        // Value equality
        assertEquals(id1, id2)
        assertEquals(id2, id3)
        assertEquals(id1.hashCode(), id2.hashCode())
        assertEquals(id2.hashCode(), id3.hashCode())

        // compareTo is 0 for case-differing equivalents
        assertEquals(0, id1.compareTo(id2))
        assertEquals(0, id2.compareTo(id3))

        // But canonical registered spelling is preserved!
        assertEquals("Tessellate", id1.namespace)
        assertEquals("stone", id1.path)
        assertEquals("Tessellate:stone", id1.toCanonicalString())

        assertEquals("STONE", id2.path)
        assertEquals("Tessellate:STONE", id2.toCanonicalString())
    }

    // --- 5. Content Registry Requirements ---

    @Test
    fun `registry supports case-insensitive lookup while preserving canonical spelling`() {
        val registry = ContentRegistry<String>(
            registryId = Identifier.of(Namespaces.TESSELLATE, "test_registry")
        )

        val stoneId = Identifier.of("Tessellate", "stone")
        registry.register(stoneId, "StoneValue")

        // Case-insensitive lookup via Identifier
        assertEquals("StoneValue", registry.get(Identifier.of("tessellate", "stone")))
        assertEquals("StoneValue", registry.get(Identifier.of("TESSELLATE", "STONE")))
        assertEquals("StoneValue", registry.get(Identifier.of("Tessellate", "Stone")))

        // Case-insensitive lookup via String
        assertEquals("StoneValue", registry.get("tessellate:stone"))
        assertEquals("StoneValue", registry.get("TESSELLATE:STONE"))
        assertEquals("StoneValue", registry.get("Tessellate:Stone"))

        // Canonical identifier resolution preserves exact registered spelling
        val canonical = registry.getCanonicalIdentifier("tessellate:stone")
        assertNotNull(canonical)
        assertEquals("Tessellate:stone", canonical?.toCanonicalString())
    }

    @Test
    fun `registry rejects case-only duplicate registrations`() {
        val registry = ContentRegistry<String>(
            registryId = Identifier.of(Namespaces.TESSELLATE, "test_duplicates")
        )

        registry.register(Identifier.of("Tessellate", "stone"), "PrimaryStone")

        // Exact duplicate
        val ex1 = assertThrows(DuplicateIdentifierException::class.java) {
            registry.register(Identifier.of("Tessellate", "stone"), "Duplicate")
        }
        assertTrue(ex1.message?.contains("Tessellate:stone") == true)

        // Case-only variant duplicate (differing only by capitalization)
        val ex2 = assertThrows(DuplicateIdentifierException::class.java) {
            registry.register(Identifier.of("TESSELLATE", "STONE"), "CapitalizedDuplicate")
        }
        assertTrue("Error must explain case conflict", ex2.message?.contains("capitalization") == true)

        val ex3 = assertThrows(DuplicateIdentifierException::class.java) {
            registry.register(Identifier.of("Tessellate", "Stone"), "MixedCaseDuplicate")
        }
        assertTrue(ex3.message?.contains("conflicts with already registered canonical identifier") == true)
    }

    @Test
    fun `registry maps numeric IDs bidirectionally`() {
        val registry = ContentRegistry<String>(
            registryId = Identifier.of(Namespaces.TESSELLATE, "test_numeric")
        )

        val id = Identifier.of("Tessellate", "granite")
        registry.register(id, 42, "GraniteValue")

        assertEquals(42, registry.getNumericId(id))
        assertEquals("GraniteValue", registry.getByNumericId(42))
        assertEquals(id, registry.getIdentifierByNumericId(42))

        // Rejection of duplicate numeric ID
        assertThrows(DuplicateNumericIdException::class.java) {
            registry.register(Identifier.of("Tessellate", "diorite"), 42, "DioriteValue")
        }
    }

    @Test
    fun `built-in namespaces are pre-registered and accessible in all builds`() {
        val registry = ContentRegistry<String>(
            registryId = Identifier.of(Namespaces.TESSELLATE, "test_namespaces")
        )

        val registeredNs = registry.getRegisteredNamespaces()
        assertTrue(registeredNs.contains("Debug"))
        assertTrue(registeredNs.contains("Tessellate"))
        assertTrue(registeredNs.contains("Developer"))

        assertTrue(registry.isNamespaceRegistered("debug"))
        assertTrue(registry.isNamespaceRegistered("tessellate"))
        assertTrue(registry.isNamespaceRegistered("developer"))
    }

    // --- 6. Compatibility & Legacy Migration ---

    @Test
    fun `legacy identifier fallback migrates un-namespaced IDs`() {
        assertEquals("Tessellate:air", Identifier.parseWithLegacyFallback("AIR").toCanonicalString())
        assertEquals("Tessellate:grass", Identifier.parseWithLegacyFallback("GRASS").toCanonicalString())
        assertEquals("Tessellate:dirt", Identifier.parseWithLegacyFallback("DIRT").toCanonicalString())
        assertEquals("Tessellate:stone", Identifier.parseWithLegacyFallback("STONE").toCanonicalString())
        assertEquals("Debug:debugbug", Identifier.parseWithLegacyFallback("DEBUGBUG").toCanonicalString())

        // Arbitrary legacy un-namespaced item
        val custom = Identifier.parseWithLegacyFallback("custom_block")
        assertEquals("Tessellate:custom_block", custom.toCanonicalString())
    }

    // --- 7. BlockType and GameRegistries Integration ---

    @Test
    fun `block type data class integrates with standardized identifiers and dynamic registry`() {
        assertEquals("Tessellate:air", BlockType.AIR.stringId)
        assertEquals("Tessellate:grass", BlockType.GRASS.stringId)
        assertEquals("Tessellate:dirt", BlockType.DIRT.stringId)
        assertEquals("Tessellate:stone", BlockType.STONE.stringId)
        assertEquals("Debug:debugbug", BlockType.DEBUGBUG.stringId)
        assertEquals("Developer:thing_that_goes_brrr", BlockType.THING_THAT_GOES_BRRR.stringId)

        // Lookup via case-insensitive identifier string
        assertEquals(BlockType.STONE, BlockType.fromIdentifier("tessellate:stone"))
        assertEquals(BlockType.STONE, BlockType.fromIdentifier("TESSELLATE:STONE"))
        assertEquals(BlockType.DEBUGBUG, BlockType.fromIdentifier("debug:debugbug"))
        assertEquals(BlockType.THING_THAT_GOES_BRRR, BlockType.fromIdentifier("developer:thing_that_goes_brrr"))

        // Lookup via legacy stringId
        assertEquals(BlockType.STONE, BlockType.fromStringId("STONE"))
        assertEquals(BlockType.DEBUGBUG, BlockType.fromStringId("DEBUGBUG"))
        assertEquals(BlockType.STONE, BlockType.fromStringId("Tessellate:stone"))

        // Air is assigned dynamic ID 0; all other blocks receive unique sequential runtime IDs
        assertEquals(0.toByte(), BlockType.AIR.id)
        val nonAirBlocks = listOf(
            BlockType.GRASS,
            BlockType.DIRT,
            BlockType.STONE,
            BlockType.DEBUGBUG,
            BlockType.THING_THAT_GOES_BRRR
        )
        assertTrue(nonAirBlocks.all { it.id.toInt() > 0 })
        assertEquals(nonAirBlocks.size, nonAirBlocks.map { it.id }.toSet().size)
        for (block in nonAirBlocks) {
            assertEquals(block, BlockType.fromId(block.id))
            assertEquals(block, BlockType.REGISTRY.getByNumericId(block.id.toInt()))
        }
    }

    @Test
    fun `game registries contain vanilla debug and developer content`() {
        assertNotNull(GameRegistries.BLOCKS.get("Tessellate:stone"))
        assertNotNull(GameRegistries.BLOCKS.get("Debug:debugbug"))
        assertNotNull(GameRegistries.BLOCKS.get("Developer:thing_that_goes_brrr"))

        assertNotNull(GameRegistries.ITEMS.get("Tessellate:stone"))
        assertNotNull(GameRegistries.ITEMS.get("Tessellate:oak_log"))
        assertNotNull(GameRegistries.ITEMS.get("Tessellate:rubidium_ingot"))
        assertNotNull(GameRegistries.ITEMS.get("Debug:debugbug"))
        assertNotNull(GameRegistries.ITEMS.get("Developer:thing_that_goes_brrr"))
    }
}
