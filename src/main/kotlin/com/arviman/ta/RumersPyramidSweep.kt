package com.arviman.ta

import com.arviman.ta.timeseries.readCsvBars
import java.time.temporal.ChronoUnit

/**
 * Focused pyramid sweep on the rejection-on base config.
 *
 * Base (locked): lookback=30, bufferPts=10, minRR=2.0, tpFrac=0.75,
 *                skipBand=0.0, requireRejection=true
 * Sweep: pyramidingLimit × requireProfitToPyramid
 *
 * For each: 1-contract realised peak DD + worst day, then max NQ-equivalent
 * size that fits the5ers $100k caps ($4k daily / $10k total DD), then
 * calendar months to the $8k profit target at that max size.
 *
 * Answers: "for the rejection-on regime, what (pyramid, size) hits the
 * target in ≤ 6 months while staying inside the eval rules?"
 */
object RumersPyramidSweep {

  private const val NQ_DATA = "sampledata/NQ_5Years_8_11_2024.csv"

  // the5ers $100k cTrader.
  private const val DAILY_CAP = 4_000.0
  private const val TOTAL_DD = 10_000.0
  private const val TARGET = 8_000.0

  private val pyramidGrid = listOf(1, 2, 3, 4, 6, 8, 10)
  private val gateGrid    = listOf(true, false)

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== Pyramid sweep on rejection-on base ===")
    println("Base: lookback=30, bufferPts=10, minRR=2.0, tpFrac=0.75, skipBand=0.0, rejection=on")
    println("Rules: the5ers \$100k → daily \$${"%,.0f".format(DAILY_CAP)} / total DD \$${"%,.0f".format(TOTAL_DD)} / target \$${"%,.0f".format(TARGET)}\n")

    val bars5m = readCsvBars(NQ_DATA)
    val bars15m = RumersTest.aggregateTo15m(bars5m)
    val rthHL = RumersTest.buildRthDailyHL(bars5m)
    val years = (bars15m.last().openTime.epochSecond - bars15m.first().openTime.epochSecond) /
        (365.25 * 24 * 3600.0)
    println("Backtest span: %.1fy\n".format(years))

    println("%-3s %-5s | %5s %5s %6s %10s %10s | %-12s %10s %8s".format(
      "pyr", "gate", "#t", "win%", "PF", "peakDD", "worstDay", "max size", "ann\$", "mo→tgt"))
    println("-".repeat(108))

    for (pyr in pyramidGrid) {
      for (gate in gateGrid) {
        val cfg = RumersTest.Config(
          lookback = 30, bufferPts = 10.0, minRR = 2.0, tpFrac = 0.75,
          skipBand = 0.0, is24x7 = false,
          pointValue = 20.0, contracts = 1.0,
          pyramidingLimit = pyr, requireProfitToPyramid = gate,
          requireRejection = true,
        )
        val r = RumersTest.runStrategy(bars15m, rthHL, cfg)
        if (r.trades.isEmpty()) {
          println("%-3d %-5s | (no trades)".format(pyr, if (gate) "on" else "off"))
          continue
        }

        // Per-day P&L + peak equity DD + worst day.
        val byDay = r.trades.groupBy { it.date }.toSortedMap()
          .map { (d, ts) -> d to ts.sumOf { it.dollarPnL } }
        var bal = 0.0; var peak = 0.0; var maxDD = 0.0; var worstDay = 0.0
        for ((_, pnl) in byDay) {
          bal += pnl
          if (bal > peak) peak = bal
          val dd = peak - bal
          if (dd > maxDD) maxDD = dd
          if (pnl < worstDay) worstDay = pnl
        }

        // Max NQ size in tenths (so MNQ granularity).
        var maxUnits = 0
        for (u in 1..1000) {
          val scale = u / 10.0
          if (scale * maxDD <= TOTAL_DD && scale * (-worstDay) <= DAILY_CAP) maxUnits = u
        }
        val scale = maxUnits / 10.0
        val projAnnual = r.total * scale / years
        val monthsToTarget = if (projAnnual > 0) TARGET / projAnnual * 12.0 else Double.POSITIVE_INFINITY
        val nq = maxUnits / 10
        val mnq = maxUnits % 10
        val sizeStr = when {
          maxUnits == 0 -> "(none)"
          mnq == 0 -> "${nq} NQ"
          else -> "${nq}NQ+${mnq}MNQ"
        }
        val mo = if (monthsToTarget.isFinite()) "%.1f".format(monthsToTarget) else "—"
        val gateStr = if (gate) "on" else "off"
        println("%-3d %-5s | %5d %5.1f %6.2f %10s %10s | %-12s %10s %8s".format(
          pyr, gateStr, r.trades.size, r.winRate, r.profitFactor,
          dollarFmt(maxDD), dollarFmt(worstDay), sizeStr, dollarFmt(projAnnual), mo))
      }
      println()
    }

    println("Tip: anything ≤ 6 mo at acceptable DD% wins. If daily cap saturates at small size,")
    println("     pyramiding helps more than naively bumping lots because it spreads risk across multiple entries.")
  }

  private fun dollarFmt(v: Double) =
    if (v >= 0) "\$%,.0f".format(v) else "-\$%,.0f".format(-v)
}
