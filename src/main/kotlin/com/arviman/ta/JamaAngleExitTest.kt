package com.arviman.ta

import com.arviman.ta.backtest.BackTestReport
import com.arviman.ta.backtest.BackTestSpec
import com.arviman.ta.backtest.BackTester
import com.arviman.ta.backtest.TradeType
import com.arviman.ta.indicators.volatilityStop
import com.arviman.ta.strategy.JamaParams
import com.arviman.ta.strategy.jamaEntryAtrFactory
import com.arviman.ta.strategy.jamaLadder
import com.arviman.ta.strategy.makeJamaHccStrategy
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.readCsvBars

/**
 * Compares JAMA exit modes on full-cycle BTC daily:
 *   - RSI rollover exit (default)
 *   - Angle-change exit (MA7 and MA20)
 * Across both entry modes (early / relaxed) so we can see how each exit
 * interacts with the entry gating.
 */
object JamaAngleExitTest {

  private data class Variant(val label: String, val params: JamaParams)

  private val variants = listOf(
    Variant("early | RSI exit         ", base(early = true)),
    Variant("early | angleChg MA20 t=0", base(early = true).copy(useAngleChangeExit = true, exitMaLength = 20, exitDecelThreshold = 0.0)),
    Variant("early | angleChg MA20 t=-0.5", base(early = true).copy(useAngleChangeExit = true, exitMaLength = 20, exitDecelThreshold = -0.5)),
    Variant("early | angleChg MA20 t=-1.0", base(early = true).copy(useAngleChangeExit = true, exitMaLength = 20, exitDecelThreshold = -1.0)),
    Variant("early | angleChg MA20 t=-2.0", base(early = true).copy(useAngleChangeExit = true, exitMaLength = 20, exitDecelThreshold = -2.0)),
    Variant("early | angleChg MA7  t=-1.0", base(early = true).copy(useAngleChangeExit = true, exitMaLength = 7, exitDecelThreshold = -1.0)),
    Variant("early | angleChg MA7  t=-2.0", base(early = true).copy(useAngleChangeExit = true, exitMaLength = 7, exitDecelThreshold = -2.0)),
  )

  private fun base(early: Boolean, relaxed: Boolean = false) = JamaParams(
    slAtrMult = 1.5,
    tpSlRatio = 3.0,
    earlyEntry = early || relaxed,
    dropTrendGates = relaxed,
    changeEmaThreshold = if (relaxed) 0.03 else 0.06,
    disableSoftExit = false,
    trailingStopPct = null,
  )

  @JvmStatic
  fun main(args: Array<String>) {
    val bars = readCsvBars("sampledata/BTC_2022_2026.csv")
    val splitIdx = (bars.size * 0.6).toInt()
    val ins = bars.subList(0, splitIdx)
    val osr = bars.subList(splitIdx, bars.size)
    println("BTC 2022→2026 daily: ${bars.size} bars → IS ${ins.size} | OS ${osr.size}")
    println()
    println(header())
    println("-".repeat(140))
    for (v in variants) {
      val rIs = run(ins, v.params)
      val rOs = run(osr, v.params)
      val tot = (1 + rIs.profitability) * (1 + rOs.profitability)
      println(row(v.label, rIs, rOs, tot))
    }
    val bnh = (bars.last().close / bars.first().close)
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
    "%-26s | %4s %6s %7s %7s %6s | %4s %6s %7s %7s %6s | %5s".format(
      "variant",
      "IS#t", "IS w%", "IS E[R]", "IS prf", "IS DD",
      "OS#t", "OS w%", "OS E[R]", "OS prf", "OS DD",
      "total",
    )

  private fun row(label: String, ins: BackTestReport, os: BackTestReport, total: Double): String =
    "%-26s | %4d %6s %7s %7s %6s | %4d %6s %7s %7s %6s | %5.2fx".format(
      label,
      ins.tradeCount, pct(ins.winRate), pct(ins.expectedValuePerTrade), pct(ins.profitability), pct(ins.maxDrawDown),
      os.tradeCount, pct(os.winRate), pct(os.expectedValuePerTrade), pct(os.profitability), pct(os.maxDrawDown),
      total,
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
}
