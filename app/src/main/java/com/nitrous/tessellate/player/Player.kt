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
    var posX: Float = 24.5f,
    var posY: Float = 20.0f,
    var posZ: Float = 24.5f
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
    var velX: Float = 0f
    @Volatile
    var velY: Float = 0f
    @Volatile
    var velZ: Float = 0f

    // Physical dimensions
    val radius: Float = 0.3f // 0.6 block width and depth
    val standingHeight: Float = 1.8f
    val crouchHeight: Float = 1.3f
    val standingEyeHeight: Float = 1.62f
    val crouchEyeHeight: Float = 1.15f

    // Movement speeds & physics constants
    val walkSpeed: Float = 4.3f       // blocks/second
    val flySpeed: Float = 8.5f        // blocks/second
    val crouchSpeed: Float = 2.2f     // blocks/second
    val jumpSpeed: Float = 8.2f       // blocks/second
    val gravity: Float = -24.0f       // blocks/second^2
    val terminalVelocity: Float = -35.0f

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

    val currentHeight: Float
        get() = if (isCrouching) crouchHeight else standingHeight

    val currentEyeHeight: Float
        get() = if (isCrouching) crouchEyeHeight else standingEyeHeight

    val eyeY: Float
        get() = posY + currentEyeHeight

    // Reusable bounding box
    val boundingBox = AABB(
        posX - radius, posY, posZ - radius,
        posX + radius, posY + currentHeight, posZ + radius
    )

    // Pre-allocated mutable scratch AABBs to ensure zero allocations during tick/update loops
    private val scratchFeetStepBox = AABB(0f, 0f, 0f, 0f, 0f, 0f)
    private val scratchClearanceBox = AABB(0f, 0f, 0f, 0f, 0f, 0f)
    private val scratchStandingTestBox = AABB(0f, 0f, 0f, 0f, 0f, 0f)
    private val scratchMoveTestBox = AABB(0f, 0f, 0f, 0f, 0f, 0f)
    private val scratchBlockBox = AABB(0f, 0f, 0f, 0f, 0f, 0f)
    private val scratchGroundTestBox = AABB(0f, 0f, 0f, 0f, 0f, 0f)

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
            velY = flySpeed * 0.75f
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
        val dt = deltaTime.coerceIn(0.001f, 0.05f)

        // 1. Process Crouching state with ceiling clearance check (crouch button doubles as descend while flying)
        if (!isFlying) {
            handleCrouching(crouchInput, world)
        } else {
            wantsCrouch = false
            isCrouching = false
            updateBoundingBox()
        }

        // 2. Compute Directional Movement & Normalize Diagonals
        var inputX = moveStrafe
        var inputZ = moveForward
        val inputMagnitude = sqrt(inputX * inputX + inputZ * inputZ)
        if (inputMagnitude > 1.0f) {
            inputX /= inputMagnitude
            inputZ /= inputMagnitude
        }

        val yawRad = Math.toRadians(yaw.toDouble())
        // In our coordinate system:
        // Forward vector on XZ plane: (sin(yaw), -cos(yaw))
        // Right strafe vector on XZ plane: (cos(yaw), sin(yaw))
        val forwardDirX = sin(yawRad).toFloat()
        val forwardDirZ = -cos(yawRad).toFloat()
        val rightDirX = cos(yawRad).toFloat()
        val rightDirZ = sin(yawRad).toFloat()

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
                jumpInput && !crouchInput -> flySpeed * 0.75f
                crouchInput && !jumpInput -> -flySpeed * 0.75f
                else -> 0f
            }
        } else {
            if (autoJump && isGrounded && inputMagnitude > 0.1f) {
                tryAutoJump(world, velX * dt, velZ * dt)
            }
            if (!isGrounded) {
                velY += gravity * dt
                if (velY < terminalVelocity) {
                    velY = terminalVelocity
                }
            } else {
                if (velY < 0f) velY = 0f
            }
        }

        // 4. Move with 3-Axis Separated AABB Collision Resolution
        moveWithCollision(velX * dt, velY * dt, velZ * dt, world)
    }

    private fun tryAutoJump(world: World, stepDx: Float, stepDz: Float) {
        val probeLen = sqrt(stepDx * stepDx + stepDz * stepDz)
        if (probeLen < 1e-4f) return
        val dirX = stepDx / probeLen
        val dirZ = stepDz / probeLen
        val lookAheadDist = (radius + 0.18f)

        scratchFeetStepBox.set(
            posX + dirX * lookAheadDist - radius * 0.8f,
            posY + 0.05f,
            posZ + dirZ * lookAheadDist - radius * 0.8f,
            posX + dirX * lookAheadDist + radius * 0.8f,
            posY + 0.95f,
            posZ + dirZ * lookAheadDist + radius * 0.8f
        )
        if (!hasBlockCollision(scratchFeetStepBox, world)) return

        scratchClearanceBox.set(
            posX + dirX * lookAheadDist - radius * 0.8f,
            posY + 1.05f,
            posZ + dirZ * lookAheadDist - radius * 0.8f,
            posX + dirX * lookAheadDist + radius * 0.8f,
            posY + 1.05f + currentHeight,
            posZ + dirZ * lookAheadDist + radius * 0.8f
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

    private fun moveWithCollision(dx: Float, dy: Float, dz: Float, world: World) {
        var resolvedDx = dx
        var resolvedDy = dy
        var resolvedDz = dz

        // --- Y Axis Collision (Ground / Ceiling) ---
        if (resolvedDy != 0f) {
            val testBoxY = scratchMoveTestBox
            testBoxY.set(
                posX - radius, posY + resolvedDy, posZ - radius,
                posX + radius, posY + currentHeight + resolvedDy, posZ + radius
            )
            val minX = floor(testBoxY.minX).toInt()
            val maxX = floor(testBoxY.maxX).toInt()
            val minY = floor(testBoxY.minY).toInt()
            val maxY = floor(testBoxY.maxY).toInt()
            val minZ = floor(testBoxY.minZ).toInt()
            val maxZ = floor(testBoxY.maxZ).toInt()

            var collidedY = false
            for (bx in minX..maxX) {
                for (by in minY..maxY) {
                    for (bz in minZ..maxZ) {
                        val block = world.getBlock(bx, by, bz)
                        if (block.isSolid) {
                            val blockBox = scratchBlockBox
                            blockBox.set(bx.toFloat(), by.toFloat(), bz.toFloat(), bx + 1f, by + 1f, bz + 1f)
                            if (testBoxY.intersects(blockBox)) {
                                if (resolvedDy < 0f) {
                                    // Landed on block top
                                    posY = blockBox.maxY
                                    resolvedDy = 0f
                                    velY = 0f
                                    isGrounded = true
                                    collidedY = true
                                } else if (resolvedDy > 0f) {
                                    // Hit block ceiling
                                    posY = blockBox.minY - currentHeight
                                    resolvedDy = 0f
                                    velY = 0f
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
        if (resolvedDx != 0f) {
            val testBoxX = scratchMoveTestBox
            testBoxX.set(
                posX + resolvedDx - radius, posY + 0.05f, posZ - radius,
                posX + resolvedDx + radius, posY + currentHeight - 0.05f, posZ + radius
            )
            val minX = floor(testBoxX.minX).toInt()
            val maxX = floor(testBoxX.maxX).toInt()
            val minY = floor(testBoxX.minY).toInt()
            val maxY = floor(testBoxX.maxY).toInt()
            val minZ = floor(testBoxX.minZ).toInt()
            val maxZ = floor(testBoxX.maxZ).toInt()

            var collidedX = false
            for (bx in minX..maxX) {
                for (by in minY..maxY) {
                    for (bz in minZ..maxZ) {
                        val block = world.getBlock(bx, by, bz)
                        if (block.isSolid) {
                            val blockBox = scratchBlockBox
                            blockBox.set(bx.toFloat(), by.toFloat(), bz.toFloat(), bx + 1f, by + 1f, bz + 1f)
                            if (testBoxX.intersects(blockBox)) {
                                if (resolvedDx > 0f) {
                                    posX = blockBox.minX - radius - 0.001f
                                } else {
                                    posX = blockBox.maxX + radius + 0.001f
                                }
                                resolvedDx = 0f
                                velX = 0f
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
        if (resolvedDz != 0f) {
            val testBoxZ = scratchMoveTestBox
            testBoxZ.set(
                posX - radius, posY + 0.05f, posZ + resolvedDz - radius,
                posX + radius, posY + currentHeight - 0.05f, posZ + resolvedDz + radius
            )
            val minX = floor(testBoxZ.minX).toInt()
            val maxX = floor(testBoxZ.maxX).toInt()
            val minY = floor(testBoxZ.minY).toInt()
            val maxY = floor(testBoxZ.maxY).toInt()
            val minZ = floor(testBoxZ.minZ).toInt()
            val maxZ = floor(testBoxZ.maxZ).toInt()

            var collidedZ = false
            for (bx in minX..maxX) {
                for (by in minY..maxY) {
                    for (bz in minZ..maxZ) {
                        val block = world.getBlock(bx, by, bz)
                        if (block.isSolid) {
                            val blockBox = scratchBlockBox
                            blockBox.set(bx.toFloat(), by.toFloat(), bz.toFloat(), bx + 1f, by + 1f, bz + 1f)
                            if (testBoxZ.intersects(blockBox)) {
                                if (resolvedDz > 0f) {
                                    posZ = blockBox.minZ - radius - 0.001f
                                } else {
                                    posZ = blockBox.maxZ + radius + 0.001f
                                }
                                resolvedDz = 0f
                                velZ = 0f
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

        // World boundary containment (only clamp X/Z in legacy finite worlds; Y stays [0..sizeY])
        if (!world.isInfinite) {
            posX = posX.coerceIn(radius + 0.05f, world.sizeX - radius - 0.05f)
            posZ = posZ.coerceIn(radius + 0.05f, world.sizeZ - radius - 0.05f)
        }
        posY = posY.coerceIn(0.1f, world.sizeY - currentHeight - 0.1f)

        // Void recovery: respawn on surface at current X/Z if fallen into void
        if (posY <= 0.2f) {
            spawnAtSurface(world, posX, posZ)
        }

        updateBoundingBox()
    }

    private fun checkGroundSupport(world: World) {
        scratchGroundTestBox.set(
            posX - radius + 0.05f, posY - 0.08f, posZ - radius + 0.05f,
            posX + radius - 0.05f, posY + 0.01f, posZ + radius - 0.05f
        )
        isGrounded = hasBlockCollision(scratchGroundTestBox, world)
    }

    fun hasBlockCollision(aabb: AABB, world: World): Boolean {
        val minX = floor(aabb.minX).toInt()
        val maxX = floor(aabb.maxX).toInt()
        val minY = floor(aabb.minY).toInt()
        val maxY = floor(aabb.maxY).toInt()
        val minZ = floor(aabb.minZ).toInt()
        val maxZ = floor(aabb.maxZ).toInt()

        val blockBox = scratchBlockBox
        for (bx in minX..maxX) {
            for (by in minY..maxY) {
                for (bz in minZ..maxZ) {
                    val block = world.getBlock(bx, by, bz)
                    if (block.isSolid) {
                        blockBox.set(bx.toFloat(), by.toFloat(), bz.toFloat(), bx + 1f, by + 1f, bz + 1f)
                        if (aabb.intersects(blockBox)) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    fun spawnAtSurface(world: World, spawnX: Float = 24.5f, spawnZ: Float = 24.5f) {
        posX = spawnX
        posZ = spawnZ
        val surfaceY = world.getSurfaceHeight(floor(spawnX).toInt(), floor(spawnZ).toInt())
        posY = surfaceY + 1.05f
        velX = 0f
        velY = 0f
        velZ = 0f
        isGrounded = true
        isCrouching = false
        pitch = 0f
        yaw = 45f
        updateBoundingBox()
    }
}
