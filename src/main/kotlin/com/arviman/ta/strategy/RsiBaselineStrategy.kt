package com.arviman.ta.strategy

import com.arviman.ta.indicators.cached
import com.arviman.ta.indicators.closePrice
import com.arviman.ta.indicators.rsi
import com.arviman.ta.timeseries.TimeSeries

/**
 * Dumb cycle baseline: buy when RSI crosses up out of oversold, sell when it
 * crosses down out of overbought. Pure cross only — misses the cycle if the
 * data window starts after RSI has already exited oversold.
 */
fun makeRsiBaselineStrategy(
  series: TimeSeries,
  rsiLength: Int = 14,
  oversold: Double = 30.0,
  overbought: Double = 80.0,
): Strategy {
  val rsi = series.closePrice.rsi(rsiLength).cached(series)
  return Strategy(
    entrySignal = rsi crossedOver oversold,
    exitSignal = rsi crossedUnder overbought,
  )
}

/**
 * Recovered-from-extremes baseline. Catches the cycle even if the cross
 * happened just before the data window: buys when RSI is currently above
 * [oversold] AND was at-or-below it within the last [lookbackBars]; sells
 * with the mirror condition against [overbought].
 */
fun makeRsiRecoveryStrategy(
  series: TimeSeries,
  rsiLength: Int = 14,
  oversold: Double = 30.0,
  overbought: Double = 80.0,
  lookbackBars: Int = 6,
): Strategy {
  val rsi = series.closePrice.rsi(rsiLength).cached(series)
  val recentlyOversold = Signal { i -> (1..lookbackBars).any { k -> rsi[i + k] <= oversold } }
  val recentlyOverbought = Signal { i -> (1..lookbackBars).any { k -> rsi[i + k] >= overbought } }
  return Strategy(
    entrySignal = (rsi isOver oversold) and recentlyOversold,
    exitSignal = (rsi isUnder overbought) and recentlyOverbought,
  )
}

/**
 * Midline cross — classic "trend up confirmed" entry. Faster signal than the
 * extreme-cross variant; more false starts but catches trends that don't
 * round-trip the full RSI range.
 */
fun makeRsiMidlineStrategy(
  series: TimeSeries,
  rsiLength: Int = 14,
  midline: Double = 50.0,
): Strategy {
  val rsi = series.closePrice.rsi(rsiLength).cached(series)
  return Strategy(
    entrySignal = rsi crossedOver midline,
    exitSignal = rsi crossedUnder midline,
  )
}
