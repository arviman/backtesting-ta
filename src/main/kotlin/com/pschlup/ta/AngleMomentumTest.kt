package com.pschlup.ta

import com.pschlup.ta.backtest.BackTestReport
import com.pschlup.ta.backtest.BackTestSpec
import com.pschlup.ta.backtest.BackTester
import com.pschlup.ta.backtest.TpSlTier
import com.pschlup.ta.backtest.TradeType
import com.pschlup.ta.indicators.averageTrueRange
import com.pschlup.ta.indicators.volatilityStop
import com.pschlup.ta.strategy.makeAngleMomentumStrategy
import com.pschlup.ta.timeseries.Bar
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.readCsvBars

/**
 * Tests the angle-momentum exhaustion strategy across MA periods (7, 20) and
 * timeframes (H1, H4, D) on BTC. 60/40 holdout. 1.5×ATR stop, 100% bet.
 */
object AngleMomentumTest {

  private data class Variant(
    val label: String,
    val path: String,
    val tf: TimeFrame,
    val maLength: Int,
  )

  private val BTC_M5 = "sampledata/chart_data_BTC_USDT_p5_730d.csv"
  private val BTC_D = "sampledata/BTC_2022_2026.csv"

  private val variants = listOf(
    Variant("BTC H1 MA7  ", BTC_M5, TimeFrame.H1, 7),
    Variant("BTC H1 MA20 ", BTC_M5, TimeFrame.H1, 20),
    Variant("BTC H4 MA7  ", BTC_M5, TimeFrame.H4, 7),
    Variant("BTC H4 MA20 ", BTC_M5, TimeFrame.H4, 20),
    Variant("BTC D  MA7  ", BTC_D,  TimeFrame.D,  7),
    Variant("BTC D  MA20 ", BTC_D,  TimeFrame.D,  20),
  )

  private val ladder = listOf(
    TpSlTier(quantityFraction = 1.0, stopLossAtrMultiplier = 1.5, takeProfitAtrMultiplier = 99.0)
  )

  @JvmStatic
  fun main(args: Array<String>) {
    val barsByPath = variants.map { it.path }.distinct().associateWith { readCsvBars(it) }

    println(header())
    println("-".repeat(140))
    var lastPath = ""
    for (v in variants) {
      if (v.path != lastPath) {
        if (lastPath.isNotEmpty()) printBnh(barsByPath[lastPath]!!, lastPath)
        lastPath = v.path
      }
      val bars = barsByPath[v.path]!!
      val splitIdx = (bars.size * 0.6).toInt()
      val ins = runOne(bars.subList(0, splitIdx), v)
      val osr = runOne(bars.subList(splitIdx, bars.size), v)
      println(row(v.label, ins, osr))
    }
    printBnh(barsByPath[lastPath]!!, lastPath)
  }

  private fun runOne(bars: List<Bar>, v: Variant): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = v.tf,
      trailingStops = false,
      pyramidingLimit = ladder.size,
      startingBalance = 5_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm ->
        makeAngleMomentumStrategy(
          series = sm.getTimeSeries(v.tf),
          maLength = v.maLength,
        )
      },
      stopLoss = { sm -> sm.getTimeSeries(v.tf).volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = ladder,
      entryAtrFactory = { sm -> sm.getTimeSeries(v.tf).averageTrueRange(14) },
      fixedPositionFraction = 1.0,
    )
    return BackTester.run(spec)
  }

  private fun printBnh(bars: List<Bar>, path: String) {
    val split = (bars.size * 0.6).toInt()
    val ins = bars.subList(0, split)
    val osr = bars.subList(split, bars.size)
    val bnhIs = ins.last().close / ins.first().close
    val bnhOs = osr.last().close / osr.first().close
    println("  BnH ref (%-44s)  IS %.2fx (%.1f%%) | OS %.2fx (%.1f%%) | Tot %.2fx".format(
      path.substringAfterLast("/"), bnhIs, 100.0 * (bnhIs - 1.0), bnhOs, 100.0 * (bnhOs - 1.0), bnhIs * bnhOs,
    ))
  }

  private fun header(): String =
    "%-14s | %4s %6s %7s %7s %6s | %4s %6s %7s %7s %6s".format(
      "variant",
      "IS#t", "IS w%", "IS E[R]", "IS prf", "IS DD",
      "OS#t", "OS w%", "OS E[R]", "OS prf", "OS DD",
    )

  private fun row(label: String, ins: BackTestReport, os: BackTestReport): String =
    "%-14s | %4d %6s %7s %7s %6s | %4d %6s %7s %7s %6s".format(
      label,
      ins.tradeCount, pct(ins.winRate), pct(ins.expectedValuePerTrade), pct(ins.profitability), pct(ins.maxDrawDown),
      os.tradeCount, pct(os.winRate), pct(os.expectedValuePerTrade), pct(os.profitability), pct(os.maxDrawDown),
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
}
