package com.arviman.ta

import com.arviman.ta.backtest.BackTestSpec
import com.arviman.ta.backtest.BackTester
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
 * Ted Zhang stage-2 long backtest on XAU/USD weekly.
 *
 *   - Enter at first weekly close where the 10/20/30/40 SMA ribbon stacks bullish.
 *   - Exit at first weekly close that breaks the stack.
 *   - Structural trailing stop = weekly 40 SMA (ratchets up each week).
 *
 * Source CSV is daily; bars are pre-aggregated into ISO weeks before backtest
 * (the BackTester's run-frame gate requires inputBars in the run timeframe).
 */
object StageGoldWeeklyTest {

  private const val DATA = "sampledata/XAU_USD.csv"

  @JvmStatic
  fun main(args: Array<String>) {
    val daily = readCsvBars(DATA)
    val weekly = aggregateWeekly(daily)
    val from = daily.first().openTime.atOffset(ZoneOffset.UTC).toLocalDate()
    val to   = daily.last().openTime.atOffset(ZoneOffset.UTC).toLocalDate()
    println("Loaded ${daily.size} daily bars → ${weekly.size} weekly bars  ($from → $to)")

    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = TimeFrame.W,
      inputBars = weekly,
      startingBalance = 10_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      trailingStops = true,
      pyramidingLimit = 1,
      strategyFactory = { sm -> makeStageStrategy(sm.w) },
      stopLoss = { sm -> sm.w.closePrice.sma(40) },
    )

    val r = BackTester.run(spec)
    val bnh = daily.last().close / daily.first().close
    val finalBal = "%.0f".format(r.finalBalance)
    val pf = if (r.profitFactor.isFinite()) "%.2f".format(r.profitFactor) else "—"

    println()
    println("=== Stage-2 long on XAU/USD weekly ===")
    println("Trades        : ${r.tradeCount}")
    println("Win rate      : ${pct(r.winRate)}")
    println("Avg win / loss: ${pct(r.avgWinPct)} / ${pct(r.avgLossPct)}")
    println("Profit factor : $pf")
    println("Profitability : ${pct(r.profitability)}   (final balance $finalBal)")
    println("Max drawdown  : ${pct(r.maxDrawDown)}")
    println("Buy & hold    : ${"%.2fx (%+.1f%%)".format(bnh, 100.0 * (bnh - 1.0))}")
  }

  // ISO week aggregation: group consecutive daily bars sharing (week-based-year, week-of-year).
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

  private fun pct(v: Double) = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
}
