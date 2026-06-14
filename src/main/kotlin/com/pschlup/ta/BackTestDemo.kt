package com.pschlup.ta

import com.pschlup.ta.backtest.BackTestReport
import com.pschlup.ta.backtest.BackTestSpec
import com.pschlup.ta.backtest.BackTester
import com.pschlup.ta.backtest.TpSlTier
import com.pschlup.ta.backtest.TradeType
import com.pschlup.ta.indicators.*
import com.pschlup.ta.strategy.*
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.TimeSeries
import com.pschlup.ta.timeseries.TimeSeriesManager
import com.pschlup.ta.timeseries.readCsvBars

/** Back tests the JAMA_HCC strategy (long-only port) on SOL daily. */
object BackTestDemo {

  /**
   * JAMA long-only TP/SL ladder. Matches the Pine script's intent: tighter stops
   * pair with smaller take-profit targets and bigger share of the position; the
   * runner tier has the widest stop and a 5% trailing stop to lock in profit.
   * Fractions sum to 1.0 (required by [BackTester.openLadderEntry]).
   */
  private val JAMA_LADDER = listOf(
    TpSlTier(stopLossPct = 0.10, takeProfitPct = 0.15, quantityFraction = 0.40),
    TpSlTier(stopLossPct = 0.15, takeProfitPct = 0.20, quantityFraction = 0.35),
    TpSlTier(stopLossPct = 0.20, takeProfitPct = 0.25, quantityFraction = 0.25, trailingStopPct = 0.05),
  )

  @JvmStatic
  fun main(args: Array<String>) {

    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = TimeFrame.D,
      trailingStops = false, // per-tier % trail used instead (see JAMA_LADDER)
      pyramidingLimit = JAMA_LADDER.size, // one ladder occupies N active trades
      // Matches JAMA's initial_capital=5000 and commission_value=0.05%.
      startingBalance = 5_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      inputBars = readCsvBars("sampledata/sol_d_02012023_14062026.csv"),
      strategyFactory = { sm -> makeJamaHccStrategy(strategy = sm.d) },
      // Unused on the ladder path but required by spec; harmless fallback.
      stopLoss = { sm -> sm.d.volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = JAMA_LADDER,
    )

    val report = BackTester.run(spec)
    printReport(report)
  }

  /**
   * JAMA_HCC strategy port — long-only.
   *
   * Entry  = jarvis (smoothed-RSI delta over threshold) OR price>ZEMA>SMA cross.
   * Trend  = Hurst Cycle Channel breakout + Mayer Multiple cap + HTF SMA50
   *          rising + SMA50 slope + medium-Hurst median slope.
   * Exit   = smoothed-RSI delta falls under (threshold − close-delta).
   * TP/SL  = configured via [BackTestSpec.entryLadder] on the spec — three
   *          tiers, the last with a 5% trailing stop.
   *
   * When [htf] is omitted (e.g. daily input data where no higher TF is
   * available), all gates collapse onto [strategy]'s timeframe.
   *
   * Skipped (ponytail): mode switching, baseline HODL, moon, fib bands,
   * pyramiding-add-to-position, HA exit, Gauss 4-pole filter.
   */
  private fun makeJamaHccStrategy(
    strategy: TimeSeries,
    htf: TimeSeries = strategy,
  ): Strategy {
    val sClose = strategy.closePrice
    val hClose = htf.closePrice

    // --- jarvis entry: smoothed-RSI delta ------------------------------------
    val rsi = sClose.rsi(18).cached(strategy)
    val rsiSmooth1 = rsi.ema(3).cached(strategy)
    val rsiSmooth2 = rsiSmooth1.ema(12).cached(strategy)
    val changeEma = rsiSmooth2.change()
    val longThreshold = 0.06
    val closeDelta = 0.02
    val jarvisLong = changeEma isOver longThreshold

    // --- MA entry: close > ZEMA(5) > SMA(21) ---------------------------------
    val zema5 = sClose.zema(5).cached(strategy)
    val sma21 = sClose.sma(21).cached(strategy)
    val maLong = (sClose isOver zema5) and (zema5 isOver sma21)

    val longEntry = jarvisLong or maLong
    val longExit = changeEma isUnder (longThreshold - closeDelta)

    // --- Hurst Cycle Channel: enter long only when breaking out top of cycles
    val shortCh = strategy.hurstChannel(length = 10, multiplier = 1.0)
    val medCh = strategy.hurstChannel(length = 30, multiplier = 3.0)
    val topShort = Indicator { i -> shortCh.bottom[i] + 0.70 * (shortCh.top[i] - shortCh.bottom[i]) }
    val topMed = Indicator { i -> medCh.bottom[i] + 0.60 * (medCh.top[i] - medCh.bottom[i]) }
    val hurstLongOk = (sClose isOver topShort) and (sClose isOver topMed)

    // --- Mayer Multiple cap + rising HTF SMA50 -------------------------------
    val sma200Htf = hClose.sma(200)
    val sma50Htf = hClose.sma(50)
    val mmFilter = Signal { i -> hClose[i] / sma200Htf[i] < 2.4 }
    val sma50Rising = Signal { i -> sma50Htf[i] > sma50Htf[i + 1] }

    // --- McTrend: SMA50 slope > 1.5° and medium-Hurst median slope > 1° ------
    val sma50Angle = htf.angle(sma50Htf)
    val medMedianAngle = strategy.angle(medCh.median)
    val sma50Trend = Signal { i -> sma50Angle[i] > 1.5 }
    val medHurstTrend = Signal { i -> medMedianAngle[i] > 1.0 }

    val trend = hurstLongOk and mmFilter and sma50Rising and sma50Trend and medHurstTrend

    return Strategy(
      trendSignal = trend,
      entrySignal = longEntry,
      exitSignal = longExit,
    )
  }

  private fun printReport(report: BackTestReport) {
    println("----------------------------------")
    println("Finished backtest with ${report.tradeCount} trade legs")
    println("----------------------------------")

    // Input parameters
    println("Pyramiding limit   : ${report.pyramidingLimit}")
    println(String.format("Account risk/trade : %.1f%%", 100.0 * report.betSize))
    println("----------------------------------")

    // Analysis
    println(String.format("Profitability      : %.2f%%", 100.0 * report.profitability))
    println(String.format("Buy-and-hold       : %.2f%%", 100.0 * report.buyAndHoldProfitability))
    println(String.format("Vs buy-and-hold    : %.2f%%", 100.0 * report.vsBuyAndHold))
    println(String.format("Win rate           : %.2f%%", 100.0 * report.winRate))
    println(String.format("Max drawdown       : %.2f%%", 100.0 * report.maxDrawDown))
    println(String.format("Risk/reward ratio  : %.2f", report.riskReward))
    println(String.format("Sortino ratio      : %.2f", report.sortinoRatio))
    println("----------------------------------")

    // Financial result
    println(String.format("Start balance      : $%,.2f", report.initialBalance))
    println(String.format("Profit/loss        : $%,.2f", report.profitLoss))
    println(String.format("Final balance      : $%,.2f", report.finalBalance))
    println("----------------------------------")
  }
}
