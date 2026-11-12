package com.mybotai.bacboanalyzer.stats

import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.models.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsEngineTest {

    private fun rounds(vararg os: Outcome): List<Round> =
        os.mapIndexed { i, o -> Round(i.toLong(), o, Source.MANUAL) }

    @Test
    fun emptyDataYieldsUniformEstimatesWithInsufficientWarning() {
        val a = StatsEngine.analyze(emptyList())
        assertEquals(0, a.n)
        assertTrue(a.insufficient)
        assertEquals(Quality.INSUFFICIENT, a.quality)
        val blue = a.global.first { it.outcome == Outcome.BLUE }
        assertEquals(1.0 / 3.0, blue.p, 1e-9)
        assertEquals(0.0, blue.ciLow, 1e-9)
        assertEquals(1.0, blue.ciHigh, 1e-9)
        assertFalse(a.fairCompatible)
    }

    @Test
    fun laplaceSmoothingMatchesClosedForm() {
        val data = rounds(
            Outcome.BLUE, Outcome.BLUE, Outcome.BLUE, Outcome.BLUE, Outcome.BLUE,
            Outcome.BLUE, Outcome.BLUE, Outcome.BLUE, Outcome.BLUE, Outcome.BLUE,
            Outcome.RED, Outcome.RED, Outcome.RED, Outcome.RED, Outcome.RED,
            Outcome.TIE, Outcome.TIE, Outcome.TIE, Outcome.TIE, Outcome.TIE
        )
        val a = StatsEngine.analyze(data)
        // (10+1)/(20+3) = 11/23 and (5+1)/(20+3) = 6/23
        assertEquals(11.0 / 23.0, a.global.first { it.outcome == Outcome.BLUE }.p, 1e-12)
        assertEquals(6.0 / 23.0, a.global.first { it.outcome == Outcome.RED }.p, 1e-12)
        assertEquals(6.0 / 23.0, a.global.first { it.outcome == Outcome.TIE }.p, 1e-12)
    }

    @Test
    fun wilsonIntervalContainsPointAndShrinksWithSample() {
        val (loSmall, hiSmall) = StatsEngine.wilson(5, 10)
        val (loBig, hiBig) = StatsEngine.wilson(500, 1000)
        val pSmall = 0.5
        assertTrue(loSmall <= pSmall && pSmall <= hiSmall)
        assertTrue(loBig <= pBig() && pBig() <= hiBig)
        assertTrue((hiSmall - loSmall) > (hiBig - loBig))
    }

    private fun pBig(): Double = 0.5

    @Test
    fun wilsonExtremeCasesStayInBounds() {
        val (lo, hi) = StatsEngine.wilson(0, 10)
        assertEquals(0.0, lo, 1e-9)
        assertTrue(hi > 0.0 && hi < 1.0)
        val (lo2, hi2) = StatsEngine.wilson(10, 10)
        assertEquals(1.0, hi2, 1e-9)
        assertTrue(lo2 > 0.0 && lo2 < 1.0)
    }

    @Test
    fun markovConditionalUsesLaplaceOnTransitions() {
        val data = rounds(Outcome.BLUE, Outcome.BLUE, Outcome.BLUE)
        val a = StatsEngine.analyze(data)
        val c = a.conditionals.first { it.lastOutcome == Outcome.BLUE }
        assertEquals(2, c.sample)
        // p(next = blue | last = blue) = (2+1)/(2+3) = 3/5
        assertEquals(3.0 / 5.0, c.estimates.first { it.outcome == Outcome.BLUE }.p, 1e-12)
        assertFalse(c.reliable)
    }

    @Test
    fun balancedDataIsCompatibleWithFairGame() {
        val data = rounds(
            Outcome.BLUE, Outcome.RED, Outcome.TIE,
            Outcome.BLUE, Outcome.RED, Outcome.TIE,
            Outcome.BLUE, Outcome.RED, Outcome.TIE,
            Outcome.BLUE, Outcome.RED, Outcome.TIE
        )
        val a = StatsEngine.analyze(data)
        assertEquals(0.0, a.chiSquare, 1e-9)
        assertEquals(1.0, a.pValue, 1e-9)
        assertTrue(a.fairCompatible)
    }

    @Test
    fun skewedDataIsFlaggedAsNotFairCompatible() {
        val data = List(30) { Round(it.toLong(), Outcome.BLUE, Source.MANUAL) }
        val a = StatsEngine.analyze(data)
        // expected 10 per outcome: (400 + 100 + 100)/10 = 60
        assertEquals(60.0, a.chiSquare, 1e-9)
        assertEquals(kotlin.math.exp(-30.0), a.pValue, 1e-12)
        assertFalse(a.fairCompatible)
    }

    @Test
    fun qualityTiersFollowThresholds() {
        assertEquals(Quality.INSUFFICIENT, StatsEngine.qualityOf(0))
        assertEquals(Quality.INSUFFICIENT, StatsEngine.qualityOf(11))
        assertEquals(Quality.LOW, StatsEngine.qualityOf(12))
        assertEquals(Quality.LOW, StatsEngine.qualityOf(39))
        assertEquals(Quality.MEDIUM, StatsEngine.qualityOf(40))
        assertEquals(Quality.MEDIUM, StatsEngine.qualityOf(99))
        assertEquals(Quality.GOOD, StatsEngine.qualityOf(100))
    }

    @Test
    fun streakCountsTrailingRepeats() {
        val data = rounds(
            Outcome.RED, Outcome.RED, Outcome.BLUE, Outcome.BLUE, Outcome.BLUE
        )
        val a = StatsEngine.analyze(data)
        assertEquals(3, a.currentStreak?.length)
        assertEquals(Outcome.BLUE, a.currentStreak?.outcome)
    }

    @Test
    fun explanationAlwaysContainsHonestDisclaimer() {
        val a1 = StatsEngine.analyze(emptyList())
        val a2 = StatsEngine.analyze(rounds(Outcome.BLUE, Outcome.RED))
        assertTrue(a1.explanation.contains("NAO sao previsoes garantidas"))
        assertTrue(a2.explanation.contains("NAO sao previsoes garantidas"))
    }
}
