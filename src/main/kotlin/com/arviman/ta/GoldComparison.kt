package com.arviman.ta

import com.arviman.ta.backtest.BackTestReport
import com.arviman.ta.backtest.BackTestSpec
import com.arviman.ta.backtest.BackTester
import com.arviman.ta.backtest.TradeType
import com.arviman.ta.indicators.Indicator
import com.arviman.ta.indicators.volatilityStop
import com.arviman.ta.strategy.JamaParams
import com.arviman.ta.strategy.Strategy
import com.arviman.ta.strategy.jamaEntryAtrFactory
import com.arviman.ta.strategy.jamaLadder
import com.arviman.ta.strategy.makeJamaHccStrategy
import com.arviman.ta.strategy.makeRsiMidlineStrategy
import com.arviman.ta.strategy.makeRsiRecoveryStrategy
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.readCsvBars

/**
 * Runs every strategy variant we have on XAU/USD daily, with the same 60/40
 * chronological holdout we used for SOL. Lets us answer: does any of this
 * actually generalize beyond a single asset, or does the SOL result repeat?
 */
object GoldComparison {

  private data class Run(val label: String, val build: (List<Bar>) -> BackTestReport)

  private val runs = listOf(
    Run("JAMA late  R:R=3  no-soft-exit") { bars -> jama(bars, tpSlRatio = 3.0, earlyEntry = false) },
    Run("JAMA late  R:R=2  no-soft-exit") { bars -> jama(bars, tpSlRatio = 2.0, earlyEntry = false) },
    Run("JAMA early R:R=3  no-soft-exit") { bars -> jama(bars, tpSlRatio = 3.0, earlyEntry = true)  },
    Run("JAMA early R:R=2  no-soft-exit") { bars -> jama(bars, tpSlRatio = 2.0, earlyEntry = true)  },
    Run("RSI Weekly RSI(7) midline 50")   { bars -> rsi(bars, TimeFrame.W, 7,  midline = 50.0) },
    Run("RSI Weekly RSI(7) recover 30/80"){ bars -> rsi(bars, TimeFrame.W, 7,  recover = true) },
    Run("RSI 3-day  RSI(14) midline 50")  { bars -> rsi(bars, TimeFrame.D3, 14, midline = 50.0) },
    Run("RSI Daily  RSI(14) midline 50")  { bars -> rsi(bars, TimeFrame.D, 14,  midline = 50.0) },
  )

  @JvmStatic
  fun main(args: Array<String>) {
    val bars = readCsvBars("sampledata/XAU_USD.csv")
    val splitIdx = (bars.size * 0.6).toInt()
    val inSample = bars.subList(0, splitIdx)
    val outSample = bars.subList(splitIdx, bars.size)
    println("Bars: ${bars.size} XAU/USD daily → IS ${inSample.size} | OS ${outSample.size}")
    println()

    val results = runs.map { Triple(it.label, it.build(inSample), it.build(outSample)) }
    println(header())
    println("-".repeat(130))
    results.forEach { (label, ins, os) -> println(row(label, ins, os)) }

    val bnhIn = runs.first().build(inSample).buyAndHoldProfitability
    val bnhOut = runs.first().build(outSample).buyAndHoldProfitability
    val bnhInDD = buyAndHoldDrawdown(inSample)
    val bnhOutDD = buyAndHoldDrawdown(outSample)
    println()
    println("Buy-and-hold reference: IS %.1f%% (DD %.1f%%, Sortino %.2f) | OS %.1f%% (DD %.1f%%, Sortino %.2f)".format(
      100.0 * bnhIn, 100.0 * bnhInDD, if (bnhInDD > 0) bnhIn / bnhInDD else Double.NaN,
      100.0 * bnhOut, 100.0 * bnhOutDD, if (bnhOutDD > 0) bnhOut / bnhOutDD else Double.NaN,
    ))
  }

  private fun jama(bars: List<Bar>, tpSlRatio: Double, earlyEntry: Boolean): BackTestReport {
    val params = JamaParams(
      slAtrMult = 2.0,
      tpSlRatio = tpSlRatio,
      earlyEntry = earlyEntry,
      trailingStopPct = null,
      disableSoftExit = true,
    )
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
      fixedPositionFraction = 0.10,
    )
    return BackTester.run(spec)
  }

  private fun rsi(
    bars: List<Bar>,
    tf: TimeFrame,
    length: Int,
    midline: Double? = null,
    recover: Boolean = false,
  ): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = tf,
      trailingStops = false,
      pyramidingLimit = 1,
      startingBalance = 5_000.0,
      betSize = 1.0,
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm ->
        val series = sm.getTimeSeries(tf)
        when {
          recover -> makeRsiRecoveryStrategy(series, length, 30.0, 80.0, lookbackBars = 6)
          midline != null -> makeRsiMidlineStrategy(series, length, midline)
          else -> error("either midline or recover required")
        }
      },
      stopLoss = { Indicator { 0.0 } }, // no stop (price = 0 never hit)
    )
    return BackTester.run(spec)
  }

  private fun buyAndHoldDrawdown(bars: List<Bar>): Double {
    val start = bars.first().close
    var peak = 1.0
    var maxDD = 0.0
    for (bar in bars) {
      val equity = bar.close / start
      if (equity > peak) peak = equity
      val dd = (peak - equity) / peak
      if (dd > maxDD) maxDD = dd
    }
    return maxDD
  }

  private fun header(): String =
    "%-34s | %4s %7s %7s %7s %7s | %4s %7s %7s %7s %7s".format(
      "strategy",
      "IS#t", "IS w%", "IS R:R", "IS E[R]", "IS prf",
      "OS#t", "OS w%", "OS R:R", "OS E[R]", "OS prf",
    )

  private fun row(label: String, ins: BackTestReport, os: BackTestReport): String =
    "%-34s | %4d %7s %7s %7s %7s | %4d %7s %7s %7s %7s".format(
      label,
      ins.tradeCount, pct(ins.winRate), fmt(ins.avgRewardRiskRatio), pct(ins.expectedValuePerTrade), pct(ins.profitability),
      os.tradeCount, pct(os.winRate), fmt(os.avgRewardRiskRatio), pct(os.expectedValuePerTrade), pct(os.profitability),
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
  private fun fmt(v: Double): String = if (v.isFinite()) "%.2f".format(v) else "—"
}
