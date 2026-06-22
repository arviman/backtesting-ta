@file:Suppress("unused")

package com.arviman.ta.indicators

import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeSeries
import kotlin.math.PI
import kotlin.math.atan

/**
 * Per-bar memoizing wrapper with a shifting window.
 *
 * Cache is keyed by reverse-chronological index (`[0]` = latest). When a new
 * bar arrives, every index slides by +1 (old `[0]` is now `[1]`, etc.), so we
 * rebuild the map with shifted keys rather than clearing — only the fresh
 * `[0]` needs (re)computing. Bounded by [windowSize] so it can't grow forever.
 *
 * Required to make chained recursive indicators (`ema(ema(rsi))`) tractable.
 */
fun Indicator.cached(series: TimeSeries, windowSize: Int = 256): Indicator {
  var cache = HashMap<Int, Double>()
  var lastBar: Bar? = null
  return Indicator { i ->
    val current = series.latestBar
    if (current !== lastBar) {
      if (lastBar != null && cache.isNotEmpty()) {
        val shifted = HashMap<Int, Double>(cache.size * 2)
        for ((k, v) in cache) {
          val nk = k + 1
          if (nk < windowSize) shifted[nk] = v
        }
        cache = shifted
      }
      lastBar = current
    }
    cache.getOrPut(i) { this.valueAt(i) }
  }
}

/** Bar-over-bar change: x[index] - x[index+1]. Mirrors Pine's `ta.change`. */
fun Indicator.change(): Indicator = Indicator { i -> this[i] - this[i + 1] }

/**
 * Zero-lag EMA (Pine-style): ema(2*src - src[lag], length) where lag = (length-1)/2.
 * ponytail: alphaFactor from Pine omitted (only used with value 1 in JAMA).
 */
fun Indicator.zema(length: Int): Indicator {
  val lag = if (length > 1) (length - 1) / 2 else 0
  val adjusted = Indicator { i -> 2 * this[i] - this[i + lag] }
  return adjusted.ema(length)
}

/**
 * Slope of [src] in degrees, normalized by ATR(14) on this series.
 * Mirrors the Pine `angle()` helper used by JAMA.
 */
fun TimeSeries.angle(src: Indicator, atrLength: Int = 14): Indicator {
  val atr = this.averageTrueRange(atrLength).cached(this)
  val rad2deg = 180.0 / PI
  return Indicator { i ->
    val a = atr[i]
    if (a == 0.0) 0.0 else rad2deg * atan((src[i] - src[i + 1]) / a)
  }
}

/** LazyBear's Hurst Cycle Channel Clone: ATR-banded RMA, offset by half the half-length. */
data class HurstChannel(val top: Indicator, val bottom: Indicator, val median: Indicator)

fun TimeSeries.hurstChannel(length: Int = 10, multiplier: Double = 1.0): HurstChannel {
  val half = length / 2          // scl / mcl in Pine
  val offset = half / 2          // scl_2 / mcl_2 in Pine
  val src = closePrice
  val rma = src.modifiedMovingAverage(half).cached(this)
  val atr = averageTrueRange(half).cached(this)
  val top = Indicator { i -> rma[i + offset] + multiplier * atr[i] }
  val bottom = Indicator { i -> rma[i + offset] - multiplier * atr[i] }
  val median = Indicator { i -> (top[i] + bottom[i]) / 2 }
  return HurstChannel(top, bottom, median)
}
