package com.arviman.ta.strategy

import com.arviman.ta.indicators.cached
import com.arviman.ta.indicators.closePrice
import com.arviman.ta.indicators.sma
import com.arviman.ta.timeseries.TimeSeries

/**
 * Cycle-bottom mean reversion. Enters long when:
 *   close < SMA([longMaLen]) × (1 − [drawdownThreshold])      // price is meaningfully below trend
 *   AND close > close[recentBars]                              // price has stopped falling
 *
 * Exits long when:
 *   close > SMA([longMaLen]) × (1 + [recoveryThreshold])      // price has reverted to / past trend
 *
 * Picks a different fight from JAMA: instead of confirming momentum, it bets
 * that an extreme deviation from the long-run mean will close. Designed for
 * cyclical assets where the entry signal needs to fire *before* momentum
 * appears, not after.
 */
fun makeMeanReversionStrategy(
  strategy: TimeSeries,
  longMaLen: Int = 200,
  drawdownThreshold: Double = 0.30,
  recoveryThreshold: Double = 0.0,
  recentBars: Int = 5,
): Strategy {
  val close = strategy.closePrice
  val longMa = close.sma(longMaLen).cached(strategy)

  val entry = Signal { i ->
    val curr = close[i]
    val ma = longMa[i]
    curr < ma * (1 - drawdownThreshold) && curr > close[i + recentBars]
  }
  val exit = Signal { i ->
    close[i] > longMa[i] * (1 + recoveryThreshold)
  }

  return Strategy(entrySignal = entry, exitSignal = exit)
}
