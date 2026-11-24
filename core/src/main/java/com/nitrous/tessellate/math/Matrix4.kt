package com.nitrous.tessellate.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure-Kotlin 4x4 column-major matrix math helpers for 3D transformations, projections, and camera matrices.
 * Operates directly on raw FloatArray(16) to match OpenGL column-major conventions with zero Android dependencies.
 */
object Matrix4 {

    private val multiplyScratch: ThreadLocal<FloatArray> = ThreadLocal.withInitial { FloatArray(16) }

    fun create(): FloatArray = FloatArray(16).apply {
        setIdentity(this)
    }

    fun setIdentity(m: FloatArray) {
        m.fill(0f, 0, 16)
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
    }

    fun perspective(m: FloatArray, fovYDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1.0f / tan(fovYDeg * (PI / 360.0)).toFloat()
        val rangeReciprocal = 1.0f / (near - far)
        m.fill(0f, 0, 16)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) * rangeReciprocal
        m[11] = -1.0f
        m[14] = 2.0f * far * near * rangeReciprocal
    }

    fun ortho(m: FloatArray, left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float) {
        val rWidth = 1.0f / (right - left)
        val rHeight = 1.0f / (top - bottom)
        val rDepth = 1.0f / (far - near)
        m.fill(0f, 0, 16)
        m[0] = 2.0f * rWidth
        m[5] = 2.0f * rHeight
        m[10] = -2.0f * rDepth
        m[12] = -(right + left) * rWidth
        m[13] = -(top + bottom) * rHeight
        m[14] = -(far + near) * rDepth
        m[15] = 1.0f
    }

    fun lookAt(
        m: FloatArray,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float, upY: Float, upZ: Float
    ) {
        var fx = centerX - eyeX
        var fy = centerY - eyeY
        var fz = centerZ - eyeZ
        val rlf = 1.0f / sqrt(fx * fx + fy * fy + fz * fz)
        fx *= rlf
        fy *= rlf
        fz *= rlf

        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        val rls = 1.0f / sqrt(sx * sx + sy * sy + sz * sz)
        sx *= rls
        sy *= rls
        sz *= rls

        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        m[0] = sx
        m[1] = ux
        m[2] = -fx
        m[3] = 0f

        m[4] = sy
        m[5] = uy
        m[6] = -fy
        m[7] = 0f

        m[8] = sz
        m[9] = uz
        m[10] = -fz
        m[11] = 0f

        m[12] = -(sx * eyeX + sy * eyeY + sz * eyeZ)
        m[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        m[14] = fx * eyeX + fy * eyeY + fz * eyeZ
        m[15] = 1.0f
    }

    fun multiply(result: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        val out = if (result === lhs || result === rhs) multiplyScratch.get() else result
        for (col in 0 until 4) {
            val rhsCol = col * 4
            val r0 = rhs[rhsCol]
            val r1 = rhs[rhsCol + 1]
            val r2 = rhs[rhsCol + 2]
            val r3 = rhs[rhsCol + 3]
            for (row in 0 until 4) {
                out[rhsCol + row] =
                    lhs[row] * r0 +
                    lhs[4 + row] * r1 +
                    lhs[8 + row] * r2 +
                    lhs[12 + row] * r3
            }
        }
        if (out !== result) {
            System.arraycopy(out, 0, result, 0, 16)
        }
    }

    fun translate(m: FloatArray, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) {
            m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z
        }
    }

    fun rotate(m: FloatArray, angleDeg: Float, x: Float, y: Float, z: Float) {
        var ax = x
        var ay = y
        var az = z
        val lenSq = ax * ax + ay * ay + az * az
        if (lenSq == 0f) return
        if (lenSq != 1f) {
            val invLen = 1.0f / sqrt(lenSq)
            ax *= invLen
            ay *= invLen
            az *= invLen
        }

        val rad = angleDeg * (PI / 180.0)
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        val nc = 1.0f - c

        val r00 = ax * ax * nc + c
        val r01 = ax * ay * nc + az * s
        val r02 = ax * az * nc - ay * s

        val r10 = ax * ay * nc - az * s
        val r11 = ay * ay * nc + c
        val r12 = ay * az * nc + ax * s

        val r20 = ax * az * nc + ay * s
        val r21 = ay * az * nc - ax * s
        val r22 = az * az * nc + c

        for (row in 0 until 4) {
            val m0 = m[row]
            val m1 = m[4 + row]
            val m2 = m[8 + row]
            m[row] = m0 * r00 + m1 * r01 + m2 * r02
            m[4 + row] = m0 * r10 + m1 * r11 + m2 * r12
            m[8 + row] = m0 * r20 + m1 * r21 + m2 * r22
        }
    }

    fun scale(m: FloatArray, sx: Float, sy: Float, sz: Float) {
        for (i in 0 until 4) {
            m[i] *= sx
            m[4 + i] *= sy
            m[8 + i] *= sz
        }
    }
}
