package com.mybotai.bacboanalyzer.game

import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.stats.StatsEngine
import com.mybotai.bacboanalyzer.stats.Streak
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Contextual interpretation engine (spec section 4).
 *
 * Combines: visual observation, sequence recognition (streaks), history
 * analysis, alternation level, trend shifts between recent windows, result
 * distribution, transition (Markov) reliability and uncertainty evaluation.
 *
 * Design constraints (intentional):
 *  - Everything is deterministic: no randomness anywhere.
 *  - Estimates blend the observed history with the exact Bac Bo theoretical
 *    distribution (Dirichlet-style prior) so small samples stay honest.
 *  - Uncertainty is reported with Wilson 95% intervals (from StatsEngine).
 *  - The engine NEVER claims that a past sequence determines the next round;
 *    every produced text says so explicitly.
 */
object InterpretationEngine {

    data class BacBoEstimate(
        val outcome: Outcome,
        val p: Double,
        val ciLow: Double,
        val ciHigh: Double,
        val count: Int
    )

    data class Interpretation(
        val summary: String,
        val details: List<String>,
        val estimates: List<BacBoEstimate>,
        val streak: Streak?,
        val alternationIndex: Double?,
        val alternationReliable: Boolean,
        val trendShift: Double?,
        val trendSignificant: Boolean,
        val markovReliable: Boolean,
        val confidence: Double,
        val insufficient: Boolean
    )

    const val PRIOR_WEIGHT = 8.0
    const val MIN_ALTERNATION_SAMPLE = 30
    const val TREND_WINDOW = 10

    private val outcomes = listOf(Outcome.BLUE, Outcome.RED, Outcome.TIE)

    /** Dirichlet-style posterior: (k + W*pi) / (n + W) with the exact Bac Bo prior. */
    fun estimates(rounds: List<Round>): List<BacBoEstimate> {
        val n = rounds.size
        return outcomes.map { o ->
            val k = rounds.count { it.outcome == o }
            val pi = when (o) {
                Outcome.BLUE -> BacBoRules.THEORETICAL_BLUE
                Outcome.RED -> BacBoRules.THEORETICAL_RED
                Outcome.TIE -> BacBoRules.THEORETICAL_TIE
            }
            val p = if (n == 0) pi else (k + PRIOR_WEIGHT * pi) / (n + PRIOR_WEIGHT)
            val (lo, hi) = if (n == 0) 0.0 to 1.0 else StatsEngine.wilson(k, n)
            BacBoEstimate(o, p, lo, hi, k)
        }
    }

    /**
     * Alternation index: fraction of consecutive non-tie transitions that
     * flip color. Under independence the expectation is 0.5; the level is
     * only called reliable with >= 30 transitions and |z| > 2.
     */
    fun alternation(rounds: List<Round>): Pair<Double, Boolean>? {
        val seq = rounds.map { it.outcome }.filter { it != Outcome.TIE }
        val pairs = seq.zipWithNext()
        val m = pairs.size
        if (m == 0) return null
        val flips = pairs.count { (a, b) -> a != b }
        val idx = flips.toDouble() / m
        if (m < MIN_ALTERNATION_SAMPLE) return idx to false
        val z = (idx - 0.5) / sqrt(0.25 / m)
        return idx to (abs(z) > 2.0)
    }

    /**
     * Trend shift: blue share (ties excluded) in the last [TREND_WINDOW]
     * rounds minus the share in the previous window. Significant only when
     * the two Wilson intervals do not overlap.
     */
    fun trend(rounds: List<Round>): Pair<Double, Boolean>? {
        if (rounds.size < 2 * TREND_WINDOW) return null
        val last = rounds.takeLast(TREND_WINDOW)
        val prev = rounds.dropLast(TREND_WINDOW).takeLast(TREND_WINDOW)

        fun blueStats(list: List<Round>): Pair<Int, Int> {
            val nonTie = list.count { it.outcome != Outcome.TIE }
            val blue = list.count { it.outcome == Outcome.BLUE }
            return blue to nonTie
        }

        val (kB, nB) = blueStats(last)
        val (kP, nP) = blueStats(prev)
        if (nB == 0 || nP == 0) return null
        val shift = kB.toDouble() / nB - kP.toDouble() / nP
        val (loB, hiB) = StatsEngine.wilson(kB, nB)
        val (loP, hiP) = StatsEngine.wilson(kP, nP)
        val significant = (hiP < loB) || (hiB < loP)
        return shift to significant
    }

    fun confidenceOf(n: Int): Double = when {
        n >= StatsEngine.MIN_MEDIUM -> 0.9
        n >= StatsEngine.MIN_LOW -> 0.7
        n >= StatsEngine.MIN_INSUFFICIENT -> 0.45
        else -> 0.15
    }

    fun interpret(rounds: List<Round>, diceRounds: List<ObservedRound> = emptyList()): Interpretation {
        val n = rounds.size
        val est = estimates(rounds)
        val streak = StatsEngine.analyze(rounds).currentStreak
        val alt = alternation(rounds)
        val tr = trend(rounds)
        val markovReliable = n >= StatsEngine.MIN_MARKOV_SAMPLE * 2 &&
            StatsEngine.analyze(rounds).conditionals.any { it.reliable }
        val confidence = confidenceOf(n)
        val insufficient = n < StatsEngine.MIN_INSUFFICIENT

        val summary = buildSummary(n, streak, alt, tr, insufficient)
        val details = buildDetails(rounds, diceRounds, est, streak, alt, tr, markovReliable, confidence, insufficient)

        return Interpretation(
            summary = summary,
            details = details,
            estimates = est,
            streak = streak,
            alternationIndex = alt?.first,
            alternationReliable = alt?.second ?: false,
            trendShift = tr?.first,
            trendSignificant = tr?.second ?: false,
            markovReliable = markovReliable,
            confidence = confidence,
            insufficient = insufficient
        )
    }

    private fun buildSummary(
        n: Int,
        streak: Streak?,
        alt: Pair<Double, Boolean>?,
        tr: Pair<Double, Boolean>?,
        insufficient: Boolean
    ): String {
        val sb = StringBuilder()
        if (n == 0) {
            sb.append("Mesa sem rondas registadas: as estimativas mostram a distribuição teórica do Bac Bo ")
            sb.append("(Azul ≈ 44,4%, Vermelho ≈ 44,4%, Empate ≈ 11,3%) com incerteza máxima.")
        } else {
            sb.append("Mesa observada: ").append(n).append(if (n == 1) " ronda." else " rondas.")
            streak?.let {
                sb.append(" Sequência atual: ").append(it.length).append("x ").append(StatsEngine.label(it.outcome)).append('.')
            }
            if (insufficient) {
                sb.append(" Dados insuficientes — as estimativas podem mudar bastante a cada ronda.")
            } else if (tr?.second == true) {
                sb.append(" Mudança de tendência detetada nas janelas recentes (pode ser variância normal).")
            } else if (alt?.second == true) {
                sb.append(" Nível de alternância fora do esperado nesta amostra (descreve o passado).")
            } else {
                sb.append(" Padrões dentro do esperado para rondas independentes.")
            }
        }
        sb.append(" As estimativas descrevem o histórico — não preveem a próxima ronda.")
        return sb.toString()
    }

    private fun buildDetails(
        rounds: List<Round>,
        diceRounds: List<ObservedRound>,
        est: List<BacBoEstimate>,
        streak: Streak?,
        alt: Pair<Double, Boolean>?,
        tr: Pair<Double, Boolean>?,
        markovReliable: Boolean,
        confidence: Double,
        insufficient: Boolean
    ): List<String> {
        val out = mutableListOf<String>()
        for (e in est) {
            out.add(
                "Estimativa ${StatsEngine.label(e.outcome)}: " + StatsEngine.pct(e.p) +
                    " (IC 95%: " + StatsEngine.pct(e.ciLow) + " – " + StatsEngine.pct(e.ciHigh) +
                    ", " + e.count + " rondas)"
            )
        }
        alt?.let { (idx, reliable) ->
            out.add(
                "Alternância (mudança de cor entre rondas não-empate): " + StatsEngine.pct(idx) +
                    if (reliable) " — desvio fiável nesta amostra."
                    else " — dentro/ainda perto do esperado (50%) para rondas independentes."
            )
        }
        tr?.let { (shift, significant) ->
            out.add(
                "Tendência (participação de Azul: últimas " + TREND_WINDOW + " vs " + TREND_WINDOW + " anteriores): " +
                    (if (shift >= 0) "+" else "") + StatsEngine.pct(shift) +
                    if (significant) " — intervalos não se sobrepõem: mudança marcada nesta amostra."
                    else " — sem evidência fiável de mudança."
            )
        }
        out.add(
            if (markovReliable) "Transições (Markov): amostra suficiente; ver 'Explicação estatística'."
            else "Transições (Markov): amostra ainda pequena para relatórios fiáveis."
        )
        if (diceRounds.isNotEmpty()) {
            val bs = diceRounds.map { it.blueSum }
            val rs = diceRounds.map { it.redSum }
            val avgB = bs.average()
            val avgR = rs.average()
            val tieShare = diceRounds.count { it.outcome == Outcome.TIE }.toDouble() / diceRounds.size
            out.add(
                String.format(
                    Locale.US,
                    "Dados observados: soma média Azul %.1f, Vermelho %.1f; empates observados %s vs teórico ≈ 11,3%%.",
                    avgB, avgR, StatsEngine.pct(tieShare)
                )
            )
        }
        out.add(
            "Confiança da interpretação: " + StatsEngine.pct(confidence) +
                if (insufficient) " (INSUFICIENTE — recolher mais rondas)."
                else "."
        )
        streak?.let {
            if (it.length >= 4) {
                out.add(
                    "Sequência de " + it.length + "x " + StatsEngine.label(it.outcome) +
                        " — em rondas independentes isto NÃO torna o resultado seguinte mais provável num dos lados."
                )
            }
        }
        out.add(
            "As rondas são tratadas como independentes: os padrões observados descrevem o passado " +
                "e não determinam a próxima ronda."
        )
        out.add(
            "NOTA: reconhecimento visual, interpretação da mesa e estimativa estatística são coisas distintas; " +
                "nenhuma delas garante o próximo resultado."
        )
        return out
    }
}
