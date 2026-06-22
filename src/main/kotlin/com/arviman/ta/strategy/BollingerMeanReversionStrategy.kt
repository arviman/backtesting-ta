package com.arviman.ta.strategy

import com.arviman.ta.indicators.bollingerBands
import com.arviman.ta.indicators.cached
import com.arviman.ta.indicators.closePrice
import com.arviman.ta.indicators.rsi
import com.arviman.ta.timeseries.TimeSeries

/**
 * Long-only Bollinger Band mean reversion.
 *
 * Entry: close ≤ lower band AND (optionally) RSI < [rsiOversold]
 * Exit:  close ≥ middle band (price reverted to mean)
 *
 * Without a regime filter mean reversion gets steamrolled in strong trends.
 * The RSI gate is the cheap regime check: only enter when price is *also*
 * oversold on momentum, not just stretched on volatility.
 */
fun makeBollingerMeanReversionStrategy(
  series: TimeSeries,
  bbLength: Int = 20,
  bbMultiplier: Double = 2.0,
  rsiLength: Int = 14,
  rsiOversold: Double = 30.0,
  useRsiFilter: Boolean = true,
): Strategy {
  val close = series.closePrice
  val bb = close.bollingerBands(bbLength, bbMultiplier)
  val mid = bb.middle.cached(series)
  val lower = bb.lower.cached(series)
  val rsi = close.rsi(rsiLength).cached(series)

  val belowLower = close isUnder lower
  val entry = if (useRsiFilter) belowLower and (rsi isUnder rsiOversold) else belowLower
  val exit = close isOver mid

  return Strategy(entrySignal = entry, exitSignal = exit)
}
