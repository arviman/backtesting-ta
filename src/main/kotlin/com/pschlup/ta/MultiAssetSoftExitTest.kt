package com.pschlup.ta

import com.pschlup.ta.backtest.BackTestReport
import com.pschlup.ta.backtest.BackTestSpec
import com.pschlup.ta.backtest.BackTester
import com.pschlup.ta.backtest.TpSlTier
import com.pschlup.ta.backtest.TradeType
import com.pschlup.ta.indicators.volatilityStop
import com.pschlup.ta.strategy.JamaParams
import com.pschlup.ta.strategy.jamaEntryAtrFactory
import com.pschlup.ta.strategy.makeJamaHccStrategy
import com.pschlup.ta.timeseries.Bar
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.readCsvBars

/**
 * Tests the JAMA entry/exit signal pair in isolation: soft exit ON, no
 * effective SL/TP (ladder uses 99% SL and 10000% TP which neither will ever
 * hit on these assets), fixed 10% bet per trade. Only the smoothed-RSI
 * rollover closes trades. Same setup across gold, SOL, and BTC daily.
 */
object MultiAssetSoftExitTest {

  private data class Asset(val label: String, val path: String)
  private val assets = listOf(
    Asset("Gold  (XAU/USD daily)",   "sampledata/XAU_USD.csv"),
    Asset("SOL/USD daily",           "sampledata/sol_d_02012023_14062026.csv"),
    Asset("BTC/USDT (730d, M5→D)",   "sampledata/chart_data_BTC_USDT_p5_730d.csv"),
  )

  private data class Variant(val label: String, val params: JamaParams)
  private val variants = listOf(
    Variant("JAMA late ", JamaParams(earlyEntry = false, disableSoftExit = false, trailingStopPct = null)),
    Variant("JAMA early", JamaParams(earlyEntry = true,  disableSoftExit = false, trailingStopPct = null)),
  )

  // Effectively no SL / no TP. 99% SL = stop at 1% of entry; 10000% TP = up
  // 100×. Neither will trigger on any realistic price path for these assets,
  // so the soft exit signal is the only thing that closes a trade.
  private val ladder = listOf(
    TpSlTier(quantityFraction = 1.0, stopLossPct = 0.99, takeProfitPct = 99.99)
  )

  @JvmStatic
  fun main(args: Array<String>) {
    for (asset in assets) {
      val bars = readCsvBars(asset.path)
      val splitIdx = (bars.size * 0.6).toInt()
      val ins = bars.subList(0, splitIdx)
      val os = bars.subList(splitIdx, bars.size)

      println("=== ${asset.label}  (${bars.size} bars → IS ${ins.size} | OS ${os.size}) ===")
      println(header())
      println("-".repeat(127))
      for (variant in variants) {
        val rIs = run(ins, variant.params)
        val rOs = run(os, variant.params)
        println(row(variant.label, rIs, rOs))
      }

      val anyResultIs = run(ins, variants.first().params)
      val anyResultOs = run(os, variants.first().params)
      val bnhDDIs = buyAndHoldDD(ins)
      val bnhDDOs = buyAndHoldDD(os)
      println("Buy-and-hold reference: IS %.1f%% (DD %.1f%%, Sortino %.2f) | OS %.1f%% (DD %.1f%%, Sortino %.2f)".format(
        100.0 * anyResultIs.buyAndHoldProfitability, 100.0 * bnhDDIs,
        if (bnhDDIs > 0) anyResultIs.buyAndHoldProfitability / bnhDDIs else Double.NaN,
        100.0 * anyResultOs.buyAndHoldProfitability, 100.0 * bnhDDOs,
        if (bnhDDOs > 0) anyResultOs.buyAndHoldProfitability / bnhDDOs else Double.NaN,
      ))
      println()
    }
  }

  private fun run(bars: List<Bar>, params: JamaParams): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = TimeFrame.D,
      trailingStops = false,
      pyramidingLimit = ladder.size,
      startingBalance = 5_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm -> makeJamaHccStrategy(strategy = sm.d, params = params) },
      stopLoss = { sm -> sm.d.volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = ladder,
      entryAtrFactory = jamaEntryAtrFactory(TimeFrame.D),
      fixedPositionFraction = 0.10,
    )
    return BackTester.run(spec)
  }

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

  private fun header(): String =
    "%-12s | %4s %7s %7s %7s %7s | %4s %7s %7s %7s %7s".format(
      "variant",
      "IS#t", "IS w%", "IS R:R", "IS E[R]", "IS prf",
      "OS#t", "OS w%", "OS R:R", "OS E[R]", "OS prf",
    )

  private fun row(label: String, ins: BackTestReport, os: BackTestReport): String =
    "%-12s | %4d %7s %7s %7s %7s | %4d %7s %7s %7s %7s".format(
      label,
      ins.tradeCount, pct(ins.winRate), fmt(ins.avgRewardRiskRatio), pct(ins.expectedValuePerTrade), pct(ins.profitability),
      os.tradeCount, pct(os.winRate), fmt(os.avgRewardRiskRatio), pct(os.expectedValuePerTrade), pct(os.profitability),
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
  private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(v) else "—"
}
