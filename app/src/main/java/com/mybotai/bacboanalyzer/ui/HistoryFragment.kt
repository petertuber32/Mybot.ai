package com.mybotai.bacboanalyzer.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mybotai.bacboanalyzer.R
import com.mybotai.bacboanalyzer.data.RoundStore
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.models.Source
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private val dataListener = { if (view != null) refresh() }
    private val adapter = RoundsAdapter()
    private val timeFmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
    private val outcomeNames = arrayOf("Azul", "Vermelho", "Empate")
    private val outcomes = listOf(Outcome.BLUE, Outcome.RED, Outcome.TIE)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerRounds)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<Button>(R.id.btnUndoH).setOnClickListener {
            val rounds = RoundStore.getAll()
            if (rounds.isNotEmpty()) RoundStore.removeAt(rounds.size - 1)
        }
        view.findViewById<Button>(R.id.btnExportH).setOnClickListener { exportHistory() }
        view.findViewById<Button>(R.id.btnClearH).setOnClickListener { confirmClear() }
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

    private fun refresh() {
        val v = view ?: return
        val rounds = RoundStore.getAll()
        adapter.submit(rounds)
        v.findViewById<TextView>(R.id.txtEmptyHistory).isVisible = rounds.isEmpty()
        v.findViewById<TextView>(R.id.txtHistoryCount).text =
            getString(R.string.history_count_fmt, rounds.size)
        v.findViewById<Button>(R.id.btnUndoH).isEnabled = rounds.isNotEmpty()
        v.findViewById<Button>(R.id.btnExportH).isEnabled = rounds.isNotEmpty()
        v.findViewById<Button>(R.id.btnClearH).isEnabled = rounds.isNotEmpty()
    }

    private fun exportHistory() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BAC BO AI ANALYZER - historico")
            putExtra(Intent.EXTRA_TEXT, RoundStore.exportJson())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_export)))
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.btn_clear)
            .setMessage(R.string.confirm_clear)
            .setPositiveButton(R.string.yes) { _, _ -> RoundStore.clear() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditDialog(position: Int) {
        val rounds = RoundStore.getAll()
        if (position !in rounds.indices) return
        val current = outcomes.indexOf(rounds[position].outcome)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_outcome)
            .setSingleChoiceItems(outcomeNames, current) { dialog, which ->
                RoundStore.updateOutcome(position, outcomes[which])
                dialog.dismiss()
            }
            .setNeutralButton(R.string.delete) { _, _ -> RoundStore.removeAt(position) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun outcomeName(o: Outcome): String = when (o) {
        Outcome.BLUE -> getString(R.string.lbl_blue)
        Outcome.RED -> getString(R.string.lbl_red)
        Outcome.TIE -> getString(R.string.lbl_tie)
    }

    private fun sourceName(s: Source): String = when (s) {
        Source.MANUAL -> getString(R.string.src_manual)
        Source.OCR -> getString(R.string.src_ocr)
        Source.VISION -> getString(R.string.src_vision)
    }

    private inner class RoundsAdapter : RecyclerView.Adapter<RoundsAdapter.VH>() {

        private var items: List<Round> = emptyList()

        fun submit(list: List<Round>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dotRound)
            val outcome: TextView = v.findViewById(R.id.txtRoundOutcome)
            val time: TextView = v.findViewById(R.id.txtRoundTime)
            val source: TextView = v.findViewById(R.id.txtRoundSource)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_round, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.dot.setBackgroundResource(
                when (r.outcome) {
                    Outcome.BLUE -> R.drawable.dot_blue
                    Outcome.RED -> R.drawable.dot_red
                    Outcome.TIE -> R.drawable.dot_tie
                }
            )
            holder.outcome.text = outcomeName(r.outcome)
            holder.time.text = timeFmt.format(Date(r.timestamp))
            holder.source.text = sourceName(r.source)
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showEditDialog(pos)
            }
        }
    }
}
