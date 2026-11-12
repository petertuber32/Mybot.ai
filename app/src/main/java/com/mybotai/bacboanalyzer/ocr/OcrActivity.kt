package com.mybotai.bacboanalyzer.ocr

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mybotai.bacboanalyzer.R
import com.mybotai.bacboanalyzer.data.RoundStore
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.models.Source

/**
 * Screenshot import: photo picker -> on-device OCR (ML Kit) -> review
 * list where the user corrects every recognized round before saving.
 */
class OcrActivity : AppCompatActivity() {

    private lateinit var rowsContainer: LinearLayout
    private lateinit var txtStatus: TextView
    private val outcomeNames = listOf("Azul", "Vermelho", "Empate")
    private val outcomes = listOf(Outcome.BLUE, Outcome.RED, Outcome.TIE)

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) processImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)
        rowsContainer = findViewById(R.id.ocrRows)
        txtStatus = findViewById(R.id.txtOcrStatus)

        findViewById<Button>(R.id.btnOcrPick).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btnOcrAddRow).setOnClickListener { addRow(Outcome.BLUE) }
        findViewById<Button>(R.id.btnOcrSave).setOnClickListener { saveRows() }
        findViewById<ImageButton>(R.id.btnOcrBack).setOnClickListener { finish() }
    }

    private fun processImage(uri: Uri) {
        txtStatus.text = getString(R.string.ocr_processing)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            txtStatus.text = getString(R.string.ocr_error_fmt, e.message ?: "")
            return
        }
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val parsed = ResultParser.parse(visionText.text)
                rowsContainer.removeAllViews()
                if (parsed.isEmpty()) {
                    txtStatus.text = getString(R.string.ocr_none)
                } else {
                    txtStatus.text = getString(R.string.ocr_found_fmt, parsed.size)
                    parsed.forEach { addRow(it) }
                }
            }
            .addOnFailureListener { e ->
                txtStatus.text = getString(R.string.ocr_error_fmt, e.message ?: "")
            }
    }

    private fun addRow(initial: Outcome) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_ocr_row, rowsContainer, false)
        val spinner = row.findViewById<Spinner>(R.id.spinOutcome)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, outcomeNames)
        spinner.setSelection(outcomes.indexOf(initial))
        row.findViewById<ImageButton>(R.id.btnRowRemove).setOnClickListener {
            rowsContainer.removeView(row)
        }
        rowsContainer.addView(row)
    }

    private fun saveRows() {
        val collected = mutableListOf<Outcome>()
        for (i in 0 until rowsContainer.childCount) {
            val row = rowsContainer.getChildAt(i)
            val spinner = row.findViewById<Spinner>(R.id.spinOutcome)
            collected.add(outcomes[spinner.selectedItemPosition])
        }
        if (collected.isEmpty()) {
            Toast.makeText(this, R.string.ocr_none, Toast.LENGTH_SHORT).show()
            return
        }
        collected.forEachIndexed { index, outcome ->
            RoundStore.add(Round(System.currentTimeMillis() + index, outcome, Source.OCR, "OCR"))
        }
        Toast.makeText(this, getString(R.string.ocr_saved_fmt, collected.size), Toast.LENGTH_SHORT).show()
        finish()
    }
}
