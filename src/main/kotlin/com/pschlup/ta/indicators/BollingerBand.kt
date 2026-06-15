@file:Suppress("unused")

package com.pschlup.ta.indicators

import kotlin.math.sqrt

/**
 * Population standard deviation of this indicator over [length] bars.
 * Matches the formula used by most charting platforms for Bollinger Bands.
 */
fun Indicator.standardDeviation(length: Int = 20): Indicator {
  val sma = simpleMovingAverage(length)
  return Indicator { i ->
    val avg = sma[i]
    var sumSq = 0.0
    for (k in i until i + length) {
      val d = this[k] - avg
      sumSq += d * d
    }
    sqrt(sumSq / length)
  }
}

/** SMA-centered Bollinger Bands. */
data class BollingerBands(val upper: Indicator, val middle: Indicator, val lower: Indicator)

fun Indicator.bollingerBands(length: Int = 20, multiplier: Double = 2.0): BollingerBands {
  val mid = simpleMovingAverage(length)
  val sd = standardDeviation(length)
  return BollingerBands(
    upper = Indicator { i -> mid[i] + multiplier * sd[i] },
    middle = mid,
    lower = Indicator { i -> mid[i] - multiplier * sd[i] },
  )
}
