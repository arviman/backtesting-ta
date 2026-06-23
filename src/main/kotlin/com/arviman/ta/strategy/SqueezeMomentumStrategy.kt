package com.arviman.ta.strategy

import com.arviman.ta.indicators.Indicator
import com.arviman.ta.indicators.cached
import com.arviman.ta.indicators.closePrice
import com.arviman.ta.indicators.highPrice
import com.arviman.ta.indicators.highestValue
import com.arviman.ta.indicators.linearRegressionSlope
import com.arviman.ta.indicators.lowPrice
import com.arviman.ta.indicators.lowestValue
import com.arviman.ta.indicators.sma
import com.arviman.ta.indicators.standardDeviation
import com.arviman.ta.indicators.trueRange
import com.arviman.ta.timeseries.TimeSeries

/**
 * Tunable inputs for the LazyBear Squeeze Momentum strategy. Defaults match
 * the ETH 1H optimization winners reported by the user.
 *
 * With `kcMult = 0.1` the squeeze release transition effectively never
 * fires (BB never sits inside the very narrow KC), so the entry signal
 * is the momentum histogram (`val`) crossing zero, not squeeze release.
 */
enum class SqzEntryMode {
  ZeroCross,           // val crosses 0
  Color,               // transition INTO lime (long) / red (short)
  ContinuationLime,    // every bar val > 0 && rising (cTrader bot's mode)
}
enum class SqzExitMode {
  ZeroCross,
  Color,                       // first sign of decel while still on momentum side
  ZeroCrossPlusContinuation,   // val on other side of 0 AND still moving away (cTrader's exit)
}

data class SqueezeParams(
  val bbLength: Int = 10,
  val bbMult: Double = 2.1,
  val kcLength: Int = 20,
  val kcMult: Double = 0.1,
  val useHtf: Boolean = true,
  val htfLength: Int = 47,
  val entryMode: SqzEntryMode = SqzEntryMode.ZeroCross,
  val exitMode: SqzExitMode = SqzExitMode.ZeroCross,
  /** When true, entries require `sqzOff` = BB expanded outside KC (volatility expansion). */
  val requireSqzOff: Boolean = false,
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

  // ContinuationLime = every bar that is lime (cTrader bot's entry).
  val contEntryLong = limeNow
  val contEntryShort = redNow

  // ZeroCrossPlusContinuation = val on other side of 0 AND still moving away.
  //   long exit:  val < 0 AND val < prev (crossed below AND falling)
  //   short exit: val > 0 AND val > prev (crossed above AND rising)
  val zeroPlusFallLong = Signal { i -> sqzVal[i] < 0 && sqzVal[i] < sqzVal[i + 1] }
  val zeroPlusRiseShort = Signal { i -> sqzVal[i] > 0 && sqzVal[i] > sqzVal[i + 1] }

  // Squeeze-off gate: BB has expanded outside KC (volatility expanding).
  // Only meaningful when kcMult is wide enough that BB can actually sit
  // inside KC; with kcMult=0.1 this is essentially always true so the
  // gate is a no-op. With kcMult=1.4 (cTrader prop-firm config) it
  // filters out compressed/quiet phases.
  val sqzOffGate: Signal = if (params.requireSqzOff) {
    val basis = close.sma(params.bbLength).cached(series)
    val sd = close.standardDeviation(params.bbLength).cached(series)
    val upperBB = Indicator { i -> basis[i] + params.bbMult * sd[i] }
    val lowerBB = Indicator { i -> basis[i] - params.bbMult * sd[i] }
    val kcMa = close.sma(params.kcLength).cached(series)
    val rngma = series.trueRange().sma(params.kcLength).cached(series)
    val upperKC = Indicator { i -> kcMa[i] + rngma[i] * params.kcMult }
    val lowerKC = Indicator { i -> kcMa[i] - rngma[i] * params.kcMult }
    Signal { i -> lowerBB[i] < lowerKC[i] && upperBB[i] > upperKC[i] }
  } else Signal { true }

  val longEntry = when (params.entryMode) {
    SqzEntryMode.ZeroCross -> crossUp
    SqzEntryMode.Color -> colorEntryLong
    SqzEntryMode.ContinuationLime -> contEntryLong
  } and htfBullish and sqzOffGate
  val longExit = when (params.exitMode) {
    SqzExitMode.ZeroCross -> crossDown
    SqzExitMode.Color -> colorExitLong
    SqzExitMode.ZeroCrossPlusContinuation -> zeroPlusFallLong
  }
  val shortEntry = when (params.entryMode) {
    SqzEntryMode.ZeroCross -> crossDown
    SqzEntryMode.Color -> colorEntryShort
    SqzEntryMode.ContinuationLime -> contEntryShort
  } and htfBearish and sqzOffGate
  val shortExit = when (params.exitMode) {
    SqzExitMode.ZeroCross -> crossUp
    SqzExitMode.Color -> colorExitShort
    SqzExitMode.ZeroCrossPlusContinuation -> zeroPlusRiseShort
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
