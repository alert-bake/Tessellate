package com.nitrous.tessellate.graphics

import android.opengl.GLES30
import android.util.Log
import com.nitrous.tessellate.core.IRenderBackend

/**
 * OpenGL ES 3.0 Rendering Backend implementation for Tessellate.
 */
class OpenGlEs3Backend : IRenderBackend {

    override var apiVersion: String = "OpenGL ES 3.0"
        private set

    override var vendorInfo: String = "Unknown Vendor"
        private set

    override var rendererInfo: String = "Unknown Renderer"
        private set

    override fun init() {
        apiVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "OpenGL ES 3.0"
        vendorInfo = GLES30.glGetString(GLES30.GL_VENDOR) ?: "Android GPU"
        rendererInfo = GLES30.glGetString(GLES30.GL_RENDERER) ?: "GLES3 Device"

        Log.i(TAG, "Initialized OpenGL ES 3 Backend: $apiVersion | Vendor: $vendorInfo | Renderer: $rendererInfo")

        // Default render state
        setDepthTest(true)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        setCullFace(true)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        setBlending(true)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glClearColor(0.45f, 0.65f, 1.0f, 1.0f)

        checkGlError("Backend init")
    }

    override fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        GLES30.glViewport(x, y, width, height)
    }

    override fun clear(r: Float, g: Float, b: Float, a: Float) {
        GLES30.glClearColor(r, g, b, a)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
    }

    override fun setDepthTest(enabled: Boolean) {
        if (enabled) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        } else {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        }
    }

    override fun setCullFace(enabled: Boolean) {
        if (enabled) {
            GLES30.glEnable(GLES30.GL_CULL_FACE)
        } else {
            GLES30.glDisable(GLES30.GL_CULL_FACE)
        }
    }

    override fun setBlending(enabled: Boolean) {
        if (enabled) {
            GLES30.glEnable(GLES30.GL_BLEND)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    override fun release() {
        Log.i(TAG, "Releasing OpenGL ES 3 Backend resources")
    }

    fun checkGlError(op: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL Error after $op: 0x${Integer.toHexString(error)}")
        }
    }

    companion object {
        private const val TAG = "Tessellate.GLES3"
    }
}
