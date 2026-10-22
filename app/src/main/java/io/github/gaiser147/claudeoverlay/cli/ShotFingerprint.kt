package io.github.gaiser147.claudeoverlay.cli

/**
 * Perceptual fingerprint of a screenshot, ported from the desktop overlay's _shot_phash.
 *
 * Every message auto-attaches a capture that then sits in the context for good, and a live
 * screen is never bit-for-bit still, so byte equality almost never says "same screen". A
 * difference hash over a coarse luma grid does: each bit says "this cell is brighter than the
 * one to its right". A blinking caret or a clock digit averages away inside one cell, while
 * anything worth re-sending moves whole regions and flips bits by the dozen.
 */
object ShotFingerprint {
    const val SIDE = 32
    /** Differing bits (of 1024) still counted as the same screen; the desktop default. */
    const val SAME_SCREEN_BITS = 2

    /**
     * @param argb row-major ARGB pixels, as from Bitmap.getPixels.
     * @return 1024 bits packed into 16 longs.
     */
    fun of(argb: IntArray, width: Int, height: Int): LongArray {
        require(width > SIDE && height >= SIDE && argb.size >= width * height)
        val cols = SIDE + 1
        // Box-average luma per grid cell, so a sharp edge can't swing a whole cell.
        val sum = LongArray(cols * SIDE)
        val count = IntArray(cols * SIDE)
        for (y in 0 until height) {
            val gy = y * SIDE / height
            val row = y * width
            for (x in 0 until width) {
                val p = argb[row + x]
                val luma = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
                val cell = gy * cols + x * cols / width
                sum[cell] += luma.toLong()
                count[cell]++
            }
        }
        val bits = LongArray(SIDE * SIDE / 64)
        var bit = 0
        for (gy in 0 until SIDE) {
            for (gx in 0 until SIDE) {
                val a = sum[gy * cols + gx] * count[gy * cols + gx + 1]
                val b = sum[gy * cols + gx + 1] * count[gy * cols + gx]
                if (a > b) bits[bit / 64] = bits[bit / 64] or (1L shl (bit % 64))
                bit++
            }
        }
        return bits
    }

    fun distance(a: LongArray, b: LongArray): Int =
        a.indices.sumOf { java.lang.Long.bitCount(a[it] xor b[it]) }

    fun sameScreen(a: LongArray?, b: LongArray?): Boolean =
        a != null && b != null && distance(a, b) <= SAME_SCREEN_BITS
}
