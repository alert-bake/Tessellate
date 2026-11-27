package com.nitrous.tessellate.player

import com.nitrous.tessellate.item.Inventory
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.world.World
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First-person player controller handling movement, strafing, crouching,
 * gravity, ground detection, and axis-separated AABB voxel collision.
 */
class Player(
    var posX: Double = 24.5,
    var posY: Double = 20.0,
    var posZ: Double = 24.5
) {
    /** 36-slot player inventory (0..8 hotbar, 9..35 main inventory). */
    val inventory = Inventory(size = Inventory.TOTAL_SIZE)

    // Camera Look Orientation
    @Volatile
    var yaw: Float = 0f
    @Volatile
    var pitch: Float = 0f
        set(value) {
            field = value.coerceIn(-89f, 89f)
        }

    // Velocity (blocks per second)
    @Volatile
    var velX: Double = 0.0
    @Volatile
    var velY: Double = 0.0
    @Volatile
    var velZ: Double = 0.0

    // Physical dimensions
    val radius: Double = 0.3 // 0.6 block width and depth
    val standingHeight: Double = 1.8
    val crouchHeight: Double = 1.3
    val standingEyeHeight: Double = 1.62
    val crouchEyeHeight: Double = 1.15

    // Movement speeds & physics constants
    val walkSpeed: Double = 4.3       // blocks/second
    val flySpeed: Double = 8.5        // blocks/second
    val crouchSpeed: Double = 2.2     // blocks/second
    val jumpSpeed: Double = 8.2       // blocks/second
    val gravity: Double = -24.0       // blocks/second^2
    val terminalVelocity: Double = -35.0

    // Player States
    @Volatile
    var isCrouching: Boolean = false
        private set
    @Volatile
    var wantsCrouch: Boolean = false
    @Volatile
    var isGrounded: Boolean = false
        private set
    @Volatile
    var isInstaMine: Boolean = false
    @Volatile
    var isFlying: Boolean = false
    @Volatile
    var autoJump: Boolean = false

    val currentHeight: Double
        get() = if (isCrouching) crouchHeight else standingHeight

    val currentEyeHeight: Double
        get() = if (isCrouching) crouchEyeHeight else standingEyeHeight

    val eyeY: Double
        get() = posY + currentEyeHeight

    // Reusable bounding box
    val boundingBox = AABB(
        posX - radius, posY, posZ - radius,
        posX + radius, posY + currentHeight, posZ + radius
    )

    // Pre-allocated mutable scratch AABBs to ensure zero allocations during tick/update loops
    private val scratchFeetStepBox = AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val scratchClearanceBox = AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val scratchStandingTestBox = AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val scratchMoveTestBox = AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val scratchBlockBox = AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    private val scratchGroundTestBox = AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    fun updateBoundingBox() {
        boundingBox.set(
            posX - radius, posY, posZ - radius,
            posX + radius, posY + currentHeight, posZ + radius
        )
    }

    /**
     * Triggers an upward jump if the player is currently grounded (or nudges upward while flying).
     */
    fun jump(): Boolean {
        if (isFlying) {
            velY = flySpeed * 0.75
            return true
        }
        if (!isGrounded) return false
        velY = jumpSpeed
        isGrounded = false
        return true
    }

    /**
     * Updates player physics for a frame with delta time and touch inputs.
     */
    fun update(
        deltaTime: Float,
        world: World,
        moveForward: Float, // +1 for forward, -1 for backward
        moveStrafe: Float,  // +1 for right, -1 for left
        crouchInput: Boolean,
        jumpInput: Boolean = false
    ) {
        val dt = deltaTime.coerceIn(0.001f, 0.05f).toDouble()

        // 1. Process Crouching state with ceiling clearance check (crouch button doubles as descend while flying)
        if (!isFlying) {
            handleCrouching(crouchInput, world)
        } else {
            wantsCrouch = false
            isCrouching = false
            updateBoundingBox()
        }

        // 2. Compute Directional Movement & Normalize Diagonals
        var inputX = moveStrafe.toDouble()
        var inputZ = moveForward.toDouble()
        val inputMagnitude = sqrt(inputX * inputX + inputZ * inputZ)
        if (inputMagnitude > 1.0) {
            inputX /= inputMagnitude
            inputZ /= inputMagnitude
        }

        val yawRad = Math.toRadians(yaw.toDouble())
        // In our coordinate system:
        // Forward vector on XZ plane: (sin(yaw), -cos(yaw))
        // Right strafe vector on XZ plane: (cos(yaw), sin(yaw))
        val forwardDirX = sin(yawRad)
        val forwardDirZ = -cos(yawRad)
        val rightDirX = cos(yawRad)
        val rightDirZ = sin(yawRad)

        val activeSpeed = when {
            isFlying -> flySpeed
            isCrouching -> crouchSpeed
            else -> walkSpeed
        }
        velX = (forwardDirX * inputZ + rightDirX * inputX) * activeSpeed
        velZ = (forwardDirZ * inputZ + rightDirZ * inputX) * activeSpeed

        // 3. Verify Ground Support & Apply Gravity or Flight Vertical Velocity
        checkGroundSupport(world)
        if (isFlying) {
            velY = when {
                jumpInput && !crouchInput -> flySpeed * 0.75
                crouchInput && !jumpInput -> -flySpeed * 0.75
                else -> 0.0
            }
        } else {
            if (autoJump && isGrounded && inputMagnitude > 0.1) {
                tryAutoJump(world, velX * dt, velZ * dt)
            }
            if (!isGrounded) {
                velY += gravity * dt
                if (velY < terminalVelocity) {
                    velY = terminalVelocity
                }
            } else {
                if (velY < 0.0) velY = 0.0
            }
        }

        // 4. Move with 3-Axis Separated AABB Collision Resolution
        moveWithCollision(velX * dt, velY * dt, velZ * dt, world)
    }

    private fun tryAutoJump(world: World, stepDx: Double, stepDz: Double) {
        val probeLen = sqrt(stepDx * stepDx + stepDz * stepDz)
        if (probeLen < 1e-6) return
        val dirX = stepDx / probeLen
        val dirZ = stepDz / probeLen
        val lookAheadDist = (radius + 0.18)

        scratchFeetStepBox.set(
            posX + dirX * lookAheadDist - radius * 0.8,
            posY + 0.05,
            posZ + dirZ * lookAheadDist - radius * 0.8,
            posX + dirX * lookAheadDist + radius * 0.8,
            posY + 0.95,
            posZ + dirZ * lookAheadDist + radius * 0.8
        )
        if (!hasBlockCollision(scratchFeetStepBox, world)) return

        scratchClearanceBox.set(
            posX + dirX * lookAheadDist - radius * 0.8,
            posY + 1.05,
            posZ + dirZ * lookAheadDist - radius * 0.8,
            posX + dirX * lookAheadDist + radius * 0.8,
            posY + 1.05 + currentHeight,
            posZ + dirZ * lookAheadDist + radius * 0.8
        )
        if (!hasBlockCollision(scratchClearanceBox, world)) {
            jump()
        }
    }

    private fun handleCrouching(crouchInput: Boolean, world: World) {
        wantsCrouch = crouchInput
        if (wantsCrouch) {
            isCrouching = true
        } else if (isCrouching) {
            // Only un-crouch if there is vertical clearance above the player
            scratchStandingTestBox.set(
                posX - radius, posY, posZ - radius,
                posX + radius, posY + standingHeight, posZ + radius
            )
            val ceilingObstructed = hasBlockCollision(scratchStandingTestBox, world)
            if (!ceilingObstructed) {
                isCrouching = false
            }
        }
        updateBoundingBox()
    }

    private fun moveWithCollision(dx: Double, dy: Double, dz: Double, world: World) {
        var resolvedDx = dx
        var resolvedDy = dy
        var resolvedDz = dz

        // --- Y Axis Collision (Ground / Ceiling) ---
        if (resolvedDy != 0.0) {
            val testBoxY = scratchMoveTestBox
            testBoxY.set(
                posX - radius, posY + resolvedDy, posZ - radius,
                posX + radius, posY + currentHeight + resolvedDy, posZ + radius
            )
            val minX = floor(testBoxY.minX).toLong()
            val maxX = floor(testBoxY.maxX).toLong()
            val minY = floor(testBoxY.minY).toLong()
            val maxY = floor(testBoxY.maxY).toLong()
            val minZ = floor(testBoxY.minZ).toLong()
            val maxZ = floor(testBoxY.maxZ).toLong()

            var collidedY = false
            for (bx in minX..maxX) {
                for (by in minY..maxY) {
                    for (bz in minZ..maxZ) {
                        val block = world.getBlock(bx, by, bz)
                        if (block.isSolid) {
                            val blockBox = scratchBlockBox
                            blockBox.set(
                                bx.toDouble(), by.toDouble(), bz.toDouble(),
                                bx + 1.0, by + 1.0, bz + 1.0
                            )
                            if (testBoxY.intersects(blockBox)) {
                                if (resolvedDy < 0.0) {
                                    // Landed on block top
                                    posY = blockBox.maxY
                                    resolvedDy = 0.0
                                    velY = 0.0
                                    isGrounded = true
                                    collidedY = true
                                } else if (resolvedDy > 0.0) {
                                    // Hit block ceiling
                                    posY = blockBox.minY - currentHeight
                                    resolvedDy = 0.0
                                    velY = 0.0
                                    collidedY = true
                                }
                                break
                            }
                        }
                    }
                    if (collidedY) break
                }
                if (collidedY) break
            }

            if (!collidedY) {
                posY += resolvedDy
            }
        }

        // Test if ground is beneath player feet
        checkGroundSupport(world)

        // --- X Axis Collision ---
        if (resolvedDx != 0.0) {
            val testBoxX = scratchMoveTestBox
            testBoxX.set(
                posX + resolvedDx - radius, posY + 0.05, posZ - radius,
                posX + resolvedDx + radius, posY + currentHeight - 0.05, posZ + radius
            )
            val minX = floor(testBoxX.minX).toLong()
            val maxX = floor(testBoxX.maxX).toLong()
            val minY = floor(testBoxX.minY).toLong()
            val maxY = floor(testBoxX.maxY).toLong()
            val minZ = floor(testBoxX.minZ).toLong()
            val maxZ = floor(testBoxX.maxZ).toLong()

            var collidedX = false
            for (bx in minX..maxX) {
                for (by in minY..maxY) {
                    for (bz in minZ..maxZ) {
                        val block = world.getBlock(bx, by, bz)
                        if (block.isSolid) {
                            val blockBox = scratchBlockBox
                            blockBox.set(
                                bx.toDouble(), by.toDouble(), bz.toDouble(),
                                bx + 1.0, by + 1.0, bz + 1.0
                            )
                            if (testBoxX.intersects(blockBox)) {
                                if (resolvedDx > 0.0) {
                                    posX = blockBox.minX - radius - 0.001
                                } else {
                                    posX = blockBox.maxX + radius + 0.001
                                }
                                resolvedDx = 0.0
                                velX = 0.0
                                collidedX = true
                                break
                            }
                        }
                    }
                    if (collidedX) break
                }
                if (collidedX) break
            }

            if (!collidedX) {
                posX += resolvedDx
            }
        }

        // --- Z Axis Collision ---
        if (resolvedDz != 0.0) {
            val testBoxZ = scratchMoveTestBox
            testBoxZ.set(
                posX - radius, posY + 0.05, posZ + resolvedDz - radius,
                posX + radius, posY + currentHeight - 0.05, posZ + resolvedDz + radius
            )
            val minX = floor(testBoxZ.minX).toLong()
            val maxX = floor(testBoxZ.maxX).toLong()
            val minY = floor(testBoxZ.minY).toLong()
            val maxY = floor(testBoxZ.maxY).toLong()
            val minZ = floor(testBoxZ.minZ).toLong()
            val maxZ = floor(testBoxZ.maxZ).toLong()

            var collidedZ = false
            for (bx in minX..maxX) {
                for (by in minY..maxY) {
                    for (bz in minZ..maxZ) {
                        val block = world.getBlock(bx, by, bz)
                        if (block.isSolid) {
                            val blockBox = scratchBlockBox
                            blockBox.set(
                                bx.toDouble(), by.toDouble(), bz.toDouble(),
                                bx + 1.0, by + 1.0, bz + 1.0
                            )
                            if (testBoxZ.intersects(blockBox)) {
                                if (resolvedDz > 0.0) {
                                    posZ = blockBox.minZ - radius - 0.001
                                } else {
                                    posZ = blockBox.maxZ + radius + 0.001
                                }
                                resolvedDz = 0.0
                                velZ = 0.0
                                collidedZ = true
                                break
                            }
                        }
                    }
                    if (collidedZ) break
                }
                if (collidedZ) break
            }

            if (!collidedZ) {
                posZ += resolvedDz
            }
        }

        // World boundary containment (only clamp X/Z/Y ceiling in finite worlds; allow vertical /fpptest above sizeY in infinite worlds)
        if (!world.isInfinite) {
            posX = posX.coerceIn(radius + 0.05, world.sizeX - radius - 0.05)
            posZ = posZ.coerceIn(radius + 0.05, world.sizeZ - radius - 0.05)
            posY = posY.coerceIn(0.1, world.sizeY - currentHeight - 0.1)
        } else if (posY < 0.1) {
            posY = 0.1
        }

        // Void recovery: respawn on surface at current X/Z if fallen into void
        if (posY <= 0.2) {
            spawnAtSurface(world, posX, posZ)
        }

        updateBoundingBox()
    }

    private fun checkGroundSupport(world: World) {
        scratchGroundTestBox.set(
            posX - radius + 0.05, posY - 0.08, posZ - radius + 0.05,
            posX + radius - 0.05, posY + 0.01, posZ + radius - 0.05
        )
        isGrounded = hasBlockCollision(scratchGroundTestBox, world)
    }

    fun hasBlockCollision(aabb: AABB, world: World): Boolean {
        val minX = floor(aabb.minX).toLong()
        val maxX = floor(aabb.maxX).toLong()
        val minY = floor(aabb.minY).toLong()
        val maxY = floor(aabb.maxY).toLong()
        val minZ = floor(aabb.minZ).toLong()
        val maxZ = floor(aabb.maxZ).toLong()

        val blockBox = scratchBlockBox
        for (bx in minX..maxX) {
            for (by in minY..maxY) {
                for (bz in minZ..maxZ) {
                    val block = world.getBlock(bx, by, bz)
                    if (block.isSolid) {
                        blockBox.set(
                            bx.toDouble(), by.toDouble(), bz.toDouble(),
                            bx + 1.0, by + 1.0, bz + 1.0
                        )
                        if (aabb.intersects(blockBox)) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    fun spawnAtSurface(world: World, spawnX: Double = 24.5, spawnZ: Double = 24.5) {
        posX = spawnX
        posZ = spawnZ
        val surfaceY = world.getSurfaceHeight(floor(spawnX).toLong(), floor(spawnZ).toLong())
        posY = surfaceY + 1.05
        velX = 0.0
        velY = 0.0
        velZ = 0.0
        isGrounded = true
        isCrouching = false
        pitch = 0f
        yaw = 45f
        updateBoundingBox()
    }
}
