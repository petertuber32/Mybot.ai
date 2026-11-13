package com.mybotai.bacboanalyzer.game

import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.models.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationEngineTest {

    private fun round(outcome: Outcome, ts: Long) = Round(timestamp = ts, outcome = outcome, source = Source.VISION)

    @Test
    fun estimatesWithNoRoundsEqualTheoreticalDistribution() {
        val est = InterpretationEngine.estimates(emptyList())
        val blue = est.first { it.outcome == Outcome.BLUE }
        val red = est.first { it.outcome == Outcome.RED }
        val tie = est.first { it.outcome == Outcome.TIE }
        assertEquals(BacBoRules.THEORETICAL_BLUE, blue.p, 1e-12)
        assertEquals(BacBoRules.THEORETICAL_RED, red.p, 1e-12)
        assertEquals(BacBoRules.THEORETICAL_TIE, tie.p, 1e-12)
        assertEquals(0.0, blue.ciLow, 1e-12)
        assertEquals(1.0, blue.ciHigh, 1e-12)
    }

    @Test
    fun estimatesBlendHistoryWithPrior() {
        val rounds = List(10) { round(Outcome.BLUE, it.toLong()) }
        val est = InterpretationEngine.estimates(rounds)
        val blue = est.first { it.outcome == Outcome.BLUE }
        val w = InterpretationEngine.PRIOR_WEIGHT
        val expected = (10 + w * BacBoRules.THEORETICAL_BLUE) / (10 + w)
        assertEquals(expected, blue.p, 1e-12)
        assertEquals(10, blue.count)
        // Wilson interval reflects the raw 10/10 observations.
        assertTrue(blue.ciHigh < 1.0)
    }

    @Test
    fun alternationDetection() {
        val alternating = (0 until 40).map {
            round(if (it % 2 == 0) Outcome.BLUE else Outcome.RED, it.toLong())
        }
        val (idx, reliable) = InterpretationEngine.alternation(alternating)!!
        assertEquals(1.0, idx, 1e-12)
        assertTrue(reliable)

        val allBlue = List(40) { round(Outcome.BLUE, it.toLong()) }
        val (idx2, reliable2) = InterpretationEngine.alternation(allBlue)!!
        assertEquals(0.0, idx2, 1e-12)
        assertTrue(reliable2)

        // Small sample is never called reliable.
        val short = (0 until 8).map { round(if (it % 2 == 0) Outcome.BLUE else Outcome.RED, it.toLong()) }
        val (_, reliable3) = InterpretationEngine.alternation(short)!!
        assertFalse(reliable3)
    }

    @Test
    fun trendDetection() {
        val reds = List(10) { round(Outcome.RED, it.toLong()) }
        val blues = List(10) { round(Outcome.BLUE, 100L + it) }
        val (shift, significant) = InterpretationEngine.trend(reds + blues)!!
        assertEquals(1.0, shift, 1e-12)
        assertTrue(significant)

        val mixed = List(20) { round(if (it % 2 == 0) Outcome.BLUE else Outcome.RED, it.toLong()) }
        val (shift2, significant2) = InterpretationEngine.trend(mixed)!!
        assertEquals(0.0, shift2, 1e-12)
        assertFalse(significant2)
    }

    @Test
    fun insufficientDataFlagged() {
        val rounds = List(5) { round(Outcome.BLUE, it.toLong()) }
        val interp = InterpretationEngine.interpret(rounds)
        assertTrue(interp.insufficient)
        assertEquals(0.15, interp.confidence, 1e-12)
    }

    @Test
    fun summaryAlwaysCarriesTheDisclaimer() {
        val empty = InterpretationEngine.interpret(emptyList())
        assertTrue(empty.summary.contains("não preveem"))

        val rounds = List(50) { round(if (it % 3 == 0) Outcome.BLUE else Outcome.RED, it.toLong()) }
        val interp = InterpretationEngine.interpret(rounds)
        assertTrue(interp.summary.contains("não preveem"))
        assertTrue(interp.details.any { it.contains("NÃO determina") || it.contains("independentes") || it.contains("NÃO torna") })
    }

    /**
     * Backtest without future information: the estimate for a prefix of the
     * history depends only on that prefix — appending future rounds never
     * changes past estimates (evaluate exactly as the live system would).
     */
    @Test
    fun backtestUsesOnlyPastRounds() {
        val seq = listOf(
            Outcome.BLUE, Outcome.RED, Outcome.RED, Outcome.TIE, Outcome.BLUE,
            Outcome.BLUE, Outcome.RED, Outcome.TIE, Outcome.TIE, Outcome.BLUE,
            Outcome.RED, Outcome.BLUE, Outcome.BLUE, Outcome.RED, Outcome.RED
        ).mapIndexed { i, o -> round(o, i.toLong()) }

        for (i in 1..seq.size) {
            val prefix = seq.take(i)
            val est = InterpretationEngine.estimates(prefix)
            val k = prefix.count { it.outcome == Outcome.BLUE }
            val expected = (k + InterpretationEngine.PRIOR_WEIGHT * BacBoRules.THEORETICAL_BLUE) / (i + InterpretationEngine.PRIOR_WEIGHT)
            assertEquals("prefix $i", expected, est.first { it.outcome == Outcome.BLUE }.p, 1e-12)
        }
    }

    @Test
    fun diceDetailsAreIncludedWhenAvailable() {
        val dice = listOf(
            ObservedRound(1, 3, 5, 2, 4, 8, 6, Outcome.BLUE, 1L, 0.9, Source.VISION),
            ObservedRound(2, 1, 1, 3, 4, 2, 7, Outcome.RED, 2L, 0.9, Source.VISION)
        )
        val interp = InterpretationEngine.interpret(dice.map { Round(it.recognizedAt, it.outcome, it.source) }, dice)
        val diceLine = interp.details.first { it.contains("soma média") }
        assertNotNull(diceLine)
        assertTrue(diceLine.contains("5.0")) // mean blue sum (8+2)/2
        assertTrue(diceLine.contains("6.5")) // mean red sum (6+7)/2
    }

    @Test
    fun streakLongSequenceFlaggedButNotAsPrediction() {
        val rounds = List(6) { round(Outcome.BLUE, it.toLong()) }
        val interp = InterpretationEngine.interpret(rounds)
        assertNotNull(interp.streak)
        assertEquals(6, interp.streak!!.length)
        assertTrue(interp.details.any { it.contains("NÃO torna") })
    }
}
