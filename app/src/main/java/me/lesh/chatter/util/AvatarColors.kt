package me.lesh.chatter.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The two colours a picture is mostly made of, for a banner behind it. Pixels are ARGB ints, as
 * `Bitmap.getPixels` gives them. Hues are sorted into [HUE_BUCKETS] buckets, weighted by how vivid
 * a pixel is, so a small bright patch beats a large grey background.
 */
object AvatarColors {
    private const val HUE_BUCKETS = 12

    /** Null for a picture with no visible pixels. A grey picture gives its average, then darker. */
    fun of(pixels: IntArray): Pair<Int, Int>? {
        val weight = FloatArray(HUE_BUCKETS)
        val sums = Array(HUE_BUCKETS) { LongArray(4) }
        val all = LongArray(4)
        for (p in pixels) {
            if (p ushr 24 < 128) continue
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            all.add(r, g, b)
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            if (hi == 0) continue
            val saturation = (hi - lo) / hi.toFloat()
            val value = hi / 255f
            // Near-grey and near-black pixels have no hue worth showing.
            if (saturation < 0.2f || value < 0.15f) continue
            val bucket = (hue(r, g, b, hi, lo) / 360f * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            weight[bucket] += saturation * value
            sums[bucket].add(r, g, b)
        }
        if (all[3] == 0L) return null
        val ranked = weight.indices.filter { weight[it] > 0f }.sortedByDescending { weight[it] }
        val first = ranked.firstOrNull() ?: return all.average().let { it to darker(it) }
        // A neighbouring hue looks like the same colour; the second one should differ.
        val second = ranked.firstOrNull { bucketDistance(it, first) >= 2 && weight[it] >= weight[first] * 0.08f }
        val main = sums[first].average()
        return main to (second?.let { sums[it].average() } ?: darker(main))
    }

    private fun LongArray.add(r: Int, g: Int, b: Int) {
        this[0] += r.toLong(); this[1] += g.toLong(); this[2] += b.toLong(); this[3]++
    }

    private fun LongArray.average(): Int =
        (0xFF shl 24) or ((this[0] / this[3]).toInt() shl 16) or ((this[1] / this[3]).toInt() shl 8) or (this[2] / this[3]).toInt()

    private fun darker(color: Int): Int {
        fun channel(shift: Int) = ((color shr shift and 0xFF) * 0.55f).toInt() shl shift
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    private fun bucketDistance(a: Int, b: Int): Int = abs(a - b).let { min(it, HUE_BUCKETS - it) }

    /** In degrees, 0 until 360. */
    private fun hue(r: Int, g: Int, b: Int, hi: Int, lo: Int): Float {
        val d = (hi - lo).toFloat()
        if (d == 0f) return 0f
        val h = when (hi) {
            r -> 60f * ((g - b) / d)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return if (h < 0f) h + 360f else h
    }
}
