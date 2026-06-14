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
 * Param sweep on BTC_2022_2026.csv (full cycle, daily). Axes:
 *
 *   slAtrMult  ∈ {1.5, 2.0, 3.0}      — initial stop distance in ATR units
 *   tpSlRatio  ∈ {1.5, 2.0, 3.0}      — TP / SL distance ratio
 *   entryMode  ∈ {early, relaxed}    — early keeps MM + SMA50 gates,
 *                                       relaxed drops all trend gates
 *
 * Soft exit ON (matches Pine default). 100% bet per trade. Sorted by
 * compounded IS×OS total return so the best survivors surface at top.
 */
object ParameterSweep {

  private val slAtrMults = listOf(1.5, 2.0, 3.0)
  private val tpSlRatios = listOf(1.5, 2.0, 3.0)
  private val entryModes = listOf(EntryMode.EARLY, EntryMode.RELAXED)

  private enum class EntryMode { EARLY, RELAXED }

  private fun grid(): List<JamaParams> = entryModes.flatMap { mode ->
    slAtrMults.flatMap { sl ->
      tpSlRatios.map { ratio ->
        JamaParams(
          slAtrMult = sl,
          tpSlRatio = ratio,
          earlyEntry = true,
          dropTrendGates = (mode == EntryMode.RELAXED),
          changeEmaThreshold = if (mode == EntryMode.RELAXED) 0.03 else 0.06,
          disableSoftExit = false, // soft exit ON
          trailingStopPct = null,
        )
      }
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val bars = readCsvBars("sampledata/BTC_2022_2026.csv")
    val splitIdx = (bars.size * 0.6).toInt()
    val inSample = bars.subList(0, splitIdx)
    val outOfSample = bars.subList(splitIdx, bars.size)
    println("Bars: ${bars.size} total → IS ${inSample.size} | OS ${outOfSample.size} (BTC 2022→2026 daily)")

    val combos = grid()
    println("Sweeping ${combos.size} JAMA configs (soft exit ON, 100% bet)\n")

    val results = combos.map { p -> Result(p, runOne(inSample, p), runOne(outOfSample, p)) }

    println(header())
    println("-".repeat(140))
    results
      .sortedByDescending { totalMult(it.inSample) * totalMult(it.outSample) }
      .forEach { println(it.toRow()) }

    val bnhIs = inSample.last().close / inSample.first().close
    val bnhOs = outOfSample.last().close / outOfSample.first().close
    println()
    println("Buy-and-hold reference: IS %.2fx (%.1f%%) | OS %.2fx (%.1f%%) | Total %.2fx".format(
      bnhIs, 100.0 * (bnhIs - 1.0), bnhOs, 100.0 * (bnhOs - 1.0), bnhIs * bnhOs,
    ))
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
      fixedPositionFraction = 1.0,
    )
    return BackTester.run(spec)
  }

  private fun totalMult(r: BackTestReport): Double = 1.0 + r.profitability

  private data class Result(val params: JamaParams, val inSample: BackTestReport, val outSample: BackTestReport) {
    fun toRow(): String {
      val p = params
      val mode = if (p.dropTrendGates) "relaxed" else "early  "
      val paramStr = "%s sl=%.1f tp:sl=%.1f".format(mode, p.slAtrMult, p.tpSlRatio)
      val totalMult = totalMult(inSample) * totalMult(outSample)
      return "%-26s | %4d %6s %7s %7s %6s | %4d %6s %7s %7s %6s | %5.2fx".format(
        paramStr,
        inSample.tradeCount, pct(inSample.winRate), pct(inSample.expectedValuePerTrade),
        pct(inSample.profitability), pct(inSample.maxDrawDown),
        outSample.tradeCount, pct(outSample.winRate), pct(outSample.expectedValuePerTrade),
        pct(outSample.profitability), pct(outSample.maxDrawDown),
        totalMult,
      )
    }
  }

  private fun header(): String =
    "%-26s | %4s %6s %7s %7s %6s | %4s %6s %7s %7s %6s | %5s".format(
      "params",
      "IS#t", "IS w%", "IS E[R]", "IS prf", "IS DD",
      "OS#t", "OS w%", "OS E[R]", "OS prf", "OS DD",
      "total",
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
}
