# Tessellate Architecture

This document describes the implementation currently present in the repository. It is an architectural map, not a promise that every subsystem is finished or a frozen public API. When this document and the source disagree, the source is authoritative.

## 1. Project boundaries

Tessellate is split into three Gradle modules:

| Module | Responsibility | Platform boundary |
|---|---|---|
| `:core` | World data, chunks, entities, items, registries, geometry generation, lighting, and world generation | Kotlin/JVM; no Android or OpenGL dependency |
| `:app` | Android application, engine orchestration, input, UI, OpenGL ES rendering, assets, settings, saves, and Lua integration | Android |
| `:desktop` | Small JVM entry point for exercising shared-core systems | JVM |

The dependency direction is intentional: `:app` and `:desktop` depend on `:core`; the core module does not depend on either client module.

The Android application uses Compose for application-level UI, while the in-world rendering path is a custom OpenGL ES 3.0 implementation. The desktop module is currently a diagnostic harness for core behavior, not a complete second game client.

## 2. Android runtime and engine orchestration

The Android runtime is coordinated by `TessellateEngine`. It implements the graphics runtime and `GLSurfaceView.Renderer` contracts and owns the Android-facing systems:

- OpenGL ES backend and texture pipeline.
- Camera and touch-input controller.
- Active world and showcase scenes.
- Application state, pause state, and scene mode.
- Settings manager and settings registry.
- Content bootstrap and Lua mod runtime.
- Textured in-game UI renderer.

Startup ordering matters. The engine initializes the Android content bootstrap before constructing the active world systems. Vanilla settings are registered, the Lua runtime is created, mods are loaded, and persisted settings are applied through the engine's settings flow.

The main scene modes are `WORLD` and `SHOWCASE`. The former is the first-person voxel-world path; the latter is a diagnostic orbit scene for inspecting blocks and textures.

Android-specific work stays in `:app`. The shared world and geometry systems do not issue GL calls or depend on Android classes.

## 3. World model and chunk storage

### 3.1 World

`World` is the authoritative container for loaded chunks and active entities. It supports two construction paths:

- **Infinite streaming worlds**, configured through a `WorldSession`, seed, render distance, and vertical chunk count.
- **Bounded worlds**, used by tests and diagnostic harnesses.

Chunks are addressed by integer chunk coordinates and stored behind a synchronized lookup boundary. World-space block coordinates are converted to chunk coordinates and local coordinates using the 16-block chunk size.

Infinite-world startup generates the immediate spawn core synchronously so initial terrain and surface queries are available immediately. Chunks farther from the initial area are generated and streamed asynchronously. The world tracks topology changes so render-side chunk state can detect when its loaded-chunk set has changed.

The world also owns the entity collection, entity ticking and removal, item-drop merging, lighting access, and the connection to optional save storage.

### 3.2 Chunk

A `Chunk` contains exactly `16 × 16 × 16 = 4096` block cells. Block IDs are stored in a compact `ByteArray`. The chunk also tracks derived state such as:

- Whether it is empty or fully opaque.
- Whether its mesh needs rebuilding and the associated dirty epoch.
- Whether lighting needs work or is already queued.
- Uniform-light flags and optional per-voxel light buffers.

Block writes mark the chunk dirty. World-level writes also account for chunk boundaries so neighboring geometry can be rebuilt when a changed block affects a face at an edge.

Raw block writes exist for controlled bulk operations such as world generation. Callers using those paths are responsible for the appropriate post-generation recalculation and dirty marking.

## 4. Procedural world generation

`WorldGenRegistry` stores world-generation passes. Passes are ordered by ascending priority, with the pass ID used as a deterministic tie-breaker, and the registry is frozen during bootstrap.

The default pass set currently includes:

1. Base terrain.
2. Surface shaping.
3. Caves.
4. Structoid placement.
5. Ore placement.
6. Tree features.

The project's internal name for this ordered pass pipeline is **Atomic Lasagna**. A `StructoidPass` manages registered structure definitions.

World type affects generation behavior. The default world type runs the registered pass pipeline; flat and debug world types use dedicated generation paths. After generation, the chunk recalculates its empty state and is marked dirty.

## 5. Lighting

Lighting is implemented in `core` by `LightEngine` and stored by `Chunk`. Light levels use a signed range from `-16` to `+16`:

- `-16`: fully dark.
- `0`: ambient baseline.
- `+16`: maximum light.

Each chunk distinguishes two states:

- **Intended light** is the authoritative logical result used by gameplay-facing queries.
- **Rendered light** is the visual state that can catch up incrementally and drive mesh rebuilds.

Chunk initialization computes direct vertical skylight and underground darkness, seeds emissive blocks, and propagates initial light synchronously. Later propagation is processed through a bounded queue so visual updates can be time-sliced rather than forcing all work into one frame.

The propagation implementation uses a Starlight-style directional breadth-first traversal. Queue storage uses primitive arrays, and chunk lookups use a small local cache to reduce allocation and repeated lookup work.

Light storage is lazy. Uniformly dark or uniformly lit chunks can represent their light state with scalar values instead of allocating two full 4096-byte arrays. If per-voxel arrays become uniform again, the chunk can compact them back to scalar state.

## 6. Geometry generation and rendering

### 6.1 Core geometry builder

`ChunkGeometryBuilder` is a platform-independent CPU geometry builder. It reads chunk blocks, neighboring chunks, and rendered light, then produces a `ChunkMeshOutput`.

The output separates opaque and transparent geometry. Each pass has its own vertex and index data, counts, and face statistics. The builder uses greedy face merging across compatible slice regions to reduce geometry compared with emitting every visible block face independently.

The generated vertex payload is a compact 12-byte-per-vertex representation carried in primitive arrays. The core builder does not create GPU objects or call OpenGL.

### 6.2 Parallel meshing

`ParallelChunkMesher` is a bounded worker pool in `:core`. Worker threads run geometry generation away from the render thread and publish completed `ChunkMeshOutput` objects through a concurrent queue.

The pool limits queued chunk work, avoids scheduling the same chunk position more than once at a time, and uses thread-local geometry builders. It can be shut down and its pending work cleared.

`WorldRenderer` owns the mesher and the `parallelMeshingEnabled` switch. Parallel meshing is currently initialized as **disabled**; the existence of the worker pool does not mean that every build uses it by default.

### 6.3 GPU-side renderer

The Android `WorldRenderer` owns chunk GPU meshes and performs the GL-facing work:

- Synchronizes renderer state with the world's loaded-chunk topology.
- Rebuilds dirty chunk meshes and accepts completed worker outputs.
- Uploads geometry to GPU buffers.
- Skips empty chunks and applies frustum culling.
- Sorts opaque chunks front-to-back and transparent chunks back-to-front.
- Tracks rendered/culled face and chunk statistics.
- Defers GPU resource release and flushes to a safe GL-thread boundary.

Opaque and transparent geometry remain separate through rendering so their depth and blending behavior can be handled independently. Entity rendering is a separate path from static chunk geometry.

The renderer also owns a native-buffer pool for reusable direct buffers. Pooling can be enabled or disabled through the renderer's configuration surface.

## 7. Entities and item drops

### 7.1 Entity contract

The base `Entity` class lives in `:core`. It contains position, velocity, a cached axis-aligned bounding box, removal state, sleeping state, and tick counters. Physics time is expressed in seconds, and the base gravity constant is measured in world units per second squared.

Subclasses own their bounding-box update logic. Renderers consume platform-independent `EntityRenderState` snapshots rather than requiring the core entity model to know about GPU resources.

World entity ticking skips removed entities and supports sleeping entities outside the active simulation area. Entity lifecycle operations are coordinated by the world rather than being tied to chunk mesh storage.

### 7.2 Item drops

`ItemDropEntity` is a dynamic item entity with a small hitbox, vertical collision handling, spin animation, and sine-wave bobbing. Its item identifier and stack count are serializable extra data.

A drop can absorb another nearby drop with the same item identifier. Simulation stack count is not capped at the visual layer count: rendering is limited to at most 64 visible stack layers.

### 7.3 Item-drop merging

`ItemDropMerger` scans active, awake item drops, sorts candidates by X coordinate, and checks nearby candidates within a 1.5-block radius. Candidate checks are capped per entity to bound worst-case work. Successful merges update the primary stack and remove the absorbed entity from the world.

This is a deliberately bounded broad-phase strategy, not a general-purpose spatial index.

## 8. Items, inventory, and recipes

The item and inventory model lives in `:core`.

- Items are identified through the item registry and namespaced identifiers.
- `ItemStack` represents an item identifier and count.
- `Inventory` manages slots, stack insertion, selection, and capacity checks.
- The default inventory size is a nine-slot hotbar; larger inventories can be constructed for the player inventory layout.
- `RecipeRegistry` stores recipe definitions used by crafting systems.

Inventory operations synchronize access to the slot array. Stack insertion attempts to fill compatible existing stacks before consuming empty slots, and capacity checks can determine whether an amount fits without mutating inventory state.

## 9. Registries and data-driven content

### 9.1 Identifiers and registries

Content uses namespaced `Identifier` values. The generic `ContentRegistry` supports lookup by identifier and numeric ID, tracks namespace ownership metadata, and prevents duplicate identifiers or numeric IDs.

Registries are populated during bootstrap and frozen before normal world operation. This provides a stable mapping between namespaced content and compact numeric IDs used by chunk block storage.

### 9.2 Android content bootstrap

The Android bootstrap reads built-in content assets from the application's assets directory. Current data-driven directories include:

- `content/blocks/`
- `content/items/`
- `content/recipes/`
- `content/commands/`

Definitions are parsed and registered before the engine creates the active world. Block and item relationships are resolved as part of registration/bootstrap rather than being discovered ad hoc in the rendering loop.

The JSON files are content definitions, not a replacement for the engine's Kotlin implementation.

## 10. Commands

The Android command layer contains a command contract, execution context/result types, a registry, and a command console. Built-in command definitions are loaded from `assets/content/commands/`.

The registry normalizes command names for lookup and dispatches parsed input to the matching command. The content directory currently includes definitions such as clear, fly, give, instamine, and spawn.

The command layer is an application integration surface; it does not belong to the platform-independent world data model.

## 11. Settings and Lua modding

### 11.1 Settings

`SettingsManager` persists `GameSettings` as JSON in the application's external files area, with an internal-files fallback. Disk work is asynchronous. Parsing is lenient per field: an invalid or missing value can fall back to its own default without discarding every other valid setting.

`SettingsRegistry` describes settings, categories, references, and supported control types. Vanilla settings are registered through a dedicated registrar. Mod-provided settings can be persisted in the settings model's mod-value map.

### 11.2 Lua runtime

`LuaModRuntime` integrates LuaJ with the settings and reference registries. It discovers mod directories under the application's external files `mods/` directory, reads Lua scripts and an optional `permissions.yml`, and executes each mod in a dedicated sandboxed Lua environment.

The runtime records mod load results and errors and exposes settings-related APIs and permission-guarded engine integration points. The permission manager inspects mod permission declarations and maintains permission records.

Treat the Lua surface as a developing API. A feature existing internally does not automatically make it a stable compatibility promise for third-party mods.

## 12. Persistence

Per-world storage is coordinated by `WorldSaveManager` and `WorldSaveStorage`. A world folder contains:

```text
saves/<world-folder>/
├── level.json
├── chunks/
│   └── c_<x>_<y>_<z>.bin
└── entities/
    └── c_<x>_<y>_<z>.json
```

- `level.json` stores world-level metadata.
- Chunk files contain the raw 4096-byte block-ID array. Completely air chunks are omitted.
- Entity files store serialized entities associated with each chunk.

Chunk and entity writes are performed asynchronously on an I/O dispatcher. The storage layer keeps versioned pending snapshots so reads can observe the newest queued state even before the corresponding disk write completes. Per-chunk locks coordinate writes, and snapshot-save operations capture loaded chunks and active entities together with world metadata.

Settings use a separate `settings.json` file managed by `SettingsManager`.

## 13. Tests and diagnostics

The repository includes core JVM tests and Android local unit tests. The test suite covers areas such as identifiers and registries, entity/item-drop behavior, controls, inventory and pickup, commands, atlas/mipmap behavior, world creation, save persistence, settings/modding, and optimization regressions.

The desktop entry point is a lightweight harness for exercising shared-core behavior such as world creation, item-drop merging, render-state creation, buffer pooling, and mesher output. It should be treated as a diagnostic executable, not as the desktop game.

## 14. Architectural rules of thumb

When changing Tessellate, keep these boundaries intact unless there is a deliberate architectural reason to change them:

1. Keep world rules and reusable simulation logic in `:core`.
2. Keep Android APIs, input plumbing, UI, asset access, and OpenGL calls in `:app`.
3. Do not call OpenGL from chunk-generation or meshing workers.
4. Pass CPU-side geometry results to the render thread for GPU upload.
5. Treat chunk dirty state and topology changes as explicit synchronization signals.
6. Register content before freezing registries and before generating the active world.
7. Keep intended gameplay lighting distinct from incrementally updated rendered lighting.
8. Keep persistence snapshots independent of mutable live buffers by copying data before asynchronous writes.
9. Treat mod APIs and serialized formats as evolving until compatibility guarantees are explicitly established.

---

For the project overview and build entry points, see [README.md](README.md).

## 15. Repository tree

The following is the complete tracked-file tree from the repository at the time this document was generated. It includes source, tests, build configuration, assets, and wrapper files.

```text
Tessellate/
├── .gitignore
├── ARCHITECTURE.md
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── LICENSE
├── metadata.json
├── README.md
└── settings.gradle.kts
```
