package com.pschlup.ta

import com.pschlup.ta.backtest.BackTestReport
import com.pschlup.ta.backtest.BackTestSpec
import com.pschlup.ta.backtest.BackTester
import com.pschlup.ta.backtest.TradeType
import com.pschlup.ta.indicators.*
import com.pschlup.ta.strategy.*
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.TimeSeriesManager
import com.pschlup.ta.timeseries.readCsvBars

/** Back tests the JAMA_HCC strategy (long-only port). */
object BackTestDemo {
  @JvmStatic
  fun main(args: Array<String>) {

    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = TimeFrame.H1,
      trailingStops = false,
      pyramidingLimit = 1,
      // Matches JAMA's initial_capital=5000 and commission_value=0.05%.
      startingBalance = 5_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      inputBars = readCsvBars("sampledata/chart_data_BTC_USDT_p5_730d.csv"),
      strategyFactory = { sm -> makeJamaHccStrategy(sm) },
      stopLoss = { sm -> sm.h4.volatilityStop(length = 4, multiplier = 0.2) }
    )

    val report = BackTester.run(spec)
    printReport(report)
  }

  /**
   * JAMA_HCC strategy port — long-only.
   *
   * Entry = jarvis (smoothed-RSI delta over threshold) OR price>ZEMA>SMA crossover.
   * Trend gate = Hurst Cycle Channel breakout + Mayer Multiple cap + HTF SMA50
   *              rising + SMA50 slope + medium-Hurst median slope.
   * Exit  = smoothed-RSI delta falls under (threshold − close-delta).
   *
   * Skipped (ponytail) — not modeled by this backtest framework or not
   * load-bearing for a long-only run:
   *   - mode switching (bull/bear/dynamic), preset/risk inputs
   *   - baseline HODL position, moon phase, fib regression bands
   *   - multi-tier TP/SL, pyramiding, trailing-stop %
   *   - Heikin-Ashi exit (defaults to off for non-eth preset anyway)
   *   - Gauss 4-pole filter (McTrend slope gate covers regime detection)
   * Add when measurably falls short.
   */
  private fun makeJamaHccStrategy(sm: TimeSeriesManager): Strategy {
    val h1 = sm.h1          // strategy timeframe
    val h4 = sm.h4          // HTF context (~12h in Pine `mayerMultiple_timeFrame`)

    val h1Close = h1.closePrice
    val h4Close = h4.closePrice

    // --- jarvis entry: smoothed-RSI delta ------------------------------------
    // ponytail: each recursive ema/rma layer needs caching, else chained
    //   ema(ema(rsi)) is O(31^k) per bar evaluation.
    val rsi = h1Close.rsi(18).cached(h1)
    val rsiSmooth1 = rsi.ema(3).cached(h1)
    val rsiSmooth2 = rsiSmooth1.ema(12).cached(h1)
    val changeEma = rsiSmooth2.change()
    val longThreshold = 0.06
    val closeDelta = 0.02
    val jarvisLong = changeEma isOver longThreshold

    // --- MA entry: close > ZEMA(5) > SMA(21) ---------------------------------
    val zema5 = h1Close.zema(5).cached(h1)
    val sma21 = h1Close.sma(21).cached(h1)
    val maLong = (h1Close isOver zema5) and (zema5 isOver sma21)

    val longEntry = jarvisLong or maLong
    val longExit = changeEma isUnder (longThreshold - closeDelta)

    // --- Hurst Cycle Channel: enter long only when breaking out top of cycles
    val shortCh = h1.hurstChannel(length = 10, multiplier = 1.0)
    val medCh = h1.hurstChannel(length = 30, multiplier = 3.0)
    val topShort = Indicator { i -> shortCh.bottom[i] + 0.70 * (shortCh.top[i] - shortCh.bottom[i]) }
    val topMed = Indicator { i -> medCh.bottom[i] + 0.60 * (medCh.top[i] - medCh.bottom[i]) }
    val hurstLongOk = (h1Close isOver topShort) and (h1Close isOver topMed)

    // --- Mayer Multiple cap + rising HTF SMA50 -------------------------------
    val sma200Htf = h4Close.sma(200)
    val sma50Htf = h4Close.sma(50)
    val mmFilter = Signal { i -> h4Close[i] / sma200Htf[i] < 2.4 }
    val sma50Rising = Signal { i -> sma50Htf[i] > sma50Htf[i + 1] }

    // --- McTrend: SMA50 slope > 1.5° and medium-Hurst median slope > 1° ------
    val sma50Angle = h4.angle(sma50Htf)
    val medMedianAngle = h1.angle(medCh.median)
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
    println("Finished backtest with ${report.tradeCount} trades")
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
