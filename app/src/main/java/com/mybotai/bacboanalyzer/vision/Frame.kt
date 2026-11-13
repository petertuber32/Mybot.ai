package com.mybotai.bacboanalyzer.vision

/**
 * A downscaled screenshot frame in ARGB layout (0xAARRGGBB ints, the same
 * convention as android.graphics.Color). Pure JVM: the observation service
 * converts MediaProjection RGBA buffers into this format before analysis,
 * and unit tests build synthetic frames directly.
 *
 * Instances are immutable and reused only for the duration of one analysis.
 */
class Frame(val pixels: IntArray, val width: Int, val height: Int) {

    init {
        require(pixels.size == width * height) { "pixels size ${pixels.size} != ${width * height}" }
    }

    fun argb(x: Int, y: Int): Int = pixels[y * width + x]

    val area: Int get() = width * height
}

/** Basic color utilities shared by the vision pipeline. Pure JVM. */
object Colors {

    fun r(c: Int): Int = (c shr 16) and 0xFF
    fun g(c: Int): Int = (c shr 8) and 0xFF
    fun b(c: Int): Int = c and 0xFF
    fun a(c: Int): Int = (c shr 24) and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * HSV conversion. Hue in degrees [0, 360), saturation and value in [0, 1].
     * Standard formula (hue 0 for undefined / achromatic pixels).
     */
    fun hsv(c: Int): DoubleArray {
        val rf = r(c) / 255.0
        val gf = g(c) / 255.0
        val bf = b(c) / 255.0
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val d = max - min
        val v = max
        val s = if (max <= 0.0) 0.0 else d / max
        val h = when {
            d <= 0.0 -> 0.0
            max == rf -> 60.0 * (((gf - bf) / d) % 6.0)
            max == gf -> 60.0 * (((bf - rf) / d) + 2.0)
            else -> 60.0 * (((rf - gf) / d) + 4.0)
        }
        return doubleArrayOf(if (h < 0) h + 360.0 else h, s, v)
    }
}
