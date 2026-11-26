package com.nitrous.tessellate.scene

import android.opengl.GLES30
import com.nitrous.tessellate.core.FrameProfiler
import com.nitrous.tessellate.core.IGameScene
import com.nitrous.tessellate.core.IGraphicsRuntime
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.entity.EntityRenderer
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.entity.ItemDropMerger
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.input.TouchInputController
import com.nitrous.tessellate.item.Inventory
import com.nitrous.tessellate.item.Item
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.item.ItemStack
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.save.SavedHotbarSlot
import com.nitrous.tessellate.save.WorldSaveData
import com.nitrous.tessellate.save.WorldSaveManager
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.GameMode
import com.nitrous.tessellate.world.RaycastHit
import com.nitrous.tessellate.world.TypedValue
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
    var saveManager: WorldSaveManager? = null
        private set

    val saveStorage: WorldSaveManager?
        get() = saveManager

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

    @Volatile
    var asyncMeshPrebuildEnabled: Boolean = false

    private fun resolveMeshPrebuilder(overridePipeline: TexturePipeline? = cachedTexturePipeline): TexturePipeline? {
        return if (asyncMeshPrebuildEnabled) (overridePipeline ?: cachedTexturePipeline) else null
    }

    val profiler = FrameProfiler()

    private val pickupEntitiesScratch = ArrayList<com.nitrous.tessellate.entity.Entity>()
    private val renderEntitiesScratch = ArrayList<com.nitrous.tessellate.entity.Entity>()
    private val viewProjScratch = com.nitrous.tessellate.math.Matrix4.create()
    private val placementBoxScratch = AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Volatile
    var renderFrameIndex: Long = 0L

    var lastMergeCandidateChecks: Int = 0
        private set
    var lastMergedDropsCount: Int = 0
        private set

    @Volatile
    private var hasInitializedSpawn = false

    override fun init(runtime: IGraphicsRuntime) {
        cachedTexturePipeline = runtime.texturePipeline as? TexturePipeline
        if (!world.isDestroyed) {
            // Only snap the player to default surface spawn before a world session starts;
            // on EGL context recovery during active play, preserve the player's current position.
            if (currentSession == null && !hasInitializedSpawn) {
                player.spawnAtSurface(world, 24.5, 24.5)
                hasInitializedSpawn = true
            }
            world.streamChunksAround(player.posX, player.posZ, resolveMeshPrebuilder())
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
        saveStorage: WorldSaveManager? = this.saveManager
    ) {
        cancelMining()
        currentTargetHit = null
        pendingWorldRegeneration = false
        autosaveTimerSeconds = 0f
        hasInitializedSpawn = true
        world.destroy()

        this.saveManager = saveStorage
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

        player.spawnAtSurface(newWorld, 24.5, 24.5)
        camera?.setFirstPersonView(player.posX, player.eyeY, player.posZ, player.pitch, player.yaw)

        worldRenderer.replaceWorld(newWorld)
        newWorld.streamChunksAround(
            playerX = player.posX,
            playerZ = player.posZ,
            meshPrebuilder = resolveMeshPrebuilder(texturePipeline),
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
        storage: WorldSaveManager,
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
        saveManager = storage

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
        player.velX = 0.0
        player.velY = 0.0
        player.velZ = 0.0
        player.isInstaMine = (saveData.gamemode == GameMode.CREATIVE)
        player.updateBoundingBox()

        player.inventory.clear()
        for (entry in saveData.hotbar) {
            val itemId = entry.itemId
            if (entry.slot in 0 until player.inventory.size && !itemId.isNullOrBlank() && entry.count > 0) {
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
            meshPrebuilder = resolveMeshPrebuilder(texturePipeline),
            forceCheck = true
        )
        storage.cleanupOrphanedMastersAsync()
    }

    /**
     * Captures current world session, player transform, and all inventory slots into [WorldSaveData].
     */
    fun buildWorldSaveData(): WorldSaveData? {
        val session = currentSession ?: return null
        val hotbarList = ArrayList<SavedHotbarSlot>(player.inventory.size)
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
            folderName = saveManager?.worldDir?.name.orEmpty(),
            lastSavedEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Captures metadata (`level.json`), player inventory, and entity snapshots,
     * and delegates all chunk `.bin` and disk persistence to the unified [WorldSaveManager].
     */
    fun saveCurrentWorld(blocking: Boolean = false): Job? {
        if (world.isDestroyed) return null
        val manager = saveManager ?: return null
        val data = buildWorldSaveData() ?: return null

        val loadedChunks = world.getAllChunks()
        val activeEntities = ArrayList<Entity>()
        world.copyEntitiesTo(activeEntities)

        return if (blocking) {
            manager.saveWorldSnapshotSync(data, loadedChunks, activeEntities, world.chunksY)
            null
        } else {
            manager.saveWorldSnapshotAsync(data, loadedChunks, activeEntities, world.chunksY)
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
        saveManager = null
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
        if (world.isDestroyed) return
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
        val shouldBob = camera.viewBobbingEnabled && player.isGrounded && !player.isFlying && horizSpeed > 0.25
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
            world.raycast(
                cam.eyeX, cam.eyeY, cam.eyeZ,
                cam.forwardX.toDouble(), cam.forwardY.toDouble(), cam.forwardZ.toDouble(),
                64.0
            )
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
            var py = (kotlin.math.floor(player.posY - 0.1).toInt() shr 4).coerceIn(0, world.chunksY - 1)
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
     * Updates dynamic world entities, chunk streaming, time-sliced lighting, and player item pickups
     * once per frame after [updatePlayer] and before [render].
     */
    override fun update(deltaTimeSeconds: Float) {
        if (world.isDestroyed) return
        if (pendingWorldRegeneration) {
            pendingWorldRegeneration = false
            performWorldRegeneration()
        }

        val profileEnabled = profiler.enabled
        val streamStartNs = if (profileEnabled) System.nanoTime() else 0L
        world.streamChunksAround(player.posX, player.posZ, resolveMeshPrebuilder())
        val chunkGenMs = if (profileEnabled) {
            val streamCallMs = (System.nanoTime() - streamStartNs) * FrameProfiler.NS_TO_MS
            maxOf(streamCallMs, world.lastChunkGenMs)
        } else 0f

        val lightStartNs = if (profileEnabled) System.nanoTime() else 0L
        world.stepLighting()
        val lightingMs = if (profileEnabled) (System.nanoTime() - lightStartNs) * FrameProfiler.NS_TO_MS else 0f

        val entityStartNs = if (profileEnabled) System.nanoTime() else 0L
        world.tickEntities(deltaTimeSeconds, player.posX, player.posZ)
        mergeNearbyItemDrops(minTickCount = 1)
        checkItemPickups()
        val entityUpdatesMs = if (profileEnabled) (System.nanoTime() - entityStartNs) * FrameProfiler.NS_TO_MS else 0f

        profiler.recordUpdateStage(
            chunkGenMs = chunkGenMs,
            entityUpdateMs = entityUpdatesMs,
            lightingMs = lightingMs,
            tickedEntities = world.lastTickedEntitiesCount,
            sleepingEntities = world.lastSleepingEntitiesCount
        )
        if (!isGlInitialized) {
            profiler.maybeLog()
        }

        if (saveManager != null && currentSession != null && deltaTimeSeconds > 0f) {
            autosaveTimerSeconds += deltaTimeSeconds
            if (autosaveTimerSeconds >= AUTOSAVE_INTERVAL_SECONDS) {
                autosaveTimerSeconds = 0f
                saveCurrentWorld(blocking = false)
            }
        }
    }

    /**
     * NHPHP Entity Merging (TDD-022 / TDD-022.1):
     * Delegates to `:core`'s [ItemDropMerger] on [World] to fuse identical drops within 1.5 blocks
     * with a cap of 5 candidate checks per entity per tick.
     */
    fun mergeNearbyItemDrops(minTickCount: Int = 0): Int {
        val result = world.mergeNearbyItemDrops(minTickCount = minTickCount)
        lastMergeCandidateChecks = result.candidateChecks
        lastMergedDropsCount = result.mergedCount
        return result.mergedCount
    }

    /**
     * Checks if the player's AABB intersects any active [ItemDropEntity].
     * On intersection, transfers as many items from [ItemDropEntity.count] into inventory as fit.
     */
    fun checkItemPickups() {
        player.updateBoundingBox()
        world.copyEntitiesTo(pickupEntitiesScratch)
        for (i in pickupEntitiesScratch.size - 1 downTo 0) {
            val entity = pickupEntitiesScratch[i]
            if (entity is ItemDropEntity && !entity.isRemoved && !entity.isSleeping) {
                if (player.boundingBox.intersects(entity.boundingBox)) {
                    val beforeCount = entity.count
                    val remaining = player.inventory.addItem(entity.itemId, beforeCount)
                    if (remaining <= 0) {
                        entity.isRemoved = true
                        world.removeEntity(entity)
                        syncSelectedItemFromInventory()
                    } else if (remaining < beforeCount) {
                        entity.count = remaining
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
            dirX = camera.forwardX.toDouble(),
            dirY = camera.forwardY.toDouble(),
            dirZ = camera.forwardZ.toDouble(),
            maxDistance = 6.0 // First-person reach distance
        )
    }

    override fun render(runtime: IGraphicsRuntime) {
        val pipeline = runtime.texturePipeline as? TexturePipeline ?: return
        val camera = runtime.camera as? Camera3D ?: return
        cachedTexturePipeline = pipeline

        if (!isGlInitialized) {
            init(runtime)
        }
        if (world.isDestroyed) return

        // Configure OpenGL ES 3.0 render state
        runtime.backend.setDepthTest(true)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        runtime.backend.setCullFace(true)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        val profileEnabled = profiler.enabled

        // Update meshes within per-frame GPU budget, prioritizing chunks near the player
        val meshStartNs = if (profileEnabled) System.nanoTime() else 0L
        worldRenderer.updateMeshes(
            texturePipeline = pipeline,
            uploadToGpu = true,
            focusX = player.posX,
            focusZ = player.posZ,
            currentFrameIndex = renderFrameIndex
        )
        val meshBuildMs = if (profileEnabled) (System.nanoTime() - meshStartNs) * FrameProfiler.NS_TO_MS else 0f

        // Render visible world chunks and targeted block wireframe outline using matrix snapshot
        val renderStartNs = if (profileEnabled) System.nanoTime() else 0L
        camera.copyViewProjectionMatrix(viewProjScratch)
        worldRenderer.render(
            viewProjectionMatrix = viewProjScratch,
            texturePipeline = pipeline,
            targetHit = currentTargetHit,
            cameraX = camera.eyeX,
            cameraY = camera.eyeY,
            cameraZ = camera.eyeZ
        )

        // Render dynamic entities via independent draw path outside chunk meshes with world lightIntended
        world.copyEntitiesTo(renderEntitiesScratch)
        entityRenderer.render(renderEntitiesScratch, camera, pipeline, world)

        // Render wireframe AABB hitboxes when Show Hitboxes debug toggle is active
        if (worldRenderer.showHitboxes) {
            worldRenderer.renderHitboxes(
                viewProjMatrix = viewProjScratch,
                entities = renderEntitiesScratch,
                player = player,
                cameraX = camera.eyeX,
                cameraY = camera.eyeY,
                cameraZ = camera.eyeZ
            )
        }

        // Render player first-person hand / held block in foreground depth layer shaded by lightIntended
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        entityRenderer.renderFirstPersonHand(camera, world, selectedItem, pipeline)
        val renderMs = if (profileEnabled) (System.nanoTime() - renderStartNs) * FrameProfiler.NS_TO_MS else 0f

        profiler.recordRenderStage(
            meshBuildMs = meshBuildMs,
            renderMs = renderMs,
            visibleChunks = worldRenderer.visibleChunksCount,
            culledChunks = worldRenderer.frustumCulledChunksCount
        )
        profiler.maybeLog()
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
        if (!block.isMineable) {
            cancelMining()
            return false
        }

        if (player.isInstaMine || block.hardness <= 0f) {
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
            x = pos.x + 0.5,
            y = pos.y + 0.25,
            z = pos.z + 0.5
        )
        val drop = ItemDropEntity(
            position = dropPos,
            itemId = item.identifier.toCanonicalString()
        )
        drop.velocity.y = 2.0
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
     * Returns true if the currently selected hotbar item is `Developer:greener`.
     */
    fun isHoldingGreener(): Boolean {
        return selectedItem.identifier.toCanonicalString().equals("Developer:greener", ignoreCase = true)
    }

    /**
     * Applies `"devpaint" -> TypedValue.BooleanValue(true)` to the currently targeted block when
     * holding `Developer:greener` without breaking the block, marking the chunk dirty for mesh
     * rebuild and disk persistence.
     */
    fun useGreenerAtTarget(): Boolean {
        val hit = currentTargetHit ?: return false
        if (!isHoldingGreener()) return false
        val pos = hit.blockPos
        val chunk = world.getChunk(pos.x shr 4, pos.y shr 4, pos.z shr 4) ?: return false
        val lx = pos.x and Chunk.MASK
        val ly = pos.y and Chunk.MASK
        val lz = pos.z and Chunk.MASK
        if (chunk.getBlock(lx, ly, lz) == BlockType.AIR) return false

        val localIndex = chunk.getBlockIndex(lx, ly, lz)
        chunk.setBlockDataAtIndex(localIndex, "devpaint", TypedValue.BooleanValue(true))
        return true
    }

    /**
     * Handles a quick tap on the currently targeted block: paints with `Developer:greener` if held,
     * otherwise attempts normal block placement.
     */
    fun tapTargetedBlock(): Boolean {
        if (isHoldingGreener()) {
            return useGreenerAtTarget()
        }
        return placeBlockAtTarget()
    }

    /**
     * Places the currently selected hotbar stack's block adjacent to the targeted block face.
     * Cancels placement if the selected inventory slot is empty or non-placeable.
     */
    fun placeBlockAtTarget(): Boolean {
        if (isHoldingGreener()) {
            return useGreenerAtTarget()
        }
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

        // 3. Reject placing inside the player's bounding box (using pre-allocated scratch AABB)
        placementBoxScratch.set(
            targetPos.x.toDouble(), targetPos.y.toDouble(), targetPos.z.toDouble(),
            targetPos.x + 1.0, targetPos.y + 1.0, targetPos.z + 1.0
        )
        if (player.boundingBox.intersects(placementBoxScratch)) {
            return false
        }

        return world.setBlock(targetPos, blockToPlace)
    }

    /**
     * Spawns a debug ItemDropEntity ~3 blocks in front of the player's look direction
     * and ~5 blocks above the player's eye level for physics/rendering diagnostics.
     */
    fun spawnDebugItemDrop(camera: Camera3D? = null): ItemDropEntity {
        val forwardX: Double
        val forwardY: Double
        val forwardZ: Double
        if (camera != null) {
            forwardX = camera.forwardX.toDouble()
            forwardY = camera.forwardY.toDouble()
            forwardZ = camera.forwardZ.toDouble()
        } else {
            val pitchRad = Math.toRadians(player.pitch.toDouble())
            val yawRad = Math.toRadians(player.yaw.toDouble())
            forwardX = sin(yawRad) * cos(pitchRad)
            forwardY = sin(pitchRad)
            forwardZ = -cos(yawRad) * cos(pitchRad)
        }

        val spawnPos = Vec3(
            x = player.posX + forwardX * 3.0,
            y = player.eyeY + forwardY * 3.0 + 5.0,
            z = player.posZ + forwardZ * 3.0
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
        player.spawnAtSurface(world, 24.5, 24.5)
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

        /** Horizontal/3D item drop fusion distance (1.5 blocks per TDD-022 / :core). */
        const val ITEM_MERGE_RADIUS_BLOCKS: Double = ItemDropMerger.ITEM_MERGE_RADIUS_BLOCKS
        const val ITEM_MERGE_RADIUS_SQ: Double = ItemDropMerger.ITEM_MERGE_RADIUS_SQ

        /** Maximum nearby candidate comparisons per entity per tick (NHPHP O(n^2) prevention). */
        const val MAX_MERGE_CHECKS_PER_ENTITY: Int = ItemDropMerger.MAX_MERGE_CHECKS_PER_ENTITY
    }
}
