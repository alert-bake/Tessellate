package com.nitrous.tessellate.graphics

import com.nitrous.tessellate.core.ICamera
import com.nitrous.tessellate.math.Matrix4
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D Camera supporting both First-Person Player View and Orbit Diagnostic View.
 */
class Camera3D(
    override var fovDegrees: Float = 65f,
    override var nearPlane: Float = 0.1f,
    override var farPlane: Float = 250f
) : ICamera {

    override val viewMatrix: FloatArray = Matrix4.create()
    override val projectionMatrix: FloatArray = Matrix4.create()
    override val viewProjectionMatrix: FloatArray = Matrix4.create()

    var targetX: Double = 0.0
    var targetY: Double = 0.0
    var targetZ: Double = 0.0

    var minDistance: Float = 1.2f
    var maxDistance: Float = 120f

    override var pitch: Float = 0f
        set(value) {
            field = value.coerceIn(-89f, 89f)
        }

    override var yaw: Float = 0f
    override var distance: Float = 4.2f
        set(value) {
            field = value.coerceIn(minDistance, maxDistance)
        }

    var eyeX: Double = 0.0
        private set
    var eyeY: Double = 0.0
        private set
    var eyeZ: Double = 0.0
        private set

    var forwardX: Float = 0f
        private set
    var forwardY: Float = 0f
        private set
    var forwardZ: Float = -1f
        private set

    var isFirstPerson: Boolean = true

    @Volatile
    var viewBobbingEnabled: Boolean = true

    private var aspectRatio: Float = 16f / 9f

    init {
        updateView()
    }

    @Synchronized
    override fun updateProjection(aspectRatio: Float) {
        val safeAspect = if (aspectRatio.isFinite() && aspectRatio > 0.01f) aspectRatio else 16f / 9f
        this.aspectRatio = safeAspect
        Matrix4.perspective(projectionMatrix, fovDegrees, safeAspect, nearPlane, farPlane)
        updateViewProjection()
    }

    @Synchronized
    fun updateProjection() {
        updateProjection(aspectRatio)
    }

    /**
     * Thread-safe snapshot copy of [viewProjectionMatrix] so render threads never read torn matrices
     * while the UI thread orbits or zooms the camera.
     */
    @Synchronized
    fun copyViewProjectionMatrix(dest: FloatArray) {
        if (dest.size >= 16) {
            System.arraycopy(viewProjectionMatrix, 0, dest, 0, 16)
        }
    }

    @Synchronized
    fun copyProjectionMatrix(dest: FloatArray) {
        if (dest.size >= 16) {
            System.arraycopy(projectionMatrix, 0, dest, 0, 16)
        }
    }

    /**
     * Updates First-Person camera transformation placed at the player's eye level.
     * The GPU [viewMatrix] is always camera-relative (eye fixed at `(0, 0, 0)`), preserving full
     * 32-bit float precision regardless of world coordinate magnitude.
     */
    @Synchronized
    fun setFirstPersonView(
        posX: Double,
        posY: Double,
        posZ: Double,
        playerPitch: Float,
        playerYaw: Float
    ) {
        isFirstPerson = true
        this.pitch = playerPitch
        this.yaw = playerYaw
        this.eyeX = posX
        this.eyeY = posY
        this.eyeZ = posZ

        val pitchRad = Math.toRadians(pitch.toDouble())
        val yawRad = Math.toRadians(yaw.toDouble())

        // Calculate directional forward vector
        val fx = sin(yawRad) * cos(pitchRad)
        val fy = sin(pitchRad)
        val fz = -cos(yawRad) * cos(pitchRad)

        forwardX = fx.toFloat()
        forwardY = fy.toFloat()
        forwardZ = fz.toFloat()

        targetX = eyeX + fx
        targetY = eyeY + fy
        targetZ = eyeZ + fz

        // Camera-relative view matrix: camera is always at (0, 0, 0) in shader space
        Matrix4.lookAt(
            viewMatrix,
            0f, 0f, 0f,
            forwardX, forwardY, forwardZ,
            0f, 1f, 0f
        )
        updateViewProjection()
    }

    /**
     * Updates Orbit camera transformation looking at (targetX, targetY, targetZ).
     * The GPU [viewMatrix] stays camera-relative at `(0, 0, 0)`.
     */
    @Synchronized
    override fun updateView() {
        if (isFirstPerson) {
            setFirstPersonView(eyeX, eyeY, eyeZ, pitch, yaw)
            return
        }

        val pitchRad = Math.toRadians(pitch.toDouble())
        val yawRad = Math.toRadians(yaw.toDouble())
        val dist = distance.toDouble()

        val offsetX = dist * cos(pitchRad) * sin(yawRad)
        val offsetY = dist * sin(pitchRad)
        val offsetZ = dist * cos(pitchRad) * cos(yawRad)

        eyeX = targetX + offsetX
        eyeY = targetY + offsetY
        eyeZ = targetZ + offsetZ

        val dx = targetX - eyeX
        val dy = targetY - eyeY
        val dz = targetZ - eyeZ
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.0001)
        forwardX = (dx / len).toFloat()
        forwardY = (dy / len).toFloat()
        forwardZ = (dz / len).toFloat()

        Matrix4.lookAt(
            viewMatrix,
            0f, 0f, 0f,
            forwardX, forwardY, forwardZ,
            0f, 1f, 0f
        )
        updateViewProjection()
    }

    private fun updateViewProjection() {
        Matrix4.multiply(viewProjectionMatrix, projectionMatrix, viewMatrix)
    }

    @Synchronized
    override fun orbit(deltaPitch: Float, deltaYaw: Float) {
        pitch += deltaPitch
        yaw = (yaw + deltaYaw) % 360f
        updateView()
    }

    @Synchronized
    override fun zoom(deltaDistance: Float) {
        distance += deltaDistance
        updateView()
    }

    @Synchronized
    fun setTarget(tx: Double, ty: Double, tz: Double, dist: Float, p: Float = 30f, y: Float = 45f) {
        isFirstPerson = false
        targetX = tx
        targetY = ty
        targetZ = tz
        distance = dist
        pitch = p
        yaw = y
        updateView()
    }

    @Synchronized
    override fun reset() {
        if (isFirstPerson) {
            pitch = 0f
            yaw = 0f
        } else {
            targetX = 0.0
            targetY = 0.0
            targetZ = 0.0
            pitch = 28f
            yaw = 45f
            distance = 4.2f
        }
        updateProjection(aspectRatio)
        updateView()
    }
}
