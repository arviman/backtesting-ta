@file:Suppress("unused")

package com.pschlup.ta.indicators

import com.pschlup.ta.timeseries.TimeSeries
import kotlin.math.PI
import kotlin.math.atan

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
  val atr = this.averageTrueRange(atrLength)
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
  val rma = src.modifiedMovingAverage(half)
  val atr = averageTrueRange(half)
  val top = Indicator { i -> rma[i + offset] + multiplier * atr[i] }
  val bottom = Indicator { i -> rma[i + offset] - multiplier * atr[i] }
  val median = Indicator { i -> (top[i] + bottom[i]) / 2 }
  return HurstChannel(top, bottom, median)
}
