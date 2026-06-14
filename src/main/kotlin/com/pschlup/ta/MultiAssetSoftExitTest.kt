package com.pschlup.ta

import com.pschlup.ta.backtest.BackTestReport
import com.pschlup.ta.backtest.BackTestSpec
import com.pschlup.ta.backtest.BackTester
import com.pschlup.ta.backtest.TpSlTier
import com.pschlup.ta.backtest.TradeType
import com.pschlup.ta.indicators.Indicator
import com.pschlup.ta.indicators.volatilityStop
import com.pschlup.ta.strategy.JamaParams
import com.pschlup.ta.strategy.jamaEntryAtrFactory
import com.pschlup.ta.strategy.makeJamaHccStrategy
import com.pschlup.ta.strategy.makeMeanReversionStrategy
import com.pschlup.ta.timeseries.Bar
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.readCsvBars

/**
 * Multi-asset, multi-timeframe comparison with strategy DD displayed alongside
 * the BnH reference. Soft-exit JAMA across gold/SOL/BTC daily + BTC on H4 and
 * H1, plus a cycle-bottom mean-reversion variant for SOL. 100% bet per trade.
 */
object MultiAssetSoftExitTest {

  private data class Run(
    val label: String,
    val path: String,
    val build: (List<Bar>) -> BackTestReport,
  )

  // --- Effectively no SL / no TP — soft exit (or mean-rev exit) does it all.
  private val openLadder = listOf(
    TpSlTier(quantityFraction = 1.0, stopLossPct = 0.99, takeProfitPct = 99.99)
  )

  private val GOLD = "sampledata/XAU_USD.csv"
  private val SOL = "sampledata/sol_d_02012023_14062026.csv"
  private val BTC = "sampledata/chart_data_BTC_USDT_p5_730d.csv"

  private val runs = listOf(
    // --- Gold (daily only) ---
    Run("Gold D JAMA late  ", GOLD) { bars -> runJama(bars, TimeFrame.D, jama(earlyEntry = false)) },
    Run("Gold D JAMA early ", GOLD) { bars -> runJama(bars, TimeFrame.D, jama(earlyEntry = true)) },
    Run("Gold D JAMA relax ", GOLD) { bars -> runJama(bars, TimeFrame.D, jamaRelaxed()) },

    // --- SOL (daily) ---
    Run("SOL  D JAMA late  ", SOL)  { bars -> runJama(bars, TimeFrame.D, jama(earlyEntry = false)) },
    Run("SOL  D JAMA relax ", SOL)  { bars -> runJama(bars, TimeFrame.D, jamaRelaxed()) },
    Run("SOL  D MeanRev    ", SOL)  { bars -> runMeanRev(bars, TimeFrame.D, longMaLen = 200, drawdown = 0.30) },
    Run("SOL  D MeanRev x.5", SOL)  { bars -> runMeanRev(bars, TimeFrame.D, longMaLen = 100, drawdown = 0.25) },

    // --- BTC across timeframes (relaxed JAMA — best variant we found) ---
    Run("BTC  D  JAMA relax", BTC)  { bars -> runJama(bars, TimeFrame.D,  jamaRelaxed()) },
    Run("BTC  H4 JAMA relax", BTC)  { bars -> runJama(bars, TimeFrame.H4, jamaRelaxed()) },
    Run("BTC  H1 JAMA relax", BTC)  { bars -> runJama(bars, TimeFrame.H1, jamaRelaxed()) },
  )

  @JvmStatic
  fun main(args: Array<String>) {
    // Load each CSV once (BTC is 19 MB, costly to re-read).
    val barsByPath = runs.map { it.path }.distinct().associateWith { readCsvBars(it) }

    println(header())
    println("-".repeat(140))
    var lastPath = ""
    for (run in runs) {
      if (run.path != lastPath) {
        if (lastPath.isNotEmpty()) printBnh(barsByPath[lastPath]!!, lastPath)
        lastPath = run.path
      }
      val bars = barsByPath[run.path]!!
      val splitIdx = (bars.size * 0.6).toInt()
      val ins = run.build(bars.subList(0, splitIdx))
      val osr = run.build(bars.subList(splitIdx, bars.size))
      println(row(run.label, ins, osr))
    }
    printBnh(barsByPath[lastPath]!!, lastPath)
  }

  // --- JAMA helpers --------------------------------------------------------

  private fun jama(earlyEntry: Boolean) = JamaParams(
    earlyEntry = earlyEntry,
    disableSoftExit = false,
    trailingStopPct = null,
  )

  private fun jamaRelaxed() = JamaParams(
    earlyEntry = true,
    dropTrendGates = true,
    changeEmaThreshold = 0.03,
    disableSoftExit = false,
    trailingStopPct = null,
  )

  private fun runJama(bars: List<Bar>, tf: TimeFrame, params: JamaParams): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = tf,
      trailingStops = false,
      pyramidingLimit = openLadder.size,
      startingBalance = 5_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm -> makeJamaHccStrategy(strategy = sm.getTimeSeries(tf), params = params) },
      stopLoss = { sm -> sm.getTimeSeries(tf).volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = openLadder,
      entryAtrFactory = jamaEntryAtrFactory(tf),
      fixedPositionFraction = 1.0,
    )
    return BackTester.run(spec)
  }

  // --- Mean-reversion helpers ---------------------------------------------

  private fun runMeanRev(bars: List<Bar>, tf: TimeFrame, longMaLen: Int, drawdown: Double): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = tf,
      trailingStops = false,
      pyramidingLimit = 1,
      startingBalance = 5_000.0,
      betSize = 1.0, // full account when single-tier without ladder
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm ->
        makeMeanReversionStrategy(
          strategy = sm.getTimeSeries(tf),
          longMaLen = longMaLen,
          drawdownThreshold = drawdown,
        )
      },
      stopLoss = { Indicator { 0.0 } }, // no stop (price = 0 never hit)
    )
    return BackTester.run(spec)
  }

  // --- Reporting -----------------------------------------------------------

  private fun buyAndHoldDD(bars: List<Bar>): Double {
    val start = bars.first().close
    var peak = 1.0
    var maxDD = 0.0
    for (b in bars) {
      val eq = b.close / start
      if (eq > peak) peak = eq
      val dd = (peak - eq) / peak
      if (dd > maxDD) maxDD = dd
    }
    return maxDD
  }

  private fun printBnh(bars: List<Bar>, path: String) {
    val split = (bars.size * 0.6).toInt()
    val ins = bars.subList(0, split)
    val osr = bars.subList(split, bars.size)
    val bnhIs = ins.last().close / ins.first().close - 1.0
    val bnhOs = osr.last().close / osr.first().close - 1.0
    val ddIs = buyAndHoldDD(ins)
    val ddOs = buyAndHoldDD(osr)
    println("  BnH ref (%-44s)  IS %.1f%% / DD %.1f%% | OS %.1f%% / DD %.1f%%".format(
      path.substringAfterLast("/"),
      100.0 * bnhIs, 100.0 * ddIs, 100.0 * bnhOs, 100.0 * ddOs,
    ))
  }

  private fun header(): String =
    "%-18s | %4s %6s %6s %7s %7s %6s | %4s %6s %6s %7s %7s %6s".format(
      "variant",
      "IS#t", "IS w%", "IS R:R", "IS E[R]", "IS prf", "IS DD",
      "OS#t", "OS w%", "OS R:R", "OS E[R]", "OS prf", "OS DD",
    )

  private fun row(label: String, ins: BackTestReport, os: BackTestReport): String =
    "%-18s | %4d %6s %6s %7s %7s %6s | %4d %6s %6s %7s %7s %6s".format(
      label,
      ins.tradeCount, pct(ins.winRate), fmt(ins.avgRewardRiskRatio), pct(ins.expectedValuePerTrade), pct(ins.profitability), pct(ins.maxDrawDown),
      os.tradeCount, pct(os.winRate), fmt(os.avgRewardRiskRatio), pct(os.expectedValuePerTrade), pct(os.profitability), pct(os.maxDrawDown),
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
  private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(v) else "—"
}
