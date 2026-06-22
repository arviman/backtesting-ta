package com.arviman.ta

import com.arviman.ta.backtest.BackTestReport
import com.arviman.ta.backtest.BackTestSpec
import com.arviman.ta.backtest.BackTester
import com.arviman.ta.backtest.TpSlTier
import com.arviman.ta.backtest.TradeType
import com.arviman.ta.indicators.volatilityStop
import com.arviman.ta.strategy.SqueezeParams
import com.arviman.ta.strategy.SqzEntryMode
import com.arviman.ta.strategy.SqzExitMode
import com.arviman.ta.strategy.makeSqueezeMomentumStrategy
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.readCsvBars

/**
 * Replicates the user's cTrader config and sweeps TP:SL ratios so we
 * can see whether their "zero-cross entry + deceleration exit" edge
 * came from the exit logic or from a tighter R:R.
 *
 * Fixed config:
 *   entry: zero-cross of val
 *   exit : color-change (deceleration while still on momentum side)
 *   SL   : 50 absolute price points (matches user's ~49 pips on ETH)
 *
 * Sweep: TP:SL ratio ∈ {0.3, 0.5, 1.0} × entry/exit variant ∈ {A, B, C}.
 */
object SqueezeMomentumTest {

  private const val DATA_PATH = "sampledata/ETHUSD_1h_Bitstamp.csv"

  private const val START_BAL = 13_000.0
  private const val SL_ABS = 50.0           // absolute price points (~$50 on ETH)
  private const val PYRAMID = 6
  private const val FIXED_POS_FRACTION = 0.15
  private const val FEE_PER_TRADE = 0.0004

  private val tpRatios = listOf(0.3, 0.4, 0.5, 0.7, 1.0, 1.5)

  private data class Variant(
    val label: String,
    val entryMode: SqzEntryMode,
    val exitMode: SqzExitMode,
  )

  private val variants = listOf(
    Variant("A zero/zero ", SqzEntryMode.ZeroCross, SqzExitMode.ZeroCross),
    Variant("B zero/color", SqzEntryMode.ZeroCross, SqzExitMode.Color),
    Variant("C color/color", SqzEntryMode.Color, SqzExitMode.Color),
  )

  @JvmStatic
  fun main(args: Array<String>) {
    println("Loading bars from $DATA_PATH ...")
    val bars = readCsvBars(DATA_PATH)
    val splitIdx = (bars.size * 0.6).toInt()
    val ins = bars.subList(0, splitIdx)
    val osr = bars.subList(splitIdx, bars.size)
    println("Loaded ${bars.size} H1 bars. IS=${ins.size}  OS=${osr.size}\n")

    println("=== ETHUSD H1 — OS-only TP:SL sweep (BB 10/2.1, KC 20/0.1, HTF SMA(47), SL $SL_ABS pts, pyramid $PYRAMID) ===")
    println(header())
    println("-".repeat(125))

    val rows = mutableListOf<Triple<String, BackTestReport, Double>>() // label, report, score
    for (v in variants) {
      for (tp in tpRatios) {
        val long = run(osr, longSide = true, v, tp)
        val short = run(osr, longSide = false, v, tp)
        val labelL = "${v.label} tp=$tp L"
        val labelS = "${v.label} tp=$tp S"
        println(row(labelL, long))
        println(row(labelS, short))
        rows += Triple(labelL, long, long.profitability)
        rows += Triple(labelS, short, short.profitability)
      }
      println()
    }
    println("=== Top 5 by net profit ===")
    rows.sortedByDescending { it.third }.take(5)
      .forEach { (label, r, _) -> println(row(label, r)) }
    println("\n=== Top 5 by profit factor ===")
    rows.filter { it.second.tradeCount > 50 }
      .sortedByDescending { it.second.profitFactor }.take(5)
      .forEach { (label, r, _) -> println(row(label, r)) }
    println("\n=== Top 5 by profit / DD ===")
    rows.filter { it.second.tradeCount > 50 && it.second.maxDrawDown > 0 }
      .sortedByDescending { it.second.profitability / it.second.maxDrawDown }.take(5)
      .forEach { (label, r, _) -> println(row(label, r)) }

    val bnh = osr.last().close / osr.first().close
    println("\nBuy & hold OS: %.2fx (%+.1f%%)".format(bnh, 100.0 * (bnh - 1.0)))
  }

  private fun run(bars: List<Bar>, longSide: Boolean, v: Variant, tpRatio: Double): BackTestReport {
    val params = SqueezeParams(
      bbLength = 10, bbMult = 2.1, kcLength = 20, kcMult = 0.1,
      useHtf = true, htfLength = 47,
      entryMode = v.entryMode, exitMode = v.exitMode,
    )
    val tier = TpSlTier(
      quantityFraction = 1.0,
      stopLossAbs = SL_ABS,
      takeProfitAbs = SL_ABS * tpRatio,
    )
    val spec = BackTestSpec(
      tradeType = if (longSide) TradeType.LONG else TradeType.SHORT,
      runTimeFrame = TimeFrame.H1,
      trailingStops = false,
      pyramidingLimit = PYRAMID,
      startingBalance = START_BAL,
      betSize = 0.02,
      feePerTrade = FEE_PER_TRADE,
      inputBars = bars,
      strategyFactory = { sm -> makeSqueezeMomentumStrategy(sm.h1, params, longSide) },
      stopLoss = { sm -> sm.h1.volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = listOf(tier),
      fixedPositionFraction = FIXED_POS_FRACTION,
    )
    return BackTester.run(spec)
  }

  private fun header(): String =
    "%-26s | %5s %7s %7s %7s %7s %7s %5s".format(
      "variant/tp/side", "#t", "win%", "avgWin%", "avgLoss%", "profit%", "DD%", "PF",
    )

  private fun row(label: String, r: BackTestReport): String =
    "%-26s | %5d %7s %7s %7s %7s %7s %5s".format(
      label,
      r.tradeCount,
      pct(r.winRate),
      pct(r.avgWinPct),
      pct(r.avgLossPct),
      pct(r.profitability),
      pct(r.maxDrawDown),
      if (r.profitFactor.isFinite()) "%.2f".format(r.profitFactor) else "—",
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
}
