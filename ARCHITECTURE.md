# Tessellate Engine Architecture

Concise overview of core engine subsystems.

---

## 1. Entity Lifecycle (`Entity.kt`, `EntityRenderer.kt`)

Dynamic entities are decoupled from static voxel chunks. `Entity` defines seconds-based gravity (`24.0f` units/s²), velocity integration, and an abstract `updateBoundingBox()` contract owned by subclasses (such as `ItemDropEntity` with a `0.25 × 0.25 × 0.25` hitbox and swept floor collision). `World.tickEntities()` iterates active entities safely with deferred spawn/removal queues.

```kotlin
abstract class Entity(
    var position: Vec3,
    val boundingBox: AABB
) {
    val velocity = Vec3(0f, 0f, 0f)
    var isRemoved = false

    open fun tick(world: World, deltaTime: Float) {
        if (isRemoved || deltaTime <= 0f) return
        velocity.y -= GRAVITY * deltaTime
        position.x += velocity.x * deltaTime
        position.y += velocity.y * deltaTime
        position.z += velocity.z * deltaTime
        updateBoundingBox()
    }

    protected abstract fun updateBoundingBox()
}
```

`EntityRenderer` draws non-removed entities on an independent OpenGL ES 3.0 path outside `ChunkMesh` batching:

```kotlin
fun render(entities: List<Entity>, camera: Camera3D, texturePipeline: ITexturePipeline) {
    // ...
    for (i in 0 until entities.size) {
        val entity = entities[i]
        if (entity.isRemoved) continue
        // Build per-entity MVP at boundingBox center scaled to 0.25^3 and draw
        mesh.drawUniformBlock(texturePipeline, shader, TextureConstants.PROTO_DEBUG_BUG)
    }
}
```

---

## 2. Dirty Chunk Mesh Rebuild Logic (`Chunk.kt`)

Each `16 × 16 × 16` `Chunk` tracks `needsMeshRebuild` (`true` on creation and terrain generation). Mutating a block via `setBlock()` sets `needsMeshRebuild = true`, and `World.setBlock()` also marks boundary-adjacent chunks dirty. `WorldRenderer.updateMeshes()` skips unchanged chunks and clears the flag after rebuilding.

```kotlin
class Chunk(val chunkX: Int, val chunkY: Int, val chunkZ: Int) {
    var needsMeshRebuild: Boolean = true
    var isDirty: Boolean
        get() = needsMeshRebuild
        set(value) { needsMeshRebuild = value }

    fun setBlock(lx: Int, ly: Int, lz: Int, type: BlockType): Boolean {
        // ...
        if (oldId != type.id) {
            blocks[idx] = type.id
            needsMeshRebuild = true
            return true
        }
        return false
    }
}
```

---

## 3. Dual-Pass Transparent Block Rendering (`ChunkMesh.kt`)

`ChunkMesh` splits chunk geometry into two GPU buffer sets and applies 6-plane `Frustum` culling in `WorldRenderer` against each chunk's `worldBounds` AABB:

- **Pass 1 (Opaque)**: Dirt, Stone, Grass (`GL_BLEND` disabled, depth writes on). Transparent neighbors never cull opaque faces.
- **Pass 2 (Transparent)**: Glass, Water, Leaves (`GL_BLEND` enabled with `GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`, `glDepthMask(false)`). Identical transparent neighbors cull inner faces.

```kotlin
fun shouldRenderFace(block: BlockType, neighbor: BlockType): Boolean {
    if (block == BlockType.AIR) return false
    if (block.isOpaque) {
        return !neighbor.isOpaque
    }
    if (block.isTransparent) {
        if (neighbor.isOpaque) return false
        if (neighbor == block) return false
        return true
    }
    return false
}
```

---

## 4. Asynchronous Persistence (`SettingsManager.kt`)

`SettingsManager` serializes `GameSettings` to `settings.json` inside `context.getExternalFilesDir(null)` (falling back to `context.filesDir`) using Moshi. Disk I/O runs on `Dispatchers.IO`, while loaded state updates (`settings` and `settingsFlow`) are published on `Dispatchers.Main` to keep the render and main threads stutter-free. Per-world saves (`level.json`, raw 4096-byte `chunks/c_X_Y_Z.bin`, and `entities/c_X_Y_Z.json`) share the same base external files directory under `saves/<world_name>/`.

```kotlin
fun loadSettings(onLoaded: ((GameSettings) -> Unit)? = null): Job {
    return scope.launch(ioDispatcher) {
        val loaded = readSettingsFromDisk()
        withContext(mainDispatcher) {
            settings = loaded
            _settingsFlow.value = loaded
            onLoaded?.invoke(loaded)
        }
    }
}

fun saveSettings(
    newSettings: GameSettings = settings,
    onComplete: ((Boolean) -> Unit)? = null
): Job {
    val sanitized = sanitize(newSettings)
    settings = sanitized
    _settingsFlow.value = sanitized
    return CoroutineScope(ioDispatcher).launch {
        val success = writeSettingsToDisk(sanitized)
        if (onComplete != null) {
            withContext(mainDispatcher) { onComplete(success) }
        }
    }
}
```
