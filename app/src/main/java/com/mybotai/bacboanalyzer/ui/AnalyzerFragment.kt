package com.mybotai.bacboanalyzer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mybotai.bacboanalyzer.R
import com.mybotai.bacboanalyzer.data.RoundStore
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.models.Source
import com.mybotai.bacboanalyzer.ocr.OcrActivity
import com.mybotai.bacboanalyzer.stats.Analysis
import com.mybotai.bacboanalyzer.stats.Quality
import com.mybotai.bacboanalyzer.stats.StatsEngine

class AnalyzerFragment : Fragment(R.layout.fragment_analyzer) {

    private val dataListener = { if (view != null) refresh() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        RoundStore.init(requireContext().applicationContext)

        view.findViewById<Button>(R.id.btnBlue).setOnClickListener { add(Outcome.BLUE) }
        view.findViewById<Button>(R.id.btnRed).setOnClickListener { add(Outcome.RED) }
        view.findViewById<Button>(R.id.btnTie).setOnClickListener { add(Outcome.TIE) }

        view.findViewById<Button>(R.id.btnUndo).setOnClickListener {
            val rounds = RoundStore.getAll()
            if (rounds.isNotEmpty()) RoundStore.removeAt(rounds.size - 1)
        }

        view.findViewById<Button>(R.id.btnOcr).setOnClickListener {
            startActivity(Intent(requireContext(), OcrActivity::class.java))
        }

        view.findViewById<Button>(R.id.btnAnalyze).setOnClickListener {
            val text = view.findViewById<TextView>(R.id.txtExplanation).text
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.explanation_title)
                .setMessage(text)
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        refresh()
    }

    override fun onStart() {
        super.onStart()
        RoundStore.addListener(dataListener)
    }

    override fun onStop() {
        RoundStore.removeListener(dataListener)
        super.onStop()
    }

    private fun add(outcome: Outcome) {
        RoundStore.add(Round(System.currentTimeMillis(), outcome, Source.MANUAL))
    }

    private fun refresh() {
        val v = view ?: return
        val rounds = RoundStore.getAll()
        val analysis = StatsEngine.analyze(rounds)

        setProb(v, R.id.barBlue, R.id.txtBluePct, analysis, Outcome.BLUE)
        setProb(v, R.id.barRed, R.id.txtRedPct, analysis, Outcome.RED)
        setProb(v, R.id.barTie, R.id.txtTiePct, analysis, Outcome.TIE)

        v.findViewById<TextView>(R.id.txtStatsMeta).text = getString(
            R.string.stats_meta_fmt,
            analysis.n,
            qualityLabel(analysis.quality)
        )
        v.findViewById<TextView>(R.id.txtExplanation).text = analysis.explanation
        v.findViewById<TextView>(R.id.txtEmpty).isVisible = rounds.isEmpty()
        v.findViewById<Button>(R.id.btnUndo).isEnabled = rounds.isNotEmpty()
    }

    private fun setProb(v: View, barId: Int, txtId: Int, a: Analysis, o: Outcome) {
        val est = a.global.firstOrNull { it.outcome == o } ?: return
        v.findViewById<ProgressBar>(barId).progress = (est.p * 100).toInt()
        v.findViewById<TextView>(txtId).text = StatsEngine.pct(est.p)
    }

    private fun qualityLabel(q: Quality): String = getString(
        when (q) {
            Quality.INSUFFICIENT -> R.string.quality_insufficient
            Quality.LOW -> R.string.quality_low
            Quality.MEDIUM -> R.string.quality_medium
            Quality.GOOD -> R.string.quality_good
        }
    )
}
