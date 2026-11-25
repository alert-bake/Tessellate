package com.nitrous.tessellate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.ui.TexturedUiRenderer
import com.nitrous.tessellate.math.Matrix4
import com.nitrous.tessellate.input.TouchInputController
import com.nitrous.tessellate.player.Player
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.world.BlockPos
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.Direction
import com.nitrous.tessellate.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `appName resource matches Tessellate`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Tessellate", appName)
    }

    @Test
    fun `prototype texture assets exist in assets directory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assetManager = context.assets

        for (id in TextureConstants.ALL_PROTOTYPE_IDS) {
            val path = TextureConstants.getAssetPath(id)
            val inputStream = assetManager.open(path)
            assertNotNull("Asset $path must be readable", inputStream)
            assertTrue("Asset $path must contain byte data", inputStream.available() > 0)
            inputStream.close()
        }
    }

    @Test
    fun `controller texture assets exist in assets directory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assetManager = context.assets

        for (id in TextureConstants.ALL_CONTROLLER_IDS) {
            val path = TextureConstants.getAssetPath(id)
            val inputStream = assetManager.open(path)
            assertNotNull("Controller asset $path must be readable", inputStream)
            assertTrue("Controller asset $path must contain byte data", inputStream.available() > 0)
            inputStream.close()
        }
    }

    @Test
    fun `block registry maintains stable IDs and texture mappings`() {
        // Dynamic runtime IDs: air is 0, loaded blocks have distinct positive IDs with bidirectional lookup
        assertEquals(0.toByte(), BlockType.AIR.id)
        val loadedBlocks = listOf(BlockType.AIR, BlockType.GRASS, BlockType.DIRT, BlockType.STONE, BlockType.DEBUGBUG)
        assertEquals(loadedBlocks.size, loadedBlocks.map { it.id }.toSet().size)

        // ID Lookup
        for (block in loadedBlocks) {
            assertEquals(block, BlockType.fromId(block.id))
        }
        assertEquals(BlockType.AIR, BlockType.fromId(99.toByte()))

        // Opacity
        assertFalse(BlockType.AIR.isOpaque)
        assertTrue(BlockType.GRASS.isOpaque)
        assertTrue(BlockType.DIRT.isOpaque)
        assertTrue(BlockType.STONE.isOpaque)
        assertTrue(BlockType.DEBUGBUG.isOpaque)

        // Grass block face mapping
        assertEquals(TextureConstants.PROTO_GRASS, BlockType.GRASS.getTextureForFace(Direction.UP))
        assertEquals(TextureConstants.PROTO_GRASS_SIDE, BlockType.GRASS.getTextureForFace(Direction.NORTH))
        assertEquals(TextureConstants.PROTO_GRASS_SIDE, BlockType.GRASS.getTextureForFace(Direction.SOUTH))
        assertEquals(TextureConstants.PROTO_GRASS_SIDE, BlockType.GRASS.getTextureForFace(Direction.EAST))
        assertEquals(TextureConstants.PROTO_GRASS_SIDE, BlockType.GRASS.getTextureForFace(Direction.WEST))
        assertEquals(TextureConstants.PROTO_DIRT, BlockType.GRASS.getTextureForFace(Direction.DOWN))

        // Uniform blocks
        assertEquals(TextureConstants.PROTO_DIRT, BlockType.DIRT.getTextureForFace(Direction.UP))
        assertEquals(TextureConstants.BLOCK_STONE, BlockType.STONE.getTextureForFace(Direction.UP))
        assertEquals(TextureConstants.PROTO_STONE, BlockType.PROTO_STONE.getTextureForFace(Direction.UP))
        assertEquals(TextureConstants.PROTO_DEBUG_BUG, BlockType.DEBUGBUG.getTextureForFace(Direction.UP))

        // Block hardness & mineability (TDD-005)
        assertEquals(0.0f, BlockType.AIR.hardness, 0.01f)
        assertEquals(0.6f, BlockType.GRASS.hardness, 0.01f)
        assertEquals(0.5f, BlockType.DIRT.hardness, 0.01f)
        assertEquals(1.5f, BlockType.STONE.hardness, 0.01f)
        assertEquals(0.2f, BlockType.DEBUGBUG.hardness, 0.01f)
        assertFalse(BlockType.AIR.isMineable)
        assertTrue(BlockType.GRASS.isMineable)
        assertTrue(BlockType.STONE.isMineable)
    }

    @Test
    fun `chunk storage stores blocks and tracks dirty state`() {
        val chunk = Chunk(chunkX = 0, chunkY = 0, chunkZ = 0)
        assertTrue(chunk.isDirty)
        chunk.isDirty = false
        assertFalse(chunk.isDirty)

        assertEquals(BlockType.AIR, chunk.getBlock(0, 0, 0))
        assertEquals(BlockType.AIR, chunk.getBlock(15, 15, 15))

        val changed = chunk.setBlock(5, 8, 5, BlockType.GRASS)
        assertTrue(changed)
        assertTrue(chunk.isDirty)
        assertEquals(BlockType.GRASS, chunk.getBlock(5, 8, 5))

        chunk.isDirty = false
        val changedSame = chunk.setBlock(5, 8, 5, BlockType.GRASS)
        assertFalse(changedSame)
        assertFalse(chunk.isDirty)
    }

    @Test
    fun `finite world generates flat tessellate stone plane across 18 chunks`() {
        val world = World(chunksX = 3, chunksY = 2, chunksZ = 3)
        assertEquals(48, world.sizeX)
        assertEquals(32, world.sizeY)
        assertEquals(48, world.sizeZ)
        assertEquals(18, world.getAllChunks().size)

        assertEquals(BlockType.AIR, world.getBlock(-1, 0, 0))
        assertEquals(BlockType.AIR, world.getBlock(48, 0, 0))
        assertEquals(BlockType.AIR, world.getBlock(0, 32, 0))

        assertEquals(BlockType.STONE, world.getBlock(20, 2, 20))
        assertEquals(BlockType.STONE, world.getBlock(12, 6, 12))
        assertEquals(BlockType.AIR, world.getBlock(12, 7, 12))
        assertEquals(6, world.getSurfaceHeight(24, 24))
        assertEquals(BlockType.STONE, world.getBlock(24, 6, 24))
    }

    @Test
    fun `player initial spawn, gravity, and ground detection`() {
        val world = World(chunksX = 2, chunksY = 2, chunksZ = 2)
        val player = Player()

        player.spawnAtSurface(world, 10.5, 10.5)
        assertTrue(player.isGrounded)
        assertTrue(player.posY > 5.0)

        // Teleport player 5 blocks into the air
        player.posY += 5.0
        val startAirY = player.posY

        // Simulate 1 tick of falling
        player.update(deltaTime = 0.05f, world = world, moveForward = 0f, moveStrafe = 0f, crouchInput = false)
        assertFalse("Player in air must not be grounded", player.isGrounded)
        assertTrue("Player must fall under gravity", player.posY < startAirY)
        assertTrue("Player vertical velocity must be downward", player.velY < 0.0)

        // Simulate enough ticks to hit ground
        for (i in 0 until 50) {
            player.update(deltaTime = 0.05f, world = world, moveForward = 0f, moveStrafe = 0f, crouchInput = false)
            if (player.isGrounded) break
        }
        assertTrue("Player must land and become grounded", player.isGrounded)
    }

    @Test
    fun `player four-direction and normalized diagonal movement`() {
        val world = World(chunksX = 2, chunksY = 2, chunksZ = 2)
        // Clear a flat area to test movement freely
        for (x in 5..15) {
            for (z in 5..15) {
                for (y in 0..10) {
                    val block = if (y < 4) BlockType.STONE else BlockType.AIR
                    world.setBlock(x, y, z, block)
                }
            }
        }

        val player = Player(posX = 10.0, posY = 4.0, posZ = 10.0)
        player.yaw = 0f // Facing -Z
        player.pitch = 0f

        // Move Forward (+moveForward = 1.0)
        val startZ = player.posZ
        player.update(deltaTime = 0.1f, world = world, moveForward = 1.0f, moveStrafe = 0f, crouchInput = false)
        assertTrue("Moving forward with yaw 0 must decrease posZ", player.posZ < startZ)

        // Move Strafe Right (+moveStrafe = 1.0)
        val startX = player.posX
        player.update(deltaTime = 0.1f, world = world, moveForward = 0f, moveStrafe = 1.0f, crouchInput = false)
        assertTrue("Strafing right with yaw 0 must increase posX", player.posX > startX)

        // Diagonal Movement (moveForward = 1.0, moveStrafe = 1.0)
        player.update(deltaTime = 0.1f, world = world, moveForward = 1.0f, moveStrafe = 1.0f, crouchInput = false)
        val horizontalSpeed = kotlin.math.hypot(player.velX, player.velZ)
        assertEquals("Diagonal speed must equal walk speed (normalized)", player.walkSpeed, horizontalSpeed, 0.05)
    }

    @Test
    fun `player block collision prevents moving through solid walls`() {
        val world = World(chunksX = 2, chunksY = 2, chunksZ = 2)
        // Clear area
        for (x in 5..15) {
            for (z in 5..15) {
                for (y in 0..10) {
                    val b = if (y < 4) BlockType.STONE else BlockType.AIR
                    world.setBlock(x, y, z, b)
                }
            }
        }

        // Place a solid wall at z = 7 (y = 4 and 5)
        for (x in 5..15) {
            world.setBlock(x, 4, 7, BlockType.STONE)
            world.setBlock(x, 5, 7, BlockType.STONE)
        }

        val player = Player(posX = 10.0, posY = 4.0, posZ = 8.5)
        player.yaw = 0f // Facing -Z directly towards the stone wall

        // Walk forward into the wall for 20 frames
        for (i in 0 until 20) {
            player.update(deltaTime = 0.05f, world = world, moveForward = 1.0f, moveStrafe = 0f, crouchInput = false)
        }

        // Wall is at z=7 (occupies [7..8]). Player radius is 0.3.
        // Player posZ must not enter the block (must be >= 8.30)
        assertTrue("Player must be stopped by the solid wall", player.posZ >= 8.29)
    }

    @Test
    fun `player crouching reduces height, speed, and prevents uncrouching under low ceiling`() {
        val world = World(chunksX = 2, chunksY = 2, chunksZ = 2)
        // Clear area
        for (x in 5..15) {
            for (z in 5..15) {
                for (y in 0..10) {
                    val b = if (y < 4) BlockType.STONE else BlockType.AIR
                    world.setBlock(x, y, z, b)
                }
            }
        }

        val player = Player(posX = 10.0, posY = 4.0, posZ = 10.0)
        assertEquals(1.8, player.currentHeight, 0.01)
        assertEquals(1.62, player.currentEyeHeight, 0.01)

        // Crouch in open space
        player.update(deltaTime = 0.05f, world = world, moveForward = 1.0f, moveStrafe = 0f, crouchInput = true)
        assertTrue(player.isCrouching)
        assertEquals(1.3, player.currentHeight, 0.01)
        assertEquals(1.15, player.currentEyeHeight, 0.01)
        assertEquals("Crouch speed must be 2.2 blocks/sec", player.crouchSpeed, kotlin.math.hypot(player.velX, player.velZ), 0.05)

        // Place a ceiling at y = 5.5 (blocks y=5) directly above player
        // Since player feet are at y=4, height is 1.3, top is at 5.3 (fits under ceiling at 5.5).
        // But standing height is 1.8, top is at 5.8 (collides with ceiling at 5.0..6.0).
        world.setBlock(10, 5, 10, BlockType.STONE)

        // Try to release crouch under the low ceiling
        player.update(deltaTime = 0.05f, world = world, moveForward = 0f, moveStrafe = 0f, crouchInput = false)
        assertTrue("Player must remain crouching because ceiling is obstructed", player.isCrouching)

        // Remove ceiling
        world.setBlock(10, 5, 10, BlockType.AIR)
        player.update(deltaTime = 0.05f, world = world, moveForward = 0f, moveStrafe = 0f, crouchInput = false)
        assertFalse("Player must stand up once ceiling clearance is restored", player.isCrouching)
    }

    @Test
    fun `touch controller tracks multitouch D-pad and prevents stuck states`() {
        val tc = TouchInputController()
        tc.onSurfaceChanged(1920, 1080)

        assertFalse(tc.dpadRect.isEmpty)
        assertFalse(tc.isUpPressed)
        assertFalse(tc.isDownPressed)
        assertFalse(tc.isLeftPressed)
        assertFalse(tc.isRightPressed)
        assertFalse(tc.isCrouchPressed)

        // 1. Touch Up
        tc.simulateDpadTouch(pointerId = 0, nx = 0.5f, ny = 0.1f)
        assertTrue(tc.isUpPressed)
        assertEquals(1.0f, tc.moveForward, 0.01f)
        assertEquals(0.0f, tc.moveStrafe, 0.01f)

        // 2. Multitouch: Second finger touches Crouch center diamond
        tc.simulateDpadTouch(pointerId = 1, nx = 0.5f, ny = 0.5f)
        assertTrue(tc.isUpPressed)
        assertTrue(tc.isCrouchPressed)

        // 3. Diagonal touch on Top-Right corner (nx = 0.85, ny = 0.15)
        tc.simulateDpadTouch(pointerId = 0, nx = 0.85f, ny = 0.15f)
        assertTrue(tc.isUpPressed)
        assertTrue(tc.isRightPressed)
        val diagMag = kotlin.math.hypot(tc.moveForward, tc.moveStrafe)
        assertEquals(1.0f, diagMag, 0.01f)

        // 4. Release all pointers
        tc.simulateDpadRelease(0)
        tc.simulateDpadRelease(1)
        assertFalse("Releasing pointers must clear all states", tc.isUpPressed)
        assertFalse(tc.isRightPressed)
        assertFalse(tc.isCrouchPressed)
        assertEquals(0f, tc.moveForward, 0.01f)
        assertEquals(0f, tc.moveStrafe, 0.01f)
    }

    @Test
    fun `camera first-person view clamps vertical rotation and synchronizes with player`() {
        val camera = Camera3D()
        camera.updateProjection(16f / 9f)

        // Set first-person view
        camera.setFirstPersonView(posX = 12.0, posY = 15.62, posZ = 18.0, playerPitch = 30f, playerYaw = 90f)
        assertTrue(camera.isFirstPerson)
        assertEquals(12.0, camera.eyeX, 0.01)
        assertEquals(15.62, camera.eyeY, 0.01)
        assertEquals(18.0, camera.eyeZ, 0.01)

        // Pitch clamp check
        camera.setFirstPersonView(posX = 12.0, posY = 15.0, posZ = 18.0, playerPitch = 120f, playerYaw = 0f)
        assertEquals(89f, camera.pitch, 0.01f)

        camera.setFirstPersonView(posX = 12.0, posY = 15.0, posZ = 18.0, playerPitch = -110f, playerYaw = 0f)
        assertEquals(-89f, camera.pitch, 0.01f)
    }

    @Test
    fun `matrix4 identity is valid`() {
        val m = Matrix4.create()
        assertEquals(1f, m[0], 0.001f)
        assertEquals(0f, m[1], 0.001f)
        assertEquals(1f, m[5], 0.001f)
        assertEquals(1f, m[10], 0.001f)
        assertEquals(1f, m[15], 0.001f)
    }

    @Test
    fun `jump button asset is registered and aligned to bottom right corner`() {
        assertTrue(TextureConstants.ALL_CONTROLLER_IDS.contains(TextureConstants.PROTO_CONTR_JUMP))
        assertEquals("textures/proto_contr_jump.png", TextureConstants.getAssetPath(TextureConstants.PROTO_CONTR_JUMP))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = TessellateEngine(context)
        engine.touchController.onSurfaceChanged(1920, 1080)
        engine.uiRenderer.onSurfaceChanged(1920, 1080)

        val dpadRect = engine.touchController.dpadRect
        val jumpRect = engine.uiRenderer.jumpButtonRect

        assertFalse(jumpRect.isEmpty)
        assertEquals(jumpRect.width(), jumpRect.height(), 0.01f)
        assertEquals(dpadRect.width() * 0.56f, jumpRect.width(), 0.5f)
        assertEquals(1920f - 36f, jumpRect.right, 0.01f)
        assertEquals(1080f - 24f, jumpRect.bottom, 0.01f)
    }
}
