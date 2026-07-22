package com.example.diaryflip.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class StabilityAnalyzer(
    private val onStatus: (ScanStatus) -> Unit,
    private val onCaptureReady: () -> Unit
) : ImageAnalysis.Analyzer {

    enum class ScanStatus {
        IDLE,
        PAGE_TURNING,
        HOLD_STILL,
        IMPROVING_FOCUS,
        READY_TO_CAPTURE,
        WAITING_FOR_NEXT_PAGE
    }

    private var previousSample: IntArray? = null
    private var stableFrames = 0
    private var lastAttemptMs = 0L
    private var captureArmed = true
    private var firstCapture = true
    private val enabled = AtomicBoolean(false)
    private val captureInFlight = AtomicBoolean(false)

    private val motionThreshold = 10.0
    private val sharpnessThreshold = 15.0
    private val stableFramesRequired = 11
    private val cooldownMs = 1300L

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) {
            previousSample = null
            stableFrames = 0
            captureInFlight.set(false)
            onStatus(ScanStatus.IDLE)
        } else {
            firstCapture = true
            captureArmed = true
            stableFrames = 0
        }
    }

    fun captureCompleted(success: Boolean) {
        captureInFlight.set(false)
        stableFrames = 0
        if (!success) {
            captureArmed = true
        } else {
            firstCapture = false
            captureArmed = false
            onStatus(ScanStatus.WAITING_FOR_NEXT_PAGE)
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled.get()) return

            val sample = sampleLuma(image, 32, 24)
            val previous = previousSample
            previousSample = sample

            if (previous == null) {
                onStatus(ScanStatus.HOLD_STILL)
                return
            }

            val motion = averageDifference(previous, sample)
            val sharpness = edgeEnergy(sample, 32, 24)
            val now = System.currentTimeMillis()

            if (motion > motionThreshold) {
                stableFrames = 0
                if (!firstCapture) captureArmed = true
                onStatus(ScanStatus.PAGE_TURNING)
                return
            }

            // After a successful capture, keep a clear confirmation on screen until
            // the user physically starts turning to the next spread. This prevents
            // ordinary stable frames from immediately replacing "Next page" with
            // another "Hold still" message.
            if (!firstCapture && !captureArmed) {
                stableFrames = 0
                onStatus(ScanStatus.WAITING_FOR_NEXT_PAGE)
                return
            }

            stableFrames++
            if (sharpness < sharpnessThreshold) {
                onStatus(ScanStatus.IMPROVING_FOCUS)
                return
            }

            if (stableFrames < stableFramesRequired) {
                onStatus(ScanStatus.HOLD_STILL)
                return
            }

            if (
                captureArmed &&
                !captureInFlight.get() &&
                now - lastAttemptMs >= cooldownMs &&
                captureInFlight.compareAndSet(false, true)
            ) {
                lastAttemptMs = now
                onStatus(ScanStatus.READY_TO_CAPTURE)
                onCaptureReady()
            }
        } finally {
            image.close()
        }
    }

    private fun sampleLuma(image: ImageProxy, sampleWidth: Int, sampleHeight: Int): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val output = IntArray(sampleWidth * sampleHeight)

        for (sy in 0 until sampleHeight) {
            val y = (sy * (height - 1)) / (sampleHeight - 1)
            for (sx in 0 until sampleWidth) {
                val x = (sx * (width - 1)) / (sampleWidth - 1)
                val index = y * rowStride + x * pixelStride
                output[sy * sampleWidth + sx] = buffer.get(index).toInt() and 0xFF
            }
        }
        return output
    }

    private fun averageDifference(a: IntArray, b: IntArray): Double {
        var total = 0L
        for (i in a.indices) total += abs(a[i] - b[i])
        return total.toDouble() / a.size
    }

    private fun edgeEnergy(values: IntArray, width: Int, height: Int): Double {
        var total = 0L
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                total += abs(values[i] - values[i - 1])
                total += abs(values[i] - values[i - width])
                count += 2
            }
        }
        return if (count == 0) 0.0 else total.toDouble() / count
    }
}
