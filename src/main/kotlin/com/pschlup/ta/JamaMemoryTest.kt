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
 * Tests whether gating MA-cross by "recent jarvis" improves JAMA early on
 * gold and BTC daily. Compares legacy OR (MA-cross fires standalone) vs
 * jarvis-memory continuation at multiple memory windows.
 */
object JamaMemoryTest {

  private data class Variant(val label: String, val useMemory: Boolean, val memoryBars: Int)
  private val variants = listOf(
    Variant("legacy OR (no gate)", useMemory = false, memoryBars = 0),
    Variant("memory N=5         ", useMemory = true, memoryBars = 5),
    Variant("memory N=10        ", useMemory = true, memoryBars = 10),
    Variant("memory N=20        ", useMemory = true, memoryBars = 20),
    Variant("memory N=50        ", useMemory = true, memoryBars = 50),
  )

  private data class Asset(val label: String, val path: String)
  private val assets = listOf(
    Asset("Gold D ", "sampledata/XAU_USD.csv"),
    Asset("BTC  D ", "sampledata/BTC_2022_2026.csv"),
  )

  @JvmStatic
  fun main(args: Array<String>) {
    val barsByPath = assets.map { it.path }.distinct().associateWith { readCsvBars(it) }

    for (asset in assets) {
      val bars = barsByPath[asset.path]!!
      val splitIdx = (bars.size * 0.6).toInt()
      val ins = bars.subList(0, splitIdx)
      val osr = bars.subList(splitIdx, bars.size)
      println("=== ${asset.label} (${bars.size} bars → IS ${ins.size} | OS ${osr.size}) ===")
      println(header())
      println("-".repeat(140))
      for (v in variants) {
        val params = JamaParams(
          slAtrMult = 5.0,
          tpSlRatio = 3.0,
          earlyEntry = true,
          disableSoftExit = false,
          trailingStopPct = null,
          useJarvisMemory = v.useMemory,
          jarvisMemoryBars = v.memoryBars,
        )
        val rIs = run(ins, params)
        val rOs = run(osr, params)
        val tot = (1 + rIs.profitability) * (1 + rOs.profitability)
        println(row(v.label, rIs, rOs, tot))
      }
      val bnh = bars.last().close / bars.first().close
      println("BnH total: %.2fx (%.1f%%)".format(bnh, 100.0 * (bnh - 1.0)))
      println()
    }
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
    "%-22s | %4s %6s %7s %7s %6s | %4s %6s %7s %7s %6s | %5s".format(
      "variant",
      "IS#t", "IS w%", "IS E[R]", "IS prf", "IS DD",
      "OS#t", "OS w%", "OS E[R]", "OS prf", "OS DD",
      "total",
    )

  private fun row(label: String, ins: BackTestReport, os: BackTestReport, total: Double): String =
    "%-22s | %4d %6s %7s %7s %6s | %4d %6s %7s %7s %6s | %5.2fx".format(
      label,
      ins.tradeCount, pct(ins.winRate), pct(ins.expectedValuePerTrade), pct(ins.profitability), pct(ins.maxDrawDown),
      os.tradeCount, pct(os.winRate), pct(os.expectedValuePerTrade), pct(os.profitability), pct(os.maxDrawDown),
      total,
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
}
