package com.nitrous.tessellate.save

import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.item.Inventory
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.WorldSession
import com.nitrous.tessellate.world.WorldType
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializable representation of a single hotbar slot stored in `level.json`.
 */
data class SavedHotbarSlot(
    val slot: Int,
    val itemId: String? = null,
    val count: Int = 0
)

/**
 * Persistent metadata, player transform, and hotbar inventory saved to `saves/<world>/level.json`.
 */
data class WorldSaveData(
    val worldName: String,
    val seed: Long,
    val gamemode: GameMode,
    val worldType: WorldType,
    val playerX: Double,
    val playerY: Double,
    val playerZ: Double,
    val playerYaw: Float,
    val playerPitch: Float,
    val selectedSlot: Int = 0,
    val hotbar: List<SavedHotbarSlot> = List(Inventory.TOTAL_SIZE) { SavedHotbarSlot(slot = it) },
    val folderName: String = "",
    val lastSavedEpochMs: Long = 0L
) {
    fun toWorldSession(): WorldSession = WorldSession(
        name = worldName,
        gamemode = gamemode,
        worldType = worldType,
        seed = seed
    )
}

/**
 * Serializable snapshot of an active entity stored inside `entities/c_X_Y_Z.json`.
 */
data class SavedEntityData(
    val typeId: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val vz: Double = 0.0,
    val extraData: Map<String, Any?> = emptyMap()
)

/**
 * Per-chunk JSON wrapper saved at `saves/<world>/entities/c_X_Y_Z.json`.
 */
data class ChunkEntityFileData(
    val chunkX: Int,
    val chunkY: Int,
    val chunkZ: Int,
    val entities: List<SavedEntityData> = emptyList()
)

/**
 * Registry mapping entity `typeId` strings to factory functions for base and modded entities.
 */
object EntityPersistenceRegistry {

    private val factories = ConcurrentHashMap<String, (SavedEntityData) -> Entity?>()

    init {
        registerBuiltIns()
    }

    fun registerBuiltIns() {
        register(ItemDropEntity.TYPE_ID) { saved ->
            val itemId = (saved.extraData["itemId"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: ItemRegistry.DIRT.identifier.toCanonicalString()
            val count = (saved.extraData["count"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
            ItemDropEntity(
                position = Vec3(saved.x, saved.y, saved.z),
                itemId = itemId,
                count = count
            )
        }
    }

    fun register(typeId: String, factory: (SavedEntityData) -> Entity?) {
        factories[typeId] = factory
    }

    fun unregister(typeId: String) {
        factories.remove(typeId)
    }

    fun serialize(entity: Entity): SavedEntityData {
        return SavedEntityData(
            typeId = entity.typeId,
            x = entity.position.x,
            y = entity.position.y,
            z = entity.position.z,
            vx = entity.velocity.x,
            vy = entity.velocity.y,
            vz = entity.velocity.z,
            extraData = entity.writeExtraData()
        )
    }

    fun deserialize(data: SavedEntityData): Entity? {
        val factory = factories[data.typeId] ?: return null
        val entity = factory(data) ?: return null
        entity.position.set(data.x, data.y, data.z)
        entity.velocity.set(data.vx, data.vy, data.vz)
        entity.readExtraData(data.extraData)
        entity.refreshBoundingBox()
        return entity
    }
}
