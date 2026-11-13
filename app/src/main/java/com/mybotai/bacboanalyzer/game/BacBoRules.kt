package com.mybotai.bacboanalyzer.game

import com.mybotai.bacboanalyzer.models.Outcome

/**
 * Bac Bo rules module (Evolution standard variant):
 *  - 2 blue dice, 2 red dice, each die 1..6 pips;
 *  - blue sum vs red sum: higher sum wins; equal sums = tie.
 *
 * Also exposes the exact theoretical outcome probabilities over all 6^4 =
 * 1296 ordered dice outcomes, computed by brute force. These constants are
 * the honest baseline the interpretation engine compares the observed table
 * against (AZUL ~44.4%, VERMELHO ~44.4%, EMPATE ~11.3%) and are covered by
 * a unit test that hard-codes the closed-form values.
 */
object BacBoRules {

    data class DiceValidation(
        val valid: Boolean,
        val derived: Outcome?,
        val blueSum: Int?,
        val redSum: Int?,
        val reason: String? = null
    )

    fun isValidDie(v: Int): Boolean = v in 1..6

    fun outcomeOf(blueSum: Int, redSum: Int): Outcome = when {
        blueSum > redSum -> Outcome.BLUE
        redSum > blueSum -> Outcome.RED
        else -> Outcome.TIE
    }

    /**
     * Validates that four recognized die values form a legal Bac Bo round
     * and derives the outcome from the sums. The recognized outcome must
     * always be checked against this derivation before anything is stored.
     */
    fun validate(b1: Int, b2: Int, r1: Int, r2: Int): DiceValidation {
        val dice = listOf(b1, b2, r1, r2)
        if (dice.any { !isValidDie(it) }) {
            return DiceValidation(false, null, null, null, "valor de dado inválido (fora de 1..6)")
        }
        val bs = b1 + b2
        val rs = r1 + r2
        return DiceValidation(true, outcomeOf(bs, rs), bs, rs)
    }

    /** Exact P(AZUL) over all 1296 ordered outcomes. */
    val THEORETICAL_BLUE: Double by lazy { bruteForce(Outcome.BLUE) }

    /** Exact P(VERMELHO) over all 1296 ordered outcomes. */
    val THEORETICAL_RED: Double by lazy { bruteForce(Outcome.RED) }

    /** Exact P(EMPATE) over all 1296 ordered outcomes. */
    val THEORETICAL_TIE: Double by lazy { bruteForce(Outcome.TIE) }

    private fun bruteForce(target: Outcome): Double {
        var matches = 0
        var total = 0
        for (b1 in 1..6) for (b2 in 1..6) for (r1 in 1..6) for (r2 in 1..6) {
            total++
            if (outcomeOf(b1 + b2, r1 + r2) == target) matches++
        }
        return matches.toDouble() / total
    }
}
