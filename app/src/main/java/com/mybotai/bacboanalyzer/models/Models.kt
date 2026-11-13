package com.mybotai.bacboanalyzer.models

/** Result of a single Bac Bo round. */
enum class Outcome { BLUE, RED, TIE }

/**
 * How the result was captured.
 * VISION = recognized continuously from the screen (MediaProjection);
 * rounds committed after explicit user confirmation also use VISION.
 */
enum class Source { MANUAL, OCR, VISION }

/** A single recorded round. Stored as JSON on device. */
data class Round(
    val timestamp: Long,
    val outcome: Outcome,
    val source: Source,
    val note: String? = null
)
