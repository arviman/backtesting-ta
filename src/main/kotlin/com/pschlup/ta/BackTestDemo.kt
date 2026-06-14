package com.pschlup.ta

import com.pschlup.ta.backtest.BackTestReport
import com.pschlup.ta.backtest.BackTestSpec
import com.pschlup.ta.backtest.BackTester
import com.pschlup.ta.backtest.TradeType
import com.pschlup.ta.indicators.volatilityStop
import com.pschlup.ta.strategy.JamaParams
import com.pschlup.ta.strategy.jamaEntryAtrFactory
import com.pschlup.ta.strategy.jamaLadder
import com.pschlup.ta.strategy.makeJamaHccStrategy
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.readCsvBars

/** Single-run JAMA_HCC backtest on SOL daily with default params. */
object BackTestDemo {
  @JvmStatic
  fun main(args: Array<String>) {
    val params = JamaParams()
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = TimeFrame.D,
      trailingStops = false,
      pyramidingLimit = jamaLadder(params).size,
      startingBalance = 5_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      inputBars = readCsvBars("sampledata/sol_d_02012023_14062026.csv"),
      strategyFactory = { sm -> makeJamaHccStrategy(strategy = sm.d, params = params) },
      stopLoss = { sm -> sm.d.volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = jamaLadder(params),
      entryAtrFactory = jamaEntryAtrFactory(TimeFrame.D),
    )

    printReport(BackTester.run(spec))
  }

  private fun printReport(report: BackTestReport) {
    println("----------------------------------")
    println("Finished backtest with ${report.tradeCount} trade legs")
    println("----------------------------------")
    println("Pyramiding limit   : ${report.pyramidingLimit}")
    println(String.format("Account risk/trade : %.1f%%", 100.0 * report.betSize))
    println("----------------------------------")
    println(String.format("Profitability      : %.2f%%", 100.0 * report.profitability))
    println(String.format("Buy-and-hold       : %.2f%%", 100.0 * report.buyAndHoldProfitability))
    println(String.format("Vs buy-and-hold    : %.2f%%", 100.0 * report.vsBuyAndHold))
    println(String.format("Win rate           : %.2f%%", 100.0 * report.winRate))
    println(String.format("Max drawdown       : %.2f%%", 100.0 * report.maxDrawDown))
    println(String.format("Risk/reward ratio  : %.2f", report.riskReward))
    println(String.format("Sortino ratio      : %.2f", report.sortinoRatio))
    println("----------------------------------")
    println(String.format("Start balance      : $%,.2f", report.initialBalance))
    println(String.format("Profit/loss        : $%,.2f", report.profitLoss))
    println(String.format("Final balance      : $%,.2f", report.finalBalance))
    println("----------------------------------")
  }
}
