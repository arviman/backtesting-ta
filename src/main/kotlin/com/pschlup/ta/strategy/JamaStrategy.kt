package com.pschlup.ta.strategy

import com.pschlup.ta.backtest.TpSlTier
import com.pschlup.ta.indicators.Indicator
import com.pschlup.ta.indicators.angle
import com.pschlup.ta.indicators.averageTrueRange
import com.pschlup.ta.indicators.cached
import com.pschlup.ta.indicators.change
import com.pschlup.ta.indicators.closePrice
import com.pschlup.ta.indicators.ema
import com.pschlup.ta.indicators.hurstChannel
import com.pschlup.ta.indicators.rsi
import com.pschlup.ta.indicators.sma
import com.pschlup.ta.indicators.zema
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.TimeSeries
import com.pschlup.ta.timeseries.TimeSeriesManager

/**
 * Tunable inputs for the JAMA_HCC strategy. Defaults match the values used in
 * the original Pine script (ETH/alt preset, long-only). Sweepable axes are
 * grouped at the top.
 */
data class JamaParams(
  // --- Sizing / exit ladder ------------------------------------------------
  /** SL distance = [slAtrMult] × ATR(14) at entry. */
  val slAtrMult: Double = 2.0,
  /** TP distance = [tpSlRatio] × SL distance. */
  val tpSlRatio: Double = 1.5,
  /** % trailing stop applied to the position. null disables. */
  val trailingStopPct: Double? = 0.05,

  // --- Entry gates ---------------------------------------------------------
  /** Smoothed-RSI delta threshold for the jarvis long entry. */
  val changeEmaThreshold: Double = 0.06,
  /** How far below [changeEmaThreshold] the delta must fall to exit a long. */
  val closeDelta: Double = 0.02,

  // --- Trend / regime gates ------------------------------------------------
  /** Mayer Multiple ceiling; trend filter blocks entries when MM ≥ this. */
  val mmCap: Double = 2.4,
  /** Percentile (0-1) into the short Hurst cycle band for the long breakout filter. */
  val hurstShortLongPct: Double = 0.70,
  /** Percentile (0-1) into the medium Hurst cycle band for the long breakout filter. */
  val hurstMediumLongPct: Double = 0.60,
  /** Minimum SMA50 slope (degrees) for the long McTrend gate. */
  val sma50SlopeDeg: Double = 1.5,
  /** Minimum medium-Hurst median slope (degrees) for the long McTrend gate. */
  val mediumHurstSlopeDeg: Double = 1.0,
)

/**
 * Builds the JAMA_HCC long-only strategy on the given [strategy] timeseries,
 * with optional separate [htf] series for Mayer Multiple / SMA50 context.
 * When [htf] is omitted, all gates collapse onto the [strategy] timeframe.
 */
fun makeJamaHccStrategy(
  strategy: TimeSeries,
  htf: TimeSeries = strategy,
  params: JamaParams = JamaParams(),
): Strategy {
  val sClose = strategy.closePrice
  val hClose = htf.closePrice

  // --- jarvis entry: smoothed-RSI delta ------------------------------------
  val rsi = sClose.rsi(18).cached(strategy)
  val rsiSmooth1 = rsi.ema(3).cached(strategy)
  val rsiSmooth2 = rsiSmooth1.ema(12).cached(strategy)
  val changeEma = rsiSmooth2.change()
  val jarvisLong = changeEma isOver params.changeEmaThreshold

  // --- MA entry: close > ZEMA(5) > SMA(21) ---------------------------------
  val zema5 = sClose.zema(5).cached(strategy)
  val sma21 = sClose.sma(21).cached(strategy)
  val maLong = (sClose isOver zema5) and (zema5 isOver sma21)

  val longEntry = jarvisLong or maLong
  val longExit = changeEma isUnder (params.changeEmaThreshold - params.closeDelta)

  // --- Hurst Cycle Channel: enter long only when breaking out top of cycles
  val shortCh = strategy.hurstChannel(length = 10, multiplier = 1.0)
  val medCh = strategy.hurstChannel(length = 30, multiplier = 3.0)
  val topShort = Indicator { i -> shortCh.bottom[i] + params.hurstShortLongPct * (shortCh.top[i] - shortCh.bottom[i]) }
  val topMed = Indicator { i -> medCh.bottom[i] + params.hurstMediumLongPct * (medCh.top[i] - medCh.bottom[i]) }
  val hurstLongOk = (sClose isOver topShort) and (sClose isOver topMed)

  // --- Mayer Multiple cap + rising HTF SMA50 -------------------------------
  val sma200Htf = hClose.sma(200)
  val sma50Htf = hClose.sma(50)
  val mmFilter = Signal { i -> hClose[i] / sma200Htf[i] < params.mmCap }
  val sma50Rising = Signal { i -> sma50Htf[i] > sma50Htf[i + 1] }

  // --- McTrend: SMA50 slope + medium-Hurst median slope --------------------
  val sma50Angle = htf.angle(sma50Htf)
  val medMedianAngle = strategy.angle(medCh.median)
  val sma50Trend = Signal { i -> sma50Angle[i] > params.sma50SlopeDeg }
  val medHurstTrend = Signal { i -> medMedianAngle[i] > params.mediumHurstSlopeDeg }

  val trend = hurstLongOk and mmFilter and sma50Rising and sma50Trend and medHurstTrend

  return Strategy(
    trendSignal = trend,
    entrySignal = longEntry,
    exitSignal = longExit,
  )
}

/**
 * Builds the JAMA TP/SL ladder from [params]. Single ATR-derived tier — keeps
 * the sweep surface small. Multi-tier variants can be added later.
 */
fun jamaLadder(params: JamaParams): List<TpSlTier> = listOf(
  TpSlTier(
    quantityFraction = 1.0,
    stopLossAtrMultiplier = params.slAtrMult,
    takeProfitAtrMultiplier = params.slAtrMult * params.tpSlRatio,
    trailingStopPct = params.trailingStopPct,
  ),
)

/** ATR(14) factory on the given [timeFrame] for [jamaLadder]'s ATR-based SL/TP. */
fun jamaEntryAtrFactory(timeFrame: TimeFrame): (TimeSeriesManager) -> Indicator =
  { sm -> sm.getTimeSeries(timeFrame).averageTrueRange(14) }
