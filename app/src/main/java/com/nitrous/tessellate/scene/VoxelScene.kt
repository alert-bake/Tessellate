package com.nitrous.tessellate.scene

import android.opengl.GLES30
import com.nitrous.tessellate.core.IGameScene
import com.nitrous.tessellate.core.IGraphicsRuntime
import com.nitrous.tessellate.graphics.Shader
import com.nitrous.tessellate.math.Matrix4
import com.nitrous.tessellate.texture.TextureConstants

enum class SceneRenderMode {
    NATURAL_VOXEL_BLOCK,    // Classic grass voxel: proto_grass top, proto_grass_side sides, proto_dirt bottom
    INSPECT_SINGLE_TEXTURE, // Uniform cube mapped with selected prototype texture
    VOXEL_COLUMN,           // 3-tier voxel stack: Grass, Dirt, Stone
    SHOWCASE_ALL            // Grid of 5 blocks showing every prototype texture simultaneously
}

/**
 * Diagnostic 3D showcase scene for inspecting voxel geometry and prototype block textures.
 * Manages lighting, shaders, 3D transformations, voxel models, and scene rendering modes.
 */
class VoxelScene : IGameScene {

    private var voxelShader: Shader? = null
    private var voxelMesh: VoxelMesh? = null

    // Scene transformation matrices
    private val modelMatrix = Matrix4.create()
    private val mvpMatrix = Matrix4.create()
    private val tempMatrix = Matrix4.create()

    // Scene properties
    var renderMode: SceneRenderMode = SceneRenderMode.NATURAL_VOXEL_BLOCK
    var activeInspectedTextureId: String = TextureConstants.PROTO_GRASS
    var isAutoRotateEnabled: Boolean = true
    var autoRotateAngle: Float = 0f
    var rotationSpeedDegPerSec: Float = 25f

    // Lighting parameters
    private val lightDir = floatArrayOf(0.4f, 0.9f, 0.5f)
    private val lightColor = floatArrayOf(0.85f, 0.85f, 0.85f)
    private val ambientColor = floatArrayOf(0.45f, 0.45f, 0.45f)

    private val viewProjScratch = Matrix4.create()

    override fun init(runtime: IGraphicsRuntime) {
        release()
        val vertexSource = """
            #version 300 es
            layout (location = 0) in vec3 aPosition;
            layout (location = 1) in vec3 aNormal;
            layout (location = 2) in vec2 aTexCoord;

            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;

            out vec2 vTexCoord;
            out vec3 vNormal;
            out vec3 vFragPos;

            void main() {
                vTexCoord = aTexCoord;
                vNormal = mat3(uModelMatrix) * aNormal;
                vFragPos = vec3(uModelMatrix * vec4(aPosition, 1.0));
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """.trimIndent()

        val fragmentSource = """
            #version 300 es
            precision mediump float;

            in vec2 vTexCoord;
            in vec3 vNormal;
            in vec3 vFragPos;

            uniform sampler2D uTexture;
            uniform vec3 uLightDir;
            uniform vec3 uLightColor;
            uniform vec3 uAmbientColor;

            out vec4 fragColor;

            void main() {
                vec4 texColor = texture(uTexture, vTexCoord);
                vec3 norm = normalize(vNormal);
                vec3 lightDirection = normalize(uLightDir);

                // Directional voxel lighting
                float diff = max(dot(norm, lightDirection), 0.0);
                vec3 diffuse = diff * uLightColor;
                vec3 ambient = uAmbientColor;

                vec3 result = (ambient + diffuse) * texColor.rgb;
                fragColor = vec4(result, texColor.a);
            }
        """.trimIndent()

        voxelShader = Shader(vertexSource, fragmentSource).apply {
            compile()
        }

        voxelMesh = VoxelMesh()
    }

    override fun update(deltaTimeSeconds: Float) {
        if (isAutoRotateEnabled) {
            autoRotateAngle = (autoRotateAngle + rotationSpeedDegPerSec * deltaTimeSeconds) % 360f
        }
    }

    override fun render(runtime: IGraphicsRuntime) {
        if (voxelShader == null || voxelMesh == null) {
            init(runtime)
        }
        val shader = voxelShader ?: return
        if (shader.programId == 0) return
        val mesh = voxelMesh ?: return
        val pipeline = runtime.texturePipeline
        val camera = runtime.camera
        if (camera is com.nitrous.tessellate.graphics.Camera3D) {
            camera.copyViewProjectionMatrix(viewProjScratch)
        } else {
            System.arraycopy(camera.viewProjectionMatrix, 0, viewProjScratch, 0, 16)
        }

        runtime.backend.setDepthTest(true)
        runtime.backend.setCullFace(true)

        shader.bind()
        shader.setUniform3f("uLightDir", lightDir[0], lightDir[1], lightDir[2])
        shader.setUniform3f("uLightColor", lightColor[0], lightColor[1], lightColor[2])
        shader.setUniform3f("uAmbientColor", ambientColor[0], ambientColor[1], ambientColor[2])

        when (renderMode) {
            SceneRenderMode.NATURAL_VOXEL_BLOCK -> {
                drawCubeAt(
                    mesh, shader, viewProjScratch,
                    x = 0f, y = 0f, z = 0f,
                    rotY = autoRotateAngle,
                    topTexture = TextureConstants.PROTO_GRASS,
                    sideTexture = TextureConstants.PROTO_GRASS_SIDE,
                    bottomTexture = TextureConstants.PROTO_DIRT,
                    pipeline = pipeline
                )
            }
            SceneRenderMode.INSPECT_SINGLE_TEXTURE -> {
                drawUniformCubeAt(
                    mesh, shader, viewProjScratch,
                    x = 0f, y = 0f, z = 0f,
                    rotY = autoRotateAngle,
                    textureId = activeInspectedTextureId,
                    pipeline = pipeline
                )
            }
            SceneRenderMode.VOXEL_COLUMN -> {
                // Stack of 3 blocks: Grass on top, Dirt in center, Stone on bottom
                val baseY = -0.5f
                // Top: Grass block
                drawCubeAt(
                    mesh, shader, viewProjScratch,
                    x = 0f, y = baseY + 1.05f, z = 0f,
                    rotY = autoRotateAngle,
                    topTexture = TextureConstants.PROTO_GRASS,
                    sideTexture = TextureConstants.PROTO_GRASS_SIDE,
                    bottomTexture = TextureConstants.PROTO_DIRT,
                    pipeline = pipeline
                )
                // Middle: Dirt block
                drawUniformCubeAt(
                    mesh, shader, viewProjScratch,
                    x = 0f, y = baseY, z = 0f,
                    rotY = autoRotateAngle,
                    textureId = TextureConstants.PROTO_DIRT,
                    pipeline = pipeline
                )
                // Bottom: Stone block
                drawUniformCubeAt(
                    mesh, shader, viewProjScratch,
                    x = 0f, y = baseY - 1.05f, z = 0f,
                    rotY = autoRotateAngle,
                    textureId = TextureConstants.PROTO_STONE,
                    pipeline = pipeline
                )
            }
            SceneRenderMode.SHOWCASE_ALL -> {
                // Showcase all 5 prototype textures side-by-side in a circular layout
                val ids = TextureConstants.ALL_PROTOTYPE_IDS
                val radius = 1.6f
                val count = ids.size
                for (i in 0 until count) {
                    val angleRad = (i.toFloat() / count * 2.0 * Math.PI) + Math.toRadians(autoRotateAngle.toDouble())
                    val cx = (radius * Math.cos(angleRad)).toFloat()
                    val cz = (radius * Math.sin(angleRad)).toFloat()
                    drawUniformCubeAt(
                        mesh, shader, viewProjScratch,
                        x = cx, y = 0f, z = cz,
                        rotY = -Math.toDegrees(angleRad).toFloat(),
                        textureId = ids[i],
                        scale = 0.7f,
                        pipeline = pipeline
                    )
                }
            }
        }

        shader.unbind()
    }

    private fun drawCubeAt(
        mesh: VoxelMesh,
        shader: Shader,
        viewProjMatrix: FloatArray,
        x: Float, y: Float, z: Float,
        rotY: Float,
        topTexture: String,
        sideTexture: String,
        bottomTexture: String,
        scale: Float = 1.0f,
        pipeline: com.nitrous.tessellate.core.ITexturePipeline
    ) {
        Matrix4.setIdentity(modelMatrix)
        Matrix4.translate(modelMatrix, x, y, z)
        Matrix4.rotate(modelMatrix, rotY, 0f, 1f, 0f)
        if (scale != 1.0f) {
            Matrix4.scale(modelMatrix, scale, scale, scale)
        }

        Matrix4.multiply(mvpMatrix, viewProjMatrix, modelMatrix)

        shader.setUniformMatrix4fv("uModelMatrix", modelMatrix)
        shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)

        mesh.drawVoxelBlock(pipeline, shader, topTexture, sideTexture, bottomTexture)
    }

    private fun drawUniformCubeAt(
        mesh: VoxelMesh,
        shader: Shader,
        viewProjMatrix: FloatArray,
        x: Float, y: Float, z: Float,
        rotY: Float,
        textureId: String,
        scale: Float = 1.0f,
        pipeline: com.nitrous.tessellate.core.ITexturePipeline
    ) {
        Matrix4.setIdentity(modelMatrix)
        Matrix4.translate(modelMatrix, x, y, z)
        Matrix4.rotate(modelMatrix, rotY, 0f, 1f, 0f)
        if (scale != 1.0f) {
            Matrix4.scale(modelMatrix, scale, scale, scale)
        }

        Matrix4.multiply(mvpMatrix, viewProjMatrix, modelMatrix)

        shader.setUniformMatrix4fv("uModelMatrix", modelMatrix)
        shader.setUniformMatrix4fv("uMVPMatrix", mvpMatrix)

        mesh.drawUniformBlock(pipeline, shader, textureId)
    }

    override fun release() {
        voxelShader?.release()
        voxelShader = null
        voxelMesh?.release()
        voxelMesh = null
    }
}
