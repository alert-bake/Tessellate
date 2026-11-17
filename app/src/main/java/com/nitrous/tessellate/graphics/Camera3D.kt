package com.nitrous.tessellate.graphics

import com.nitrous.tessellate.core.ICamera
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

    var targetX: Float = 0f
    var targetY: Float = 0f
    var targetZ: Float = 0f

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

    var eyeX: Float = 0f
        private set
    var eyeY: Float = 0f
        private set
    var eyeZ: Float = 0f
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

    /**
     * Updates First-Person camera transformation placed at the player's eye level.
     */
    @Synchronized
    fun setFirstPersonView(
        posX: Float,
        posY: Float,
        posZ: Float,
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
        forwardX = (sin(yawRad) * cos(pitchRad)).toFloat()
        forwardY = sin(pitchRad).toFloat()
        forwardZ = (-cos(yawRad) * cos(pitchRad)).toFloat()

        targetX = eyeX + forwardX
        targetY = eyeY + forwardY
        targetZ = eyeZ + forwardZ

        Matrix4.lookAt(
            viewMatrix,
            eyeX, eyeY, eyeZ,
            targetX, targetY, targetZ,
            0f, 1f, 0f
        )
        updateViewProjection()
    }

    /**
     * Updates Orbit camera transformation looking at (targetX, targetY, targetZ).
     */
    @Synchronized
    override fun updateView() {
        if (isFirstPerson) {
            setFirstPersonView(eyeX, eyeY, eyeZ, pitch, yaw)
            return
        }

        val pitchRad = Math.toRadians(pitch.toDouble())
        val yawRad = Math.toRadians(yaw.toDouble())

        val offsetX = (distance * cos(pitchRad) * sin(yawRad)).toFloat()
        val offsetY = (distance * sin(pitchRad)).toFloat()
        val offsetZ = (distance * cos(pitchRad) * cos(yawRad)).toFloat()

        eyeX = targetX + offsetX
        eyeY = targetY + offsetY
        eyeZ = targetZ + offsetZ

        val dx = targetX - eyeX
        val dy = targetY - eyeY
        val dz = targetZ - eyeZ
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.0001f)
        forwardX = dx / len
        forwardY = dy / len
        forwardZ = dz / len

        Matrix4.lookAt(
            viewMatrix,
            eyeX, eyeY, eyeZ,
            targetX, targetY, targetZ,
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
    fun setTarget(tx: Float, ty: Float, tz: Float, dist: Float, p: Float = 30f, y: Float = 45f) {
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
            targetX = 0f
            targetY = 0f
            targetZ = 0f
            pitch = 28f
            yaw = 45f
            distance = 4.2f
        }
        updateProjection(aspectRatio)
        updateView()
    }
}
