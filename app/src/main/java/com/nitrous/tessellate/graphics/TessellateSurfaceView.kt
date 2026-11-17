package com.nitrous.tessellate.graphics

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import com.nitrous.tessellate.core.TessellateEngine
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Custom GLSurfaceView configured for robust OpenGL ES 3.0 runtime.
 * Implements fallback-tolerant EGL context and config selection to prevent
 * "No config chosen" or emulator-specific EGL initialization crashes.
 */
class TessellateSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val engine = TessellateEngine(context)

    init {
        // Set EGL context client version to 3 (OpenGL ES 3.0)
        setEGLContextClientVersion(3)

        // Custom resilient EGLContextFactory to ensure GLES3 context creation with error logging
        setEGLContextFactory(object : EGLContextFactory {
            private val EGL_CONTEXT_CLIENT_VERSION = 0x3098

            override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
                val attribList = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
                var context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribList)
                if (context == null || context == EGL10.EGL_NO_CONTEXT) {
                    Log.w(TAG, "GLES 3.0 context creation failed, attempting fallback to GLES 2.0")
                    val fallbackAttrib = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
                    context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, fallbackAttrib)
                }
                return context
            }

            override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
                if (!egl.eglDestroyContext(display, context)) {
                    Log.e(TAG, "Failed to destroy EGLContext: " + egl.eglGetError())
                }
            }
        })

        // Resilient ConfigChooser: RGBA8888 with Depth >= 16
        setEGLConfigChooser(object : EGLConfigChooser {
            override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
                val configSpec = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_NONE
                )
                val fallbackSpec = intArrayOf(
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_NONE
                )
                val numConfigs = IntArray(1)
                var chosenSpec = configSpec
                if (!egl.eglChooseConfig(display, configSpec, null, 0, numConfigs) || numConfigs[0] <= 0) {
                    chosenSpec = fallbackSpec
                    if (!egl.eglChooseConfig(display, fallbackSpec, null, 0, numConfigs) || numConfigs[0] <= 0) {
                        throw IllegalStateException("No compatible EGL configs found on device")
                    }
                }

                val configs = arrayOfNulls<EGLConfig>(numConfigs[0])
                egl.eglChooseConfig(display, chosenSpec, configs, numConfigs[0], numConfigs)
                return configs[0] ?: throw IllegalStateException("Failed to pick valid EGL config")
            }
        })

        preserveEGLContextOnPause = true
        setRenderer(engine)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return engine.onTouchEvent(event) || super.onTouchEvent(event)
    }

    fun handlePause() {
        engine.onPause()
        onPause()
    }

    fun handleResume() {
        onResume()
        engine.onResume()
    }

    fun handleDestroy() {
        engine.touchController.resetAllInputs()
        queueEvent {
            engine.onDestroy()
        }
    }

    companion object {
        private const val TAG = "Tessellate.SurfaceView"
    }
}
