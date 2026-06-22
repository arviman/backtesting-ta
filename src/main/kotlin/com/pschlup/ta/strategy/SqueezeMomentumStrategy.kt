package com.pschlup.ta.strategy

import com.pschlup.ta.indicators.Indicator
import com.pschlup.ta.indicators.cached
import com.pschlup.ta.indicators.closePrice
import com.pschlup.ta.indicators.highPrice
import com.pschlup.ta.indicators.highestValue
import com.pschlup.ta.indicators.linearRegressionSlope
import com.pschlup.ta.indicators.lowPrice
import com.pschlup.ta.indicators.lowestValue
import com.pschlup.ta.indicators.sma
import com.pschlup.ta.timeseries.TimeSeries

/**
 * Tunable inputs for the LazyBear Squeeze Momentum strategy. Defaults match
 * the ETH 1H optimization winners reported by the user.
 *
 * With `kcMult = 0.1` the squeeze release transition effectively never
 * fires (BB never sits inside the very narrow KC), so the entry signal
 * is the momentum histogram (`val`) crossing zero, not squeeze release.
 */
enum class SqzEntryMode { ZeroCross, Color }
enum class SqzExitMode { ZeroCross, Color }

data class SqueezeParams(
  val bbLength: Int = 10,
  val bbMult: Double = 2.1,
  val kcLength: Int = 20,
  val kcMult: Double = 0.1,
  val useHtf: Boolean = true,
  val htfLength: Int = 47,
  val entryMode: SqzEntryMode = SqzEntryMode.ZeroCross,
  val exitMode: SqzExitMode = SqzExitMode.ZeroCross,
)

/**
 * Builds a Squeeze Momentum strategy. [longSide] = true produces a
 * long-only Strategy (zero-cross up = entry, zero-cross down = exit);
 * false produces the symmetric short side.
 *
 * The Kotlin BackTester runs one direction per spec, so to exercise the
 * full both-sides strategy run two backtests with [longSide] flipped
 * and aggregate the trade ledgers.
 */
fun makeSqueezeMomentumStrategy(
  series: TimeSeries,
  params: SqueezeParams = SqueezeParams(),
  longSide: Boolean = true,
): Strategy {
  val close = series.closePrice
  val sqzVal = squeezeMomentumValue(series, params.kcLength).cached(series)

  // HTF MA trend bias on the same series (SMA of length htfLength).
  val htfMa = close.sma(params.htfLength).cached(series)
  val htfBullish: Signal = if (params.useHtf) close isOver htfMa else Signal { true }
  val htfBearish: Signal = if (params.useHtf) close isUnder htfMa else Signal { true }

  val crossUp = sqzVal crossedOver 0.0
  val crossDown = sqzVal crossedUnder 0.0

  // Color transitions (reverse-chrono: i+1 = prev bar, i+2 = bar before).
  //   lime  : val > 0 AND val > val[prev]  (positive & rising)
  //   green : val > 0 AND val < val[prev]  (positive & falling = decelerating bull)
  //   red   : val < 0 AND val < val[prev]  (negative & falling = accelerating bear)
  //   maroon: val < 0 AND val > val[prev]  (negative & rising  = decelerating bear)
  val limeNow  = Signal { i -> sqzVal[i] > 0 && sqzVal[i] > sqzVal[i + 1] }
  val limePrev = Signal { i -> sqzVal[i + 1] > 0 && sqzVal[i + 1] > sqzVal[i + 2] }
  val redNow   = Signal { i -> sqzVal[i] < 0 && sqzVal[i] < sqzVal[i + 1] }
  val redPrev  = Signal { i -> sqzVal[i + 1] < 0 && sqzVal[i + 1] < sqzVal[i + 2] }

  // Color entry = transition INTO the strong-momentum color.
  val colorEntryLong  = limeNow and !limePrev
  val colorEntryShort = redNow and !redPrev

  // Color exit = first sign of deceleration while still on our side of 0.
  //   long  exits when val decelerates while still positive  (lime → green)
  //   short exits when val decelerates while still negative  (red  → maroon)
  val colorExitLong  = Signal { i -> sqzVal[i] > 0 && sqzVal[i] < sqzVal[i + 1] }
  val colorExitShort = Signal { i -> sqzVal[i] < 0 && sqzVal[i] > sqzVal[i + 1] }

  val longEntry = when (params.entryMode) {
    SqzEntryMode.ZeroCross -> crossUp
    SqzEntryMode.Color -> colorEntryLong
  } and htfBullish
  val longExit = when (params.exitMode) {
    SqzExitMode.ZeroCross -> crossDown
    SqzExitMode.Color -> colorExitLong
  }
  val shortEntry = when (params.entryMode) {
    SqzEntryMode.ZeroCross -> crossDown
    SqzEntryMode.Color -> colorEntryShort
  } and htfBearish
  val shortExit = when (params.exitMode) {
    SqzExitMode.ZeroCross -> crossUp
    SqzExitMode.Color -> colorExitShort
  }

  return if (longSide) Strategy(entrySignal = longEntry, exitSignal = longExit)
         else          Strategy(entrySignal = shortEntry, exitSignal = shortExit)
}

/**
 * LazyBear's squeeze-momentum histogram value:
 *   val = linreg(close − avg(avg(highest(high, n), lowest(low, n)), sma(close, n)), n, 0)
 * where linreg(..., n, 0) is the value of the linear regression line at
 * the most recent bar of the n-bar window.
 *
 * For an n-point fit with x ∈ [0, n-1], linreg(at x=n-1) = ȳ + slope·(n-1)/2.
 */
fun squeezeMomentumValue(series: TimeSeries, kcLength: Int): Indicator {
  val close = series.closePrice
  val highest = series.highPrice.highestValue(kcLength)
  val lowest = series.lowPrice.lowestValue(kcLength)
  val smaClose = close.sma(kcLength)
  val midpoint = Indicator { i -> ((highest[i] + lowest[i]) / 2.0 + smaClose[i]) / 2.0 }
  val diff = Indicator { i -> close[i] - midpoint[i] }
  return diff.linearRegressionValueAtLatest(kcLength)
}

/**
 * Pine-style `linreg(src, length, 0)`: value of the linear regression line
 * over the last [length] bars, evaluated at the most recent bar.
 */
fun Indicator.linearRegressionValueAtLatest(length: Int): Indicator {
  val slope = linearRegressionSlope(length)
  val avg = sma(length)
  val offset = (length - 1) / 2.0
  return Indicator { i -> avg[i] + slope[i] * offset }
}
