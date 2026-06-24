package com.arviman.ta.strategy

import com.arviman.ta.indicators.cached
import com.arviman.ta.indicators.closePrice
import com.arviman.ta.indicators.sma
import com.arviman.ta.timeseries.TimeSeries

/**
 * Ted Zhang four-stage market on the 10/20/30/40 SMA ribbon.
 *
 *   Stage 2 (markup)   : price > SMA10 > SMA20 > SMA30 > SMA40   → long  side
 *   Stage 4 (markdown) : price < SMA10 < SMA20 < SMA30 < SMA40   → short side
 *
 * Entry: this bar is in the active stage and the previous bar wasn't.
 * Exit : this bar isn't in the active stage and the previous bar was.
 *
 * The structural trailing stop (SMA40) is supplied by the spec, not here.
 * Run one Strategy per side; the BackTester runs a single direction per spec.
 */
fun makeStageStrategy(series: TimeSeries, longSide: Boolean = true): Strategy {
  val close = series.closePrice
  val sma10 = close.sma(10).cached(series)
  val sma20 = close.sma(20).cached(series)
  val sma30 = close.sma(30).cached(series)
  val sma40 = close.sma(40).cached(series)

  val active = if (longSide) {
    Signal { i ->
      close[i] > sma10[i]
          && sma10[i] > sma20[i]
          && sma20[i] > sma30[i]
          && sma30[i] > sma40[i]
    }
  } else {
    Signal { i ->
      close[i] < sma10[i]
          && sma10[i] < sma20[i]
          && sma20[i] < sma30[i]
          && sma30[i] < sma40[i]
    }
  }

  val entry = Signal { i ->  active[i] && !active[i + 1] }
  val exit  = Signal { i -> !active[i] &&  active[i + 1] }

  return Strategy(entrySignal = entry, exitSignal = exit)
}
