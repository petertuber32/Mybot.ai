package com.mybotai.bacboanalyzer.models

/** Result of a single Bac Bo round. */
enum class Outcome { BLUE, RED, TIE }

/** How the result was captured. */
enum class Source { MANUAL, OCR }

/** A single recorded round. Stored as JSON on device. */
data class Round(
    val timestamp: Long,
    val outcome: Outcome,
    val source: Source,
    val note: String? = null
)
