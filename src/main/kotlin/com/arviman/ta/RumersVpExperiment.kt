package com.arviman.ta

import com.arviman.ta.indicators.VolumeProfile
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.readCsvBars
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Bolt volume-profile composite POC onto Rumers as a TP target.
 *
 * Hypothesis: replacing the arbitrary `tpFrac × range` TP with a
 * structurally-meaningful target (recent N-day composite POC) should
 * give faster, more reliable exits and improve PF / lower DD.
 *
 * Mechanism: per RTH day, build a volume profile from the 5m NQ bars.
 * For each subsequent day, compute the rolling N-day composite POC
 * (POC of summed prior-N-day profiles). When the strategy enters,
 * substitute that POC for the range-based TP if and only if it's:
 *   - in the favourable direction (below entry for shorts, above for longs)
 *   - tighter than the original range-based TP
 *
 * Run + abandon: if PF doesn't improve materially, drop the idea.
 */
object RumersVpExperiment {

  private const val NQ_DATA = "sampledata/NQ_5Years_8_11_2024.csv"
  private const val VP_TICK_SIZE = 5.0    // 5 NQ points per bucket
  private val COMPOSITE_DAYS_GRID = listOf(3, 5, 10, 20)

  // Match RumersTest's RTH window for daily H/L (mod 9:35..16:00).
  private const val RTH_FIRST_MIN = 9 * 60 + 35
  private const val RTH_LAST_MIN = 16 * 60

  // Deployed config (same as RumersBot defaults).
  private val DEPLOY_CFG = RumersTest.Config(
    lookback = 30, bufferPts = 10.0, minRR = 2.0, tpFrac = 0.75,
    skipBand = 0.0, is24x7 = false,
    pointValue = 20.0, contracts = 1.0,
    pyramidingLimit = 1, requireProfitToPyramid = true,
    requireRejection = true,
  )

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== Rumers × Volume-Profile target experiment ===\n")
    println("Loading NQ data...")
    val bars5m = readCsvBars(NQ_DATA)
    val bars15m = RumersTest.aggregateTo15m(bars5m)
    val rthHL = RumersTest.buildRthDailyHL(bars5m)

    println("Building per-day VPs from RTH 5m bars...")
    val dailyVp = buildDailyVp(bars5m)
    println("  ${dailyVp.size} day VPs.\n")

    println("=== Baseline (no VP target) ===")
    val baseline = RumersTest.runStrategy(bars15m, rthHL, DEPLOY_CFG)
    printSummary("Baseline", baseline)

    val variants = COMPOSITE_DAYS_GRID.map { n ->
      val pocByDate = computeCompositePocByDate(dailyVp, n)
      val r = RumersTest.runStrategy(bars15m, rthHL, DEPLOY_CFG, vpTargetByDate = pocByDate)
      "VP-${n}d-POC" to r
    }
    for ((name, r) in variants) {
      println()
      printSummary(name, r)
    }

    println("\n=== Side-by-side ===")
    val all = listOf("Baseline" to baseline) + variants
    printTable(all)

    val best = all.maxByOrNull { it.second.profitFactor }!!
    val baselinePf = baseline.profitFactor
    val improvement = (best.second.profitFactor - baselinePf) / baselinePf * 100.0
    println("\nBest: ${best.first} (PF=${"%.2f".format(best.second.profitFactor)}, " +
      "Δ vs baseline = %+.1f%%)".format(improvement))
    if (best.first == "Baseline" || improvement < 5.0) {
      println("\nVERDICT: VP-target does NOT materially improve PF (< 5% lift). Abandon.")
    } else {
      println("\nVERDICT: VP-target improves PF by ${"%.1f".format(improvement)}%. Worth exploring further.")
    }
  }

  // ── per-day VP from RTH 5m bars
  private fun buildDailyVp(bars: List<Bar>): Map<LocalDate, List<Pair<Double, Double>>> {
    val byDate = sortedMapOf<LocalDate, VolumeProfile>()
    for (b in bars) {
      val zdt = b.openTime.atZone(ZoneOffset.UTC)
      val mod = zdt.hour * 60 + zdt.minute
      if (mod < RTH_FIRST_MIN || mod > RTH_LAST_MIN) continue
      val date = zdt.toLocalDate()
      val vp = byDate.getOrPut(date) { VolumeProfile(VP_TICK_SIZE) }
      vp.addBar(b)
    }
    return byDate.mapValues { it.value.snapshot() }
  }

  // ── rolling N-day composite POC, indexed by the date AFTER the window
  // (so it has no lookahead into the current day's prints)
  private fun computeCompositePocByDate(
    dailyVp: Map<LocalDate, List<Pair<Double, Double>>>,
    windowDays: Int,
  ): Map<LocalDate, Double> {
    val out = mutableMapOf<LocalDate, Double>()
    val dates = dailyVp.keys.sorted()
    val window = ArrayDeque<LocalDate>()
    for (date in dates) {
      if (window.size >= 2) {
        val composite = VolumeProfile(VP_TICK_SIZE)
        for (d in window) {
          for ((p, v) in dailyVp[d] ?: continue) composite.addBar(p, p, v)
        }
        if (!composite.isEmpty()) out[date] = composite.poc()
      }
      window.addLast(date)
      while (window.size > windowDays) window.removeFirst()
    }
    return out
  }

  private fun printSummary(name: String, r: RumersTest.Result) {
    println("  $name: ${r.trades.size} trades, win=${"%.1f".format(r.winRate)}%, " +
      "PF=${"%.2f".format(r.profitFactor)}, total=$${"%,.0f".format(r.total)}, " +
      "maxYrDD=$${"%,.0f".format(r.maxYearlyDD)}, avgWin=${"%.1f".format(r.avgWinPts)}pt, " +
      "avgLoss=${"%.1f".format(r.avgLossPts)}pt")
  }

  private fun printTable(rows: List<Pair<String, RumersTest.Result>>) {
    println("%-15s | %5s %5s %5s %9s %10s %8s %8s".format(
      "variant", "#t", "win%", "PF", "total$", "maxYrDD$", "avgWin", "avgLoss"))
    println("-".repeat(75))
    for ((name, r) in rows) {
      println("%-15s | %5d %5.1f %5.2f %9s %10s %7.1fpt %7.1fpt".format(
        name, r.trades.size, r.winRate, r.profitFactor,
        "$%,.0f".format(r.total), "$%,.0f".format(r.maxYearlyDD),
        r.avgWinPts, r.avgLossPts))
    }
  }
}
