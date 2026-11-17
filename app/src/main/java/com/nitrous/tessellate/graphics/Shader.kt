package com.nitrous.tessellate.graphics

import android.opengl.GLES30
import android.util.Log

/**
 * Encapsulates an OpenGL ES 3.0 Shader Program.
 */
class Shader(
    private val vertexSource: String,
    private val fragmentSource: String
) {
    var programId: Int = 0
        private set

    private val uniformLocations = mutableMapOf<String, Int>()

    fun compile(): Boolean {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return false

        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES30.glDeleteShader(vertexShader)
            return false
        }

        programId = GLES30.glCreateProgram()
        if (programId == 0) {
            Log.e(TAG, "Failed to create GL program")
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
            return false
        }

        GLES30.glAttachShader(programId, vertexShader)
        GLES30.glAttachShader(programId, fragmentShader)
        GLES30.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(programId)
            Log.e(TAG, "Shader program link failed: $log")
            GLES30.glDetachShader(programId, vertexShader)
            GLES30.glDetachShader(programId, fragmentShader)
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
            GLES30.glDeleteProgram(programId)
            programId = 0
            return false
        }

        // Shaders can be detached and deleted once linked into the program
        GLES30.glDetachShader(programId, vertexShader)
        GLES30.glDetachShader(programId, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        uniformLocations.clear()
        return true
    }

    private fun compileShader(type: Int, source: String): Int {
        val shaderId = GLES30.glCreateShader(type)
        if (shaderId == 0) {
            Log.e(TAG, "Could not create shader of type $type")
            return 0
        }

        GLES30.glShaderSource(shaderId, source)
        GLES30.glCompileShader(shaderId)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shaderId, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shaderId)
            val typeStr = if (type == GLES30.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"
            Log.e(TAG, "$typeStr compilation failed:\n$log")
            GLES30.glDeleteShader(shaderId)
            return 0
        }
        return shaderId
    }

    fun bind() {
        if (programId != 0) {
            GLES30.glUseProgram(programId)
        }
    }

    fun unbind() {
        GLES30.glUseProgram(0)
    }

    fun getUniformLocation(name: String): Int {
        if (programId == 0) return -1
        return uniformLocations.getOrPut(name) {
            GLES30.glGetUniformLocation(programId, name)
        }
    }

    fun setUniform1i(name: String, value: Int) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES30.glUniform1i(location, value)
        }
    }

    fun setUniform1f(name: String, value: Float) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES30.glUniform1f(location, value)
        }
    }

    fun setUniform3f(name: String, x: Float, y: Float, z: Float) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES30.glUniform3f(location, x, y, z)
        }
    }

    fun setUniform4f(name: String, x: Float, y: Float, z: Float, w: Float) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES30.glUniform4f(location, x, y, z, w)
        }
    }

    fun setUniformMatrix4fv(name: String, matrix: FloatArray) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES30.glUniformMatrix4fv(location, 1, false, matrix, 0)
        }
    }

    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
            uniformLocations.clear()
        }
    }

    companion object {
        private const val TAG = "Tessellate.Shader"
    }
}
