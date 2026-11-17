package com.nitrous.tessellate.scene

import android.opengl.GLES30
import com.nitrous.tessellate.core.IGameScene
import com.nitrous.tessellate.core.IGraphicsRuntime
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.entity.EntityRenderer
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.input.TouchInputController
import com.nitrous.tessellate.item.Inventory
import com.nitrous.tessellate.item.Item
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.save.SavedHotbarSlot
import com.nitrous.tessellate.save.WorldSaveData
import com.nitrous.tessellate.save.WorldSaveStorage
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.AABB
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.RaycastHit
import com.nitrous.tessellate.world.World
import com.nitrous.tessellate.world.WorldRenderer
import com.nitrous.tessellate.world.WorldSession
import kotlinx.coroutines.Job
import kotlin.math.cos
import kotlin.math.sin

/**
 * Primary game scene coordinating first-person player physics, voxel chunk rendering,
 * raycast targeting, hardness-based block mining, block placement, and entity pickups.
 */
class WorldScene : IGameScene {

    @Volatile
    var currentSession: WorldSession? = null
        private set

    @Volatile
    var saveStorage: WorldSaveStorage? = null
        private set

    @Volatile
    var world: World = World() // Streaming procedural world (renderDistance = 4, heightChunks = 8)
        private set

    var autosaveTimerSeconds: Float = 0f
        private set

    @Volatile
    var configuredRenderDistance: Int = 4

    private var viewBobPhase: Float = 0f
    private var viewBobAmplitude: Float = 0f

    val worldRenderer = WorldRenderer(world)
    val entityRenderer = EntityRenderer()
    val player = Player()

    /** Currently selected item in the player's hand/hotbar */
    var selectedItem: Item = ItemRegistry.AIR

    /**
     * Associated block for placement, mapped to [selectedItem].
     * Backwards-compatible getter and setter.
     */
    var selectedPlacementBlock: BlockType
        get() = selectedItem.associatedBlock ?: BlockType.AIR
        set(value) {
            selectedItem = ItemRegistry.getItemForBlock(value) ?: ItemRegistry.AIR
        }

    /** Current targeted voxel raycast hit from the player's camera */
    var currentTargetHit: RaycastHit? = null
        private set

    // Mining system state
    var miningBlockPos: BlockPos? = null
        private set
    var miningProgress: Float = 0f
        private set
    var isMining: Boolean = false
        private set

    @Volatile
    private var isGlInitialized = false

    @Volatile
    private var pendingWorldRegeneration = false

    @Volatile
    private var cachedTexturePipeline: TexturePipeline? = null

    private val pickupEntitiesScratch = ArrayList<com.nitrous.tessellate.entity.Entity>()
    private val renderEntitiesScratch = ArrayList<com.nitrous.tessellate.entity.Entity>()
    private val viewProjScratch = com.nitrous.tessellate.graphics.Matrix4.create()

    @Volatile
    private var hasInitializedSpawn = false

    override fun init(runtime: IGraphicsRuntime) {
        cachedTexturePipeline = runtime.texturePipeline as? TexturePipeline
        if (!world.isDestroyed) {
            // Only snap the player to default surface spawn before a world session starts;
            // on EGL context recovery during active play, preserve the player's current position.
            if (currentSession == null && !hasInitializedSpawn) {
                player.spawnAtSurface(world, 24.5f, 24.5f)
                hasInitializedSpawn = true
            }
            world.streamChunksAround(player.posX, player.posZ, cachedTexturePipeline)
        }

        val camera = runtime.camera as? Camera3D
        if (camera != null) {
            camera.setFirstPersonView(player.posX, player.eyeY, player.posZ, player.pitch, player.yaw)
        }

        worldRenderer.initGl()
        entityRenderer.initGl()
        isGlInitialized = true
    }

    /**
     * Initializes a new [World] from [session], configures gamemode and inventory,
     * spawns the player on the surface, starts chunk streaming, and writes initial `level.json` metadata.
     */
    fun startSession(
        session: WorldSession,
        camera: Camera3D? = null,
        texturePipeline: TexturePipeline? = cachedTexturePipeline,
        saveStorage: WorldSaveStorage? = this.saveStorage
    ) {
        cancelMining()
        currentTargetHit = null
        pendingWorldRegeneration = false
        autosaveTimerSeconds = 0f
        hasInitializedSpawn = true
        world.destroy()

        this.saveStorage = saveStorage
        currentSession = session
        val newWorld = World(
            session = session,
            renderDistance = configuredRenderDistance.coerceIn(1, 16),
            saveStorage = saveStorage
        )
        world = newWorld

        player.inventory.clear()
        if (session.gamemode == GameMode.CREATIVE) {
            player.isInstaMine = true
            populateCreativeHotbar()
        } else {
            player.isInstaMine = false
        }
        player.isFlying = false
        syncSelectedItemFromInventory()

        player.spawnAtSurface(newWorld, 24.5f, 24.5f)
        camera?.setFirstPersonView(player.posX, player.eyeY, player.posZ, player.pitch, player.yaw)

        worldRenderer.replaceWorld(newWorld)
        newWorld.streamChunksAround(
            playerX = player.posX,
            playerZ = player.posZ,
            texturePipeline = texturePipeline ?: cachedTexturePipeline,
            forceCheck = true
        )

        if (saveStorage != null) {
            buildWorldSaveData()?.let { initialData ->
                saveStorage.saveLevelDataSync(initialData)
            }
        }
    }

    /**
     * Restores a saved world from [saveData] and [storage]:
     * 1. Restores player position, look orientation, and 9 hotbar slots.
     * 2. Loads saved chunks (`chunks/c_X_Y_Z.bin`) or falls back to procedural worldgen when absent.
     * 3. Respawns saved chunk entities (`entities/c_X_Y_Z.json`).
     */
    fun loadSavedWorld(
        saveData: WorldSaveData,
        storage: WorldSaveStorage,
        camera: Camera3D? = null,
        texturePipeline: TexturePipeline? = cachedTexturePipeline
    ) {
        cancelMining()
        currentTargetHit = null
        pendingWorldRegeneration = false
        autosaveTimerSeconds = 0f
        hasInitializedSpawn = true
        world.destroy()

        val session = saveData.toWorldSession()
        currentSession = session
        saveStorage = storage

        val loadedWorld = World(
            session = session,
            renderDistance = configuredRenderDistance.coerceIn(1, 16),
            saveStorage = storage,
            initialCenterX = saveData.playerX,
            initialCenterZ = saveData.playerZ
        )
        world = loadedWorld

        player.posX = saveData.playerX
        player.posY = saveData.playerY
        player.posZ = saveData.playerZ
        player.yaw = saveData.playerYaw
        player.pitch = saveData.playerPitch
        player.velX = 0f
        player.velY = 0f
        player.velZ = 0f
        player.isInstaMine = (saveData.gamemode == GameMode.CREATIVE)
        player.updateBoundingBox()

        player.inventory.clear()
        for (entry in saveData.hotbar) {
            val itemId = entry.itemId
            if (entry.slot in 0 until Inventory.HOTBAR_SIZE && !itemId.isNullOrBlank() && entry.count > 0) {
                player.inventory.setSlot(entry.slot, ItemStack(itemId, entry.count))
            }
        }
        player.inventory.selectedSlot = saveData.selectedSlot
        syncSelectedItemFromInventory()

        camera?.setFirstPersonView(player.posX, player.eyeY, player.posZ, player.pitch, player.yaw)

        worldRenderer.replaceWorld(loadedWorld)
        loadedWorld.streamChunksAround(
            playerX = player.posX,
            playerZ = player.posZ,
            texturePipeline = texturePipeline ?: cachedTexturePipeline,
            forceCheck = true
        )
    }

    /**
     * Captures current world session, player transform, and all 9 hotbar slots into [WorldSaveData].
     */
    fun buildWorldSaveData(): WorldSaveData? {
        val session = currentSession ?: return null
        val hotbarList = ArrayList<SavedHotbarSlot>(Inventory.HOTBAR_SIZE)
        for (i in 0 until player.inventory.size) {
            val stack = player.inventory.getSlot(i)
            hotbarList.add(
                SavedHotbarSlot(
                    slot = i,
                    itemId = stack?.itemId,
                    count = stack?.count ?: 0
                )
            )
        }
        return WorldSaveData(
            worldName = session.name,
            seed = session.seed,
            gamemode = session.gamemode,
            worldType = session.worldType,
            playerX = player.posX,
            playerY = player.posY,
            playerZ = player.posZ,
            playerYaw = player.yaw,
            playerPitch = player.pitch,
            selectedSlot = player.inventory.selectedSlot,
            hotbar = hotbarList,
            folderName = saveStorage?.worldDir?.name.orEmpty(),
            lastSavedEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Persists `level.json`, all loaded non-empty chunks, and all active entities.
     */
    fun saveCurrentWorld(blocking: Boolean = false): Job? {
        if (world.isDestroyed) return null
        val storage = saveStorage ?: return null
        val data = buildWorldSaveData() ?: return null

        val loadedChunks = world.getAllChunks()
        val activeEntities = ArrayList<Entity>()
        world.copyEntitiesTo(activeEntities)

        return if (blocking) {
            storage.saveWorldSnapshotSync(data, loadedChunks, activeEntities, world.chunksY)
            null
        } else {
            storage.saveWorldSnapshotAsync(data, loadedChunks, activeEntities, world.chunksY)
        }
    }

    /**
     * Destroys the active [World] session and clears loaded chunks and entities when returning to the menu.
     */
    fun destroySession() {
        cancelMining()
        currentTargetHit = null
        pendingWorldRegeneration = false
        autosaveTimerSeconds = 0f
        hasInitializedSpawn = false
        currentSession = null
        saveStorage = null
        world.destroy()
        worldRenderer.replaceWorld(world)
    }

    private fun populateCreativeHotbar() {
        val creativeItems = listOf(
            "tessellate:grass",
            "tessellate:dirt",
            "tessellate:stone",
            "tessellate:cobblestone",
            "tessellate:oak_log",
            "tessellate:leaves",
            "tessellate:glass",
            "tessellate:sand",
            "prototesselate:stone"
        )
        for (i in creativeItems.indices) {
            player.inventory.setSlot(i, com.nitrous.tessellate.item.ItemStack(creativeItems[i], 64))
        }
    }

    fun updatePlayer(deltaTimeSeconds: Float, touchInput: TouchInputController, camera: Camera3D) {
        // Update player movement, collision, gravity, flight, auto-jump, crouching
        player.update(
            deltaTime = deltaTimeSeconds,
            world = world,
            moveForward = touchInput.moveForward,
            moveStrafe = touchInput.moveStrafe,
            crouchInput = touchInput.isCrouchPressed,
            jumpInput = touchInput.isJumpHeld
        )

        val horizSpeed = kotlin.math.sqrt(player.velX * player.velX + player.velZ * player.velZ)
        val shouldBob = camera.viewBobbingEnabled && player.isGrounded && !player.isFlying && horizSpeed > 0.25f
        if (shouldBob) {
            viewBobPhase += deltaTimeSeconds * 10.5f
            viewBobAmplitude = (viewBobAmplitude + deltaTimeSeconds * 6f).coerceAtMost(1f)
        } else {
            viewBobAmplitude = (viewBobAmplitude - deltaTimeSeconds * 8f).coerceAtLeast(0f)
        }
        val bobOffsetY = if (camera.viewBobbingEnabled && viewBobAmplitude > 0.001f) {
            sin(viewBobPhase) * 0.055f * viewBobAmplitude
        } else {
            0f
        }

        // Synchronize first-person camera with player position & orientation
        camera.setFirstPersonView(
            player.posX,
            player.eyeY + bobOffsetY,
            player.posZ,
            player.pitch,
            player.yaw
        )

        // Update targeting raycast from player's view
        updateCameraRaycast(camera)

        // Update active mining progress if touch interaction is in progress
        touchInput.updateInteraction(deltaTimeSeconds, this)
    }

    /**
     * Resolves the currently targeted chunk (from crosshair hit, extended look raycast, or player
     * feet fallback) and safely removes it via [World.vaporizeChunkAt].
     */
    fun vaporizeTargetedChunk(camera: Camera3D? = null): com.nitrous.tessellate.world.ChunkPos? {
        if (world.isDestroyed) return null
        val hit = currentTargetHit ?: camera?.let { cam ->
            world.raycast(cam.eyeX, cam.eyeY, cam.eyeZ, cam.forwardX, cam.forwardY, cam.forwardZ, 64f)
        }
        val targetPos = if (hit != null) {
            com.nitrous.tessellate.world.ChunkPos(
                hit.blockPos.x shr 4,
                hit.blockPos.y shr 4,
                hit.blockPos.z shr 4
            )
        } else {
            val px = kotlin.math.floor(player.posX).toInt() shr 4
            val pz = kotlin.math.floor(player.posZ).toInt() shr 4
            var py = (kotlin.math.floor(player.posY - 0.1f).toInt() shr 4).coerceIn(0, world.chunksY - 1)
            while (py >= 0 && world.getChunk(px, py, pz)?.isEmpty != false) {
                py--
            }
            if (py < 0) return null
            com.nitrous.tessellate.world.ChunkPos(px, py, pz)
        }
        val removed = world.vaporizeChunkAt(targetPos.x, targetPos.y, targetPos.z)
        worldRenderer.syncWithWorld(initGlBuffers = false)
        return if (removed) targetPos else null
    }

    /**
     * Updates dynamic world entities, chunk streaming, and player item pickups once per frame
     * after [updatePlayer] and before [render].
     */
    override fun update(deltaTimeSeconds: Float) {
        if (world.isDestroyed) return
        if (pendingWorldRegeneration) {
            pendingWorldRegeneration = false
            performWorldRegeneration()
        }
        world.streamChunksAround(player.posX, player.posZ, cachedTexturePipeline)
        world.tickEntities(deltaTimeSeconds)
        checkItemPickups()

        if (saveStorage != null && currentSession != null && deltaTimeSeconds > 0f) {
            autosaveTimerSeconds += deltaTimeSeconds
            if (autosaveTimerSeconds >= AUTOSAVE_INTERVAL_SECONDS) {
                autosaveTimerSeconds = 0f
                saveCurrentWorld(blocking = false)
            }
        }
    }

    /**
     * Checks if the player's AABB intersects any active [ItemDropEntity].
     * On intersection, removes the entity and stacks 1 item into the player's inventory.
     */
    fun checkItemPickups() {
        player.updateBoundingBox()
        world.copyEntitiesTo(pickupEntitiesScratch)
        for (i in pickupEntitiesScratch.size - 1 downTo 0) {
            val entity = pickupEntitiesScratch[i]
            if (entity is ItemDropEntity && !entity.isRemoved) {
                if (player.boundingBox.intersects(entity.boundingBox)) {
                    val remaining = player.inventory.addItem(entity.itemId, 1)
                    if (remaining == 0) {
                        entity.isRemoved = true
                        world.removeEntity(entity)
                        syncSelectedItemFromInventory()
                    }
                }
            }
        }
    }

    /**
     * Selects a hotbar slot [0..8] in the player's inventory and updates [selectedItem].
     */
    fun selectHotbarSlot(slotIndex: Int) {
        if (player.inventory.selectSlot(slotIndex)) {
            syncSelectedItemFromInventory()
        }
    }

    fun syncSelectedItemFromInventory() {
        val stack = player.inventory.getSelectedStack()
        selectedItem = if (stack != null && stack.count > 0) {
            ItemRegistry.get(stack.itemId) ?: ItemRegistry.AIR
        } else {
            ItemRegistry.AIR
        }
    }

    fun updateCameraRaycast(camera: Camera3D) {
        currentTargetHit = world.raycast(
            originX = camera.eyeX,
            originY = camera.eyeY,
            originZ = camera.eyeZ,
            dirX = camera.forwardX,
            dirY = camera.forwardY,
            dirZ = camera.forwardZ,
            maxDistance = 6.0f // First-person reach distance
        )
    }

    override fun render(runtime: IGraphicsRuntime) {
        val pipeline = runtime.texturePipeline as? TexturePipeline ?: return
        val camera = runtime.camera as? Camera3D ?: return
        cachedTexturePipeline = pipeline

        if (!isGlInitialized) {
            init(runtime)
        }

        // Configure OpenGL ES 3.0 render state
        runtime.backend.setDepthTest(true)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        runtime.backend.setCullFace(true)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        // Update meshes within per-frame GPU budget, prioritizing chunks near the player
        worldRenderer.updateMeshes(
            texturePipeline = pipeline,
            uploadToGpu = true,
            focusX = player.posX,
            focusZ = player.posZ
        )

        // Render visible world chunks and targeted block wireframe outline using matrix snapshot
        camera.copyViewProjectionMatrix(viewProjScratch)
        worldRenderer.render(viewProjScratch, pipeline, currentTargetHit)

        // Render dynamic entities via independent draw path outside chunk meshes
        world.copyEntitiesTo(renderEntitiesScratch)
        entityRenderer.render(renderEntitiesScratch, camera, pipeline)

        // Render wireframe AABB hitboxes when Show Hitboxes debug toggle is active
        if (worldRenderer.showHitboxes) {
            worldRenderer.renderHitboxes(viewProjScratch, renderEntitiesScratch, player)
        }
    }

    // --- Mining System Implementation ---

    /**
     * Initiates mining on the targeted block pos.
     */
    fun startMining(pos: BlockPos): Boolean {
        val block = world.getBlock(pos)
        if (!block.isMineable) {
            cancelMining()
            return false
        }
        miningBlockPos = pos
        miningProgress = 0f
        isMining = true
        return true
    }

    /**
     * Updates mining progress by delta time based on block hardness.
     * Spawns an [ItemDropEntity] when the block breaks.
     * Returns true if the block was broken during this tick.
     */
    fun updateMining(deltaTimeSeconds: Float, currentHit: RaycastHit?): Boolean {
        if (!isMining || miningBlockPos == null) return false

        // Cancel mining if the crosshair/touch moved off the targeted block
        if (currentHit == null || currentHit.blockPos != miningBlockPos) {
            cancelMining()
            return false
        }

        val targetPos = miningBlockPos ?: return false
        val block = world.getBlock(targetPos)
        if (!block.isMineable || block.hardness <= 0f) {
            cancelMining()
            return false
        }

        if (player.isInstaMine) {
            miningProgress = 1.0f
        } else {
            miningProgress += deltaTimeSeconds / block.hardness
        }
        if (miningProgress >= 1.0f) {
            // Remove broken block and spawn an item drop entity at its center
            world.setBlock(targetPos, BlockType.AIR)
            spawnBlockDrop(targetPos, block)
            cancelMining()
            return true
        }
        return false
    }

    /**
     * Spawns an [ItemDropEntity] at the center of [pos] for the mined [block].
     */
    fun spawnBlockDrop(pos: BlockPos, block: BlockType): ItemDropEntity? {
        val item = ItemRegistry.getItemForBlock(block) ?: return null
        if (block == BlockType.AIR) return null

        val dropPos = Vec3(
            x = pos.x + 0.5f,
            y = pos.y + 0.25f,
            z = pos.z + 0.5f
        )
        val drop = ItemDropEntity(
            position = dropPos,
            itemId = item.identifier.toCanonicalString()
        )
        drop.velocity.y = 2.0f
        world.spawnEntity(drop)
        return drop
    }

    /**
     * Cancels active mining and resets progress.
     */
    fun cancelMining() {
        isMining = false
        miningProgress = 0f
        miningBlockPos = null
    }

    /**
     * Breaks the targeted block immediately (for legacy actions/testing).
     */
    fun breakTargetedBlock(): Boolean {
        val hit = currentTargetHit ?: return false
        val block = world.getBlock(hit.blockPos)
        val broken = world.setBlock(hit.blockPos, BlockType.AIR)
        if (broken) {
            spawnBlockDrop(hit.blockPos, block)
        }
        return broken
    }

    // --- Placement System Implementation ---

    /**
     * Places the currently selected hotbar stack's block adjacent to the targeted block face.
     * Cancels placement if the selected inventory slot is empty or non-placeable.
     */
    fun placeBlockAtTarget(): Boolean {
        val hit = currentTargetHit ?: return false
        val stack = player.inventory.getSelectedStack()
        if (stack == null || stack.count <= 0) {
            syncSelectedItemFromInventory()
            return false
        }

        val item = ItemRegistry.get(stack.itemId) ?: return false
        val blockToPlace = item.associatedBlock ?: return false
        if (blockToPlace == BlockType.AIR) return false

        val placed = placeBlockAt(hit, blockToPlace)
        if (placed) {
            if (currentSession?.gamemode != GameMode.CREATIVE) {
                stack.count -= 1
                if (stack.count <= 0) {
                    player.inventory.setSlot(player.inventory.selectedSlot, null)
                }
            }
            syncSelectedItemFromInventory()
        }
        return placed
    }

    /**
     * Places a block adjacent to the targeted face after validation.
     */
    fun placeBlockAt(hit: RaycastHit, blockToPlace: BlockType): Boolean {
        val targetPos = hit.blockPos.offset(hit.face)

        // 1. Boundary validation
        if (!world.isHorizontalInBounds(targetPos.x, targetPos.z) ||
            targetPos.y !in 0 until world.sizeY
        ) {
            return false
        }

        // 2. Reject placing inside existing solid or transparent blocks
        val existingBlock = world.getBlock(targetPos)
        if (existingBlock.isSolid || existingBlock.isTransparent) {
            return false
        }

        // 3. Reject placing inside the player's bounding box
        val placedBox = AABB(
            targetPos.x.toFloat(), targetPos.y.toFloat(), targetPos.z.toFloat(),
            targetPos.x + 1f, targetPos.y + 1f, targetPos.z + 1f
        )
        if (player.boundingBox.intersects(placedBox)) {
            return false
        }

        return world.setBlock(targetPos, blockToPlace)
    }

    /**
     * Spawns a debug ItemDropEntity ~3 blocks in front of the player's look direction
     * and ~5 blocks above the player's eye level for physics/rendering diagnostics.
     */
    fun spawnDebugItemDrop(camera: Camera3D? = null): ItemDropEntity {
        val forwardX: Float
        val forwardY: Float
        val forwardZ: Float
        if (camera != null) {
            forwardX = camera.forwardX
            forwardY = camera.forwardY
            forwardZ = camera.forwardZ
        } else {
            val pitchRad = Math.toRadians(player.pitch.toDouble())
            val yawRad = Math.toRadians(player.yaw.toDouble())
            forwardX = (sin(yawRad) * cos(pitchRad)).toFloat()
            forwardY = sin(pitchRad).toFloat()
            forwardZ = (-cos(yawRad) * cos(pitchRad)).toFloat()
        }

        val spawnPos = Vec3(
            x = player.posX + forwardX * 3.0f,
            y = player.eyeY + forwardY * 3.0f + 5.0f,
            z = player.posZ + forwardZ * 3.0f
        )
        val drop = ItemDropEntity(spawnPos)
        world.spawnEntity(drop)
        return drop
    }

    fun requestRegenerateWorld() {
        pendingWorldRegeneration = true
    }

    private fun performWorldRegeneration() {
        cancelMining()
        world.clearEntities()
        world.generateTerrain()
        player.spawnAtSurface(world, 24.5f, 24.5f)
        worldRenderer.markAllChunksDirty()
    }

    fun regenerateWorld(runtime: IGraphicsRuntime) {
        performWorldRegeneration()
    }

    override fun release() {
        cancelMining()
        world.shutdownStreaming()
        worldRenderer.release()
        entityRenderer.release()
        isGlInitialized = false
    }

    companion object {
        /** Periodic autosave interval in seconds (5 minutes). */
        const val AUTOSAVE_INTERVAL_SECONDS: Float = 300f
    }
}
