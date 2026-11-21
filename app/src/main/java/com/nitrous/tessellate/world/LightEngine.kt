package com.nitrous.tessellate.world

/**
 * Time-sliced Lazyprop voxel light propagation engine (`lightIntended` vs `lightRendered`).
 *
 * Optimizations:
 * - **Zero-Allocation Ring Queues:** Coordinates `(wx, wy, wz)` and old light levels are stored
 *   in primitive `IntArray` circular buffers instead of allocating `BlockPos` nodes.
 * - **Time-Slicing:** Each frame [step] processes at most [maxNodesPerFrame] BFS nodes and
 *   respects [maxBudgetNs] so a full 15-block flood-fill spreads across multiple frames without hitching.
 * - **Dirty Chunk Tracking & Early Skip:** Only evaluates chunks flagged with [Chunk.needsLightUpdate],
 *   skipping chunks that are [Chunk.isCompletelyDark] or [Chunk.isCompletelyLit].
 */
class LightEngine(
    var maxNodesPerFrame: Int = DEFAULT_MAX_NODES_PER_FRAME,
    var maxBudgetNs: Long = DEFAULT_MAX_BUDGET_NS
) {
    private val lightLock = Any()

    // Primitive ring buffers for BFS addition (lightIntended propagation) & removal (darkening)
    private var addX = IntArray(INITIAL_QUEUE_CAPACITY)
    private var addY = IntArray(INITIAL_QUEUE_CAPACITY)
    private var addZ = IntArray(INITIAL_QUEUE_CAPACITY)
    private var addHead = 0
    private var addTail = 0
    private var addCount = 0

    private var remX = IntArray(INITIAL_QUEUE_CAPACITY)
    private var remY = IntArray(INITIAL_QUEUE_CAPACITY)
    private var remZ = IntArray(INITIAL_QUEUE_CAPACITY)
    private var remLevel = IntArray(INITIAL_QUEUE_CAPACITY)
    private var remHead = 0
    private var remTail = 0
    private var remCount = 0

    private val dirtyChunks = ArrayList<Chunk>()

    @Volatile
    var lastStepNodesProcessed: Int = 0
        private set

    @Volatile
    var lastStepSkippedChunks: Int = 0
        private set

    @Volatile
    var totalNodesProcessed: Long = 0L
        private set

    @Volatile
    var totalStepsExecuted: Long = 0L
        private set

    val pendingAddCount: Int
        get() = synchronized(lightLock) { addCount }

    val pendingRemoveCount: Int
        get() = synchronized(lightLock) { remCount }

    fun hasPendingWork(): Boolean = synchronized(lightLock) {
        addCount > 0 || remCount > 0 || dirtyChunks.isNotEmpty()
    }

    fun clear() {
        synchronized(lightLock) {
            addHead = 0
            addTail = 0
            addCount = 0
            remHead = 0
            remTail = 0
            remCount = 0
            for (i in 0 until dirtyChunks.size) {
                dirtyChunks[i].inLightQueue = false
            }
            dirtyChunks.clear()
        }
    }

    /**
     * Initializes vertical direct skylight on a newly built or loaded [chunk].
     * Empty sky chunks become [Chunk.isCompletelyLit] (`needsLightUpdate = false`),
     * solid opaque chunks become [Chunk.isCompletelyDark] (`needsLightUpdate = false`),
     * and mixed boundary chunks seed vertical sky columns cleanly while queuing horizontal
     * light seams for time-sliced BFS.
     */
    fun initializeChunkLighting(chunk: Chunk, world: World) {
        if (chunk.isEmpty) {
            val above = if (chunk.chunkY < world.chunksY - 1) {
                world.getChunk(chunk.chunkX, chunk.chunkY + 1, chunk.chunkZ)
            } else {
                null
            }
            if (above == null || above.isCompletelyLit) {
                chunk.markCompletelyLit()
                return
            }
        }

        if (chunk.isSolidOpaque) {
            chunk.markCompletelyDark()
            return
        }

        // Mixed surface/cave chunk: seed direct sky light top-down cleanly without allocating
        // per-block objects, and track whether any horizontal light gradients need BFS.
        var allDark = true
        var allLit = true
        val intended = ByteArray(Chunk.VOLUME)
        val rendered = ByteArray(Chunk.VOLUME)

        val above = if (chunk.chunkY < world.chunksY - 1) {
            world.getChunk(chunk.chunkX, chunk.chunkY + 1, chunk.chunkZ)
        } else {
            null
        }

        for (lz in 0 until Chunk.SIZE) {
            for (lx in 0 until Chunk.SIZE) {
                var sky = if (above == null || above.isCompletelyLit) {
                    MAX_LIGHT
                } else {
                    above.getIntendedLightUnchecked(lx, 0, lz)
                }

                for (ly in Chunk.SIZE - 1 downTo 0) {
                    val block = chunk.getBlockUnchecked(lx, ly, lz)
                    val level = if (block.isOpaque) {
                        sky = 0
                        0
                    } else if (block.isTransparent) {
                        sky = (sky - 1).coerceAtLeast(0)
                        sky
                    } else {
                        sky
                    }

                    val idx = (ly shl 8) or (lz shl 4) or lx
                    intended[idx] = level.toByte()
                    rendered[idx] = level.toByte()
                    if (level != 0) allDark = false
                    if (level != MAX_LIGHT) allLit = false
                }
            }
        }

        when {
            allDark -> chunk.markCompletelyDark()
            allLit -> chunk.markCompletelyLit()
            else -> {
                val (dstIntended, dstRendered) = chunk.ensureLightStorage()
                System.arraycopy(intended, 0, dstIntended, 0, Chunk.VOLUME)
                System.arraycopy(rendered, 0, dstRendered, 0, Chunk.VOLUME)
                scheduleChunkLightUpdate(chunk)
            }
        }
    }

    /**
     * Marks [chunk] dirty for lighting and queues it for [step] unless already completely dark/lit.
     */
    fun scheduleChunkLightUpdate(chunk: Chunk) {
        if (chunk.isCompletelyDark || chunk.isCompletelyLit) {
            chunk.needsLightUpdate = false
            return
        }
        chunk.needsLightUpdate = true
        synchronized(lightLock) {
            if (!chunk.inLightQueue) {
                chunk.inLightQueue = true
                dirtyChunks.add(chunk)
            }
        }
    }

    /**
     * Schedules a Lazyprop update when a block transitions from [oldBlock] to [newBlock] at `(wx, wy, wz)`.
     */
    fun onBlockChanged(world: World, wx: Int, wy: Int, wz: Int, oldBlock: BlockType, newBlock: BlockType) {
        val cx = wx shr 4
        val cy = wy shr 4
        val cz = wz shr 4
        val chunk = world.getChunk(cx, cy, cz) ?: return

        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK

        val oldLight = chunk.getIntendedLightUnchecked(lx, ly, lz)
        if (newBlock.isOpaque) {
            // Solid block placed: clear light here and start darkening wave + neighbor re-seed
            chunk.setIntendedLight(lx, ly, lz, 0)
            chunk.setRenderedLight(lx, ly, lz, 0)
            if (oldLight > 0) {
                synchronized(lightLock) {
                    enqueueRemoveLocked(wx, wy, wz, oldLight)
                }
            }
            chunk.needsLightUpdate = true
            scheduleChunkLightUpdate(chunk)
        } else {
            // Transparent / Air block placed (or block broken): pull incoming light from neighbors & sky
            var bestIncoming = 0
            // Vertical sky check directly above
            val aboveLight = getIntendedLightGlobal(world, wx, wy + 1, wz)
            if (wy == world.sizeY - 1 || aboveLight == MAX_LIGHT) {
                bestIncoming = if (newBlock.isTransparent) MAX_LIGHT - 1 else MAX_LIGHT
            } else {
                bestIncoming = (aboveLight - (if (newBlock.isTransparent) 1 else 1)).coerceAtLeast(0)
            }

            val belowLight = getIntendedLightGlobal(world, wx, wy - 1, wz)
            val westLight = getIntendedLightGlobal(world, wx - 1, wy, wz)
            val eastLight = getIntendedLightGlobal(world, wx + 1, wy, wz)
            val northLight = getIntendedLightGlobal(world, wx, wy, wz - 1)
            val southLight = getIntendedLightGlobal(world, wx, wy, wz + 1)

            val attenuation = if (newBlock.isTransparent) 2 else 1
            bestIncoming = maxOf(
                bestIncoming,
                belowLight - attenuation,
                westLight - attenuation,
                eastLight - attenuation,
                northLight - attenuation,
                southLight - attenuation,
                0
            )

            chunk.ensureLightStorage()
            chunk.setIntendedLight(lx, ly, lz, bestIncoming)
            chunk.needsLightUpdate = true
            synchronized(lightLock) {
                enqueueAddLocked(wx, wy, wz)
            }
            scheduleChunkLightUpdate(chunk)
        }

        // Mark adjacent boundary chunks dirty if on chunk border
        if (lx == 0) world.getChunk(cx - 1, cy, cz)?.let { if (!it.isCompletelyDark) scheduleChunkLightUpdate(it) }
        if (lx == Chunk.MASK) world.getChunk(cx + 1, cy, cz)?.let { if (!it.isCompletelyDark) scheduleChunkLightUpdate(it) }
        if (ly == 0 && cy > 0) world.getChunk(cx, cy - 1, cz)?.let { if (!it.isCompletelyDark) scheduleChunkLightUpdate(it) }
        if (ly == Chunk.MASK && cy < world.chunksY - 1) world.getChunk(cx, cy + 1, cz)?.let { if (!it.isCompletelyDark) scheduleChunkLightUpdate(it) }
        if (lz == 0) world.getChunk(cx, cy, cz - 1)?.let { if (!it.isCompletelyDark) scheduleChunkLightUpdate(it) }
        if (lz == Chunk.MASK) world.getChunk(cx, cy, cz + 1)?.let { if (!it.isCompletelyDark) scheduleChunkLightUpdate(it) }
    }

    /**
     * Manually seeds a light source or level at `(wx, wy, wz)` and enqueues it for time-sliced BFS.
     * Useful for emissive blocks/mods and verifying time-sliced 15-block propagation.
     */
    fun seedLight(world: World, wx: Int, wy: Int, wz: Int, level: Int) {
        if (wy !in 0 until world.sizeY) return
        val chunk = world.getChunk(wx shr 4, wy shr 4, wz shr 4) ?: return
        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK
        chunk.ensureLightStorage()
        chunk.setIntendedLight(lx, ly, lz, level.coerceIn(0, MAX_LIGHT))
        chunk.needsLightUpdate = true
        synchronized(lightLock) {
            enqueueAddLocked(wx, wy, wz)
        }
    }

    /**
     * Runs one time-sliced Lazyprop update pass across dirty chunks and queued BFS nodes.
     * Returns the number of BFS nodes processed during this frame.
     */
    fun step(
        world: World,
        maxNodes: Int = maxNodesPerFrame,
        budgetNs: Long = maxBudgetNs
    ): Int {
        val startNs = System.nanoTime()
        var nodesProcessed = 0
        var skippedChunks = 0

        // 1. Seed perimeter/interior light discrepancies for chunks flagged needsLightUpdate
        synchronized(lightLock) {
            var i = dirtyChunks.size - 1
            while (i >= 0 && nodesProcessed < maxNodes && (System.nanoTime() - startNs) < budgetNs) {
                val chunk = dirtyChunks.removeAt(i)
                chunk.inLightQueue = false
                i--

                if (!chunk.needsLightUpdate || chunk.isCompletelyDark || chunk.isCompletelyLit) {
                    chunk.needsLightUpdate = false
                    skippedChunks++
                    continue
                }

                seedDirtyChunkBoundaryLocked(chunk, world)
                chunk.needsLightUpdate = false
            }
        }

        // 2. Process darkening/removal BFS wave (time-sliced)
        while (nodesProcessed < maxNodes) {
            if (nodesProcessed > 0 && (System.nanoTime() - startNs) >= budgetNs) break

            val wx: Int
            val wy: Int
            val wz: Int
            val oldLevel: Int
            synchronized(lightLock) {
                if (remCount == 0) break
                wx = remX[remHead]
                wy = remY[remHead]
                wz = remZ[remHead]
                oldLevel = remLevel[remHead]
                remHead = (remHead + 1) % remX.size
                remCount--
            }

            processRemoveNode(world, wx, wy, wz, oldLevel)
            nodesProcessed++
        }

        // 3. Process Lazyprop add/sync BFS wave (time-sliced)
        while (nodesProcessed < maxNodes) {
            if (nodesProcessed > 0 && (System.nanoTime() - startNs) >= budgetNs) break

            val wx: Int
            val wy: Int
            val wz: Int
            synchronized(lightLock) {
                if (addCount == 0) break
                wx = addX[addHead]
                wy = addY[addHead]
                wz = addZ[addHead]
                addHead = (addHead + 1) % addX.size
                addCount--
            }

            processAddNode(world, wx, wy, wz)
            nodesProcessed++
        }

        lastStepNodesProcessed = nodesProcessed
        lastStepSkippedChunks = skippedChunks
        totalNodesProcessed += nodesProcessed
        totalStepsExecuted++
        return nodesProcessed
    }

    private fun seedDirtyChunkBoundaryLocked(chunk: Chunk, world: World) {
        val baseX = chunk.chunkX * Chunk.SIZE
        val baseY = chunk.chunkY * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        // Seed only boundary faces + bright interior air pockets that differ from neighbors
        for (ly in 0 until Chunk.SIZE) {
            for (lz in 0 until Chunk.SIZE) {
                for (lx in 0 until Chunk.SIZE) {
                    val isEdge = lx == 0 || lx == Chunk.MASK || ly == 0 || ly == Chunk.MASK || lz == 0 || lz == Chunk.MASK
                    if (!isEdge) continue
                    val light = chunk.getIntendedLightUnchecked(lx, ly, lz)
                    if (light > 1 && !chunk.getBlockUnchecked(lx, ly, lz).isOpaque) {
                        enqueueAddLocked(baseX + lx, baseY + ly, baseZ + lz)
                    }
                }
            }
        }
    }

    private fun processRemoveNode(world: World, wx: Int, wy: Int, wz: Int, oldLevel: Int) {
        val cx = wx shr 4
        val cy = wy shr 4
        val cz = wz shr 4
        val chunk = world.getChunk(cx, cy, cz) ?: return
        chunk.setRenderedLight(wx and Chunk.MASK, wy and Chunk.MASK, wz and Chunk.MASK, 0)

        for (d in 0 until DIRECTIONS.size) {
            val dir = DIRECTIONS[d]
            val nx = wx + dir.dx
            val ny = wy + dir.dy
            val nz = wz + dir.dz
            if (ny !in 0 until world.sizeY) continue
            val nChunk = world.getChunk(nx shr 4, ny shr 4, nz shr 4) ?: continue
            val nlx = nx and Chunk.MASK
            val nly = ny and Chunk.MASK
            val nlz = nz and Chunk.MASK
            val neighborLight = nChunk.getIntendedLightUnchecked(nlx, nly, nlz)
            if (neighborLight != 0 && neighborLight < oldLevel) {
                nChunk.setIntendedLight(nlx, nly, nlz, 0)
                nChunk.setRenderedLight(nlx, nly, nlz, 0)
                synchronized(lightLock) {
                    enqueueRemoveLocked(nx, ny, nz, neighborLight)
                }
            } else if (neighborLight >= oldLevel) {
                synchronized(lightLock) {
                    enqueueAddLocked(nx, ny, nz)
                }
            }
        }
    }

    private fun processAddNode(world: World, wx: Int, wy: Int, wz: Int) {
        if (wy !in 0 until world.sizeY) return
        val cx = wx shr 4
        val cy = wy shr 4
        val cz = wz shr 4
        val chunk = world.getChunk(cx, cy, cz) ?: return
        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK

        val intended = chunk.getIntendedLightUnchecked(lx, ly, lz)
        // Synchronize rendered light lazily to match intended light as BFS advances
        chunk.setRenderedLight(lx, ly, lz, intended)
        if (intended <= 1) return

        for (d in 0 until DIRECTIONS.size) {
            val dir = DIRECTIONS[d]
            val nx = wx + dir.dx
            val ny = wy + dir.dy
            val nz = wz + dir.dz
            if (ny !in 0 until world.sizeY) continue
            val nChunk = world.getChunk(nx shr 4, ny shr 4, nz shr 4) ?: continue
            val nlx = nx and Chunk.MASK
            val nly = ny and Chunk.MASK
            val nlz = nz and Chunk.MASK

            val neighborBlock = nChunk.getBlockUnchecked(nlx, nly, nlz)
            if (neighborBlock.isOpaque) continue

            val attenuation = if (neighborBlock.isTransparent) 2 else 1
            val nextLevel = intended - attenuation
            if (nextLevel <= 0) continue

            val neighborIntended = nChunk.getIntendedLightUnchecked(nlx, nly, nlz)
            if (neighborIntended < nextLevel) {
                nChunk.setIntendedLight(nlx, nly, nlz, nextLevel)
                nChunk.needsLightUpdate = true
                synchronized(lightLock) {
                    enqueueAddLocked(nx, ny, nz)
                }
            }
        }
    }

    private fun getIntendedLightGlobal(world: World, wx: Int, wy: Int, wz: Int): Int {
        if (wy !in 0 until world.sizeY) {
            return if (wy >= world.sizeY) MAX_LIGHT else 0
        }
        val chunk = world.getChunk(wx shr 4, wy shr 4, wz shr 4) ?: return MAX_LIGHT
        return chunk.getIntendedLightUnchecked(wx and Chunk.MASK, wy and Chunk.MASK, wz and Chunk.MASK)
    }

    private fun enqueueAddLocked(x: Int, y: Int, z: Int) {
        if (addCount == addX.size) {
            val newCap = addX.size * 2
            val nx = IntArray(newCap)
            val ny = IntArray(newCap)
            val nz = IntArray(newCap)
            for (i in 0 until addCount) {
                val idx = (addHead + i) % addX.size
                nx[i] = addX[idx]
                ny[i] = addY[idx]
                nz[i] = addZ[idx]
            }
            addX = nx
            addY = ny
            addZ = nz
            addHead = 0
            addTail = addCount
        }
        addX[addTail] = x
        addY[addTail] = y
        addZ[addTail] = z
        addTail = (addTail + 1) % addX.size
        addCount++
    }

    private fun enqueueRemoveLocked(x: Int, y: Int, z: Int, oldLevel: Int) {
        if (remCount == remX.size) {
            val newCap = remX.size * 2
            val nx = IntArray(newCap)
            val ny = IntArray(newCap)
            val nz = IntArray(newCap)
            val nl = IntArray(newCap)
            for (i in 0 until remCount) {
                val idx = (remHead + i) % remX.size
                nx[i] = remX[idx]
                ny[i] = remY[idx]
                nz[i] = remZ[idx]
                nl[i] = remLevel[idx]
            }
            remX = nx
            remY = ny
            remZ = nz
            remLevel = nl
            remHead = 0
            remTail = remCount
        }
        remX[remTail] = x
        remY[remTail] = y
        remZ[remTail] = z
        remLevel[remTail] = oldLevel
        remTail = (remTail + 1) % remX.size
        remCount++
    }

    companion object {
        const val MAX_LIGHT = 15
        const val DEFAULT_MAX_NODES_PER_FRAME = 256
        const val DEFAULT_MAX_BUDGET_NS = 1_000_000L // 1.0 ms per frame budget
        private const val INITIAL_QUEUE_CAPACITY = 1024
        private val DIRECTIONS = Direction.values()
    }
}
