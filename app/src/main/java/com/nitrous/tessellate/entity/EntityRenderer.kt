package com.nitrous.tessellate.entity

import android.opengl.GLES30
import com.nitrous.tessellate.core.ITexturePipeline
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.graphics.Frustum
import com.nitrous.tessellate.graphics.Matrix4
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.item.Item
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.scene.VoxelMesh
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.world.Chunk
import com.nitrous.tessellate.world.World
import kotlin.math.floor

/**
 * Renders active world entities and the player's first-person held item/hand outside batched chunk meshes.
 *
 * Optimizations & TDD-019 Lighting:
 * - **Entity Frustum Culling:** Skips entities whose AABBs fall outside the 6 camera frustum planes.
 * - **Texture Grouping:** Sorts visible entities in-place by resolved texture handle to minimize
 *   `glBindTexture` state switches per frame.
 * - **Light Intended Shading:** Samples `world.getIntendedLight(pos)` and maps `(-16..+16)` into
 *   `0.0..1.0` brightness (`(light + 16) / 32`) on dropped items and the player's first-person hand.
 */
class EntityRenderer {

    private var entityShader: Shader? = null
    private var cubeMesh: VoxelMesh? = null

    private val modelMatrix = Matrix4.create()
    private val mvpMatrix = Matrix4.create()
    private val viewProjScratch = Matrix4.create()
    private val projScratch = Matrix4.create()
    private val frustum = Frustum()

    // Reusable zero-alloc scratch lists for frustum culling & texture grouping
    private val visibleEntitiesScratch = ArrayList<Entity>()
    private val visibleTexHandlesScratch = ArrayList<Int>()
    private val visibleTexIdsScratch = ArrayList<String>()

    var lastRenderedEntityCount: Int = 0
        private set
    var lastCulledEntityCount: Int = 0
        private set
    var lastTextureBindCount: Int = 0
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
                // Subtle directional shading so held items & drops retain crisp voxel depth
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

        entityShader = Shader(vertexSource, fragmentSource).apply {
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
        resolveKey: (Entity) -> Int
    ): List<Entity> {
        visibleEntitiesScratch.clear()
        visibleTexHandlesScratch.clear()
        frustum.extractPlanes(viewProjMatrix)

        var culled = 0
        for (i in 0 until entities.size) {
            val entity = entities[i]
            if (entity.isRemoved) continue
            if (!frustum.intersectsAabb(entity.boundingBox)) {
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
     * Frustum-culls, groups by texture, and renders non-removed world entities shaded by
     * [World.getIntendedLight].
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
        if (entities.isEmpty()) return

        val shader = entityShader ?: return
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
            if (!frustum.intersectsAabb(entity.boundingBox)) {
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

        // Group consecutive draws by texture handle
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

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        shader.bind()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        shader.setUniform1i("uTexture", 0)
        shader.setUniform3f("uTint", 1f, 1f, 1f)

        var lastBoundHandle = -1
        var bindCount = 0

        for (i in 0 until visibleEntitiesScratch.size) {
            val entity = visibleEntitiesScratch[i]
            val box = entity.boundingBox
            val cx = (box.minX + box.maxX) * 0.5f
            val cy = (box.minY + box.maxY) * 0.5f
            val cz = (box.minZ + box.maxZ) * 0.5f

            val lightMul = if (world != null) {
                val bx = floor(cx).toInt()
                val by = floor(cy).toInt()
                val bz = floor(cz).toInt()
                lightToMultiplier(world.getIntendedLight(bx, by, bz))
            } else {
                1.0f
            }

            Matrix4.setIdentity(modelMatrix)
            Matrix4.translate(modelMatrix, cx, cy, cz)
            Matrix4.scale(
                modelMatrix,
                ItemDropEntity.WIDTH,
                ItemDropEntity.HEIGHT,
                ItemDropEntity.DEPTH
            )
            Matrix4.multiply(mvpMatrix, viewProjScratch, modelMatrix)

            shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)
            shader.setUniform1f("uLightLevel", lightMul)

            val handle = visibleTexHandlesScratch[i]
            if (handle != lastBoundHandle) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle)
                lastBoundHandle = handle
                bindCount++
            }
            mesh.drawUniformBlock(texturePipeline, shader, visibleTexIdsScratch[i], bindTexture = false)
        }

        lastTextureBindCount = bindCount
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

        val eyeX = floor(camera.eyeX).toInt()
        val eyeY = floor(camera.eyeY).toInt()
        val eyeZ = floor(camera.eyeZ).toInt()
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
            // Bare voxel arm foreground proxy
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

    private fun resolveEntityTextureId(entity: Entity): String {
        val drop = entity as? ItemDropEntity ?: return TextureConstants.PROTO_DEBUG_BUG
        val item = ItemRegistry.get(drop.itemId)
        val block = item?.associatedBlock
        return if (block != null && block.topTexture.isNotEmpty()) {
            block.topTexture
        } else {
            TextureConstants.PROTO_DEBUG_BUG
        }
    }

    fun release() {
        entityShader?.release()
        entityShader = null
        cubeMesh?.release()
        cubeMesh = null
    }

    companion object {
        /** Maps signed TDD-019 `[-16..+16]` light byte to `[0.0..1.0]` linear brightness. */
        fun lightToMultiplier(lightLevel: Byte): Float {
            return ((lightLevel.toInt() + 16) / 32f).coerceIn(0f, 1f)
        }
    }
}
