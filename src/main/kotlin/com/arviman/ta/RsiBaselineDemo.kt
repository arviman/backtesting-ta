package com.arviman.ta

import com.arviman.ta.backtest.BackTestReport
import com.arviman.ta.backtest.BackTestSpec
import com.arviman.ta.backtest.BackTester
import com.arviman.ta.backtest.TradeType
import com.arviman.ta.indicators.Indicator
import com.arviman.ta.strategy.Strategy
import com.arviman.ta.strategy.makeRsiBaselineStrategy
import com.arviman.ta.strategy.makeRsiMidlineStrategy
import com.arviman.ta.strategy.makeRsiRecoveryStrategy
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.TimeSeries
import com.arviman.ta.timeseries.readCsvBars

/**
 * Runs the dumb RSI cycle baseline on SOL daily across several timeframes
 * (3-day, weekly, and a long-period daily approximation of weekly). All four
 * results plus the buy-and-hold reference go side-by-side so we can answer:
 * does any of this TA actually beat passively holding?
 */
object RsiBaselineDemo {

  private data class Config(
    val label: String,
    val timeFrame: TimeFrame,
    val rsiLength: Int,
    val build: (TimeSeries, Int) -> Strategy,
  )

  private val configs = listOf(
    // Weekly — RSI(14) is too smoothed on SOL's 3y window; try shorter lengths too.
    Config("Weekly RSI(14) cross 30/80",   TimeFrame.W, 14) { s, l -> makeRsiBaselineStrategy(s, l, 30.0, 80.0) },
    Config("Weekly RSI(7) recover 30/80",  TimeFrame.W, 7)  { s, l -> makeRsiRecoveryStrategy(s, l, 30.0, 80.0, lookbackBars = 6) },
    Config("Weekly RSI(7) midline 50",     TimeFrame.W, 7)  { s, l -> makeRsiMidlineStrategy(s, l, 50.0) },
    Config("Weekly RSI(14) midline 50",    TimeFrame.W, 14) { s, l -> makeRsiMidlineStrategy(s, l, 50.0) },
    // 3-day
    Config("3-day RSI(14) recover 30/80",  TimeFrame.D3, 14) { s, l -> makeRsiRecoveryStrategy(s, l, 30.0, 80.0, lookbackBars = 6) },
    Config("3-day RSI(14) midline 50",     TimeFrame.D3, 14) { s, l -> makeRsiMidlineStrategy(s, l, 50.0) },
    Config("3-day RSI(7) midline 50",      TimeFrame.D3, 7)  { s, l -> makeRsiMidlineStrategy(s, l, 50.0) },
    // Daily
    Config("Daily RSI(14) recover 30/80",  TimeFrame.D, 14)  { s, l -> makeRsiRecoveryStrategy(s, l, 30.0, 80.0, lookbackBars = 6) },
    Config("Daily RSI(14) midline 50",     TimeFrame.D, 14)  { s, l -> makeRsiMidlineStrategy(s, l, 50.0) },
    Config("Daily RSI(70) midline 50",     TimeFrame.D, 70)  { s, l -> makeRsiMidlineStrategy(s, l, 50.0) },
  )

  @JvmStatic
  fun main(args: Array<String>) {
    val bars = readCsvBars("sampledata/sol_d_02012023_14062026.csv")
    println("Bars: ${bars.size} daily SOL bars")
    println()

    val rows = configs.map { it to runOne(bars, it) }
    val bnh = rows.first().second.buyAndHoldProfitability

    println(header())
    println("-".repeat(105))
    rows.forEach { (cfg, rep) -> println(rowFor(cfg, rep)) }

    // Honest comparison: compute buy-and-hold drawdown so its Sortino is comparable.
    val bnhDD = buyAndHoldDrawdown(bars)
    val bnhSortino = if (bnhDD > 0) bnh / bnhDD else Double.NaN
    println()
    println("%-26s | %5s %9.1f%% %8.1f%% %8s %9.2f".format(
      "Buy-and-hold reference", "—", 100.0 * bnh, 100.0 * bnhDD, "—", bnhSortino,
    ))
  }

  /** Peak-to-trough drawdown of a passive long position over [bars]. */
  private fun buyAndHoldDrawdown(bars: List<Bar>): Double {
    val start = bars.first().close
    var peak = 1.0
    var maxDD = 0.0
    for (bar in bars) {
      val equity = bar.close / start
      if (equity > peak) peak = equity
      val dd = (peak - equity) / peak
      if (dd > maxDD) maxDD = dd
    }
    return maxDD
  }

  private fun runOne(bars: List<Bar>, cfg: Config): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = cfg.timeFrame,
      trailingStops = false,
      pyramidingLimit = 1,
      startingBalance = 5_000.0,
      // ponytail: betSize × (1/slPct) = 1.0 means "commit full account on entry";
      // slPct = 1.0 means the stop sits at price=0 so it never triggers.
      betSize = 1.0,
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm -> cfg.build(sm.getTimeSeries(cfg.timeFrame), cfg.rsiLength) },
      stopLoss = { Indicator { 0.0 } }, // no real stop (sits at $0)
    )
    return BackTester.run(spec)
  }

  private fun header() =
    "%-26s | %5s %10s %9s %9s %9s".format("config", "#trd", "profit", "DD", "win%", "sortino")

  private fun rowFor(cfg: Config, r: BackTestReport): String =
    "%-26s | %5d %9.1f%% %8.1f%% %8.1f%% %9.2f".format(
      cfg.label, r.tradeCount, 100.0 * r.profitability, 100.0 * r.maxDrawDown,
      100.0 * r.winRate, r.sortinoRatio,
    )
}

