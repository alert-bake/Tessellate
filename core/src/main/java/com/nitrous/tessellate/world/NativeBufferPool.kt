package com.nitrous.tessellate.world

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.logging.Logger

/**
 * Platform-independent Native DirectByteBuffer Pool + continuous 1-buffer-per-tick Flood GC
 * living in `:core`.
 *
 * Lifecycle ownership (TDD-022.1):
 * - `:core` manages pre-allocation of [capacity] (50) [DirectByteBuffer] staging buffers, borrowing,
 *   raw array staging, in-flight frame tracking, and 1-per-tick reclamation when
 *   `uploadedFrameIndex < currentFrameIndex`.
 * - `:app` only binds the returned buffer to OpenGL ES (`glBufferData`) and reports completion via
 *   [markUploaded].
 * - When disabled or exhausted, returns `null` (invoking [onWarning] / JUL logger) so callers
 *   cleanly fall back to standard JVM heap staging buffers.
 */
class NativeBufferPool(
    val capacity: Int = DEFAULT_POOL_CAPACITY,
    val defaultBufferBytes: Int = DEFAULT_BUFFER_BYTES,
    private val onWarning: ((String) -> Unit)? = null
) {
    private data class InFlightEntry(
        val buffer: ByteBuffer,
        val uploadedFrameIndex: Long
    )

    private val lock = Any()
    private val available = ArrayDeque<ByteBuffer>(capacity)
    private val inFlight = ArrayDeque<InFlightEntry>(capacity)

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var reclaimedCount: Long = 0L
        private set

    @Volatile
    var fallbackCount: Long = 0L
        private set

    @Volatile
    var borrowCount: Long = 0L
        private set

    @Volatile
    var exhaustedWarningCount: Long = 0L
        private set

    val availableCount: Int
        get() = synchronized(lock) { available.size }

    val inFlightCount: Int
        get() = synchronized(lock) { inFlight.size }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            isEnabled = enabled
            if (enabled && !isInitialized) {
                initializePoolLocked()
            }
        }
    }

    fun initializePool() {
        synchronized(lock) {
            initializePoolLocked()
        }
    }

    private fun initializePoolLocked() {
        if (isInitialized) return
        available.clear()
        inFlight.clear()
        repeat(capacity) {
            available.addLast(allocateDirect(defaultBufferBytes))
        }
        isInitialized = true
    }

    /**
     * Borrows a [DirectByteBuffer] of at least [requiredBytes] when pooling is enabled.
     * Returns `null` if disabled or exhausted (logging a warning on exhaustion).
     */
    fun borrowBuffer(requiredBytes: Int): ByteBuffer? {
        synchronized(lock) {
            if (!isEnabled) return null
            if (!isInitialized) {
                initializePoolLocked()
            }
            if (available.isEmpty()) {
                exhaustedWarningCount++
                fallbackCount++
                val msg =
                    "NativeBufferPool exhausted (0/$capacity available, ${inFlight.size} in flight); " +
                        "falling back to standard JVM heap buffer."
                LOGGER.warning(msg)
                onWarning?.invoke(msg)
                return null
            }

            var buf = available.removeFirst()
            if (buf.capacity() < requiredBytes) {
                val nextSize = maxOf(requiredBytes, buf.capacity() * 2)
                buf = allocateDirect(nextSize)
            }
            buf.clear()
            buf.limit(requiredBytes)
            borrowCount++
            return buf
        }
    }

    /**
     * Borrows a pooled [DirectByteBuffer] and copies [floatLength] raw elements from [data] into it.
     * Returns `null` when disabled or exhausted.
     */
    fun borrowAndStageFloats(data: FloatArray, floatLength: Int): ByteBuffer? {
        val bytes = floatLength * 4
        val buf = borrowBuffer(bytes) ?: return null
        val view = buf.asFloatBuffer()
        view.clear()
        view.put(data, 0, floatLength)
        view.flip()
        return buf
    }

    /**
     * Borrows a pooled [DirectByteBuffer] and copies [shortLength] raw elements from [data] into it.
     * Returns `null` when disabled or exhausted.
     */
    fun borrowAndStageShorts(data: ShortArray, shortLength: Int): ByteBuffer? {
        val bytes = shortLength * 2
        val buf = borrowBuffer(bytes) ?: return null
        val view = buf.asShortBuffer()
        view.clear()
        view.put(data, 0, shortLength)
        view.flip()
        return buf
    }

    /**
     * Marks a borrowed buffer as uploaded to the GPU on [uploadedFrameIndex] so it enters
     * the Flood GC queue for safe future reclamation.
     */
    fun markUploaded(buffer: ByteBuffer, uploadedFrameIndex: Long) {
        synchronized(lock) {
            if (!isEnabled || !isInitialized) {
                return
            }
            inFlight.addLast(InFlightEntry(buffer, uploadedFrameIndex))
        }
    }

    /**
     * Continuous 1-buffer-per-tick Flood GC.
     * Inspects only the oldest in-flight buffer; if `uploadedFrameIndex < currentFrameIndex`,
     * reclaims **exactly 1** buffer back into the available pool. Never batch-sweeps.
     */
    fun floodGcTick(currentFrameIndex: Long): Boolean {
        synchronized(lock) {
            val head = inFlight.firstOrNull() ?: return false
            if (head.uploadedFrameIndex >= currentFrameIndex) {
                return false
            }
            inFlight.removeFirst()
            head.buffer.clear()
            if (available.size < capacity) {
                available.addLast(head.buffer)
            }
            reclaimedCount++
            return true
        }
    }

    fun recordStandardFallback() {
        fallbackCount++
    }

    fun release() {
        synchronized(lock) {
            available.clear()
            inFlight.clear()
            isInitialized = false
        }
    }

    private fun allocateDirect(sizeBytes: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.nativeOrder())
    }

    companion object {
        private val LOGGER = Logger.getLogger("Tessellate.NativePool")
        const val DEFAULT_POOL_CAPACITY = 50
        const val DEFAULT_BUFFER_BYTES = 131_072 // 128 KB per pre-allocated direct staging buffer
    }
}
