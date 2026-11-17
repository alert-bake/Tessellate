package com.nitrous.tessellate.graphics

import android.opengl.Matrix

/**
 * 4x4 Matrix math helpers for 3D transformations, projections, and camera view matrices.
 * Operates on raw FloatArray(16) to avoid heap allocation during the render loop.
 */
object Matrix4 {

    fun create(): FloatArray = FloatArray(16).apply {
        setIdentity(this)
    }

    fun setIdentity(m: FloatArray) {
        Matrix.setIdentityM(m, 0)
    }

    fun perspective(m: FloatArray, fovYDeg: Float, aspect: Float, near: Float, far: Float) {
        Matrix.perspectiveM(m, 0, fovYDeg, aspect, near, far)
    }

    fun ortho(m: FloatArray, left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float) {
        Matrix.orthoM(m, 0, left, right, bottom, top, near, far)
    }

    fun lookAt(
        m: FloatArray,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float, upY: Float, upZ: Float
    ) {
        Matrix.setLookAtM(m, 0, eyeX, eyeY, eyeZ, centerX, centerY, centerZ, upX, upY, upZ)
    }

    fun multiply(result: FloatArray, lhs: FloatArray, rhs: FloatArray) {
        Matrix.multiplyMM(result, 0, lhs, 0, rhs, 0)
    }

    fun translate(m: FloatArray, x: Float, y: Float, z: Float) {
        Matrix.translateM(m, 0, x, y, z)
    }

    fun rotate(m: FloatArray, angleDeg: Float, x: Float, y: Float, z: Float) {
        Matrix.rotateM(m, 0, angleDeg, x, y, z)
    }

    fun scale(m: FloatArray, sx: Float, sy: Float, sz: Float) {
        Matrix.scaleM(m, 0, sx, sy, sz)
    }
}
