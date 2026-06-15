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
import com.pschlup.ta.timeseries.Bar
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.readCsvBars

/**
 * JAMA early on Gold daily with SL ATR multiplier sweep. Answers: at 0.05/0.1
 * lot per entry on a $100K prop account ($5K daily DD ceiling), does a wider
 * SL keep the strategy safe AND tradeable?
 */
object GoldSlSweep {

  private val slMults = listOf(1.5, 2.5, 5.0, 7.0, 10.0)

  @JvmStatic
  fun main(args: Array<String>) {
    val bars = readCsvBars("sampledata/XAU_USD.csv")
    val splitIdx = (bars.size * 0.6).toInt()
    val ins = bars.subList(0, splitIdx)
    val osr = bars.subList(splitIdx, bars.size)
    println("Gold XAU/USD daily: ${bars.size} bars → IS ${ins.size} | OS ${osr.size}")
    println()
    println(header())
    println("-".repeat(140))
    for (mult in slMults) {
      val params = JamaParams(
        slAtrMult = mult,
        tpSlRatio = 3.0,
        earlyEntry = true,
        disableSoftExit = false,
        trailingStopPct = null,
      )
      val rIs = run(ins, params)
      val rOs = run(osr, params)
      val tot = (1 + rIs.profitability) * (1 + rOs.profitability)
      println(row("slAtrMult=%.1f".format(mult), rIs, rOs, tot))
    }
    val bnh = bars.last().close / bars.first().close
    println()
    println("BnH ref: total %.2fx (%.1f%%)".format(bnh, 100.0 * (bnh - 1.0)))
  }

  private fun run(bars: List<Bar>, params: JamaParams): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = TimeFrame.D,
      trailingStops = false,
      pyramidingLimit = jamaLadder(params).size,
      startingBalance = 5_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm -> makeJamaHccStrategy(strategy = sm.d, params = params) },
      stopLoss = { sm -> sm.d.volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = jamaLadder(params),
      entryAtrFactory = jamaEntryAtrFactory(TimeFrame.D),
      fixedPositionFraction = 1.0,
    )
    return BackTester.run(spec)
  }

  private fun header(): String =
    "%-18s | %4s %6s %7s %7s %6s | %4s %6s %7s %7s %6s | %5s".format(
      "variant",
      "IS#t", "IS w%", "IS E[R]", "IS prf", "IS DD",
      "OS#t", "OS w%", "OS E[R]", "OS prf", "OS DD",
      "total",
    )

  private fun row(label: String, ins: BackTestReport, os: BackTestReport, total: Double): String =
    "%-18s | %4d %6s %7s %7s %6s | %4d %6s %7s %7s %6s | %5.2fx".format(
      label,
      ins.tradeCount, pct(ins.winRate), pct(ins.expectedValuePerTrade), pct(ins.profitability), pct(ins.maxDrawDown),
      os.tradeCount, pct(os.winRate), pct(os.expectedValuePerTrade), pct(os.profitability), pct(os.maxDrawDown),
      total,
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
}
