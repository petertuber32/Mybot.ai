package com.mybotai.bacboanalyzer.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mybotai.bacboanalyzer.R
import com.mybotai.bacboanalyzer.data.RoundStore
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.models.Source
import com.mybotai.bacboanalyzer.observe.ObservationBus
import com.mybotai.bacboanalyzer.observe.ObservationConsentActivity
import com.mybotai.bacboanalyzer.observe.ObservationService
import com.mybotai.bacboanalyzer.ocr.OcrActivity
import com.mybotai.bacboanalyzer.stats.Analysis
import com.mybotai.bacboanalyzer.stats.Quality
import com.mybotai.bacboanalyzer.stats.StatsEngine

class AnalyzerFragment : Fragment(R.layout.fragment_analyzer) {

    private val dataListener = { if (view != null) refresh() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val busListener: (com.mybotai.bacboanalyzer.observe.ObservationUiState) -> Unit = {
        mainHandler.post { if (view != null) refreshObservation() }
    }

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

        view.findViewById<Button>(R.id.btnObsToggle).setOnClickListener { toggleObservation() }
        view.findViewById<Button>(R.id.btnObsShot).setOnClickListener { requestScreenshot() }

        refresh()
        refreshObservation()
    }

    override fun onStart() {
        super.onStart()
        RoundStore.addListener(dataListener)
        ObservationBus.subscribe(busListener)
    }

    override fun onStop() {
        RoundStore.removeListener(dataListener)
        ObservationBus.unsubscribe(busListener)
        super.onStop()
    }

    private fun add(outcome: Outcome) {
        RoundStore.add(Round(System.currentTimeMillis(), outcome, Source.MANUAL))
    }

    private fun toggleObservation() {
        val ctx = requireContext()
        if (ObservationBus.current.observing) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ObservationService::class.java).setAction(ObservationService.ACTION_STOP)
            )
        } else {
            // Shows the Android MediaProjection consent dialog (explicit).
            startActivity(Intent(ctx, ObservationConsentActivity::class.java))
        }
    }

    private fun requestScreenshot() {
        val ctx = requireContext()
        if (ObservationBus.current.observing) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ObservationService::class.java).setAction(ObservationService.ACTION_CAPTURE_ONCE)
            )
        } else {
            startActivity(Intent(ctx, ObservationConsentActivity::class.java))
        }
    }

    private fun refreshObservation() {
        val v = view ?: return
        val bus = ObservationBus.current

        v.findViewById<Button>(R.id.btnObsToggle).setText(if (bus.observing) R.string.obs_on else R.string.obs_off)

        val stateView = v.findViewById<TextView>(R.id.txtObsState)
        stateView.text =
            if (bus.observing) getString(R.string.obs_state_fmt, bus.state.name)
            else getString(R.string.obs_state_idle_fmt)

        val read = bus.lastRead
        val lastReadView = v.findViewById<TextView>(R.id.txtObsLastRead)
        if (read != null && read.knownCount == 4) {
            val derived = when {
                (read.blueSum ?: 0) > (read.redSum ?: 0) -> StatsEngine.label(Outcome.BLUE)
                (read.redSum ?: 0) > (read.blueSum ?: 0) -> StatsEngine.label(Outcome.RED)
                else -> StatsEngine.label(Outcome.TIE)
            }
            lastReadView.text = getString(
                R.string.obs_last_read_fmt,
                read.b1 ?: 0, read.b2 ?: 0, read.r1 ?: 0, read.r2 ?: 0, derived
            )
        } else {
            lastReadView.setText(R.string.obs_no_data)
        }

        v.findViewById<TextView>(R.id.txtObsInterp).text =
            bus.interpretationSummary ?: getString(R.string.obs_no_data)

        val msgView = v.findViewById<TextView>(R.id.txtObsMsg)
        val parts = mutableListOf<String>()
        bus.readConfidence?.let { parts.add(getString(R.string.obs_confidence_fmt, StatsEngine.pct(it))) }
        bus.message?.let { parts.add(it) }
        if (parts.isEmpty()) {
            msgView.isVisible = false
        } else {
            msgView.text = parts.joinToString(" · ")
            msgView.isVisible = true
        }
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
