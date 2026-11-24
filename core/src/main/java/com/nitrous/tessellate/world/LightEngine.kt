package com.nitrous.tessellate.world

/**
 * Time-sliced Lazyprop voxel light propagation engine (`lightIntended` vs `lightRendered`)
 * using the Starlight `fromDir` 5-neighbor BFS method on the signed `-16..+16` scale.
 *
 * Architecture & Optimizations:
 * - **Signed Scale (`-16..+16`):** `-16` is absolute darkness, `0` ambient baseline, `+16` max skylight.
 * - **Dual Light State:** `lightIntended` propagates synchronously during chunk generation and
 *   localized block edits so gameplay/AI/entity lighting queries are immediately authoritative;
 *   `lightRendered` updates incrementally via the time-sliced queue (`maxNodesPerFrame = 500`) and
 *   marks affected chunks `needsMeshRebuild = true`.
 * - **Starlight `fromDir` BFS:** Each queued node tracks its incoming direction and inspects only
 *   the 5 outgoing neighbors (`d != fromDir`), reducing neighbor checks by ~16.7% and cutting
 *   chunk-lookup lock contention via a local 4-entry chunk cache.
 * - **Zero-Allocation Ring Queues:** Coordinates and incoming directions live in primitive arrays.
 */
class LightEngine(
    var maxNodesPerFrame: Int = DEFAULT_MAX_NODES_PER_FRAME,
    var maxBudgetNs: Long = DEFAULT_MAX_BUDGET_NS
) {
    private val lightLock = Any()

    // Primitive ring buffers for time-sliced lightRendered BFS addition & removal waves
    private var addX = IntArray(INITIAL_QUEUE_CAPACITY)
    private var addY = IntArray(INITIAL_QUEUE_CAPACITY)
    private var addZ = IntArray(INITIAL_QUEUE_CAPACITY)
    private var addFromDir = ByteArray(INITIAL_QUEUE_CAPACITY)
    private var addHead = 0
    private var addTail = 0
    private var addCount = 0

    private var remX = IntArray(INITIAL_QUEUE_CAPACITY)
    private var remY = IntArray(INITIAL_QUEUE_CAPACITY)
    private var remZ = IntArray(INITIAL_QUEUE_CAPACITY)
    private var remLevel = IntArray(INITIAL_QUEUE_CAPACITY)
    private var remFromDir = ByteArray(INITIAL_QUEUE_CAPACITY)
    private var remHead = 0
    private var remTail = 0
    private var remCount = 0

    // Synchronous lightIntended scratch queues (guarded by lightLock)
    private var syncAddX = IntArray(512)
    private var syncAddY = IntArray(512)
    private var syncAddZ = IntArray(512)
    private var syncAddFromDir = ByteArray(512)

    private var syncRemX = IntArray(512)
    private var syncRemY = IntArray(512)
    private var syncRemZ = IntArray(512)
    private var syncRemLevel = IntArray(512)
    private var syncRemFromDir = ByteArray(512)

    private var reseedX = IntArray(256)
    private var reseedY = IntArray(256)
    private var reseedZ = IntArray(256)

    // Reusable 4-slot chunk cache to eliminate repeated World.getChunk() chunkLock acquisitions inside BFS
    private val cacheCx = IntArray(4)
    private val cacheCy = IntArray(4)
    private val cacheCz = IntArray(4)
    private val cacheChunks = arrayOfNulls<Chunk>(4)
    private var cacheCursor = 0

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
            invalidateChunkCache()
        }
    }

    private fun invalidateChunkCache() {
        for (i in 0 until 4) {
            cacheChunks[i] = null
        }
    }

    private fun getChunkCached(world: World, cx: Int, cy: Int, cz: Int): Chunk? {
        if (cy !in 0 until world.chunksY) return null
        for (i in 0 until 4) {
            val c = cacheChunks[i]
            if (c != null && cacheCx[i] == cx && cacheCy[i] == cy && cacheCz[i] == cz) {
                return c
            }
        }
        val fetched = world.getChunk(cx, cy, cz)
        if (fetched != null) {
            val slot = cacheCursor and 3
            cacheCursor++
            cacheCx[slot] = cx
            cacheCy[slot] = cy
            cacheCz[slot] = cz
            cacheChunks[slot] = fetched
        }
        return fetched
    }

    /**
     * Synchronously computes vertical direct skylight (`+16`) and underground darkness (`-16`),
     * seeds any emissive blocks (`lightEmission > 0`), and runs synchronous `lightIntended` Starlight BFS
     * during chunk generation. Initializes `lightRendered` to match initial terrain lighting so new
     * chunks render cleanly right away.
     */
    fun initializeChunkLighting(
        chunk: Chunk,
        world: World,
        chunkLookup: (Int, Int, Int) -> Chunk? = { cx, cy, cz -> world.getChunk(cx, cy, cz) }
    ) {
        if (chunk.isEmpty) {
            val above = if (chunk.chunkY < world.chunksY - 1) {
                chunkLookup(chunk.chunkX, chunk.chunkY + 1, chunk.chunkZ)
            } else {
                null
            }
            if (above == null || above.isCompletelyLit) {
                chunk.markCompletelyLit()
                return
            }
        }

        if (chunk.isSolidOpaque) {
            var hasEmitter = false
            for (i in 0 until Chunk.VOLUME) {
                if (BlockType.fromId(chunk.blocks[i]).lightEmission > 0) {
                    hasEmitter = true
                    break
                }
            }
            if (!hasEmitter) {
                chunk.markCompletelyDark()
                return
            }
        }

        var allDark = true
        var allLit = true
        val intended = ByteArray(Chunk.VOLUME)
        val rendered = ByteArray(Chunk.VOLUME)

        val above = if (chunk.chunkY < world.chunksY - 1) {
            chunkLookup(chunk.chunkX, chunk.chunkY + 1, chunk.chunkZ)
        } else {
            null
        }

        val baseX = chunk.chunkX * Chunk.SIZE
        val baseY = chunk.chunkY * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        // 1. Seed direct vertical skylight columns (+16 unobstructed, -16 beneath opaque) & emitters
        val localQueue = IntArray(Chunk.VOLUME)
        val localDir = ByteArray(Chunk.VOLUME)
        var qHead = 0
        var qTail = 0

        for (lz in 0 until Chunk.SIZE) {
            for (lx in 0 until Chunk.SIZE) {
                var skyOpen = if (above == null || above.isCompletelyLit) {
                    true
                } else {
                    above.getIntendedLightUnchecked(lx, 0, lz) >= MAX_LIGHT &&
                        !above.getBlockUnchecked(lx, 0, lz).isOpaque
                }

                for (ly in Chunk.SIZE - 1 downTo 0) {
                    val block = chunk.getBlockUnchecked(lx, ly, lz)
                    val emission = block.lightEmission.toInt()
                    val level: Int
                    if (block.isOpaque) {
                        skyOpen = false
                        level = if (emission > 0) emission.coerceIn(MIN_LIGHT, MAX_LIGHT) else MIN_LIGHT
                    } else if (skyOpen) {
                        level = maxOf(MAX_LIGHT, emission).coerceIn(MIN_LIGHT, MAX_LIGHT)
                    } else {
                        level = if (emission > 0) emission.coerceIn(MIN_LIGHT, MAX_LIGHT) else MIN_LIGHT
                    }

                    val idx = (ly shl 8) or (lz shl 4) or lx
                    intended[idx] = level.toByte()
                    if (level > MIN_LIGHT) allDark = false
                    if (level < MAX_LIGHT) allLit = false

                    if (level > MIN_LIGHT + 1 && (!block.isOpaque || emission > 0)) {
                        localQueue[qTail] = idx
                        localDir[qTail] = DIR_NONE
                        qTail++
                    }
                }
            }
        }

        // Also seed light entering horizontally/vertically from neighboring already-loaded chunks
        val nWest = chunkLookup(chunk.chunkX - 1, chunk.chunkY, chunk.chunkZ)
        val nEast = chunkLookup(chunk.chunkX + 1, chunk.chunkY, chunk.chunkZ)
        val nBelow = if (chunk.chunkY > 0) chunkLookup(chunk.chunkX, chunk.chunkY - 1, chunk.chunkZ) else null
        val nNorth = chunkLookup(chunk.chunkX, chunk.chunkY, chunk.chunkZ - 1)
        val nSouth = chunkLookup(chunk.chunkX, chunk.chunkY, chunk.chunkZ + 1)

        if (!allLit) {
            for (a in 0 until Chunk.SIZE) {
                for (b in 0 until Chunk.SIZE) {
                    if (nWest != null) {
                        val nl = nWest.getIntendedLightUnchecked(Chunk.MASK, a, b) - 1
                        if (nl > MIN_LIGHT) {
                            val nextTail = seedLocalIfBrighter(chunk, intended, localQueue, localDir, qTail, 0, a, b, nl, Direction.EAST.ordinal.toByte())
                            if (nextTail > qTail) { qTail = nextTail; allDark = false }
                        }
                    }
                    if (nEast != null) {
                        val nl = nEast.getIntendedLightUnchecked(0, a, b) - 1
                        if (nl > MIN_LIGHT) {
                            val nextTail = seedLocalIfBrighter(chunk, intended, localQueue, localDir, qTail, Chunk.MASK, a, b, nl, Direction.WEST.ordinal.toByte())
                            if (nextTail > qTail) { qTail = nextTail; allDark = false }
                        }
                    }
                    if (nNorth != null) {
                        val nl = nNorth.getIntendedLightUnchecked(b, a, Chunk.MASK) - 1
                        if (nl > MIN_LIGHT) {
                            val nextTail = seedLocalIfBrighter(chunk, intended, localQueue, localDir, qTail, b, a, 0, nl, Direction.SOUTH.ordinal.toByte())
                            if (nextTail > qTail) { qTail = nextTail; allDark = false }
                        }
                    }
                    if (nSouth != null) {
                        val nl = nSouth.getIntendedLightUnchecked(b, a, 0) - 1
                        if (nl > MIN_LIGHT) {
                            val nextTail = seedLocalIfBrighter(chunk, intended, localQueue, localDir, qTail, b, a, Chunk.MASK, nl, Direction.NORTH.ordinal.toByte())
                            if (nextTail > qTail) { qTail = nextTail; allDark = false }
                        }
                    }
                    if (nBelow != null) {
                        val nl = nBelow.getIntendedLightUnchecked(b, Chunk.MASK, a) - 1
                        if (nl > MIN_LIGHT) {
                            val nextTail = seedLocalIfBrighter(chunk, intended, localQueue, localDir, qTail, b, 0, a, nl, Direction.UP.ordinal.toByte())
                            if (nextTail > qTail) { qTail = nextTail; allDark = false }
                        }
                    }
                }
            }
        }

        // 2. Synchronous Starlight 5-neighbor BFS within chunk for lightIntended
        while (qHead < qTail) {
            val idx = localQueue[qHead]
            val fromDir = localDir[qHead]
            qHead++

            val lx = idx and 15
            val lz = (idx shr 4) and 15
            val ly = (idx shr 8) and 15
            val level = intended[idx].toInt()
            val nextLevel = level - 1
            if (nextLevel <= MIN_LIGHT) continue

            for (d in 0 until DIRECTIONS.size) {
                if (d == fromDir.toInt()) continue
                val dir = DIRECTIONS[d]
                val nx = lx + dir.dx
                val ny = ly + dir.dy
                val nz = lz + dir.dz
                if (((nx or ny or nz) and -16) == 0) {
                    val nIdx = (ny shl 8) or (nz shl 4) or nx
                    if (!chunk.getBlockUnchecked(nx, ny, nz).isOpaque && intended[nIdx].toInt() < nextLevel) {
                        intended[nIdx] = nextLevel.toByte()
                        allDark = false
                        if (nextLevel > MIN_LIGHT + 1 && qTail < localQueue.size) {
                            localQueue[qTail] = nIdx
                            localDir[qTail] = OPPOSITE_ORDINAL[d]
                            qTail++
                        }
                    }
                }
            }
        }

        if (allDark) {
            chunk.markCompletelyDark()
            return
        }
        if (allLit) {
            chunk.markCompletelyLit()
            return
        }

        System.arraycopy(intended, 0, rendered, 0, Chunk.VOLUME)
        chunk.setLightArrays(intended, rendered)

        // Schedule adjacent loaded chunks for background lighting update if they are not uniform
        if (nWest != null && !nWest.isCompletelyLit && !nWest.isSolidOpaque) scheduleChunkLightUpdate(nWest)
        if (nEast != null && !nEast.isCompletelyLit && !nEast.isSolidOpaque) scheduleChunkLightUpdate(nEast)
        if (nNorth != null && !nNorth.isCompletelyLit && !nNorth.isSolidOpaque) scheduleChunkLightUpdate(nNorth)
        if (nSouth != null && !nSouth.isCompletelyLit && !nSouth.isSolidOpaque) scheduleChunkLightUpdate(nSouth)
    }

    private fun seedLocalIfBrighter(
        chunk: Chunk,
        intended: ByteArray,
        q: IntArray,
        dirs: ByteArray,
        qTail: Int,
        lx: Int,
        ly: Int,
        lz: Int,
        newLevel: Int,
        fromDir: Byte
    ): Int {
        if (chunk.getBlockUnchecked(lx, ly, lz).isOpaque) return qTail
        val idx = (ly shl 8) or (lz shl 4) or lx
        if (intended[idx].toInt() < newLevel) {
            intended[idx] = newLevel.toByte()
            if (newLevel > MIN_LIGHT + 1 && qTail < q.size) {
                q[qTail] = idx
                dirs[qTail] = fromDir
                return qTail + 1
            }
        }
        return qTail
    }

    /**
     * Marks [chunk] dirty for lighting and queues it for [step] unless already uniformly dark/lit.
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
     * Updates `lightIntended` synchronously using Starlight `fromDir` BFS and queues the matching
     * visual `lightRendered` update into the time-sliced queue (`maxNodesPerFrame = 500`) whenever
     * a block transitions from [oldBlock] to [newBlock] at `(wx, wy, wz)`.
     */
    fun onBlockChanged(world: World, wx: Int, wy: Int, wz: Int, oldBlock: BlockType, newBlock: BlockType) {
        if (wy !in 0 until world.sizeY) return
        val cx = wx shr 4
        val cy = wy shr 4
        val cz = wz shr 4
        val chunk = world.getChunk(cx, cy, cz) ?: return

        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK

        val oldIntended = chunk.getIntendedLightUnchecked(lx, ly, lz)
        val oldRendered = chunk.getRenderedLightUnchecked(lx, ly, lz)

        synchronized(lightLock) {
            invalidateChunkCache()

            val newEmission = newBlock.lightEmission.toInt().coerceIn(0, MAX_LIGHT)
            val skyOpenHere = !newBlock.isOpaque && hasDirectSkyAbove(world, wx, wy, wz)

            if (newBlock.isOpaque) {
                val targetLevel = if (newEmission > 0) newEmission else MIN_LIGHT
                chunk.ensureLightStorage()
                chunk.setIntendedLight(lx, ly, lz, targetLevel)

                if (oldIntended > targetLevel) {
                    // Remove light at this voxel and any obstructed skylight column directly underneath
                    propagateIntendedRemovalLocked(world, wx, wy, wz, oldIntended, checkSkyColumnBelow = (oldIntended == MAX_LIGHT))
                    enqueueRemoveLocked(wx, wy, wz, oldRendered, DIR_NONE)
                    // Clear direct skylight beam below for rendered wave as well
                    if (oldRendered == MAX_LIGHT) {
                        var yDown = wy - 1
                        while (yDown >= 0) {
                            val belowChunk = getChunkCached(world, wx shr 4, yDown shr 4, wz shr 4) ?: break
                            val bly = yDown and Chunk.MASK
                            if (belowChunk.getBlockUnchecked(lx, bly, lz).isOpaque) break
                            val belowRend = belowChunk.getRenderedLightUnchecked(lx, bly, lz)
                            if (belowRend < MAX_LIGHT) break
                            enqueueRemoveLocked(wx, yDown, wz, MAX_LIGHT, DIR_NONE)
                            yDown--
                        }
                    }
                }
                if (newEmission > 0) {
                    propagateIntendedAdditionLocked(world, wx, wy, wz, DIR_NONE)
                    enqueueAddLocked(wx, wy, wz, DIR_NONE)
                }
                chunk.needsLightUpdate = true
                markLightBoundaryChunks(world, cx, cy, cz, lx, ly, lz)
                return
            }

            // Non-opaque block (Air, Glass, Torch, etc.)
            val targetBase = when {
                skyOpenHere -> maxOf(MAX_LIGHT, newEmission)
                newEmission > 0 -> newEmission
                else -> MIN_LIGHT
            }

            if (oldBlock.lightEmission > 0 && newEmission < oldIntended) {
                // Light emitter removed (e.g., Torch broken): invalidate contribution first, then re-propagate surviving sources
                chunk.ensureLightStorage()
                chunk.setIntendedLight(lx, ly, lz, targetBase)
                propagateIntendedRemovalLocked(world, wx, wy, wz, oldIntended, checkSkyColumnBelow = false)
                if (targetBase > MIN_LIGHT) {
                    propagateIntendedAdditionLocked(world, wx, wy, wz, DIR_NONE)
                }
                // Re-pull from immediate neighbors in case another source illuminates (wx, wy, wz)
                pullFromNeighborsIntoIntendedLocked(world, wx, wy, wz)

                enqueueRemoveLocked(wx, wy, wz, oldRendered, DIR_NONE)
                chunk.needsLightUpdate = true
                markLightBoundaryChunks(world, cx, cy, cz, lx, ly, lz)
                return
            }

            // Air/transparent block opened or torch placed
            chunk.ensureLightStorage()
            var bestIntended = targetBase
            for (d in 0 until DIRECTIONS.size) {
                val dir = DIRECTIONS[d]
                val nLight = getIntendedLightGlobal(world, wx + dir.dx, wy + dir.dy, wz + dir.dz)
                if (nLight - 1 > bestIntended) {
                    bestIntended = nLight - 1
                }
            }
            bestIntended = bestIntended.coerceIn(MIN_LIGHT, MAX_LIGHT)

            if (bestIntended > oldIntended) {
                chunk.setIntendedLight(lx, ly, lz, bestIntended)
                propagateIntendedAdditionLocked(world, wx, wy, wz, DIR_NONE)
                enqueueAddLocked(wx, wy, wz, DIR_NONE)

                // If opening an opaque block uncovered direct skylight above an air column below, illuminate the column down
                if (skyOpenHere) {
                    var yDown = wy - 1
                    while (yDown >= 0) {
                        val bChunk = getChunkCached(world, wx shr 4, yDown shr 4, wz shr 4) ?: break
                        val bly = yDown and Chunk.MASK
                        if (bChunk.getBlockUnchecked(lx, bly, lz).isOpaque) break
                        if (bChunk.getIntendedLightUnchecked(lx, bly, lz) < MAX_LIGHT) {
                            bChunk.ensureLightStorage()
                            bChunk.setIntendedLight(lx, bly, lz, MAX_LIGHT)
                            propagateIntendedAdditionLocked(world, wx, yDown, wz, DIR_NONE)
                            enqueueAddLocked(wx, yDown, wz, DIR_NONE)
                        }
                        yDown--
                    }
                }
            } else if (bestIntended < oldIntended) {
                chunk.setIntendedLight(lx, ly, lz, bestIntended)
                propagateIntendedRemovalLocked(world, wx, wy, wz, oldIntended, checkSkyColumnBelow = false)
                enqueueRemoveLocked(wx, wy, wz, oldRendered, DIR_NONE)
            } else if (bestIntended > oldRendered) {
                enqueueAddLocked(wx, wy, wz, DIR_NONE)
            }

            chunk.needsLightUpdate = true
            markLightBoundaryChunks(world, cx, cy, cz, lx, ly, lz)
        }
    }

    /**
     * Seeds a light source level at `(wx, wy, wz)` in `lightIntended` and enqueues it for
     * time-sliced `lightRendered` propagation via [step].
     */
    fun seedLight(world: World, wx: Int, wy: Int, wz: Int, level: Int) {
        if (wy !in 0 until world.sizeY) return
        val chunk = world.getChunk(wx shr 4, wy shr 4, wz shr 4) ?: return
        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK
        val clamped = level.coerceIn(MIN_LIGHT, MAX_LIGHT)
        chunk.ensureLightStorage()
        chunk.setIntendedLight(lx, ly, lz, clamped)
        chunk.needsLightUpdate = true
        synchronized(lightLock) {
            enqueueAddLocked(wx, wy, wz, DIR_NONE)
        }
    }

    /**
     * Runs one time-sliced Lazyprop `lightRendered` update pass across dirty chunks and queued
     * Starlight `fromDir` BFS nodes (up to [maxNodes] updates per tick, default 500).
     */
    fun step(
        world: World,
        maxNodes: Int = maxNodesPerFrame,
        budgetNs: Long = maxBudgetNs
    ): Int {
        synchronized(lightLock) {
            if (remCount == 0 && addCount == 0 && dirtyChunks.isEmpty()) {
                lastStepNodesProcessed = 0
                lastStepSkippedChunks = 0
                totalStepsExecuted++
                return 0
            }

            val startNs = System.nanoTime()
            var nodesProcessed = 0
            var skippedChunks = 0
            invalidateChunkCache()

            // 1. Seed any perimeter discrepancies on chunks explicitly flagged dirty
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

                seedDirtyChunkBoundaryLocked(chunk)
                chunk.needsLightUpdate = false
            }

            // 2. Process darkening/removal Starlight BFS wave on lightRendered (time-sliced)
            while (nodesProcessed < maxNodes && remCount > 0) {
                if (nodesProcessed > 0 && (System.nanoTime() - startNs) >= budgetNs) break

                val wx = remX[remHead]
                val wy = remY[remHead]
                val wz = remZ[remHead]
                val oldLevel = remLevel[remHead]
                val fromDir = remFromDir[remHead]
                remHead = (remHead + 1) % remX.size
                remCount--

                processRemoveNodeLocked(world, wx, wy, wz, oldLevel, fromDir)
                nodesProcessed++
            }

            // 3. Process brightening/sync Starlight BFS wave on lightRendered (time-sliced)
            while (nodesProcessed < maxNodes && addCount > 0) {
                if (nodesProcessed > 0 && (System.nanoTime() - startNs) >= budgetNs) break

                val wx = addX[addHead]
                val wy = addY[addHead]
                val wz = addZ[addHead]
                val fromDir = addFromDir[addHead]
                addHead = (addHead + 1) % addX.size
                addCount--

                processAddNodeLocked(world, wx, wy, wz, fromDir)
                nodesProcessed++
            }

            lastStepNodesProcessed = nodesProcessed
            lastStepSkippedChunks = skippedChunks
            totalNodesProcessed += nodesProcessed
            totalStepsExecuted++
            return nodesProcessed
        }
    }

    // =========================================================================
    // Synchronous Starlight BFS Helpers for lightIntended
    // =========================================================================

    private fun propagateIntendedAdditionLocked(world: World, startX: Int, startY: Int, startZ: Int, startFromDir: Byte) {
        var head = 0
        var tail = 0
        syncAddX[tail] = startX
        syncAddY[tail] = startY
        syncAddZ[tail] = startZ
        syncAddFromDir[tail] = startFromDir
        tail++

        while (head < tail) {
            val wx = syncAddX[head]
            val wy = syncAddY[head]
            val wz = syncAddZ[head]
            val fromDir = syncAddFromDir[head]
            head++

            val chunk = getChunkCached(world, wx shr 4, wy shr 4, wz shr 4) ?: continue
            val level = chunk.getIntendedLightUnchecked(wx and Chunk.MASK, wy and Chunk.MASK, wz and Chunk.MASK)
            val nextLevel = level - 1
            if (nextLevel <= MIN_LIGHT) continue

            for (d in 0 until DIRECTIONS.size) {
                if (d == fromDir.toInt()) continue
                val dir = DIRECTIONS[d]
                val nx = wx + dir.dx
                val ny = wy + dir.dy
                val nz = wz + dir.dz
                if (ny !in 0 until world.sizeY) continue
                val nChunk = getChunkCached(world, nx shr 4, ny shr 4, nz shr 4) ?: continue
                val nlx = nx and Chunk.MASK
                val nly = ny and Chunk.MASK
                val nlz = nz and Chunk.MASK

                val neighborBlock = nChunk.getBlockUnchecked(nlx, nly, nlz)
                if (neighborBlock.isOpaque) continue

                if (nChunk.getIntendedLightUnchecked(nlx, nly, nlz) < nextLevel) {
                    nChunk.ensureLightStorage()
                    nChunk.setIntendedLight(nlx, nly, nlz, nextLevel)
                    if (nextLevel > MIN_LIGHT + 1) {
                        if (tail == syncAddX.size) growSyncAdd()
                        syncAddX[tail] = nx
                        syncAddY[tail] = ny
                        syncAddZ[tail] = nz
                        syncAddFromDir[tail] = OPPOSITE_ORDINAL[d]
                        tail++
                    }
                }
            }
        }
    }

    private fun propagateIntendedRemovalLocked(
        world: World,
        startX: Int,
        startY: Int,
        startZ: Int,
        startOldLevel: Int,
        checkSkyColumnBelow: Boolean
    ) {
        var rHead = 0
        var rTail = 0
        var reseedCount = 0

        syncRemX[rTail] = startX
        syncRemY[rTail] = startY
        syncRemZ[rTail] = startZ
        syncRemLevel[rTail] = startOldLevel
        syncRemFromDir[rTail] = DIR_NONE
        rTail++

        if (checkSkyColumnBelow) {
            var yDown = startY - 1
            val lx = startX and Chunk.MASK
            val lz = startZ and Chunk.MASK
            while (yDown >= 0) {
                val bChunk = getChunkCached(world, startX shr 4, yDown shr 4, startZ shr 4) ?: break
                val bly = yDown and Chunk.MASK
                val bBlock = bChunk.getBlockUnchecked(lx, bly, lz)
                if (bBlock.isOpaque) break
                val cur = bChunk.getIntendedLightUnchecked(lx, bly, lz)
                if (cur < MAX_LIGHT) break
                val resetVal = bBlock.lightEmission.toInt().let { if (it > 0) it else MIN_LIGHT }
                bChunk.ensureLightStorage()
                bChunk.setIntendedLight(lx, bly, lz, resetVal)
                if (rTail == syncRemX.size) growSyncRem()
                syncRemX[rTail] = startX
                syncRemY[rTail] = yDown
                syncRemZ[rTail] = startZ
                syncRemLevel[rTail] = MAX_LIGHT
                syncRemFromDir[rTail] = DIR_NONE
                rTail++
                if (bBlock.lightEmission > 0) {
                    if (reseedCount == reseedX.size) growReseed()
                    reseedX[reseedCount] = startX
                    reseedY[reseedCount] = yDown
                    reseedZ[reseedCount] = startZ
                    reseedCount++
                }
                yDown--
            }
        }

        while (rHead < rTail) {
            val wx = syncRemX[rHead]
            val wy = syncRemY[rHead]
            val wz = syncRemZ[rHead]
            val oldLevel = syncRemLevel[rHead]
            val fromDir = syncRemFromDir[rHead]
            rHead++

            for (d in 0 until DIRECTIONS.size) {
                if (d == fromDir.toInt()) continue
                val dir = DIRECTIONS[d]
                val nx = wx + dir.dx
                val ny = wy + dir.dy
                val nz = wz + dir.dz
                if (ny !in 0 until world.sizeY) continue
                val nChunk = getChunkCached(world, nx shr 4, ny shr 4, nz shr 4) ?: continue
                val nlx = nx and Chunk.MASK
                val nly = ny and Chunk.MASK
                val nlz = nz and Chunk.MASK

                val nBlock = nChunk.getBlockUnchecked(nlx, nly, nlz)
                val neighborLight = nChunk.getIntendedLightUnchecked(nlx, nly, nlz)
                if (neighborLight <= MIN_LIGHT) continue

                val emission = nBlock.lightEmission.toInt()
                val isDirectSky = !nBlock.isOpaque && neighborLight == MAX_LIGHT && hasDirectSkyAbove(world, nx, ny, nz)

                if (emission > 0 || isDirectSky || neighborLight >= oldLevel) {
                    // Surviving independent light source -> reseed after invalidation finishes
                    if (reseedCount == reseedX.size) growReseed()
                    reseedX[reseedCount] = nx
                    reseedY[reseedCount] = ny
                    reseedZ[reseedCount] = nz
                    reseedCount++
                } else if (!nBlock.isOpaque) {
                    nChunk.ensureLightStorage()
                    nChunk.setIntendedLight(nlx, nly, nlz, MIN_LIGHT)
                    if (rTail == syncRemX.size) growSyncRem()
                    syncRemX[rTail] = nx
                    syncRemY[rTail] = ny
                    syncRemZ[rTail] = nz
                    syncRemLevel[rTail] = neighborLight
                    syncRemFromDir[rTail] = OPPOSITE_ORDINAL[d]
                    rTail++
                }
            }
        }

        // Re-propagate from all surviving fringe sources
        for (i in 0 until reseedCount) {
            val rx = reseedX[i]
            val ry = reseedY[i]
            val rz = reseedZ[i]
            val rChunk = getChunkCached(world, rx shr 4, ry shr 4, rz shr 4) ?: continue
            val rlx = rx and Chunk.MASK
            val rly = ry and Chunk.MASK
            val rlz = rz and Chunk.MASK
            val rBlock = rChunk.getBlockUnchecked(rlx, rly, rlz)
            val emission = rBlock.lightEmission.toInt()
            if (emission > 0 && rChunk.getIntendedLightUnchecked(rlx, rly, rlz) < emission) {
                rChunk.setIntendedLight(rlx, rly, rlz, emission)
            }
            propagateIntendedAdditionLocked(world, rx, ry, rz, DIR_NONE)
        }
    }

    private fun pullFromNeighborsIntoIntendedLocked(world: World, wx: Int, wy: Int, wz: Int) {
        val chunk = getChunkCached(world, wx shr 4, wy shr 4, wz shr 4) ?: return
        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK
        var best = chunk.getIntendedLightUnchecked(lx, ly, lz)
        for (d in 0 until DIRECTIONS.size) {
            val dir = DIRECTIONS[d]
            val nLight = getIntendedLightGlobal(world, wx + dir.dx, wy + dir.dy, wz + dir.dz)
            if (nLight - 1 > best) {
                best = nLight - 1
            }
        }
        if (best > chunk.getIntendedLightUnchecked(lx, ly, lz)) {
            chunk.setIntendedLight(lx, ly, lz, best)
            propagateIntendedAdditionLocked(world, wx, wy, wz, DIR_NONE)
        }
    }

    private fun hasDirectSkyAbove(world: World, wx: Int, wy: Int, wz: Int): Boolean {
        val aboveY = wy + 1
        if (aboveY >= world.sizeY) return true
        val aboveChunk = getChunkCached(world, wx shr 4, aboveY shr 4, wz shr 4) ?: return true
        val lx = wx and Chunk.MASK
        val ly = aboveY and Chunk.MASK
        val lz = wz and Chunk.MASK
        return !aboveChunk.getBlockUnchecked(lx, ly, lz).isOpaque &&
            aboveChunk.getIntendedLightUnchecked(lx, ly, lz) >= MAX_LIGHT
    }

    // =========================================================================
    // Time-Sliced Starlight BFS Helpers for lightRendered
    // =========================================================================

    private fun seedDirtyChunkBoundaryLocked(chunk: Chunk) {
        val baseX = chunk.chunkX * Chunk.SIZE
        val baseY = chunk.chunkY * Chunk.SIZE
        val baseZ = chunk.chunkZ * Chunk.SIZE

        for (ly in 0 until Chunk.SIZE) {
            for (lz in 0 until Chunk.SIZE) {
                for (lx in 0 until Chunk.SIZE) {
                    val isEdge = lx == 0 || lx == Chunk.MASK || ly == 0 || ly == Chunk.MASK || lz == 0 || lz == Chunk.MASK
                    if (!isEdge) continue
                    val intended = chunk.getIntendedLightUnchecked(lx, ly, lz)
                    val rendered = chunk.getRenderedLightUnchecked(lx, ly, lz)
                    if (intended != rendered) {
                        if (intended > rendered) {
                            enqueueAddLocked(baseX + lx, baseY + ly, baseZ + lz, DIR_NONE)
                        } else {
                            enqueueRemoveLocked(baseX + lx, baseY + ly, baseZ + lz, rendered, DIR_NONE)
                        }
                    }
                }
            }
        }
    }

    private fun processRemoveNodeLocked(world: World, wx: Int, wy: Int, wz: Int, oldLevel: Int, fromDir: Byte) {
        if (wy !in 0 until world.sizeY) return
        val chunk = getChunkCached(world, wx shr 4, wy shr 4, wz shr 4) ?: return
        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK

        val intendedHere = chunk.getIntendedLightUnchecked(lx, ly, lz)
        val blockHere = chunk.getBlockUnchecked(lx, ly, lz)
        chunk.setRenderedLight(lx, ly, lz, if (blockHere.isOpaque) intendedHere else MIN_LIGHT)
        if (!blockHere.isOpaque && blockHere.lightEmission > 0) {
            enqueueAddLocked(wx, wy, wz, DIR_NONE)
        }

        for (d in 0 until DIRECTIONS.size) {
            if (d == fromDir.toInt()) continue
            val dir = DIRECTIONS[d]
            val nx = wx + dir.dx
            val ny = wy + dir.dy
            val nz = wz + dir.dz
            if (ny !in 0 until world.sizeY) continue
            val nChunk = getChunkCached(world, nx shr 4, ny shr 4, nz shr 4) ?: continue
            val nlx = nx and Chunk.MASK
            val nly = ny and Chunk.MASK
            val nlz = nz and Chunk.MASK

            val nBlock = nChunk.getBlockUnchecked(nlx, nly, nlz)
            val neighborRendered = nChunk.getRenderedLightUnchecked(nlx, nly, nlz)

            if (neighborRendered > MIN_LIGHT && neighborRendered < oldLevel && nBlock.lightEmission <= 0) {
                nChunk.setRenderedLight(nlx, nly, nlz, MIN_LIGHT)
                enqueueRemoveLocked(nx, ny, nz, neighborRendered, OPPOSITE_ORDINAL[d])
            } else if (neighborRendered >= oldLevel || nBlock.lightEmission > 0) {
                enqueueAddLocked(nx, ny, nz, DIR_NONE)
            }
        }
    }

    private fun processAddNodeLocked(world: World, wx: Int, wy: Int, wz: Int, fromDir: Byte) {
        if (wy !in 0 until world.sizeY) return
        val chunk = getChunkCached(world, wx shr 4, wy shr 4, wz shr 4) ?: return
        val lx = wx and Chunk.MASK
        val ly = wy and Chunk.MASK
        val lz = wz and Chunk.MASK

        val intended = chunk.getIntendedLightUnchecked(lx, ly, lz)
        if (fromDir != DIR_NONE && chunk.getRenderedLightUnchecked(lx, ly, lz) > intended) return
        chunk.setRenderedLight(lx, ly, lz, intended)

        val nextWaveLevel = intended - 1
        if (nextWaveLevel <= MIN_LIGHT) return

        for (d in 0 until DIRECTIONS.size) {
            if (d == fromDir.toInt()) continue
            val dir = DIRECTIONS[d]
            val nx = wx + dir.dx
            val ny = wy + dir.dy
            val nz = wz + dir.dz
            if (ny !in 0 until world.sizeY) continue
            val nChunk = getChunkCached(world, nx shr 4, ny shr 4, nz shr 4) ?: continue
            val nlx = nx and Chunk.MASK
            val nly = ny and Chunk.MASK
            val nlz = nz and Chunk.MASK

            val neighborBlock = nChunk.getBlockUnchecked(nlx, nly, nlz)
            if (neighborBlock.isOpaque && neighborBlock.lightEmission <= 0) continue

            val neighborIntended = nChunk.getIntendedLightUnchecked(nlx, nly, nlz)
            val targetIntended = if (neighborIntended < nextWaveLevel) {
                nChunk.ensureLightStorage()
                nChunk.setIntendedLight(nlx, nly, nlz, nextWaveLevel)
                nextWaveLevel
            } else {
                neighborIntended
            }

            val neighborRendered = nChunk.getRenderedLightUnchecked(nlx, nly, nlz)
            if (neighborRendered < targetIntended && targetIntended > MIN_LIGHT) {
                // Claim rendered light on enqueue to prevent combinatorial queue duplication
                nChunk.ensureLightStorage()
                nChunk.setRenderedLight(nlx, nly, nlz, targetIntended)
                nChunk.needsLightUpdate = true
                enqueueAddLocked(nx, ny, nz, OPPOSITE_ORDINAL[d])
            }
        }
    }

    private fun markLightBoundaryChunks(world: World, cx: Int, cy: Int, cz: Int, lx: Int, ly: Int, lz: Int) {
        if (lx == 0) getChunkCached(world, cx - 1, cy, cz)?.let { if (!it.isCompletelyDark) it.markDirty() }
        if (lx == Chunk.MASK) getChunkCached(world, cx + 1, cy, cz)?.let { if (!it.isCompletelyDark) it.markDirty() }
        if (ly == 0 && cy > 0) getChunkCached(world, cx, cy - 1, cz)?.let { if (!it.isCompletelyDark) it.markDirty() }
        if (ly == Chunk.MASK && cy < world.chunksY - 1) getChunkCached(world, cx, cy + 1, cz)?.let { if (!it.isCompletelyDark) it.markDirty() }
        if (lz == 0) getChunkCached(world, cx, cy, cz - 1)?.let { if (!it.isCompletelyDark) it.markDirty() }
        if (lz == Chunk.MASK) getChunkCached(world, cx, cy, cz + 1)?.let { if (!it.isCompletelyDark) it.markDirty() }
    }

    private fun getIntendedLightGlobal(world: World, wx: Int, wy: Int, wz: Int): Int {
        if (wy !in 0 until world.sizeY) {
            return if (wy >= world.sizeY) MAX_LIGHT else MIN_LIGHT
        }
        val chunk = getChunkCached(world, wx shr 4, wy shr 4, wz shr 4) ?: return MAX_LIGHT
        return chunk.getIntendedLightUnchecked(wx and Chunk.MASK, wy and Chunk.MASK, wz and Chunk.MASK)
    }

    private fun enqueueAddLocked(x: Int, y: Int, z: Int, fromDir: Byte) {
        if (addCount == addX.size) {
            val newCap = addX.size * 2
            val nx = IntArray(newCap)
            val ny = IntArray(newCap)
            val nz = IntArray(newCap)
            val nd = ByteArray(newCap)
            for (i in 0 until addCount) {
                val idx = (addHead + i) % addX.size
                nx[i] = addX[idx]
                ny[i] = addY[idx]
                nz[i] = addZ[idx]
                nd[i] = addFromDir[idx]
            }
            addX = nx
            addY = ny
            addZ = nz
            addFromDir = nd
            addHead = 0
            addTail = addCount
        }
        addX[addTail] = x
        addY[addTail] = y
        addZ[addTail] = z
        addFromDir[addTail] = fromDir
        addTail = (addTail + 1) % addX.size
        addCount++
    }

    private fun enqueueRemoveLocked(x: Int, y: Int, z: Int, oldLevel: Int, fromDir: Byte) {
        if (remCount == remX.size) {
            val newCap = remX.size * 2
            val nx = IntArray(newCap)
            val ny = IntArray(newCap)
            val nz = IntArray(newCap)
            val nl = IntArray(newCap)
            val nd = ByteArray(newCap)
            for (i in 0 until remCount) {
                val idx = (remHead + i) % remX.size
                nx[i] = remX[idx]
                ny[i] = remY[idx]
                nz[i] = remZ[idx]
                nl[i] = remLevel[idx]
                nd[i] = remFromDir[idx]
            }
            remX = nx
            remY = ny
            remZ = nz
            remLevel = nl
            remFromDir = nd
            remHead = 0
            remTail = remCount
        }
        remX[remTail] = x
        remY[remTail] = y
        remZ[remTail] = z
        remLevel[remTail] = oldLevel
        remFromDir[remTail] = fromDir
        remTail = (remTail + 1) % remX.size
        remCount++
    }

    private fun growSyncAdd() {
        val next = syncAddX.size * 2
        syncAddX = syncAddX.copyOf(next)
        syncAddY = syncAddY.copyOf(next)
        syncAddZ = syncAddZ.copyOf(next)
        syncAddFromDir = syncAddFromDir.copyOf(next)
    }

    private fun growSyncRem() {
        val next = syncRemX.size * 2
        syncRemX = syncRemX.copyOf(next)
        syncRemY = syncRemY.copyOf(next)
        syncRemZ = syncRemZ.copyOf(next)
        syncRemLevel = syncRemLevel.copyOf(next)
        syncRemFromDir = syncRemFromDir.copyOf(next)
    }

    private fun growReseed() {
        val next = reseedX.size * 2
        reseedX = reseedX.copyOf(next)
        reseedY = reseedY.copyOf(next)
        reseedZ = reseedZ.copyOf(next)
    }

    companion object {
        const val MIN_LIGHT = Chunk.MIN_LIGHT
        const val DEFAULT_AMBIENT_LIGHT = Chunk.DEFAULT_AMBIENT_LIGHT
        const val MAX_LIGHT = Chunk.MAX_LIGHT
        const val DEFAULT_MAX_NODES_PER_FRAME = 500
        const val DEFAULT_MAX_BUDGET_NS = 1_500_000L // 1.5 ms per frame budget
        private const val INITIAL_QUEUE_CAPACITY = 1024
        private const val DIR_NONE: Byte = -1
        private val DIRECTIONS = Direction.values()
        private val OPPOSITE_ORDINAL = ByteArray(DIRECTIONS.size) { idx ->
            DIRECTIONS[idx].opposite.ordinal.toByte()
        }
    }
}
