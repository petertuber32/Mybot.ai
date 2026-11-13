package com.mybotai.bacboanalyzer.vision

import kotlin.math.max
import kotlin.math.min

/**
 * Recognizes the four Bac Bo dice (2 blue + 2 red) and their pip values
 * directly from pixels — no network, no external ML model.
 *
 * Pipeline (pure JVM, deterministic, unit tested with synthetic frames):
 *  1. Classify every pixel as BLUE-ish / RED-ish / WHITE-ish / other (HSV).
 *  2. Connected components (8-connectivity) on the colored mask give die
 *     candidates; the two largest plausible blobs per color are the dice.
 *  3. Inside each die bounding box (expanded, rim trimmed) the white pips
 *     are counted with a second component pass; the count 1..6 is the value.
 *  4. A pip-layout score compares detected pip positions with the standard
 *     die face layouts and feeds the reported confidence.
 *
 * If anything is uncertain (missing die, unknown pip count, clutter) the
 * confidence is capped so the state machine asks for confirmation instead
 * of inventing a result.
 */
class DiceImageAnalyzer(
    /** Die bbox area must be at least this fraction of the frame area. */
    private val minDieAreaRatio: Double = 0.0006,
    /** Die bbox area must be at most this fraction of the frame area. */
    private val maxDieAreaRatio: Double = 0.30,
    /** Hue range (degrees) accepted as the blue die color. */
    private val blueHueMin: Double = 180.0,
    private val blueHueMax: Double = 265.0,
    /** Hue range accepted as the red die color (two arcs around 0). */
    private val redHueMaxLow: Double = 22.0,
    private val redHueMinHigh: Double = 328.0
) {

    enum class Pix { BLUE, RED, WHITE, OTHER }

    data class Blob(
        val isBlue: Boolean,
        val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
        val area: Int
    ) {
        val centerX: Int get() = (minX + maxX) / 2
        val centerY: Int get() = (minY + maxY) / 2
        val bw: Int get() = maxX - minX + 1
        val bh: Int get() = maxY - minY + 1
        val aspect: Double get() = if (min(bw, bh) == 0) 0.0 else max(bw, bh).toDouble() / min(bw, bh)
    }

    data class DieResult(val value: Int?, val layoutScore: Double)

    fun analyze(frame: Frame): DiceRead {
        val w = frame.width
        val h = frame.height
        val n = frame.area
        val cls = ByteArray(n)
        for (i in 0 until n) cls[i] = classify(frame.pixels[i])

        // Connected components over BLUE+RED classes.
        val labels = IntArray(n) // 0 = unvisited
        val stack = IntArray(n)
        val blobs = mutableListOf<Blob>()
        var nextLabel = 0
        for (start in 0 until n) {
            val c = cls[start]
            if ((c == BLUE_CLS || c == RED_CLS) && labels[start] == 0) {
                nextLabel++
                var sp = 0
                stack[sp++] = start
                labels[start] = nextLabel
                var minX = start % w; var maxX = minX
                var minY = start / w; var maxY = minY
                var area = 0
                while (sp > 0) {
                    val idx = stack[--sp]
                    val x = idx % w
                    val y = idx / w
                    area++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    // 8-connectivity
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            val nIdx = ny * w + nx
                            if (labels[nIdx] == 0 && cls[nIdx] == c) {
                                labels[nIdx] = nextLabel
                                stack[sp++] = nIdx
                            }
                        }
                    }
                }
                blobs.add(Blob(isBlue = c == BLUE_CLS, minX, minY, maxX, maxY, area))
            }
        }

        val blueDice = pickDice(blobs.filter { it.isBlue }, n)
        val redDice = pickDice(blobs.filter { !it.isBlue }, n)

        val b1 = blueDice.getOrNull(0)?.let { readDie(it, frame, cls) }
        val b2 = blueDice.getOrNull(1)?.let { readDie(it, frame, cls) }
        val r1 = redDice.getOrNull(0)?.let { readDie(it, frame, cls) }
        val r2 = redDice.getOrNull(1)?.let { readDie(it, frame, cls) }

        val foundDice = listOf(b1, b2, r1, r2).count { it != null }
        val known = listOf(b1, b2, r1, r2).filterNotNull().filter { it.value != null }
        val knownCount = known.size

        var confidence = 0.25 * (foundDice / 4.0) + 0.45 * (knownCount / 4.0)
        val meanLayout = if (known.isEmpty()) 0.0 else known.sumOf { it.layoutScore } / known.size
        confidence += 0.30 * meanLayout
        if (knownCount < 4) confidence = minOf(confidence, CAP_INCOMPLETE)

        return DiceRead(
            b1 = b1?.value, b2 = b2?.value, r1 = r1?.value, r2 = r2?.value,
            blueDiceFound = blueDice.size, redDiceFound = redDice.size,
            confidence = confidence.coerceIn(0.0, 1.0)
        )
    }

    /** Chooses the two most plausible dice among the blobs of one color. */
    private fun pickDice(blobs: List<Blob>, frameArea: Int): List<Blob> {
        val minArea = minDieAreaRatio * frameArea
        val maxArea = maxDieAreaRatio * frameArea
        return blobs
            .filter { it.area >= minArea && it.area <= maxArea && it.aspect <= 2.9 }
            .sortedByDescending { it.area }
            .fold(mutableListOf<Blob>()) { acc, b ->
                if (acc.size < 2 && acc.none { overlaps(it, b) }) acc.add(b)
                acc
            }
            .sortedBy { it.centerX }
    }

    private fun overlaps(a: Blob, b: Blob): Boolean =
        a.minX <= b.maxX && b.minX <= a.maxX && a.minY <= b.maxY && b.minY <= a.maxY

    /** Counts the white pips inside one die blob and scores the layout. */
    private fun readDie(blob: Blob, frame: Frame, cls: ByteArray): DieResult {
        val w = frame.width
        val h = frame.height
        // Expand bbox by 12% (rounded corners), then trim 6% inside to cut rim glare.
        val bw = blob.bw
        val bh = blob.bh
        val ex = (bw * 0.12).toInt()
        val ey = (bh * 0.12).toInt()
        val x0 = (blob.minX - ex).coerceAtLeast(0)
        val y0 = (blob.minY - ey).coerceAtLeast(0)
        val x1 = (blob.maxX + ex).coerceAtMost(w - 1)
        val y1 = (blob.maxY + ey).coerceAtMost(h - 1)
        val trimX = (bw * 0.06).toInt()
        val trimY = (bh * 0.06).toInt()
        val ix0 = x0 + trimX
        val iy0 = y0 + trimY
        val ix1 = x1 - trimX
        val iy1 = y1 - trimY
        if (ix1 <= ix0 || iy1 <= iy0) return DieResult(null, 0.0)

        val cw = ix1 - ix0 + 1
        val ch = iy1 - iy0 + 1
        val crop = BooleanArray(cw * ch)
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                val idx = (iy0 + cy) * w + (ix0 + cx)
                crop[cy * cw + cx] = cls[idx] == WHITE_CLS
            }
        }

        // Pip components (8-connectivity), must not touch the crop border.
        val seen = BooleanArray(cw * ch)
        val stack = IntArray(cw * ch)
        val pips = mutableListOf<Triple<Int, Int, Int>>() // cx, cy, area
        val dieArea = bw * bh
        val minPip = maxOf(3, (dieArea * 0.0035).toInt())
        val maxPip = (dieArea * 0.35).toInt()
        for (start in 0 until crop.size) {
            if (!crop[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var sx = 0; var sy = 0; var area = 0
            var touches = false
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % cw
                val y = idx / cw
                area++
                sx += x; sy += y
                if (x == 0 || y == 0 || x == cw - 1 || y == ch - 1) touches = true
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= ch) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= cw) continue
                        val nIdx = ny * cw + nx
                        if (crop[nIdx] && !seen[nIdx]) {
                            seen[nIdx] = true
                            stack[sp++] = nIdx
                        }
                    }
                }
            }
            if (!touches && area in minPip..maxPip) {
                pips.add(Triple(sx / area, sy / area, area))
            }
        }

        // Pip counting with MERGE TOLERANCE: on small/low-quality dice the
        // antialiased pips can touch and form one component. The median pip
        // area estimates the single-pip size; each component contributes
        // round(area / median) pips (max 3, otherwise treated as glare/noise).
        if (pips.isEmpty()) return DieResult(null, 0.0)
        val sortedAreas = pips.map { it.third }.sorted()
        val medianArea = sortedAreas[(sortedAreas.size - 1) / 2].coerceAtLeast(1)
        var count = 0
        for ((_, _, area) in pips) {
            val contrib = Math.round(area.toDouble() / medianArea).toInt().coerceAtLeast(1)
            if (contrib > 3) return DieResult(null, 0.0)
            count += contrib
        }
        if (count !in 1..6) return DieResult(null, 0.0)
        // Layout score against standard positions.
        val fx = bw.toDouble()
        val fy = bh.toDouble()
        val norm = pips.map { (px, py, _) ->
            ((ix0 + px) - blob.minX) / fx to ((iy0 + py) - blob.minY) / fy
        }
        val score = maxOf(layoutScore(count, norm, false), layoutScore(count, norm, true))
        return DieResult(count, score)
    }

    /**
     * Mean nearest-expected-position distance, converted to a 0..1 score.
     * When [horizontal] is true the "6" face is evaluated as two rows of
     * three instead of two columns of three.
     */
    private fun layoutScore(value: Int, pips: List<Pair<Double, Double>>, horizontal: Boolean): Double {
        val positions: List<Pair<Double, Double>> = when (value) {
            1 -> listOf(0.5 to 0.5)
            2 -> listOf(0.28 to 0.28, 0.72 to 0.72)
            3 -> listOf(0.28 to 0.28, 0.5 to 0.5, 0.72 to 0.72)
            4 -> listOf(0.28 to 0.28, 0.72 to 0.28, 0.28 to 0.72, 0.72 to 0.72)
            5 -> listOf(0.28 to 0.28, 0.72 to 0.28, 0.5 to 0.5, 0.28 to 0.72, 0.72 to 0.72)
            6 -> if (horizontal)
                listOf(0.28 to 0.28, 0.5 to 0.28, 0.72 to 0.28, 0.28 to 0.72, 0.5 to 0.72, 0.72 to 0.72)
            else
                listOf(0.28 to 0.28, 0.28 to 0.5, 0.28 to 0.72, 0.72 to 0.28, 0.72 to 0.5, 0.72 to 0.72)
            else -> return 0.0
        }
        if (pips.size != value) return 0.0
        val mean = pips.sumOf { (x, y) ->
            positions.minOf { (ex, ey) ->
                val dx = x - ex
                val dy = y - ey
                kotlin.math.sqrt(dx * dx + dy * dy)
            }
        } / pips.size
        // Grid spacing is ~0.22; a correct face matches within ~0.08.
        return (1.0 - mean / 0.28).coerceIn(0.0, 1.0)
    }

    private fun classify(c: Int): Byte {
        val (hue, sat, value) = Colors.hsv(c)
        return when {
            value in 0.22..1.01 && sat >= 0.30 && hue in blueHueMin..blueHueMax -> BLUE_CLS
            value in 0.22..1.01 && sat >= 0.30 && (hue <= redHueMaxLow || hue >= redHueMinHigh) -> RED_CLS
            value >= 0.62 && sat <= 0.40 -> WHITE_CLS
            else -> OTHER_CLS
        }
    }

    companion object {
        private const val BLUE_CLS: Byte = 1
        private const val RED_CLS: Byte = 2
        private const val WHITE_CLS: Byte = 3
        private const val OTHER_CLS: Byte = 0

        /** Confidence is hard-capped when any die value is missing. */
        const val CAP_INCOMPLETE = 0.55
    }
}
