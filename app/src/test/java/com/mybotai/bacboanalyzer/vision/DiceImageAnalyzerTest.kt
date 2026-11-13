package com.mybotai.bacboanalyzer.vision

import com.mybotai.bacboanalyzer.game.BacBoRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognition accuracy tests on synthetic frames. The full blue and red
 * value ranges are covered; every read must match the drawn dice exactly
 * and reach the auto-accept confidence when all four dice are present.
 */
class DiceImageAnalyzerTest {

    private val analyzer = DiceImageAnalyzer()

    private fun frame(
        w: Int = 360, h: Int = 640, size: Int = 44,
        b1: Int, b2: Int, r1: Int, r2: Int,
        yBlue: Int = 260, yRed: Int = 260
    ): Frame = TestFrames.Builder(w, h)
        .die(30, yBlue, size, blue = true, value = b1)
        .die(120, yBlue, size, blue = true, value = b2)
        .die(210, yRed, size, blue = false, value = r1)
        .die(300, yRed, size, blue = false, value = r2)
        .build()

    @Test
    fun recognizesEveryBlueCombination() {
        val small = DiceImageAnalyzer()
        for (b1 in 1..6) {
            for (b2 in 1..6) {
                val read = small.analyze(frame(w = 480, h = 720, size = 40, b1 = b1, b2 = b2, r1 = 3, r2 = 4))
                assertEquals("b1 for ($b1,$b2)", b1, read.b1)
                assertEquals("b2 for ($b1,$b2)", b2, read.b2)
                assertEquals(3, read.r1)
                assertEquals(4, read.r2)
                assertTrue("confidence for ($b1,$b2) = ${read.confidence}", read.confidence >= DiceRead.AUTO_ACCEPT)
                assertTrue(read.readable)
            }
        }
    }

    @Test
    fun recognizesEveryRedCombination() {
        for (r1 in 1..6) {
            for (r2 in 1..6) {
                val read = analyzer.analyze(frame(w = 480, h = 720, size = 40, b1 = 5, b2 = 2, r1 = r1, r2 = r2))
                assertEquals(5, read.b1)
                assertEquals(2, read.b2)
                assertEquals("r1 for ($r1,$r2)", r1, read.r1)
                assertEquals("r2 for ($r1,$r2)", r2, read.r2)
                assertTrue(read.confidence >= DiceRead.AUTO_ACCEPT)
            }
        }
    }

    @Test
    fun outcomeDerivedFromSumsMatchesRules() {
        val read = analyzer.analyze(frame(b1 = 3, b2 = 5, r1 = 2, r2 = 4)) // 8 vs 6 -> BLUE
        assertTrue(read.readable)
        val validation = BacBoRules.validate(read.b1!!, read.b2!!, read.r1!!, read.r2!!)
        assertTrue(validation.valid)
        assertEquals(com.mybotai.bacboanalyzer.models.Outcome.BLUE, validation.derived)
        assertEquals(8, validation.blueSum)
        assertEquals(6, validation.redSum)
    }

    @Test
    fun tieOutcomeIsValidated() {
        val read = analyzer.analyze(frame(b1 = 2, b2 = 3, r1 = 2, r2 = 3)) // 5 vs 5
        val validation = BacBoRules.validate(read.b1!!, read.b2!!, read.r1!!, read.r2!!)
        assertEquals(com.mybotai.bacboanalyzer.models.Outcome.TIE, validation.derived)
    }

    @Test
    fun missingDieIsNeverAutoAccepted() {
        val f = TestFrames.Builder(360, 640)
            .die(30, 260, 44, blue = true, value = 4)
            .die(210, 260, 44, blue = false, value = 3)
            .die(300, 260, 44, blue = false, value = 5)
            .build() // only 1 blue die
        val read = analyzer.analyze(f)
        assertTrue(read.blueDiceFound < 2 || read.knownCount < 4)
        assertTrue("confidence must stay below auto-accept, was ${read.confidence}", read.confidence < DiceRead.AUTO_ACCEPT)
    }

    @Test
    fun emptyTableProducesNoDice() {
        val read = analyzer.analyze(TestFrames.Builder(360, 640).build())
        assertEquals(0, read.blueDiceFound)
        assertEquals(0, read.redDiceFound)
        assertEquals(0, read.knownCount)
        assertEquals(0.0, read.confidence, 1e-9)
    }

    @Test
    fun diceAtDifferentVerticalPositionsAreRecognized() {
        val read = analyzer.analyze(frame(b1 = 6, b2 = 1, r1 = 4, r2 = 2, yBlue = 120, yRed = 380))
        assertEquals(6, read.b1)
        assertEquals(1, read.b2)
        assertEquals(4, read.r1)
        assertEquals(2, read.r2)
    }

    @Test
    fun differentDieSizesAreRecognized() {
        for (size in intArrayOf(36, 52, 64)) {
            val read = analyzer.analyze(frame(w = 480, h = 720, size = size, b1 = 2, b2 = 5, r1 = 6, r2 = 1))
            assertEquals("size $size b1", 2, read.b1)
            assertEquals("size $size b2", 5, read.b2)
            assertEquals("size $size r1", 6, read.r1)
            assertEquals("size $size r2", 1, read.r2)
            assertTrue("size $size confidence ${read.confidence}", read.confidence >= DiceRead.AUTO_ACCEPT)
        }
    }

    @Test
    fun signatureIsStableAndReadableFlagMatches() {
        val read = analyzer.analyze(frame(b1 = 1, b2 = 2, r1 = 3, r2 = 4))
        assertEquals("1,2,3,4", read.signature)
        assertTrue(read.readable)

        val broken = DiceRead(b1 = 1, b2 = null, r1 = 3, r2 = 4, blueDiceFound = 2, redDiceFound = 2, confidence = 0.4)
        assertEquals("1,-1,3,4", broken.signature)
        assertTrue(!broken.readable)
    }
}
