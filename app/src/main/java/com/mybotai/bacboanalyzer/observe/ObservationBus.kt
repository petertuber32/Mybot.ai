package com.mybotai.bacboanalyzer.observe

import com.mybotai.bacboanalyzer.game.GameState
import com.mybotai.bacboanalyzer.game.InterpretationEngine
import com.mybotai.bacboanalyzer.game.ObservedRound
import com.mybotai.bacboanalyzer.vision.DiceRead
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Snapshot published to every UI surface (floating panel, analyzer card).
 * Pure JVM — deterministic and unit-testable; the Android service is the
 * only writer, UI components only read.
 */
data class ObservationUiState(
    val observing: Boolean = false,
    val state: GameState = GameState.WAITING,
    val lastRead: DiceRead? = null,
    val lastRound: ObservedRound? = null,
    val interpretationSummary: String? = null,
    val estimates: List<InterpretationEngine.BacBoEstimate> = emptyList(),
    val awaitingConfirmation: Boolean = false,
    val readConfidence: Double? = null,
    val message: String? = null
) {
    /** Estimates in the Azul/Vermelho/Empate display order. */
    fun estimateFor(outcome: com.mybotai.bacboanalyzer.models.Outcome): InterpretationEngine.BacBoEstimate? =
        estimates.firstOrNull { it.outcome == outcome }
}

/**
 * Minimal process-local event bus. Listeners receive the latest snapshot
 * immediately upon subscription (so UI never renders stale data) and then
 * every update. Calls happen on the posting thread — Android listeners
 * marshal to the main thread themselves.
 */
object ObservationBus {

    private val listeners = CopyOnWriteArrayList<(ObservationUiState) -> Unit>()

    @Volatile
    var current: ObservationUiState = ObservationUiState()
        private set

    fun subscribe(listener: (ObservationUiState) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun unsubscribe(listener: (ObservationUiState) -> Unit) {
        listeners.remove(listener)
    }

    /** Atomically updates the snapshot and notifies all listeners. */
    fun post(update: (ObservationUiState) -> ObservationUiState) {
        current = update(current)
        listeners.forEach { l ->
            runCatching { l(current) }
        }
    }

    /** Clears everything (called when the observation service stops). */
    fun reset() {
        current = ObservationUiState()
        listeners.forEach { l ->
            runCatching { l(current) }
        }
    }
}
