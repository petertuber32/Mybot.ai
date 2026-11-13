package com.mybotai.bacboanalyzer.vision

/**
 * Result of one dice recognition pass. Values are nullable: null means
 * "not recognized" and the state machine will ask for confirmation or
 * retry instead of inventing a result.
 */
data class DiceRead(
    val b1: Int?,
    val b2: Int?,
    val r1: Int?,
    val r2: Int?,
    val blueDiceFound: Int,
    val redDiceFound: Int,
    val confidence: Double,
    val timestamp: Long = 0L
) {
    /** Number of dice values that were successfully read (0..4). */
    val knownCount: Int get() = listOf(b1, b2, r1, r2).count { it != null }

    /** True when exactly 2 blue + 2 red dice were located and all values read. */
    val readable: Boolean get() = blueDiceFound == 2 && redDiceFound == 2 && knownCount == 4

    /** Stable identity of the read, used for duplicate-round detection. */
    val signature: String
        get() = "${b1 ?: -1},${b2 ?: -1},${r1 ?: -1},${r2 ?: -1}"

    val blueSum: Int? get() = if (b1 != null && b2 != null) b1 + b2 else null
    val redSum: Int? get() = if (r1 != null && r2 != null) r1 + r2 else null

    companion object {
        /** Reads with confidence >= this are committed automatically. */
        const val AUTO_ACCEPT = 0.62

        /** Reads below this confidence are ignored entirely. */
        const val CONFIRM_MIN = 0.30
    }
}
