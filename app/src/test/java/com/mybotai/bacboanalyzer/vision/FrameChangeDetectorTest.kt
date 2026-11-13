package com.mybotai.bacboanalyzer.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChangeDetectorTest {

    private val w = 240
    private val h = 432

    private fun diceFrame(offsetX: Int): Frame {
        val b = TestFrames.Builder(w, h)
        // Same layout shifted horizontally by offsetX.
        b.die(30 + offsetX, 150, 40, blue = true, value = 3)
        b.die(90 + offsetX, 150, 40, blue = true, value = 5)
        b.die(150 + offsetX, 150, 40, blue = false, value = 2)
        b.die(210 - offsetX, 150, 40, blue = false, value = 6)
        return b.build()
    }

    @Test
    fun identicalFramesHaveZeroChange() {
        val f = diceFrame(0)
        assertEquals(0.0, FrameChangeDetector.changeRatio(f, f), 1e-12)
    }

    @Test
    fun movedDiceExceedMotionThreshold() {
        val ratio = FrameChangeDetector.changeRatio(diceFrame(0), diceFrame(24))
        assertTrue("expected motion, ratio=$ratio", ratio >= 0.012)
    }

    @Test
    fun smallShiftsStayTransitional() {
        val ratio = FrameChangeDetector.changeRatio(diceFrame(0), diceFrame(2))
        assertTrue("expected transitional, ratio=$ratio", ratio > 0.004 && ratio < 0.012)
    }

    @Test
    fun gateEmitsMotionThenStableAfterQuietFrames() {
        val gate = SmartCaptureGate(minIntervalMs = 280, stableFramesNeeded = 2)
        var t = 1_000L
        assertEquals(FrameVerdict.MOTION, gate.evaluate(0.10, t))

        // Cooldown swallows immediate evaluations.
        assertEquals(FrameVerdict.IDLE, gate.evaluate(0.10, t + 100))

        t += 300
        assertEquals(FrameVerdict.IDLE, gate.evaluate(0.001, t)) // stable #1
        t += 300
        assertEquals(FrameVerdict.STABLE, gate.evaluate(0.001, t)) // stable #2
    }

    @Test
    fun gateCooldownSuppressesBursts() {
        val gate = SmartCaptureGate(minIntervalMs = 280)
        val t = 500L
        assertEquals(FrameVerdict.MOTION, gate.evaluate(0.5, t))
        assertEquals(FrameVerdict.IDLE, gate.evaluate(0.5, t + 50))
        assertEquals(FrameVerdict.IDLE, gate.evaluate(0.5, t + 200))
        assertEquals(FrameVerdict.MOTION, gate.evaluate(0.5, t + 300))
    }

    @Test
    fun gateClassifiesTransitionalAsIdleAndResetsStableCount() {
        val gate = SmartCaptureGate(minIntervalMs = 0, stableFramesNeeded = 2)
        assertEquals(FrameVerdict.IDLE, gate.evaluate(0.001, 1L)) // stable #1
        assertEquals(FrameVerdict.IDLE, gate.evaluate(0.008, 2L)) // transitional resets counter
        assertEquals(FrameVerdict.IDLE, gate.evaluate(0.001, 3L)) // stable #1 again
        assertEquals(FrameVerdict.STABLE, gate.evaluate(0.001, 4L)) // stable #2
    }
}
