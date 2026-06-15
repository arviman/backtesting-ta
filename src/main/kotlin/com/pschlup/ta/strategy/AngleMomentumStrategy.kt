package com.pschlup.ta.strategy

import com.pschlup.ta.indicators.angle
import com.pschlup.ta.indicators.cached
import com.pschlup.ta.indicators.change
import com.pschlup.ta.indicators.closePrice
import com.pschlup.ta.indicators.sma
import com.pschlup.ta.timeseries.TimeSeries

/**
 * Trend-with-exhaustion strategy.
 *
 * "Angle" is the MA's slope in degrees, normalized by ATR(14). "Change in
 * angle" is the bar-over-bar delta of that slope — i.e. whether the trend is
 * accelerating (positive) or decelerating toward flat (negative).
 *
 * Entry: angle > [entryAngleThreshold] AND change-in-angle > [entryAccelThreshold]
 *        (trend up AND still accelerating up — confirms momentum has wind in sails)
 * Exit:  change-in-angle < [exitDecelThreshold]
 *        (trend decelerating — exhaustion before peak, exit while still profitable)
 *
 * Extends JAMA's "enter on strong trend" with an earlier exit timed to the
 * second derivative rather than waiting for the angle itself to flatten.
 */
fun makeAngleMomentumStrategy(
  series: TimeSeries,
  maLength: Int = 20,
  atrLength: Int = 14,
  entryAngleThreshold: Double = 0.5,
  entryAccelThreshold: Double = 0.0,
  exitDecelThreshold: Double = 0.0,
): Strategy {
  val close = series.closePrice
  val ma = close.sma(maLength).cached(series)
  val angle = series.angle(ma, atrLength).cached(series)
  val angleChange = angle.change()

  val entry = (angle isOver entryAngleThreshold) and (angleChange isOver entryAccelThreshold)
  val exit = angleChange isUnder exitDecelThreshold

  return Strategy(entrySignal = entry, exitSignal = exit)
}
