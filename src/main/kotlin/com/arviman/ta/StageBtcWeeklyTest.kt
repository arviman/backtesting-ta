package com.arviman.ta

import com.arviman.ta.backtest.BackTestReport
import com.arviman.ta.backtest.BackTestSpec
import com.arviman.ta.backtest.BackTester
import com.arviman.ta.backtest.TpSlTier
import com.arviman.ta.backtest.TradeType
import com.arviman.ta.indicators.closePrice
import com.arviman.ta.indicators.sma
import com.arviman.ta.strategy.makeStageStrategy
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.readCsvBars
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * Ted Zhang 4-stage strategy on BTC weekly.
 *
 *   Long  : enter Stage 2 (bullish ribbon stack), exit on break.
 *   Short : enter Stage 4 (bearish ribbon stack), exit on break.
 *
 * Sized via fixedPositionFraction + a single percent stop tier (entryLadder),
 * because the single-entry path in BackTester is long-only sizing-wise.
 */
object StageBtcWeeklyTest {

  private const val DATA = "sampledata/BTC_2022_2026.csv"
  private const val STOP_PCT = 0.15        // 15% structural stop (no TP; exit on stage change)
  private const val POS_FRACTION = 0.5     // 50% of equity per trade

  @JvmStatic
  fun main(args: Array<String>) {
    val daily = readCsvBars(DATA)
    val weekly = aggregateWeekly(daily)
    val from = daily.first().openTime.atOffset(ZoneOffset.UTC).toLocalDate()
    val to   = daily.last().openTime.atOffset(ZoneOffset.UTC).toLocalDate()
    println("Loaded ${daily.size} daily bars → ${weekly.size} weekly bars  ($from → $to)")

    val long  = run(weekly, longSide = true)
    val short = run(weekly, longSide = false)
    val bnh   = daily.last().close / daily.first().close

    println()
    println(header())
    println("-".repeat(85))
    println(row("Stage 2 LONG ", long))
    println(row("Stage 4 SHORT", short))
    println("Buy & hold     : ${"%.2fx (%+.1f%%)".format(bnh, 100.0 * (bnh - 1.0))}")
  }

  private fun run(weekly: List<Bar>, longSide: Boolean): BackTestReport {
    val tier = TpSlTier(
      quantityFraction = 1.0,
      stopLossPct = STOP_PCT,
      takeProfitPct = 99.0,   // effectively disabled — strategy exits on stage change
    )
    val spec = BackTestSpec(
      tradeType = if (longSide) TradeType.LONG else TradeType.SHORT,
      runTimeFrame = TimeFrame.W,
      inputBars = weekly,
      startingBalance = 10_000.0,
      feePerTrade = 0.0005,
      trailingStops = false,
      pyramidingLimit = 1,
      strategyFactory = { sm -> makeStageStrategy(sm.w, longSide = longSide) },
      stopLoss = { sm -> sm.w.closePrice.sma(40) },   // unused under entryLadder
      entryLadder = listOf(tier),
      fixedPositionFraction = POS_FRACTION,
    )
    return BackTester.run(spec)
  }

  private fun aggregateWeekly(bars: List<Bar>): List<Bar> {
    val out = mutableListOf<Bar>()
    var currentKey: Pair<Int, Int>? = null
    for (b in bars) {
      val dt = b.openTime.atOffset(ZoneOffset.UTC)
      val key = dt.get(IsoFields.WEEK_BASED_YEAR) to dt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
      if (key != currentKey) {
        currentKey = key
        out += Bar(
          timeFrame = TimeFrame.W,
          openTime = b.openTime,
          open = b.open, high = b.high, low = b.low, close = b.close,
          volume = b.volume,
        )
      } else {
        out.last() += b
      }
    }
    return out
  }

  private fun header(): String =
    "%-15s | %4s %6s %8s %8s %7s %7s %5s".format(
      "side", "#t", "win%", "avgWin%", "avgLoss%", "prof%", "DD%", "PF",
    )

  private fun row(label: String, r: BackTestReport): String =
    "%-15s | %4d %6s %8s %8s %7s %7s %5s".format(
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
