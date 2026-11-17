package com.nitrous.tessellate.core

/**
 * High-level graphics runtime interface for Tessellate.
 * Encapsulates the graphics lifecycle and decouples game systems
 * from underlying graphics engine backends.
 */
interface IGraphicsRuntime {
    val backend: IRenderBackend
    val texturePipeline: ITexturePipeline
    val camera: ICamera

    fun onSurfaceCreated()
    fun onSurfaceChanged(width: Int, height: Int)
    fun onDrawFrame()
    fun onPause()
    fun onResume()
    fun onDestroy()
}

/**
 * Rendering backend abstraction for OpenGL ES 3.
 */
interface IRenderBackend {
    val apiVersion: String
    val vendorInfo: String
    val rendererInfo: String

    fun init()
    fun setViewport(x: Int, y: Int, width: Int, height: Int)
    fun clear(r: Float, g: Float, b: Float, a: Float)
    fun setDepthTest(enabled: Boolean)
    fun setCullFace(enabled: Boolean)
    fun setBlending(enabled: Boolean)
    fun release()
}

/**
 * Camera interface providing view and projection transformations.
 */
interface ICamera {
    val viewMatrix: FloatArray
    val projectionMatrix: FloatArray
    val viewProjectionMatrix: FloatArray
    var fovDegrees: Float
    var nearPlane: Float
    var farPlane: Float
    var pitch: Float
    var yaw: Float
    var distance: Float

    fun updateProjection(aspectRatio: Float)
    fun updateView()
    fun orbit(deltaPitch: Float, deltaYaw: Float)
    fun zoom(deltaDistance: Float)
    fun reset()
}

/**
 * Diagnostic result when querying or loading a texture.
 */
data class TextureDiagnostic(
    val textureId: String,
    val isSuccess: Boolean,
    val isFallback: Boolean,
    val message: String
)

/**
 * Texture pipeline interface managing asset loading, filtering,
 * UV mapping lookup, and diagnostic fallbacks.
 */
interface ITexturePipeline {
    fun loadTexture(id: String, assetPath: String): Boolean
    fun getTextureHandle(id: String): Int
    fun getDiagnostic(id: String): TextureDiagnostic
    fun getAllLoadedIds(): List<String>
    fun reloadAll()
    fun release()
}

/**
 * Scene interface representing renderable voxel environments.
 */
interface IGameScene {
    fun init(runtime: IGraphicsRuntime)
    fun update(deltaTimeSeconds: Float)
    fun render(runtime: IGraphicsRuntime)
    fun release()
}
