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
 * Grid sweep of [JamaParams] with a chronological 60/40 in-sample / out-of-sample
 * holdout. Prints the top 10 by in-sample Sortino, side-by-side with the same
 * params' out-of-sample numbers — a winner on one side and a loser on the other
 * is the curve-fit one to discard.
 *
 * Grid axes here (4 × 3 × 3 = 36 combos). Add or shrink axes by editing [grid].
 */
object ParameterSweep {

  // --- Grid axes -----------------------------------------------------------
  private val slAtrMultValues = listOf(1.0, 1.5, 2.0, 3.0)
  private val tpSlRatioValues = listOf(1.0, 1.5, 2.0)
  private val changeEmaThresholdValues = listOf(0.04, 0.06, 0.08)

  private fun grid(): List<JamaParams> =
    slAtrMultValues.flatMap { sl ->
      tpSlRatioValues.flatMap { r ->
        changeEmaThresholdValues.map { th ->
          JamaParams(slAtrMult = sl, tpSlRatio = r, changeEmaThreshold = th)
        }
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
    println("Sweeping ${combos.size} param combinations…\n")

    val results = combos.mapIndexed { idx, p ->
      print("\r[${idx + 1}/${combos.size}] running…")
      val ins = runOne(inSample, p)
      val oos = runOne(outOfSample, p)
      Result(p, ins, oos)
    }
    println("\r" + " ".repeat(40) + "\r")

    println(header())
    println("-".repeat(115))
    results
      .sortedByDescending { sortKey(it.inSample) }
      .take(10)
      .forEach { println(it.toRow()) }

    println("\nBuy-and-hold reference:")
    println("  in-sample  : ${"%.2f%%".format(100.0 * runOne(inSample, JamaParams()).buyAndHoldProfitability)}")
    println("  out-of-sample: ${"%.2f%%".format(100.0 * runOne(outOfSample, JamaParams()).buyAndHoldProfitability)}")
  }

  private fun runOne(bars: List<Bar>, params: JamaParams): BackTestReport {
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
    )
    return BackTester.run(spec)
  }

  /** Rank by Sortino, penalize NaN/Inf (zero-drawdown or zero-trade runs). */
  private fun sortKey(r: BackTestReport): Double {
    val s = r.sortinoRatio
    return if (s.isFinite()) s else Double.NEGATIVE_INFINITY
  }

  private data class Result(val params: JamaParams, val inSample: BackTestReport, val outSample: BackTestReport) {
    fun toRow(): String {
      val p = params
      val paramStr = "sl=%.1f tp:sl=%.1f th=%.2f".format(p.slAtrMult, p.tpSlRatio, p.changeEmaThreshold)
      return "%-26s | %5d %8s %8s %7s | %5d %8s %8s %7s".format(
        paramStr,
        inSample.tradeCount, pct(inSample.profitability), pct(inSample.maxDrawDown), fmt(inSample.sortinoRatio),
        outSample.tradeCount, pct(outSample.profitability), pct(outSample.maxDrawDown), fmt(outSample.sortinoRatio),
      )
    }
  }

  private fun header(): String =
    "%-26s | %5s %8s %8s %7s | %5s %8s %8s %7s".format(
      "params", "IS#t", "IS prof", "IS DD", "IS sort",
      "OS#t", "OS prof", "OS DD", "OS sort",
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
  private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(v) else "—"
}
