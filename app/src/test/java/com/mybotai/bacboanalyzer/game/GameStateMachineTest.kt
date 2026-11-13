package com.mybotai.bacboanalyzer.game

import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.vision.DiceRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateMachineTest {

    private fun goodRead(confidence: Double = 0.9) =
        DiceRead(b1 = 3, b2 = 5, r1 = 2, r2 = 4, blueDiceFound = 2, redDiceFound = 2, confidence = confidence)

    private fun ambiguousRead() =
        DiceRead(b1 = 3, b2 = 5, r1 = 2, r2 = 4, blueDiceFound = 2, redDiceFound = 2, confidence = 0.45)

    @Test
    fun fullHappyPath() {
        val m = GameStateMachine()
        assertEquals(GameState.WAITING, m.state)
        assertFalse(m.motionSinceLastCommit)

        assertEquals(GameState.ROLLING, m.onEvent(GameEvent.MotionDetected))
        assertTrue(m.motionSinceLastCommit)

        assertEquals(GameState.READING, m.onEvent(GameEvent.FrameStable))

        assertEquals(GameState.VALIDATING, m.onEvent(GameEvent.DiceRead(goodRead())))
        assertFalse(m.awaitingConfirmation)

        assertEquals(GameState.SETTLED, m.onEvent(GameEvent.ValidationPassed))

        assertEquals(GameState.ANALYZING, m.onEvent(GameEvent.RoundCommitted))
        assertFalse(m.motionSinceLastCommit) // reset on commit

        assertEquals(GameState.READY, m.onEvent(GameEvent.AnalysisDone))

        // Next round re-arms from READY.
        assertEquals(GameState.ROLLING, m.onEvent(GameEvent.MotionDetected))
    }

    @Test
    fun initialSyncReadFromWaiting() {
        val m = GameStateMachine()
        // Table already settled when observation starts: a stable frame goes
        // straight to READING so the visible round can be captured.
        assertEquals(GameState.READING, m.onEvent(GameEvent.FrameStable))
        assertEquals(GameState.VALIDATING, m.onEvent(GameEvent.DiceRead(goodRead())))
    }

    @Test
    fun ambiguousReadRequiresUserConfirmation() {
        val m = GameStateMachine()
        m.onEvent(GameEvent.MotionDetected)
        m.onEvent(GameEvent.FrameStable)
        assertEquals(GameState.VALIDATING, m.onEvent(GameEvent.DiceRead(ambiguousRead())))
        assertTrue(m.awaitingConfirmation)
        assertEquals(ambiguousRead().signature, m.pendingRead?.signature)

        // No commit happens without the user: machine stays in VALIDATING.
        assertEquals(GameState.VALIDATING, m.onEvent(GameEvent.ValidationPassed))

        assertEquals(GameState.SETTLED, m.onEvent(GameEvent.UserConfirmed(Outcome.RED)))
        assertEquals(Outcome.RED, m.confirmedOutcome)
        assertFalse(m.awaitingConfirmation)
    }

    @Test
    fun userRejectedReturnsToWaiting() {
        val m = GameStateMachine()
        m.onEvent(GameEvent.MotionDetected)
        m.onEvent(GameEvent.FrameStable)
        m.onEvent(GameEvent.DiceRead(ambiguousRead()))
        assertEquals(GameState.WAITING, m.onEvent(GameEvent.UserRejected))
        assertNull(m.pendingRead)
        assertFalse(m.awaitingConfirmation)
    }

    @Test
    fun poorReadsAreIgnoredUntilMaxAttempts() {
        val m = GameStateMachine()
        m.onEvent(GameEvent.MotionDetected)
        m.onEvent(GameEvent.FrameStable)
        m.onEvent(GameEvent.DiceRead(goodRead(confidence = 0.1))) // below CONFIRM_MIN
        assertEquals(GameState.READING, m.state) // ignored, retry

        repeat(ObservationConfig.MAX_READ_ATTEMPTS) { m.onEvent(GameEvent.ReadFailed) }
        assertEquals(GameState.WAITING, m.state)
    }

    @Test
    fun idleTimeoutsReturnToWaiting() {
        val m1 = GameStateMachine()
        m1.onEvent(GameEvent.MotionDetected)
        assertEquals(GameState.WAITING, m1.onEvent(GameEvent.IdleTimeout))

        val m2 = GameStateMachine()
        m2.onEvent(GameEvent.MotionDetected)
        m2.onEvent(GameEvent.FrameStable)
        assertEquals(GameState.WAITING, m2.onEvent(GameEvent.IdleTimeout))
        assertNull(m2.pendingRead)

        val m3 = GameStateMachine()
        m3.onEvent(GameEvent.MotionDetected)
        m3.onEvent(GameEvent.FrameStable)
        m3.onEvent(GameEvent.DiceRead(goodRead()))
        m3.onEvent(GameEvent.ValidationPassed)
        m3.onEvent(GameEvent.RoundCommitted)
        m3.onEvent(GameEvent.AnalysisDone) // READY
        assertEquals(GameState.WAITING, m3.onEvent(GameEvent.IdleTimeout))
    }

    @Test
    fun motionDuringConfirmationClearsPendingCandidate() {
        val m = GameStateMachine()
        m.onEvent(GameEvent.MotionDetected)
        m.onEvent(GameEvent.FrameStable)
        m.onEvent(GameEvent.DiceRead(ambiguousRead()))
        assertTrue(m.awaitingConfirmation)

        // New round starts while the user is deciding: candidate is stale.
        assertEquals(GameState.ROLLING, m.onEvent(GameEvent.MotionDetected))
        assertNull(m.pendingRead)
        assertFalse(m.awaitingConfirmation)
    }

    @Test
    fun motionDuringAnalysisMovesToRolling() {
        val m = GameStateMachine()
        m.onEvent(GameEvent.MotionDetected)
        m.onEvent(GameEvent.FrameStable)
        m.onEvent(GameEvent.DiceRead(goodRead()))
        m.onEvent(GameEvent.ValidationPassed)
        m.onEvent(GameEvent.RoundCommitted) // ANALYZING
        assertEquals(GameState.ROLLING, m.onEvent(GameEvent.MotionDetected))
    }

    @Test
    fun resetReturnsEverythingToWaiting() {
        val m = GameStateMachine()
        m.onEvent(GameEvent.MotionDetected)
        m.onEvent(GameEvent.FrameStable)
        m.onEvent(GameEvent.DiceRead(goodRead()))
        m.reset()
        assertEquals(GameState.WAITING, m.state)
        assertNull(m.pendingRead)
        assertFalse(m.motionSinceLastCommit)
    }

    @Test
    fun stateChangeListenersAreNotified() {
        val m = GameStateMachine()
        val seen = mutableListOf<GameState>()
        m.addListener { seen.add(it) }
        m.onEvent(GameEvent.MotionDetected)
        m.onEvent(GameEvent.FrameStable)
        m.onEvent(GameEvent.IdleTimeout)
        assertEquals(listOf(GameState.ROLLING, GameState.READING, GameState.WAITING), seen)
    }
}
