package com.mybotai.bacboanalyzer.stats

import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Pure, mathematically validated statistical engine.
 *
 * Design constraints (intentional):
 *  - No randomness is ever produced: every number shown to the user is
 *    derived deterministically from the recorded history.
 *  - Probabilities use Laplace (add-one) smoothing so tiny samples never
 *    report 0% or 100%.
 *  - Uncertainty is reported with Wilson score 95% confidence intervals.
 *  - Fairness is tested with a chi-square goodness-of-fit statistic. For
 *    df = 2 the exact p-value is p = exp(-chi2 / 2) (chi-square with two
 *    degrees of freedom is an exponential distribution with rate 1/2).
 *  - If the sample is too small the engine reports a uniform estimate
 *    plus an explicit "insufficient data" warning.
 *
 * This engine NEVER claims to predict the future. Independent fair rounds
 * cannot be predicted; the outputs describe the recorded history only.
 */
enum class Quality { INSUFFICIENT, LOW, MEDIUM, GOOD }

data class ProbEstimate(
    val outcome: Outcome,
    val p: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val count: Int
)

data class ConditionalEstimate(
    val lastOutcome: Outcome,
    val sample: Int,
    val reliable: Boolean,
    val estimates: List<ProbEstimate>
)

data class Streak(val outcome: Outcome, val length: Int)

data class Analysis(
    val n: Int,
    val counts: Map<Outcome, Int>,
    val global: List<ProbEstimate>,
    val conditionals: List<ConditionalEstimate>,
    val chiSquare: Double,
    val pValue: Double,
    val fairCompatible: Boolean,
    val quality: Quality,
    val insufficient: Boolean,
    val currentStreak: Streak?,
    val explanation: String
)

object StatsEngine {

    const val MIN_INSUFFICIENT = 12
    const val MIN_LOW = 40
    const val MIN_MEDIUM = 100
    const val MIN_MARKOV_SAMPLE = 20
    const val ALPHA = 0.05

    private val outcomes = listOf(Outcome.BLUE, Outcome.RED, Outcome.TIE)

    /** Laplace (add-one) smoothed probability of a category. */
    fun laplace(k: Int, n: Int, categories: Int = 3): Double =
        (k + 1).toDouble() / (n + categories)

    /** Wilson score interval for a binomial proportion at confidence z. */
    fun wilson(k: Int, n: Int, z: Double = 1.959963984540054): Pair<Double, Double> {
        if (n <= 0) return 0.0 to 1.0
        val ph = k.toDouble() / n
        val z2 = z * z
        val denom = 1.0 + z2 / n
        val center = (ph + z2 / (2 * n)) / denom
        val margin = z * sqrt(ph * (1 - ph) / n + z2 / (4.0 * n * n)) / denom
        return (center - margin).coerceIn(0.0, 1.0) to (center + margin).coerceIn(0.0, 1.0)
    }

    /** Pearson chi-square statistic against the uniform 1/3 hypothesis. */
    fun chiSquare(counts: Map<Outcome, Int>, n: Int): Double {
        if (n <= 0) return 0.0
        val expected = n / 3.0
        return outcomes.sumOf { o ->
            val obs = counts.getOrDefault(o, 0).toDouble()
            val d = obs - expected
            (d * d) / expected
        }
    }

    /** Exact p-value for df = 2: chi-square(2) is exponential with rate 1/2. */
    fun pValueDf2(chi2: Double): Double = exp(-chi2 / 2.0)

    fun qualityOf(n: Int): Quality = when {
        n >= MIN_MEDIUM -> Quality.GOOD
        n >= MIN_LOW -> Quality.MEDIUM
        n >= MIN_INSUFFICIENT -> Quality.LOW
        else -> Quality.INSUFFICIENT
    }

    fun analyze(rounds: List<Round>): Analysis {
        val n = rounds.size
        val counts = outcomes.associateWith { o -> rounds.count { it.outcome == o } }

        // Point estimate: Laplace-smoothed frequency.
        // Interval: Wilson score interval on the RAW empirical proportion,
        // so the reported uncertainty reflects observed data only.
        val global = outcomes.map { o ->
            val k = counts.getValue(o)
            val (lo, hi) = wilson(k, n)
            ProbEstimate(o, laplace(k, n), lo, hi, k)
        }

        // One-step Markov (conditional) estimates: P(next | last outcome).
        val conditionals = outcomes.mapNotNull { last ->
            val pairs = rounds.zipWithNext().filter { it.first.outcome == last }
            val sample = pairs.size
            if (sample == 0) {
                null
            } else {
                val ests = outcomes.map { o ->
                    val k = pairs.count { it.second.outcome == o }
                    val (lo, hi) = wilson(k, sample)
                    ProbEstimate(o, laplace(k, sample), lo, hi, k)
                }
                ConditionalEstimate(last, sample, sample >= MIN_MARKOV_SAMPLE, ests)
            }
        }

        val chi2 = chiSquare(counts, n)
        val pValue = pValueDf2(chi2)
        val fairCompatible = n > 0 && pValue >= ALPHA
        val quality = qualityOf(n)
        val insufficient = n < MIN_INSUFFICIENT

        val streak: Streak? = if (n == 0) null else {
            val last = rounds.last().outcome
            var len = 0
            for (i in rounds.indices.reversed()) {
                if (rounds[i].outcome == last) len++ else break
            }
            Streak(last, len)
        }

        val explanation = buildExplanation(n, counts, global, conditionals, chi2, pValue, fairCompatible, quality, insufficient, streak)

        return Analysis(
            n = n,
            counts = counts,
            global = global,
            conditionals = conditionals,
            chiSquare = chi2,
            pValue = pValue,
            fairCompatible = fairCompatible,
            quality = quality,
            insufficient = insufficient,
            currentStreak = streak,
            explanation = explanation
        )
    }

    private fun buildExplanation(
        n: Int,
        counts: Map<Outcome, Int>,
        global: List<ProbEstimate>,
        conditionals: List<ConditionalEstimate>,
        chi2: Double,
        pValue: Double,
        fairCompatible: Boolean,
        quality: Quality,
        insufficient: Boolean,
        streak: Streak?
    ): String {
        val sb = StringBuilder()
        sb.append("Rondas analisadas: ").append(n).append('\n')

        if (n == 0) {
            sb.append('\n')
                .append("Sem dados registados: a unica estimativa honesta e a uniforme (33,3% para cada resultado) com incerteza maxima.\n")
                .append('\n')
                .append("Registe resultados manualmente ou importe um screenshot (OCR) para gerar estatisticas.\n")
        } else {
            sb.append('\n')
                .append("Frequencias historicas (suavizacao de Laplace) com intervalo de confianca Wilson 95%:\n")
            for (e in global) {
                sb.append(" - ").append(label(e.outcome)).append(": ").append(pct(e.p))
                    .append("  (IC 95%: ").append(pct(e.ciLow)).append(" - ").append(pct(e.ciHigh))
                    .append(", ").append(e.count).append(" rondas)\n")
            }

            sb.append('\n')
                .append("Teste de ajustamento chi-quadrado (df=2): chi2 = ").append(f2(chi2))
                .append(", p = ").append(f3(pValue)).append('\n')
            if (fairCompatible) {
                sb.append(" Os dados sao COMPATIVEIS com um jogo justo e independente: nao ha vantagem estatistica detetavel e o melhor estimador por resultado permanece proximo de 1/3.\n")
            } else {
                sb.append(" Desvio da uniformidade nesta amostra (p < ").append(ALPHA).append("). Isto DESCREVE o passado; nao prova um padrao exploravel e nao constitui previsao.\n")
            }

            val reliable = conditionals.filter { it.reliable }
            if (reliable.isEmpty()) {
                sb.append('\n')
                    .append("Padroes condicionais (Markov): amostra por contexto ainda pequena para relatorios fiaveis (minimo ")
                    .append(MIN_MARKOV_SAMPLE).append(" transicoes por contexto).\n")
            } else {
                sb.append('\n').append("Padroes condicionais (dado o ultimo resultado):\n")
                for (c in reliable) {
                    val best = c.estimates.maxByOrNull { it.p } ?: continue
                    sb.append(" - Apos ").append(label(c.lastOutcome)).append(": mais provavel ").append(label(best.outcome))
                        .append(" com ").append(pct(best.p)).append(" (n = ").append(c.sample).append(")\n")
                }
            }

            streak?.let {
                sb.append('\n')
                    .append("Sequencia atual: ").append(it.length).append("x ").append(label(it.outcome)).append('\n')
            }
        }

        sb.append('\n')
        sb.append("Qualidade dos dados: ")
        sb.append(
            when (quality) {
                Quality.INSUFFICIENT -> "INSUFICIENTE (minimo recomendado: $MIN_INSUFFICIENT rondas)"
                Quality.LOW -> "BAIXA (frequencias ainda instaveis)"
                Quality.MEDIUM -> "MEDIA"
                Quality.GOOD -> "BOA"
            }
        )
        if (insufficient) {
            sb.append("\nAVISO: dados insuficientes. As percentagens apresentadas tem incerteza muito alta e podem mudar bastante a cada ronda.")
        }

        sb.append("\n\nNOTA IMPORTANTE: se as rondas forem independentes e justas, nenhuma analise consegue prever o proximo resultado. ")
        sb.append("Estas percentagens descrevem o historico registado e a incerteza associada - NAO sao previsoes garantidas.")

        return sb.toString()
    }

    fun label(o: Outcome): String = when (o) {
        Outcome.BLUE -> "Azul"
        Outcome.RED -> "Vermelho"
        Outcome.TIE -> "Empate"
    }

    fun pct(x: Double): String = String.format(Locale.US, "%.1f%%", x * 100)
    fun f2(x: Double): String = String.format(Locale.US, "%.2f", x)
    fun f3(x: Double): String = String.format(Locale.US, "%.3f", x)
}
