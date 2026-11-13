package com.mybotai.bacboanalyzer.vision

/**
 * Synthetic Bac Bo table frames for JVM unit tests: dark table background,
 * colored dice squares with white pips at the standard 3x3 positions.
 * Deterministic — no randomness, so assertions are exact.
 */
object TestFrames {

    val BG = Colors.rgb(18, 53, 31)
    val BLUE = Colors.rgb(46, 107, 255)
    val RED = Colors.rgb(226, 59, 59)
    val PIP = Colors.rgb(255, 255, 255)

    class Builder(private val w: Int, private val h: Int) {
        private val px = IntArray(w * h) { BG }

        /** Draws one die square with its pips (top-left at x0,y0). */
        fun die(x0: Int, y0: Int, size: Int, blue: Boolean, value: Int): Builder {
            require(value in 1..6) { "test die value must be 1..6" }
            val color = if (blue) BLUE else RED
            for (y in y0 until y0 + size) {
                if (y < 0 || y >= h) continue
                for (x in x0 until x0 + size) {
                    if (x < 0 || x >= w) continue
                    px[y * w + x] = color
                }
            }
            val r = (size * 0.09).toInt().coerceAtLeast(2)
            val m = size * 0.28
            val c = size * 0.5
            val e = size - m
            val positions: List<Pair<Double, Double>> = when (value) {
                1 -> listOf(c to c)
                2 -> listOf(m to m, e to e)
                3 -> listOf(m to m, c to c, e to e)
                4 -> listOf(m to m, e to m, m to e, e to e)
                5 -> listOf(m to m, e to m, c to c, m to e, e to e)
                else -> listOf(m to m, e to m, m to c, e to c, m to e, e to e) // 6
            }
            for ((fx, fy) in positions) {
                val cx = x0 + fx.toInt()
                val cy = y0 + fy.toInt()
                for (y in cy - r..cy + r) {
                    if (y < 0 || y >= h) continue
                    for (x in cx - r..cx + r) {
                        if (x < 0 || x >= w) continue
                        val dx = x - cx
                        val dy = y - cy
                        if (dx * dx + dy * dy <= r * r) px[y * w + x] = PIP
                    }
                }
            }
            return this
        }

        fun build(): Frame = Frame(px, w, h)
    }
}
