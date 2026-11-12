package com.mybotai.bacboanalyzer.ocr

import com.mybotai.bacboanalyzer.models.Outcome

/**
 * Pure text classifier: turns OCR text lines into Bac Bo outcomes.
 * No Android imports - fully unit tested.
 *
 * Recognized words (case-insensitive):
 *  - TIE : "empate", "empatou", "tie", "draw"
 *  - BLUE: "azul", "blue"
 *  - RED : "vermelho", "verm", "red"
 *
 * Lines without a recognizable keyword are ignored (OCR junk, dice
 * numbers, interface labels, etc.). The user always reviews the list
 * before anything is saved.
 */
object ResultParser {

    private val TIE_WORDS = listOf("empate", "empatou", "empat", "tie", "draw")
    private val BLUE_WORDS = listOf("azul", "blue")
    private val RED_WORDS = listOf("vermelho", "verm", "red")

    fun parse(text: String): List<Outcome> = parseLines(text.lines())

    fun parseLines(lines: List<String>): List<Outcome> = lines.mapNotNull(::classify)

    /** Returns the outcome for one line, or null if the line is not recognizable. */
    fun classify(raw: String): Outcome? {
        val s = raw.lowercase().trim()
        if (s.isEmpty()) return null
        return when {
            containsAny(s, TIE_WORDS) -> Outcome.TIE
            containsAny(s, BLUE_WORDS) -> Outcome.BLUE
            containsAny(s, RED_WORDS) -> Outcome.RED
            else -> null
        }
    }

    private fun containsAny(s: String, words: List<String>): Boolean =
        words.any { s.contains(it) }
}
