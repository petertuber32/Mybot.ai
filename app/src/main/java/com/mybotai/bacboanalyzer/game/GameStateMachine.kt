package com.mybotai.bacboanalyzer.game

import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.vision.DiceRead
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tunables shared by the observation pipeline (pure JVM so tests can
 * exercise the exact production thresholds).
 */
object ObservationConfig {
    /** Confidence >= AUTO_ACCEPT -> committed automatically. */
    const val AUTO_ACCEPT_CONFIDENCE = 0.62

    /** Confidence in [CONFIRM_MIN, AUTO_ACCEPT) -> user confirmation asked. */
    const val CONFIRM_MIN_CONFIDENCE = 0.30

    /** Without motion for this long the machine falls back to WAITING. */
    const val IDLE_TIMEOUT_MS = 9_000L

    /** Two identical dice signatures within this window are the same round. */
    const val SAME_SIGNATURE_WINDOW_MS = 150_000L

    /** Frames are downscaled to this analysis width before recognition. */
    const val ANALYSIS_WIDTH = 360

    /** Minimum interval between processed frames (CPU cap on low-end devices). */
    const val MIN_FRAME_INTERVAL_MS = 280L

    /** Consecutive near-identical frames needed to declare the table stable. */
    const val STABLE_FRAMES_NEEDED = 2

    /** Max consecutive failed reads in READING before falling back to WAITING. */
    const val MAX_READ_ATTEMPTS = 8
}

/** States of the observation state machine (spec section 5). */
enum class GameState { WAITING, ROLLING, READING, VALIDATING, SETTLED, ANALYZING, READY }

/** Events driving the state machine. */
sealed class GameEvent {
    object MotionDetected : GameEvent()
    object FrameStable : GameEvent()
    /** Fully qualified payload type to avoid shadowing with GameEvent.DiceRead. */
    data class DiceRead(val read: com.mybotai.bacboanalyzer.vision.DiceRead) : GameEvent()
    object ReadFailed : GameEvent()
    object ValidationPassed : GameEvent()
    object ValidationAmbiguous : GameEvent()
    object RoundCommitted : GameEvent()
    object AnalysisDone : GameEvent()
    object IdleTimeout : GameEvent()
    data class UserConfirmed(val outcome: Outcome) : GameEvent()
    object UserRejected : GameEvent()
    object Reset : GameEvent()
}

/**
 * Pure, unit-tested state machine for one observed table:
 *
 *   WAITING  -> ROLLING    (motion detected: dice are being thrown)
 *   ROLLING  -> READING    (frames stable: dice stopped)
 *   READING  -> VALIDATING (4 dice values read with enough confidence)
 *   VALIDATING -> SETTLED  (rules validation passed / user confirmed)
 *   SETTLED  -> ANALYZING  (round committed to memory and history)
 *   ANALYZING-> READY      (interpretation refreshed and published)
 *   READY    -> ROLLING    (next round starts)
 *
 * Ambiguous reads never invent a result: the machine parks the candidate in
 * [pendingRead] with [awaitingConfirmation] = true and the UI offers
 * AZUL / VERMELHO / EMPATE buttons; only a [GameEvent.UserConfirmed] or a
 * fresh motion moves it forward.
 */
class GameStateMachine {

    var state: GameState = GameState.WAITING
        private set

    /** Candidate read currently being validated / awaiting confirmation. */
    var pendingRead: DiceRead? = null
        private set

    /** True when [pendingRead] is ambiguous and user confirmation is required. */
    var awaitingConfirmation: Boolean = false
        private set

    /** Outcome chosen by the user for the pending ambiguous read. */
    var confirmedOutcome: Outcome? = null
        private set

    /** A motion was observed since the last commit (guards duplicates). */
    var motionSinceLastCommit: Boolean = false
        private set

    private var readAttempts = 0

    private val listeners = CopyOnWriteArrayList<(GameState) -> Unit>()

    fun addListener(l: (GameState) -> Unit) = listeners.add(l)
    fun removeListener(l: (GameState) -> Unit) = listeners.remove(l)

    fun onEvent(event: GameEvent): GameState {
        when (event) {
            is GameEvent.Reset -> reset()

            is GameEvent.MotionDetected -> {
                motionSinceLastCommit = true
                when (state) {
                    GameState.WAITING, GameState.READY -> toState(GameState.ROLLING)
                    GameState.VALIDATING, GameState.SETTLED, GameState.ANALYZING -> {
                        // A new round started while the previous one was still
                        // being validated/analyzed: the pending read is stale.
                        clearPending()
                        toState(GameState.ROLLING)
                    }
                    GameState.ROLLING, GameState.READING -> clearPending()
                }
            }

            is GameEvent.FrameStable -> {
                if (state == GameState.WAITING) toState(GameState.READING) // initial sync read
                else if (state == GameState.ROLLING) toState(GameState.READING)
            }

            is GameEvent.DiceRead -> {
                if (state != GameState.READING) return state
                val r = event.read
                when {
                    r.knownCount < 4 -> readAttempts++ // treat as failure
                    r.confidence >= ObservationConfig.AUTO_ACCEPT_CONFIDENCE -> {
                        pendingRead = r
                        awaitingConfirmation = false
                        confirmedOutcome = null
                        readAttempts = 0
                        toState(GameState.VALIDATING)
                    }
                    r.confidence >= ObservationConfig.CONFIRM_MIN_CONFIDENCE -> {
                        pendingRead = r
                        awaitingConfirmation = true
                        confirmedOutcome = null
                        readAttempts = 0
                        toState(GameState.VALIDATING)
                    }
                    else -> readAttempts++
                }
            }

            is GameEvent.ReadFailed -> {
                if (state == GameState.READING) {
                    readAttempts++
                    if (readAttempts >= ObservationConfig.MAX_READ_ATTEMPTS) toState(GameState.WAITING)
                }
            }

            is GameEvent.ValidationPassed -> {
                // Only a clean (non-ambiguous) validation settles the round;
                // while the user is confirming an ambiguous read nothing moves.
                if (state == GameState.VALIDATING && !awaitingConfirmation) toState(GameState.SETTLED)
            }

            is GameEvent.ValidationAmbiguous -> {
                if (state == GameState.VALIDATING && !awaitingConfirmation) {
                    awaitingConfirmation = true
                }
            }

            is GameEvent.UserConfirmed -> {
                if (state == GameState.VALIDATING && awaitingConfirmation) {
                    confirmedOutcome = event.outcome
                    awaitingConfirmation = false
                    toState(GameState.SETTLED)
                }
            }

            is GameEvent.UserRejected -> {
                if (state == GameState.VALIDATING) {
                    clearPending()
                    toState(GameState.WAITING)
                }
            }

            is GameEvent.RoundCommitted -> {
                if (state == GameState.SETTLED) {
                    clearPending()
                    motionSinceLastCommit = false
                    toState(GameState.ANALYZING)
                }
            }

            is GameEvent.AnalysisDone -> {
                if (state == GameState.ANALYZING) toState(GameState.READY)
            }

            is GameEvent.IdleTimeout -> {
                when (state) {
                    GameState.ROLLING, GameState.READING,
                    GameState.VALIDATING, GameState.READY -> {
                        clearPending()
                        toState(GameState.WAITING)
                    }
                    else -> Unit
                }
            }
        }
        return state
    }

    private fun clearPending() {
        pendingRead = null
        awaitingConfirmation = false
        confirmedOutcome = null
        readAttempts = 0
    }

    private fun toState(s: GameState) {
        if (s != state) {
            if (s == GameState.ROLLING || s == GameState.WAITING) readAttempts = 0
            state = s
            listeners.forEach { it(s) }
        }
    }

    fun reset() {
        clearPending()
        motionSinceLastCommit = false
        toState(GameState.WAITING)
    }
}
