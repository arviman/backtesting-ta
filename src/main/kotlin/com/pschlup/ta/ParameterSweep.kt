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
 * R:R × entry-mode sweep on SOL daily.
 *
 * Fixes the SL distance at 2 × ATR(14) and the bet size at 10% of equity per
 * trade, then sweeps the TP:SL ratio across two entry modes:
 *
 *   - earlyEntry = false → original gates (Hurst breakout + slope filters)
 *   - earlyEntry = true  → drops late-confirmation gates
 *
 * Reports per-trade win %, achieved R:R, and expected value per trade — the two
 * numbers that govern long-run edge.
 */
object ParameterSweep {

  private val tpSlRatioValues = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0)
  private val earlyEntryValues = listOf(false, true)

  private fun grid(): List<JamaParams> = earlyEntryValues.flatMap { early ->
    tpSlRatioValues.map { r ->
      JamaParams(slAtrMult = 2.0, tpSlRatio = r, earlyEntry = early, trailingStopPct = null)
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val bars = readCsvBars("sampledata/sol_d_02012023_14062026.csv")
    val splitIdx = (bars.size * 0.6).toInt()
    val inSample = bars.subList(0, splitIdx)
    val outOfSample = bars.subList(splitIdx, bars.size)
    println("Bars: ${bars.size} total → in-sample ${inSample.size} | out-of-sample ${outOfSample.size}")

    val combos = grid()
    println("Sweeping ${combos.size} param combinations (fixed 10% bet/trade, SL = 2×ATR)\n")

    val results = combos.map { p -> Result(p, runOne(inSample, p), runOne(outOfSample, p)) }

    println(header())
    println("-".repeat(120))
    results
      .sortedByDescending { sortKey(it.inSample) }
      .forEach { println(it.toRow()) }

    val bnhIn = runOne(inSample, JamaParams()).buyAndHoldProfitability
    val bnhOut = runOne(outOfSample, JamaParams()).buyAndHoldProfitability
    println()
    println("Buy-and-hold reference: in-sample %.1f%% | out-of-sample %.1f%%".format(100.0 * bnhIn, 100.0 * bnhOut))
  }

  private fun runOne(bars: List<Bar>, params: JamaParams): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = TimeFrame.D,
      trailingStops = false,
      pyramidingLimit = jamaLadder(params).size,
      startingBalance = 5_000.0,
      betSize = 0.02, // ignored when fixedPositionFraction is set
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm -> makeJamaHccStrategy(strategy = sm.d, params = params) },
      stopLoss = { sm -> sm.d.volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = jamaLadder(params),
      entryAtrFactory = jamaEntryAtrFactory(TimeFrame.D),
      fixedPositionFraction = 0.10,
    )
    return BackTester.run(spec)
  }

  /** Rank by expected value per trade (Sortino is misleading when DD is tiny). */
  private fun sortKey(r: BackTestReport): Double =
    if (r.tradeCount > 0 && r.expectedValuePerTrade.isFinite()) r.expectedValuePerTrade
    else Double.NEGATIVE_INFINITY

  private data class Result(val params: JamaParams, val inSample: BackTestReport, val outSample: BackTestReport) {
    fun toRow(): String {
      val p = params
      val mode = if (p.earlyEntry) "early" else "late "
      val paramStr = "%s R:R=%.1f".format(mode, p.tpSlRatio)
      return "%-16s | %4d %6s %6s %6s %6s | %4d %6s %6s %6s %6s".format(
        paramStr,
        inSample.tradeCount, pct(inSample.winRate), pct(inSample.expectedValuePerTrade), fmt(inSample.avgRewardRiskRatio), pct(inSample.profitability),
        outSample.tradeCount, pct(outSample.winRate), pct(outSample.expectedValuePerTrade), fmt(outSample.avgRewardRiskRatio), pct(outSample.profitability),
      )
    }
  }

  private fun header(): String =
    "%-16s | %4s %6s %6s %6s %6s | %4s %6s %6s %6s %6s".format(
      "params",
      "IS#t", "IS w%", "IS E[R]", "IS R:R", "IS prf",
      "OS#t", "OS w%", "OS E[R]", "OS R:R", "OS prf",
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
  private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(v) else "—"
}
