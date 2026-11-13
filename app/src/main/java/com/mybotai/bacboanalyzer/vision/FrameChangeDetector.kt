package com.mybotai.bacboanalyzer.vision

/**
 * Event-driven capture: instead of analyzing every screenshot frame the
 * service compares successive downscaled frames and only runs recognition
 * when the table actually changed (motion) or became stable (dice stopped).
 * This keeps CPU usage low on entry-level devices and avoids burning
 * battery while the game is idle.
 */
object FrameChangeDetector {

    /**
     * Fraction of sampled pixels whose color differs more than [tolerance]
     * per channel between two frames. Sampling every [sampleStep]-th pixel
     * keeps the cost proportional to (w/step)*(h/step).
     */
    fun changeRatio(prev: Frame, next: Frame, sampleStep: Int = 2, tolerance: Int = 26): Double {
        require(prev.width == next.width && prev.height == next.height) { "frame size mismatch" }
        var sampled = 0
        var changed = 0
        var y = 0
        while (y < prev.height) {
            var x = 0
            val rowPrev = y * prev.width
            val rowNext = y * next.width
            while (x < prev.width) {
                val a = prev.pixels[rowPrev + x]
                val b = next.pixels[rowNext + x]
                val d = maxOf(
                    kotlin.math.abs(Colors.r(a) - Colors.r(b)),
                    kotlin.math.abs(Colors.g(a) - Colors.g(b)),
                    kotlin.math.abs(Colors.b(a) - Colors.b(b))
                )
                sampled++
                if (d > tolerance) changed++
                x += sampleStep
            }
            y += sampleStep
        }
        return if (sampled == 0) 0.0 else changed.toDouble() / sampled
    }
}

enum class FrameVerdict { IDLE, MOTION, STABLE }

/**
 * Stateful gate with hysteresis: MOTION needs a big change, STABLE needs
 * [stableFramesNeeded] consecutive near-identical frames, and everything in
 * between is IDLE (frame skipped). A minimum interval between processed
 * frames caps the analysis rate.
 */
class SmartCaptureGate(
    private val motionThreshold: Double = 0.012,
    private val stableThreshold: Double = 0.004,
    private val minIntervalMs: Long = 280,
    private val stableFramesNeeded: Int = 2
) {
    private var lastProcessedMs = Long.MIN_VALUE
    private var stableCount = 0

    fun evaluate(change: Double, nowMs: Long): FrameVerdict {
        if (lastProcessedMs != Long.MIN_VALUE && nowMs - lastProcessedMs < minIntervalMs) {
            return FrameVerdict.IDLE
        }
        return when {
            change >= motionThreshold -> {
                stableCount = 0
                lastProcessedMs = nowMs
                FrameVerdict.MOTION
            }
            change <= stableThreshold -> {
                stableCount++
                if (stableCount >= stableFramesNeeded) {
                    stableCount = 0
                    lastProcessedMs = nowMs
                    FrameVerdict.STABLE
                } else {
                    FrameVerdict.IDLE
                }
            }
            else -> {
                // Transitional animation frame: do not reset the stable
                // counter fully (dice decelerate gradually) but do not
                // process either.
                stableCount = 0
                FrameVerdict.IDLE
            }
        }
    }
}
