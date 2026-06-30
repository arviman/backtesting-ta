package com.arviman.ta.indicators

import com.arviman.ta.timeseries.Bar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Streaming volume profile.
 *
 * Accumulates traded volume per price bucket of size [tickSize]. Each bar
 * distributes its volume uniformly across the price levels it covered
 * ([low], [high]). For a bar that didn't move (high == low) the entire
 * volume goes to that single bucket.
 *
 * Once populated, derives:
 *   - **POC** (Point of Control): the single highest-volume price.
 *   - **VA** (Value Area): the contiguous range of buckets around POC
 *     holding at least [valueAreaFraction] (default 70%) of the total
 *     volume. Built by walking outward from POC, at each step adding the
 *     heavier adjacent bucket — the standard market-profile algorithm.
 *   - **HVN** (High Volume Nodes): local maxima — buckets whose volume
 *     exceeds all buckets within [hvnWindow] on each side. These act as
 *     magnets / support / targets.
 *   - **LVN** (Low Volume Nodes): local minima — buckets traversed quickly.
 *
 * Designed to be cheap to update incrementally so it can run inside a
 * streaming bar loop. Memory bounded by number of distinct buckets touched.
 *
 * Usage:
 * ```
 * val vp = VolumeProfile(tickSize = 1.0)
 * for (bar in stream) vp.addBar(bar)
 * println("POC=${vp.poc()}, VA=${vp.valueArea(0.70)}")
 * ```
 */
class VolumeProfile(
  val tickSize: Double = 1.0,
) {
  // Bucket index -> total volume. Index = floor(price / tickSize).
  private val buckets = HashMap<Int, Double>()
  private var totalVolume = 0.0

  /** Distribute [bar.volume] uniformly across [bar.low, bar.high]. */
  fun addBar(bar: Bar) {
    addBar(bar.high, bar.low, bar.volume)
  }

  fun addBar(high: Double, low: Double, volume: Double) {
    if (volume <= 0.0) return
    val lo = floor(low / tickSize).toInt()
    val hi = floor(high / tickSize).toInt()
    val nBuckets = hi - lo + 1
    if (nBuckets <= 0) return
    val per = volume / nBuckets
    for (k in lo..hi) {
      buckets.merge(k, per) { a, b -> a + b }
    }
    totalVolume += volume
  }

  fun reset() {
    buckets.clear()
    totalVolume = 0.0
  }

  fun isEmpty(): Boolean = buckets.isEmpty()
  fun size(): Int = buckets.size
  fun total(): Double = totalVolume

  /** Price at the center of [bucketIdx]. */
  private fun bucketPrice(bucketIdx: Int): Double = (bucketIdx + 0.5) * tickSize

  /** Returns the price (bucket centre) with maximum traded volume, or NaN if empty. */
  fun poc(): Double {
    if (buckets.isEmpty()) return Double.NaN
    var bestIdx = Int.MIN_VALUE
    var bestVol = Double.NEGATIVE_INFINITY
    for ((idx, vol) in buckets) {
      if (vol > bestVol) {
        bestVol = vol
        bestIdx = idx
      }
    }
    return bucketPrice(bestIdx)
  }

  /**
   * Returns (valueAreaLow, valueAreaHigh) — the contiguous price range that
   * contains at least [valueAreaFraction] of total volume around POC.
   *
   * Standard market-profile build: start at POC, then on each step extend
   * one bucket up OR one bucket down (whichever has more volume). Stop
   * when accumulated volume >= threshold.
   */
  fun valueArea(valueAreaFraction: Double = 0.70): Pair<Double, Double> {
    if (buckets.isEmpty()) return Double.NaN to Double.NaN
    val target = totalVolume * valueAreaFraction

    val sortedKeys = buckets.keys.sorted()
    val minIdx = sortedKeys.first()
    val maxIdx = sortedKeys.last()

    var pocIdx = Int.MIN_VALUE
    var pocVol = Double.NEGATIVE_INFINITY
    for ((idx, vol) in buckets) {
      if (vol > pocVol) { pocVol = vol; pocIdx = idx }
    }

    var lo = pocIdx
    var hi = pocIdx
    var accum = pocVol
    while (accum < target && (lo > minIdx || hi < maxIdx)) {
      val below = if (lo > minIdx) buckets[lo - 1] ?: 0.0 else -1.0
      val above = if (hi < maxIdx) buckets[hi + 1] ?: 0.0 else -1.0
      if (above >= below) {
        hi++
        accum += above.coerceAtLeast(0.0)
      } else {
        lo--
        accum += below.coerceAtLeast(0.0)
      }
    }
    return bucketPrice(lo) to bucketPrice(hi)
  }

  /**
   * Top N **high-volume nodes** (local maxima). Bucket qualifies if its
   * volume strictly exceeds every bucket within [window] on either side
   * that actually has data.
   */
  fun highVolumeNodes(window: Int = 3, topN: Int = 5): List<Pair<Double, Double>> {
    if (buckets.isEmpty()) return emptyList()
    val sortedKeys = buckets.keys.sorted()
    val result = mutableListOf<Pair<Int, Double>>()
    for ((i, idx) in sortedKeys.withIndex()) {
      val v = buckets[idx]!!
      val from = (i - window).coerceAtLeast(0)
      val to = (i + window).coerceAtMost(sortedKeys.size - 1)
      var isMax = true
      for (j in from..to) {
        if (j == i) continue
        val other = buckets[sortedKeys[j]]!!
        if (other >= v) { isMax = false; break }
      }
      if (isMax) result += idx to v
    }
    return result.sortedByDescending { it.second }
      .take(topN)
      .map { bucketPrice(it.first) to it.second }
  }

  /** Top N **low-volume nodes** (local minima with significant adjacent volume). */
  fun lowVolumeNodes(window: Int = 3, topN: Int = 5): List<Pair<Double, Double>> {
    if (buckets.isEmpty()) return emptyList()
    val sortedKeys = buckets.keys.sorted()
    val result = mutableListOf<Pair<Int, Double>>()
    for ((i, idx) in sortedKeys.withIndex()) {
      val v = buckets[idx]!!
      val from = (i - window).coerceAtLeast(0)
      val to = (i + window).coerceAtMost(sortedKeys.size - 1)
      var isMin = true
      var maxNeighbour = 0.0
      for (j in from..to) {
        if (j == i) continue
        val other = buckets[sortedKeys[j]]!!
        if (other <= v) { isMin = false; break }
        if (other > maxNeighbour) maxNeighbour = other
      }
      // Only keep "interesting" LVNs whose neighbours are at least 2× bigger.
      if (isMin && maxNeighbour >= 2.0 * v) result += idx to v
    }
    return result.sortedBy { it.second }
      .take(topN)
      .map { bucketPrice(it.first) to it.second }
  }

  /** Snapshot: returns (price, volume) pairs sorted by price. Use for plotting. */
  fun snapshot(): List<Pair<Double, Double>> =
    buckets.entries.map { bucketPrice(it.key) to it.value }
      .sortedBy { it.first }

  /** Price range covered, or (NaN, NaN) if empty. */
  fun priceRange(): Pair<Double, Double> {
    if (buckets.isEmpty()) return Double.NaN to Double.NaN
    val keys = buckets.keys
    return bucketPrice(keys.min()) to bucketPrice(keys.max())
  }
}
