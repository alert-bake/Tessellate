package com.nitrous.tessellate

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.nitrous.tessellate.core.AppState
import com.nitrous.tessellate.core.SceneMode
import com.nitrous.tessellate.core.TessellateEngine
import com.nitrous.tessellate.entity.Entity
import com.nitrous.tessellate.entity.ItemDropEntity
import com.nitrous.tessellate.math.Vec3
import com.nitrous.tessellate.scene.WorldScene
import com.nitrous.tessellate.math.AABB
import com.nitrous.tessellate.world.BlockType
import com.nitrous.tessellate.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification test suite for TDD-007 PRIME: Base Entity & ItemDrop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EntityAndItemDropTest {

    // =========================================================================
    // 1. Math Primitives (Vec3)
    // =========================================================================

    @Test
    fun `vec3 supports basic position and velocity math operations`() {
        val pos = Vec3(1.0, 2.0, 3.0)
        val vel = Vec3(0.0, -4.0, 2.0)

        val next = pos + vel * 0.5
        assertEquals(1.0, next.x, 1e-4)
        assertEquals(0.0, next.y, 1e-4)
        assertEquals(4.0, next.z, 1e-4)

        val delta = next - pos
        assertEquals(0.0, delta.x, 1e-4)
        assertEquals(-2.0, delta.y, 1e-4)
        assertEquals(1.0, delta.z, 1e-4)

        pos.addLocal(vel * 0.25)
        assertEquals(1.0, pos.x, 1e-4)
        assertEquals(1.0, pos.y, 1e-4)
        assertEquals(3.5, pos.z, 1e-4)
    }

    // =========================================================================
    // 2. Base Entity Physics & Subclass Bounding-Box Ownership
    // =========================================================================

    @Test
    fun `base entity applies time-scaled gravity in seconds and updates subclass bounding box`() {
        val world = clearColumnWorld()
        val halfW = 0.5
        val height = 1.5

        val customEntity = object : Entity(
            position = Vec3(10.5, 12.0, 10.5),
            boundingBox = AABB(10.5 - halfW, 12.0, 10.5 - halfW, 10.5 + halfW, 12.0 + height, 10.5 + halfW)
        ) {
            override fun updateBoundingBox() {
                boundingBox.set(
                    position.x - halfW,
                    position.y,
                    position.z - halfW,
                    position.x + halfW,
                    position.y + height,
                    position.z + halfW
                )
            }
        }

        val dt = 0.25f
        customEntity.tick(world, dt)

        val expectedVy = -Entity.GRAVITY * dt.toDouble()
        val expectedY = 12.0 + expectedVy * dt.toDouble()
        assertEquals(expectedVy, customEntity.velocity.y, 1e-4)
        assertEquals(expectedY, customEntity.position.y, 1e-4)

        // Verify subclass owns its custom 1.0 x 1.5 x 1.0 dimensions
        assertEquals(1.0, customEntity.boundingBox.maxX - customEntity.boundingBox.minX, 1e-4)
        assertEquals(1.5, customEntity.boundingBox.maxY - customEntity.boundingBox.minY, 1e-4)
        assertEquals(1.0, customEntity.boundingBox.maxZ - customEntity.boundingBox.minZ, 1e-4)
        assertEquals(expectedY, customEntity.boundingBox.minY, 1e-4)
        assertEquals(expectedY + 1.5, customEntity.boundingBox.maxY, 1e-4)
    }

    // =========================================================================
    // 3. ItemDropEntity Hitbox & Collision
    // =========================================================================

    @Test
    fun `item drop entity has 0_25 cube hitbox centered horizontally and resting on position y`() {
        val drop = ItemDropEntity(Vec3(5.0, 10.0, 7.0))

        assertEquals(0.25, drop.boundingBox.maxX - drop.boundingBox.minX, 1e-4)
        assertEquals(0.25, drop.boundingBox.maxY - drop.boundingBox.minY, 1e-4)
        assertEquals(0.25, drop.boundingBox.maxZ - drop.boundingBox.minZ, 1e-4)
        assertEquals(4.875, drop.boundingBox.minX, 1e-4)
        assertEquals(5.125, drop.boundingBox.maxX, 1e-4)
        assertEquals(10.0, drop.boundingBox.minY, 1e-4)
        assertEquals(10.25, drop.boundingBox.maxY, 1e-4)
        assertEquals(6.875, drop.boundingBox.minZ, 1e-4)
        assertEquals(7.125, drop.boundingBox.maxZ, 1e-4)
    }

    @Test
    fun `item drop collides cleanly with solid block below and snaps to block top`() {
        val world = clearColumnWorld()
        // Solid 1-block floor at (10, 5, 10) -> top surface at Y = 6.0
        world.setBlock(10, 5, 10, BlockType.STONE)

        val drop = ItemDropEntity(Vec3(10.5, 8.0, 10.5))
        world.spawnEntity(drop)

        // Simulate 60Hz ticks for 1.5 seconds
        repeat(90) {
            world.tickEntities(1f / 60f)
        }

        assertEquals(6.0, drop.position.y, 1e-4)
        assertEquals(0.0, drop.velocity.y, 1e-4)
        assertEquals(6.0, drop.boundingBox.minY, 1e-4)
        assertEquals(6.25, drop.boundingBox.maxY, 1e-4)
    }

    @Test
    fun `item drop does not tunnel through 1-block thick floor under large timestep`() {
        val world = clearColumnWorld()
        world.setBlock(12, 10, 12, BlockType.GRASS) // Top is Y = 11.0

        val drop = ItemDropEntity(Vec3(12.5, 15.0, 12.5))
        // Large single timestep where unconstrained fall exceeds 1 block
        drop.tick(world, 1.0f)

        assertEquals(11.0, drop.position.y, 1e-4)
        assertEquals(0.0, drop.velocity.y, 1e-4)
    }

    @Test
    fun `item drop passes through air and custom non-solid blocks without colliding`() {
        val world = clearColumnWorld()
        // Place a STONE block at (8, 5, 8) and a solid floor much lower at (8, 1, 8)
        world.setBlock(8, 5, 8, BlockType.STONE)
        world.setBlock(8, 1, 8, BlockType.DIRT)

        // Subclass treats STONE as non-solid (only DIRT is solid) to verify isBlockSolid rule
        val passThroughDrop = object : ItemDropEntity(Vec3(8.5, 7.0, 8.5)) {
            override fun isBlockSolid(block: BlockType): Boolean {
                return block != BlockType.AIR && block != BlockType.STONE
            }
        }

        repeat(60) {
            passThroughDrop.tick(world, 1f / 60f)
        }

        // Should have passed cleanly through AIR and non-solid STONE (Y=5) and landed on DIRT (top Y=2.0)
        assertEquals(2.0, passThroughDrop.position.y, 1e-4)
        assertEquals(0.0, passThroughDrop.velocity.y, 1e-4)
    }

    // =========================================================================
    // 4. World Entity Iteration Safety & Lifecycle
    // =========================================================================

    @Test
    fun `removing entities during tick does not throw ConcurrentModificationException and prunes removed`() {
        val world = clearColumnWorld()
        world.setBlock(15, 2, 15, BlockType.STONE)

        lateinit var selfRemoving: Entity
        selfRemoving = object : ItemDropEntity(Vec3(15.5, 10.0, 15.5)) {
            override fun tick(world: World, deltaTime: Float) {
                super.tick(world, deltaTime)
                world.removeEntity(this)
            }
        }
        val survivor = ItemDropEntity(Vec3(15.5, 10.0, 15.5))

        world.spawnEntity(selfRemoving)
        world.spawnEntity(survivor)

        world.tickEntities(0.05f)

        assertTrue(selfRemoving.isRemoved)
        assertFalse(world.entities.contains(selfRemoving))
        assertEquals(1, world.entities.size)
        assertTrue(world.entities.contains(survivor))
    }

    @Test
    fun `spawning entity during tick defers its first tick until the next frame`() {
        val world = clearColumnWorld()
        var childTickCount = 0
        val child = object : ItemDropEntity(Vec3(20.5, 15.0, 20.5)) {
            override fun tick(world: World, deltaTime: Float) {
                childTickCount++
                super.tick(world, deltaTime)
            }
        }

        val spawner = object : ItemDropEntity(Vec3(20.5, 15.0, 20.5)) {
            var spawned = false
            override fun tick(world: World, deltaTime: Float) {
                super.tick(world, deltaTime)
                if (!spawned) {
                    spawned = true
                    world.spawnEntity(child)
                }
            }
        }

        val scene = WorldScene()
        scene.world.clearEntities()
        scene.world.spawnEntity(spawner)

        // Frame 1: spawner spawns child during tick -> child should be registered after iteration, 0 ticks yet
        scene.update(0.05f)
        assertEquals(0, childTickCount)
        assertTrue(scene.world.entities.contains(child))

        // Frame 2: child ticks once
        scene.update(0.05f)
        assertEquals(1, childTickCount)
    }

    // =========================================================================
    // 5. Debug ItemDrop Spawn Integration
    // =========================================================================

    @Test
    fun `spawnDebugItemDrop in world mode spawns ItemDropEntity ahead and above player`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = TessellateEngine(context)
        engine.setAppState(AppState.PLAYING)
        engine.switchSceneMode(SceneMode.WORLD)
        engine.uiRenderer.onSurfaceChanged(1920, 1080)

        val scene = engine.worldScene
        scene.player.posX = 24.5
        scene.player.posY = 18.0
        scene.player.posZ = 24.5
        scene.player.yaw = 0f // Looking toward -Z
        scene.player.pitch = 0f
        engine.camera.setFirstPersonView(
            scene.player.posX,
            scene.player.eyeY,
            scene.player.posZ,
            scene.player.pitch,
            scene.player.yaw
        )
        scene.world.clearEntities()

        val drop = scene.spawnDebugItemDrop(engine.camera)

        assertEquals(1, scene.world.entities.size)
        assertTrue(scene.world.entities.first() === drop)
        // Expected: ~3 blocks along -Z from (24.5, eyeY, 24.5), ~5 blocks up
        assertEquals(24.5, drop.position.x, 1e-3)
        assertEquals(scene.player.eyeY + 5.0, drop.position.y, 1e-3)
        assertEquals(21.5, drop.position.z, 1e-3)
    }

    private fun clearColumnWorld(): World {
        val world = World(chunksX = 3, chunksY = 2, chunksZ = 3)
        for (x in 0 until world.sizeX) {
            for (z in 0 until world.sizeZ) {
                for (y in 0 until world.sizeY) {
                    world.setBlock(x, y, z, BlockType.AIR)
                }
            }
        }
        return world
    }
}
