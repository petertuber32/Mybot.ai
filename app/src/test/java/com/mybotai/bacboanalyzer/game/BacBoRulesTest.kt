package com.mybotai.bacboanalyzer.game

import com.mybotai.bacboanalyzer.models.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BacBoRulesTest {

    @Test
    fun higherBlueSumWins() {
        assertEquals(Outcome.BLUE, BacBoRules.outcomeOf(8, 6))
        assertEquals(Outcome.RED, BacBoRules.outcomeOf(6, 8))
        assertEquals(Outcome.TIE, BacBoRules.outcomeOf(7, 7))
        assertEquals(Outcome.BLUE, BacBoRules.outcomeOf(12, 2))
    }

    @Test
    fun diceRangeValidation() {
        for (v in 1..6) assertTrue(BacBoRules.isValidDie(v))
        for (v in intArrayOf(0, 7, -1, 99)) assertFalse(BacBoRules.isValidDie(v))
    }

    @Test
    fun validateAcceptsLegalRoundsAndDerivesOutcome() {
        val v = BacBoRules.validate(3, 5, 2, 4)
        assertTrue(v.valid)
        assertEquals(8, v.blueSum)
        assertEquals(6, v.redSum)
        assertEquals(Outcome.BLUE, v.derived)

        val tie = BacBoRules.validate(2, 3, 5, 0) // invalid: red die 0
        assertFalse(tie.valid)
        assertEquals(null, tie.derived)
        assertTrue(tie.reason != null)
    }

    @Test
    fun validateRejectsOutOfBoundsDice() {
        assertFalse(BacBoRules.validate(0, 3, 2, 4).valid)
        assertFalse(BacBoRules.validate(7, 3, 2, 4).valid)
        assertFalse(BacBoRules.validate(3, 3, 2, 42).valid)
    }

    @Test
    fun theoreticalProbabilitiesMatchClosedForm() {
        // Independent derivation (not computed by the production code):
        // ordered outcomes = 6^4 = 1296; tie count = sum_s c_s^2 = 146
        // where c_s = #ways to roll sum s with 2 dice (1,2,3,4,5,6,5,4,3,2,1).
        val tie = 146.0 / 1296
        val blue = 575.0 / 1296
        assertEquals(tie, BacBoRules.THEORETICAL_TIE, 1e-12)
        assertEquals(blue, BacBoRules.THEORETICAL_BLUE, 1e-12)
        assertEquals(blue, BacBoRules.THEORETICAL_RED, 1e-12)
        assertEquals(
            1.0,
            BacBoRules.THEORETICAL_BLUE + BacBoRules.THEORETICAL_RED + BacBoRules.THEORETICAL_TIE,
            1e-12
        )
        // Sanity: Bac Bo is NOT uniform — ties are much rarer than 1/3.
        assertTrue(BacBoRules.THEORETICAL_TIE < 0.12)
        assertTrue(BacBoRules.THEORETICAL_BLUE > 0.44)
    }
}
