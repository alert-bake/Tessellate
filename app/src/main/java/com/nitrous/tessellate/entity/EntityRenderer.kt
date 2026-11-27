package com.nitrous.tessellate.entity

import android.opengl.GLES30
import com.nitrous.tessellate.core.ITexturePipeline
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.graphics.Frustum
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.item.Item
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.math.Matrix4
import com.nitrous.tessellate.scene.VoxelMesh
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.texture.TexturePipeline
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.World
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor

/**
 * Renders active world entities via GPU instancing (`glDrawElementsInstanced`) and the player's
 * first-person held item/hand outside batched chunk meshes.
 *
 * Optimizations & TDD-019/TDD-022 Features:
 * - **Entity Frustum Culling:** Skips entities whose AABBs fall outside the 6 camera frustum planes.
 * - **GPU Instancing & Atlas Batching:** Batches all visible [ItemDropEntity] drops and their up-to-64
 *   spinning offset cluster layers into a single instanced draw call when the atlas is available.
 * - **Spin, Bob & Stack Silhouette:** Drives Y rotation and sine-wave bobbing while capping visual
 *   stack copies at 64 even for arbitrarily large merged simulation stacks.
 * - **Light Intended Shading:** Samples `world.getIntendedLight(pos)` and maps `(-16..+16)` into
 *   `0.0..1.0` brightness (`(light + 16) / 32`).
 */
class EntityRenderer {

    private var entityShader: Shader? = null
    private var instancedShader: Shader? = null
    private var cubeMesh: VoxelMesh? = null

    private val modelMatrix = Matrix4.create()
    private val mvpMatrix = Matrix4.create()
    private val viewProjScratch = Matrix4.create()
    private val projScratch = Matrix4.create()
    private val frustum = Frustum()

    // Reusable zero-alloc scratch lists & render-state containers for GPU instancing
    private val visibleEntitiesScratch = ArrayList<Entity>()
    private val visibleTexHandlesScratch = ArrayList<Int>()
    private val visibleTexIdsScratch = ArrayList<String>()
    private val dropRenderStateScratch = ItemDropRenderState()
    private val layerPosRotScratch = FloatArray(4)

    private var instanceStagingArray = FloatArray(INITIAL_INSTANCE_CAPACITY * VoxelMesh.FLOATS_PER_INSTANCE)
    private var instanceStagingByteBuf: ByteBuffer = ByteBuffer
        .allocateDirect(INITIAL_INSTANCE_CAPACITY * VoxelMesh.FLOATS_PER_INSTANCE * 4)
        .order(ByteOrder.nativeOrder())
    private var instanceStagingFloatBuf: FloatBuffer = instanceStagingByteBuf.asFloatBuffer()

    var lastRenderedEntityCount: Int = 0
        private set
    var lastCulledEntityCount: Int = 0
        private set
    var lastTextureBindCount: Int = 0
        private set
    var lastRenderedInstanceCount: Int = 0
        private set
    var lastDrawCallCount: Int = 0
        private set
    var lastHandLightLevel: Float = 1f
        private set

    fun initGl() {
        release()

        val vertexSource = """
            #version 300 es
            layout (location = 0) in vec3 aPosition;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;

            uniform mat4 uMVPMatrix;
            out vec2 vTexCoord;
            out float vNormalShade;

            void main() {
                vTexCoord = aTexCoord;
                vNormalShade = clamp(0.75 + aNormal.y * 0.25 - abs(aNormal.x) * 0.12, 0.55, 1.0);
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        val fragmentSource = """
            #version 300 es
            precision mediump float;

            in vec2 vTexCoord;
            in float vNormalShade;

            uniform sampler2D uTexture;
            uniform float uLightLevel;
            uniform vec3 uTint;

            out vec4 fragColor;

            void main() {
                vec4 texColor = texture(uTexture, vTexCoord);
                if (texColor.a < 0.01) {
                    discard;
                }
                vec3 litRgb = texColor.rgb * uTint * vNormalShade * uLightLevel;
                fragColor = vec4(litRgb, texColor.a);
            }
        """.trimIndent()

        val instancedVs = """
            #version 300 es
            layout (location = 0) in vec3 aPosition;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;
            layout (location = 3) in vec4 aInstancePosRot;
            layout (location = 4) in vec4 aInstanceScaleLight;
            layout (location = 5) in vec4 aInstanceUvRect;

            uniform mat4 uViewProjMatrix;

            out vec2 vTexCoord;
            out float vNormalShade;
            out float vLightLevel;

            void main() {
                float c = cos(aInstancePosRot.w);
                float s = sin(aInstancePosRot.w);

                vec3 scaled = aPosition * aInstanceScaleLight.x;
                vec3 rotatedPos = vec3(
                    scaled.x * c + scaled.z * s,
                    scaled.y,
                    -scaled.x * s + scaled.z * c
                );
                vec3 rotatedNorm = vec3(
                    aNormal.x * c + aNormal.z * s,
                    aNormal.y,
                    -aNormal.x * s + aNormal.z * c
                );

                vec3 worldPos = aInstancePosRot.xyz + rotatedPos;
                vTexCoord = mix(aInstanceUvRect.xy, aInstanceUvRect.zw, aTexCoord);
                vNormalShade = clamp(0.75 + rotatedNorm.y * 0.25 - abs(rotatedNorm.x) * 0.12, 0.55, 1.0);
                vLightLevel = aInstanceScaleLight.y;
                gl_Position = uViewProjMatrix * vec4(worldPos, 1.0);
            }
        """.trimIndent()

        val instancedFs = """
            #version 300 es
            precision mediump float;

            in vec2 vTexCoord;
            in float vNormalShade;
            in float vLightLevel;

            uniform sampler2D uTexture;

            out vec4 fragColor;

            void main() {
                vec4 texColor = texture(uTexture, vTexCoord);
                if (texColor.a < 0.01) {
                    discard;
                }
                vec3 litRgb = texColor.rgb * vNormalShade * vLightLevel;
                fragColor = vec4(litRgb, texColor.a);
            }
        """.trimIndent()

        entityShader = Shader(vertexSource, fragmentSource).apply {
            compile()
        }
        instancedShader = Shader(instancedVs, instancedFs).apply {
            compile()
        }
        cubeMesh = VoxelMesh()
    }

    /**
     * Filters [entities] against the frustum in [viewProjMatrix] and sorts visible entities
     * by texture ID hash/handle to group identical texture binds together cleanly.
     */
    fun prepareVisibleEntitiesGrouped(
        entities: List<Entity>,
        viewProjMatrix: FloatArray,
        cameraX: Double = 0.0,
        cameraY: Double = 0.0,
        cameraZ: Double = 0.0,
        resolveKey: (Entity) -> Int
    ): List<Entity> {
        visibleEntitiesScratch.clear()
        visibleTexHandlesScratch.clear()
        frustum.extractPlanes(viewProjMatrix)

        var culled = 0
        for (i in 0 until entities.size) {
            val entity = entities[i]
            if (entity.isRemoved) continue
            if (!frustum.intersectsRelativeAabb(entity.boundingBox, cameraX, cameraY, cameraZ)) {
                culled++
                continue
            }
            visibleEntitiesScratch.add(entity)
            visibleTexHandlesScratch.add(resolveKey(entity))
        }

        // In-place insertion sort by resolved texture key so identical textures draw consecutively
        for (i in 1 until visibleEntitiesScratch.size) {
            val keyEntity = visibleEntitiesScratch[i]
            val keyTex = visibleTexHandlesScratch[i]
            var j = i - 1
            while (j >= 0 && visibleTexHandlesScratch[j] > keyTex) {
                visibleEntitiesScratch[j + 1] = visibleEntitiesScratch[j]
                visibleTexHandlesScratch[j + 1] = visibleTexHandlesScratch[j]
                j--
            }
            visibleEntitiesScratch[j + 1] = keyEntity
            visibleTexHandlesScratch[j + 1] = keyTex
        }

        lastCulledEntityCount = culled
        lastRenderedEntityCount = visibleEntitiesScratch.size
        return visibleEntitiesScratch
    }

    /**
     * Populates CPU-side per-instance transform/light/UV data from `:core`'s [EntityRenderState]
     * snapshots and returns total emitted GPU instances (up to 64 offset spinning layers per stack).
     * Safe to call in headless unit tests without an EGL context.
     */
    fun buildInstancedDropData(
        visibleEntities: List<Entity>,
        texturePipeline: ITexturePipeline?,
        world: World?,
        useAtlasUvs: Boolean,
        cameraX: Double = 0.0,
        cameraY: Double = 0.0,
        cameraZ: Double = 0.0
    ): Int {
        var totalInstances = 0
        val pipelineObj = texturePipeline as? TexturePipeline

        for (i in 0 until visibleEntities.size) {
            val entity = visibleEntities[i]
            val renderState = entity.updateRenderState(dropRenderStateScratch)
            val layers = renderState?.renderLayerCount ?: 1
            ensureInstanceCapacity(totalInstances + layers)

            val baseX = renderState?.centerX ?: ((entity.boundingBox.minX + entity.boundingBox.maxX) * 0.5)
            val baseY = (renderState?.centerY ?: ((entity.boundingBox.minY + entity.boundingBox.maxY) * 0.5)) +
                (renderState?.bobOffsetY ?: 0f)
            val baseZ = renderState?.centerZ ?: ((entity.boundingBox.minZ + entity.boundingBox.maxZ) * 0.5)
            val scale = renderState?.scale ?: ItemDropEntity.WIDTH
            val yawFallback = renderState?.yawRadians ?: 0f

            val lightMul = if (world != null) {
                val bx = floor(baseX).toLong()
                val by = floor(baseY).toLong()
                val bz = floor(baseZ).toLong()
                lightToMultiplier(world.getIntendedLight(bx, by, bz))
            } else {
                1.0f
            }

            val texId = resolveEntityTextureId(entity, renderState)
            val uvRect = if (useAtlasUvs && pipelineObj != null) {
                pipelineObj.getAtlasUv(texId)
            } else {
                null
            }
            val uMin = uvRect?.uMin ?: 0f
            val vMin = uvRect?.vMin ?: 0f
            val uMax = uvRect?.uMax ?: 1f
            val vMax = uvRect?.vMax ?: 1f

            val dropState = renderState as? ItemDropRenderState
            for (layer in 0 until layers) {
                val offsetIndex = totalInstances * VoxelMesh.FLOATS_PER_INSTANCE
                val ox: Float
                val oy: Float
                val oz: Float
                val yaw: Float

                if (dropState != null) {
                    dropState.writeRelativeLayerTransform(
                        layerIndex = layer,
                        cameraX = cameraX,
                        cameraY = cameraY,
                        cameraZ = cameraZ,
                        outPosRot = layerPosRotScratch,
                        outOffset = 0
                    )
                    ox = layerPosRotScratch[0]
                    oy = layerPosRotScratch[1]
                    oz = layerPosRotScratch[2]
                    yaw = layerPosRotScratch[3]
                } else {
                    ox = (baseX - cameraX).toFloat()
                    oy = (baseY - cameraY).toFloat()
                    oz = (baseZ - cameraZ).toFloat()
                    yaw = yawFallback
                }

                instanceStagingArray[offsetIndex + 0] = ox
                instanceStagingArray[offsetIndex + 1] = oy
                instanceStagingArray[offsetIndex + 2] = oz
                instanceStagingArray[offsetIndex + 3] = yaw
                instanceStagingArray[offsetIndex + 4] = scale
                instanceStagingArray[offsetIndex + 5] = lightMul
                instanceStagingArray[offsetIndex + 6] = 0f
                instanceStagingArray[offsetIndex + 7] = 0f
                instanceStagingArray[offsetIndex + 8] = uMin
                instanceStagingArray[offsetIndex + 9] = vMin
                instanceStagingArray[offsetIndex + 10] = uMax
                instanceStagingArray[offsetIndex + 11] = vMax

                totalInstances++
            }
        }

        lastRenderedInstanceCount = totalInstances
        return totalInstances
    }

    fun getStagingFloatCount(instanceCount: Int): Int = instanceCount * VoxelMesh.FLOATS_PER_INSTANCE

    fun getStagingInstanceValues(instanceIndex: Int, out: FloatArray) {
        val base = instanceIndex * VoxelMesh.FLOATS_PER_INSTANCE
        System.arraycopy(instanceStagingArray, base, out, 0, minOf(out.size, VoxelMesh.FLOATS_PER_INSTANCE))
    }

    private fun ensureInstanceCapacity(requiredInstances: Int) {
        val requiredFloats = requiredInstances * VoxelMesh.FLOATS_PER_INSTANCE
        if (requiredFloats <= instanceStagingArray.size) return
        val nextInstances = maxOf(requiredInstances, (instanceStagingArray.size / VoxelMesh.FLOATS_PER_INSTANCE) * 2)
        val nextFloats = nextInstances * VoxelMesh.FLOATS_PER_INSTANCE
        instanceStagingArray = instanceStagingArray.copyOf(nextFloats)
        instanceStagingByteBuf = ByteBuffer.allocateDirect(nextFloats * 4).order(ByteOrder.nativeOrder())
        instanceStagingFloatBuf = instanceStagingByteBuf.asFloatBuffer()
    }

    /**
     * Frustum-culls, packs transform/light/UV instance attributes, and draws all visible
     * [ItemDropEntity] drops via batched GPU instancing (`glDrawElementsInstanced`).
     */
    fun render(
        entities: List<Entity>,
        camera: Camera3D,
        texturePipeline: ITexturePipeline,
        world: World? = null
    ) {
        lastRenderedEntityCount = 0
        lastCulledEntityCount = 0
        lastTextureBindCount = 0
        lastRenderedInstanceCount = 0
        lastDrawCallCount = 0
        if (entities.isEmpty()) return

        val shader = instancedShader ?: return
        if (shader.programId == 0) return
        val mesh = cubeMesh ?: return

        camera.copyViewProjectionMatrix(viewProjScratch)
        frustum.extractPlanes(viewProjScratch)

        visibleEntitiesScratch.clear()
        visibleTexHandlesScratch.clear()
        visibleTexIdsScratch.clear()

        var culled = 0
        for (i in 0 until entities.size) {
            val entity = entities[i]
            if (entity.isRemoved) continue
            if (!frustum.intersectsRelativeAabb(entity.boundingBox, camera.eyeX, camera.eyeY, camera.eyeZ)) {
                culled++
                continue
            }
            val texId = resolveEntityTextureId(entity)
            val handle = texturePipeline.getTextureHandle(texId)
            visibleEntitiesScratch.add(entity)
            visibleTexIdsScratch.add(texId)
            visibleTexHandlesScratch.add(handle)
        }

        lastCulledEntityCount = culled
        lastRenderedEntityCount = visibleEntitiesScratch.size
        if (visibleEntitiesScratch.isEmpty()) return

        // Group consecutive entities by texture handle (critical when falling back to per-texture batches)
        for (i in 1 until visibleEntitiesScratch.size) {
            val e = visibleEntitiesScratch[i]
            val h = visibleTexHandlesScratch[i]
            val tId = visibleTexIdsScratch[i]
            var j = i - 1
            while (j >= 0 && visibleTexHandlesScratch[j] > h) {
                visibleEntitiesScratch[j + 1] = visibleEntitiesScratch[j]
                visibleTexHandlesScratch[j + 1] = visibleTexHandlesScratch[j]
                visibleTexIdsScratch[j + 1] = visibleTexIdsScratch[j]
                j--
            }
            visibleEntitiesScratch[j + 1] = e
            visibleTexHandlesScratch[j + 1] = h
            visibleTexIdsScratch[j + 1] = tId
        }

        val pipelineObj = texturePipeline as? TexturePipeline
        val atlasHandle = pipelineObj?.atlasTextureHandle ?: 0
        val useAtlasSingleDraw = atlasHandle != 0

        val totalInstances = buildInstancedDropData(
            visibleEntities = visibleEntitiesScratch,
            texturePipeline = texturePipeline,
            world = world,
            useAtlasUvs = useAtlasSingleDraw,
            cameraX = camera.eyeX,
            cameraY = camera.eyeY,
            cameraZ = camera.eyeZ
        )
        if (totalInstances <= 0) return

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        shader.bind()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        shader.setUniform1i("uTexture", 0)
        shader.setUniformMatrix4fv("uViewProjMatrix", viewProjScratch)

        if (useAtlasSingleDraw) {
            // Optimal path: all item drops & up-to-64 stack layers in 1 single instanced draw call!
            val totalFloats = totalInstances * VoxelMesh.FLOATS_PER_INSTANCE
            instanceStagingFloatBuf.clear()
            instanceStagingFloatBuf.put(instanceStagingArray, 0, totalFloats)
            instanceStagingFloatBuf.flip()

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasHandle)
            lastTextureBindCount = 1
            mesh.drawInstanced(instanceStagingFloatBuf, totalInstances)
            lastDrawCallCount = 1
        } else {
            // Fallback path when no atlas is built: 1 instanced draw call per unique texture batch
            var bindCount = 0
            var drawCalls = 0
            var entityCursor = 0
            var instanceCursor = 0

            while (entityCursor < visibleEntitiesScratch.size) {
                val batchHandle = visibleTexHandlesScratch[entityCursor]
                var batchInstances = 0
                while (entityCursor < visibleEntitiesScratch.size &&
                    visibleTexHandlesScratch[entityCursor] == batchHandle
                ) {
                    val drop = visibleEntitiesScratch[entityCursor] as? ItemDropEntity
                    batchInstances += drop?.visualRenderLayerCount() ?: 1
                    entityCursor++
                }

                val floatStart = instanceCursor * VoxelMesh.FLOATS_PER_INSTANCE
                val batchFloats = batchInstances * VoxelMesh.FLOATS_PER_INSTANCE
                instanceStagingFloatBuf.clear()
                instanceStagingFloatBuf.put(instanceStagingArray, floatStart, batchFloats)
                instanceStagingFloatBuf.flip()

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, batchHandle)
                bindCount++
                mesh.drawInstanced(instanceStagingFloatBuf, batchInstances)
                drawCalls++

                instanceCursor += batchInstances
            }
            lastTextureBindCount = bindCount
            lastDrawCallCount = drawCalls
        }

        shader.unbind()
    }

    /**
     * Renders the player's first-person hand / held block in view space, shaded by `world.getIntendedLight`
     * at the camera eye voxel position (TDD-019 Entity & Player Interaction).
     */
    fun renderFirstPersonHand(
        camera: Camera3D,
        world: World?,
        selectedItem: Item?,
        texturePipeline: ITexturePipeline
    ) {
        val shader = entityShader ?: return
        if (shader.programId == 0) return
        val mesh = cubeMesh ?: return

        val eyeX = floor(camera.eyeX).toLong()
        val eyeY = floor(camera.eyeY).toLong()
        val eyeZ = floor(camera.eyeZ).toLong()
        val intended = world?.getIntendedLight(eyeX, eyeY, eyeZ) ?: Chunk.MAX_LIGHT.toByte()
        val lightMul = lightToMultiplier(intended)
        lastHandLightLevel = lightMul

        camera.copyProjectionMatrix(projScratch)
        val block = selectedItem?.associatedBlock

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        Matrix4.setIdentity(modelMatrix)
        if (block != null && !block.isAir) {
            Matrix4.translate(modelMatrix, 0.54f, -0.44f, -0.95f)
            Matrix4.rotate(modelMatrix, -28f, 0f, 1f, 0f)
            Matrix4.rotate(modelMatrix, 14f, 1f, 0f, 0f)
            Matrix4.scale(modelMatrix, 0.34f, 0.34f, 0.34f)
        } else {
            Matrix4.translate(modelMatrix, 0.56f, -0.50f, -0.88f)
            Matrix4.rotate(modelMatrix, -22f, 0f, 1f, 0f)
            Matrix4.rotate(modelMatrix, 26f, 1f, 0f, 0f)
            Matrix4.scale(modelMatrix, 0.20f, 0.44f, 0.20f)
        }
        Matrix4.multiply(mvpMatrix, projScratch, modelMatrix)

        val texId = if (block != null && !block.isAir && block.topTexture.isNotEmpty()) {
            block.topTexture
        } else {
            TextureConstants.PROTO_DIRT
        }
        val handle = texturePipeline.getTextureHandle(texId)

        shader.bind()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)
        shader.setUniform1i("uTexture", 0)
        shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)
        shader.setUniform1f("uLightLevel", lightMul)
        if (block != null && !block.isAir) {
            shader.setUniform3f("uTint", 1f, 1f, 1f)
        } else {
            shader.setUniform3f("uTint", 0.92f, 0.72f, 0.58f)
        }

        mesh.drawUniformBlock(texturePipeline, shader, texId, bindTexture = false)
        shader.unbind()
    }

    private fun resolveEntityTextureId(entity: Entity, renderState: EntityRenderState? = null): String {
        val stateKey = renderState?.textureKey
        if (!stateKey.isNullOrEmpty()) return stateKey
        val drop = entity as? ItemDropEntity ?: return TextureConstants.PROTO_DEBUG_BUG
        val key = drop.resolveTextureKey()
        return key.ifEmpty { TextureConstants.PROTO_DEBUG_BUG }
    }

    fun release() {
        entityShader?.release()
        entityShader = null
        instancedShader?.release()
        instancedShader = null
        cubeMesh?.release()
        cubeMesh = null
    }

    companion object {
        private const val INITIAL_INSTANCE_CAPACITY = 256

        /** Maps signed TDD-019 `[-16..+16]` light byte to `[0.0..1.0]` linear brightness. */
        fun lightToMultiplier(lightLevel: Byte): Float {
            return ((lightLevel.toInt() + 16) / 32f).coerceIn(0f, 1f)
        }

        fun computeRenderLayerCount(stackCount: Int): Int {
            return stackCount.coerceIn(1, ItemDropEntity.MAX_RENDER_STACK_LAYERS)
        }
    }
}
