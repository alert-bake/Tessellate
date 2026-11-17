package com.nitrous.tessellate.entity

import android.opengl.GLES30
import com.nitrous.tessellate.core.ITexturePipeline
import com.nitrous.tessellate.graphics.Camera3D
import com.nitrous.tessellate.graphics.Matrix4
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.item.ItemRegistry
import com.nitrous.tessellate.scene.VoxelMesh
import com.nitrous.tessellate.texture.TextureConstants
import com.nitrous.tessellate.world.BlockType

/**
 * Renders active world entities outside batched chunk meshes using an independent
 * OpenGL ES 3.0 draw path backed by [Shader], [Matrix4], [VoxelMesh], and [ITexturePipeline].
 */
class EntityRenderer {

    private var entityShader: Shader? = null
    private var cubeMesh: VoxelMesh? = null

    private val modelMatrix = Matrix4.create()
    private val mvpMatrix = Matrix4.create()

    fun initGl() {
        release()

        val vertexSource = """
            #version 300 es
            layout (location = 0) in vec3 aPosition;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;

            uniform mat4 uMVPMatrix;
            out vec2 vTexCoord;

            void main() {
                vTexCoord = aTexCoord;
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        val fragmentSource = """
            #version 300 es
            precision mediump float;

            in vec2 vTexCoord;
            uniform sampler2D uTexture;

            out vec4 fragColor;

            void main() {
                fragColor = texture(uTexture, vTexCoord);
            }
        """.trimIndent()

        entityShader = Shader(vertexSource, fragmentSource).apply {
            compile()
        }
        cubeMesh = VoxelMesh()
    }

    private val viewProjScratch = Matrix4.create()

    /**
     * Renders each non-removed entity as a 0.25 x 0.25 x 0.25 cube in an independent draw call.
     */
    fun render(entities: List<Entity>, camera: Camera3D, texturePipeline: ITexturePipeline) {
        if (entities.isEmpty()) return
        val shader = entityShader ?: return
        if (shader.programId == 0) return
        val mesh = cubeMesh ?: return

        camera.copyViewProjectionMatrix(viewProjScratch)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        shader.bind()

        for (i in 0 until entities.size) {
            val entity = entities[i]
            if (entity.isRemoved) continue

            val box = entity.boundingBox
            val cx = (box.minX + box.maxX) * 0.5f
            val cy = (box.minY + box.maxY) * 0.5f
            val cz = (box.minZ + box.maxZ) * 0.5f

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
            val textureId = resolveEntityTextureId(entity)
            mesh.drawUniformBlock(texturePipeline, shader, textureId)
        }

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
}
