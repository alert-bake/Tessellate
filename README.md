# Tessellate

[![Discord](https://img.shields.io/badge/Discord-Join%20the%20server-5865F2?logo=discord&logoColor=white)](https://discord.gg/dVgxpu4NGm)
[![License: AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-blue.svg)](LICENSE)

**Tessellate** is an Android-first voxel sandbox project built around a custom Kotlin engine and an OpenGL ES 3.0 renderer. It is an evolving experiment in procedural worlds, block-based building, data-driven content, and modded-sandbox foundations.

> Tesselate was never real, trust frfr.

## Project status

Tessellate is in **early development**. The repository contains working engine systems and an expanding gameplay foundation, but the project is not presented as a finished game or a stable release. APIs, content formats, and internal architecture may change.

The Android application is the primary game target. The separate desktop module currently acts as a JVM-side harness for exercising shared core systems; it is not a claim that a complete desktop game client is available.

## Current foundations

The codebase currently includes:

- **Voxel worlds:** 16 × 16 × 16 chunks, bounded test worlds, and an infinite-world streaming path.
- **Procedural generation:** ordered, priority-based world-generation passes, including terrain, caves, ores, trees, and structure placement foundations.
- **Block content:** namespaced identifiers, numeric registry IDs, block definitions, and JSON-backed content bootstrap.
- **Chunk geometry:** CPU-side greedy geometry generation with separate opaque and transparent output.
- **Lighting:** signed voxel-light values with separate intended and rendered state, plus queued, time-sliced propagation.
- **Rendering:** a custom OpenGL ES 3.0 backend, texture atlas pipeline, frustum culling, chunk sorting, and entity rendering.
- **Entities:** a platform-independent entity model, item drops, physics, sleeping/active ticking, and nearby item-stack merging.
- **Inventory and crafting:** stack-aware inventory operations, hotbar selection, item definitions, and recipe registry foundations.
- **Commands:** a command registry and in-game command console, with built-in command definitions loaded from content assets.
- **Persistence:** per-world metadata, compact binary chunk block data, per-chunk entity JSON, and asynchronous save operations.
- **Settings:** persistent settings, validation/default recovery, and a registry for built-in and mod-provided settings.
- **Modding foundation:** a LuaJ runtime with per-mod sandbox environments, permission records, and settings integration.

This list describes implemented foundations, not a promise that every system is feature-complete or polished for players.

## Technology

| Area | Implementation |
|---|---|
| Main language | Kotlin |
| Android application | Android SDK, Jetpack Compose for application UI |
| World renderer | OpenGL ES 3.0 |
| Shared engine systems | Kotlin/JVM in `:core` |
| Concurrency | Kotlin coroutines and JVM concurrency primitives |
| JSON | Moshi |
| Lua runtime | LuaJ |
| Tests | JUnit and Robolectric |

The shared `:core` module is deliberately separated from Android and OpenGL APIs. World data, geometry generation, registries, entities, and world-generation logic live there so they can be exercised without an Android rendering context.

## Repository layout

```text
Tessellate/
├── app/       Android application, engine integration, UI, OpenGL ES rendering,
│              content assets, settings, saves, and Lua mod runtime
├── core/      Platform-independent world, entity, item, registry, math,
│              geometry, lighting, and world-generation systems
├── desktop/   JVM harness for shared-core diagnostics and experiments
├── gradle/    Gradle version catalog and build configuration
├── README.md
├── ARCHITECTURE.md
└── LICENSE
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for subsystem responsibilities, data flow, and important implementation boundaries.

## Building

You need a compatible JDK and Android SDK installed, with the Android SDK platforms and build tools required by the project available locally.

Build the Android debug APK:

```bash
./gradlew :app:assembleDebug
```

Run the shared-core unit tests:

```bash
./gradlew :core:test
```

Run the Android module's local unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Run the desktop core harness:

```bash
./gradlew :desktop:run
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

These are build entry points, not a guarantee that every environment has the required Android SDK or dependency cache configured. The Android module targets API 24 as its minimum SDK and compiles against API 36.

## Content and modding

Built-in content definitions are stored under:

- `app/src/main/assets/content/blocks/`
- `app/src/main/assets/content/items/`
- `app/src/main/assets/content/recipes/`
- `app/src/main/assets/content/commands/`

Content is loaded during application bootstrap and registered under namespaced identifiers. Registries are frozen after bootstrap so runtime world systems can rely on stable registrations.

The current Lua integration loads mod directories from the app's external files area. A mod directory can contain Lua source files and a `permissions.yml` file. Lua scripts execute in isolated sandbox environments, and the runtime exposes permission-checked integration points, including mod settings. The modding API is still evolving; consult the source and architecture notes before relying on a particular API surface.

## Community

Tessellate's community server is the place for project discussion and development updates.

[Join the Tessellate Discord](https://discord.gg/dVgxpu4NGm)

## License

Unless a file or component states otherwise, the original Tessellate project code authored or controlled by Nitrous is licensed under the **GNU Affero General Public License v3.0 only (AGPL-3.0-only)**. See [LICENSE](LICENSE) for the complete, unmodified license text.

Copyright (C) 2026 Nitrous, to the extent applicable to copyrightable material authored or controlled by Nitrous.

Third-party dependencies, assets, and other materials retain their own applicable licenses. See their accompanying notices and license files. The project license does not relicense material that Nitrous is not authorized to license.
